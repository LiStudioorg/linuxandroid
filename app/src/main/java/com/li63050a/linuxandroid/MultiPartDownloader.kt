package com.li63050a.linuxandroid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 多线程分块并发下载器（§8.19）。
 *
 * 工作方式：
 *  1. 先发一次 `HEAD` 取 `Content-Length` 与 `Accept-Ranges`；
 *  2. 服务端支持 Range 且文件足够大时，切成 N 块并发下载到
 *     `<id>.<format>.part.<index>`；
 *  3. 全部完成后按序合并成单一的 `<id>.<format>.part`，并**校验总长度**；
 *  4. 任何一块失败 / 服务端不支持 Range / 长度对不上 → 降级或整份丢弃。
 *
 * **降级而非失败**是刻意设计：很多镜像对某些文件不返回 `Accept-Ranges`，
 * 若直接报错，用户就完全下不了。降级路径复用 [RootfsDownloader] 的单线程实现。
 *
 * 为什么分块写独立文件而不是 `RandomAccessFile` 定位写同一文件：
 * 随机写要在每块里 `seek` 后写，多线程共享一个 fd 需要额外同步，且一旦
 * 中途失败很难判断已有内容的有效性。独立分块文件让「哪块下完了」天然可查，
 * 续传与清理都更直接。
 */
class MultiPartDownloader(
    private val single: RootfsDownloader = RootfsDownloader(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {

    @Volatile
    private var cancelled = false

    /** 取消：既取消当前分块请求的 Call，也阻止后续分块继续发起 */
    fun cancel() {
        cancelled = true
        single.cancel()
    }

    /** 重置取消标记，让同一个实例可以被下一次下载复用 */
    fun reset() {
        cancelled = false
    }

    /**
     * 下载入口。**必须在 IO 线程调用**（内部已 `withContext(Dispatchers.IO)`）。
     *
     * @param distro     清单版本
     * @param partFile   最终目标文件 `<id>.<format>.part`
     * @param urls       已排好序的候选地址（第一优先）
     * @param threads    分块线程数；<=1 时直接走单线程
     * @param onProgress (已下载字节, 总字节)；总长未知时 total = -1
     */
    suspend fun download(
        distro: DistroInfo,
        partFile: File,
        urls: List<String>,
        threads: Int,
        onProgress: (done: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        reset()
        if (urls.isEmpty()) throw IOException("没有可用的下载地址")

        val url = urls.first()
        val total = probeLength(url, distro)
        val wantThreads = threads.coerceIn(1, MAX_THREADS)

        // 无法确定长度、文件太小、或服务端不支持 Range：都退回单线程。
        // 单线程本身也有 Range 续传，功能不缺失，只是慢一些。
        if (total <= 0 || wantThreads <= 1 || total < MIN_MULTIPART_BYTES) {
            AppLogger.i(
                "MultiPart",
                "single-thread (total=$total threads=$wantThreads) id=${distro.id}"
            )
            cleanupChunks(partFile)
            // 单线程路径必须**原样透传**已排序的 urls，否则测速优选与自定义镜像
            // 都会在这一步被悄悄丢掉（它会退回 distro.allUrls 的清单顺序）
            return@withContext single.download(distro, partFile, urls, onProgress)
        }

        AppLogger.i(
            "MultiPart",
            "multipart id=${distro.id} total=$total threads=$wantThreads url=$url"
        )

        try {
            downloadChunks(url, partFile, total, wantThreads, onProgress)
            mergeChunks(partFile, total, wantThreads)
            onProgress(total, total)
            AppLogger.i("MultiPart", "done id=${distro.id} bytes=${partFile.length()}")
            partFile
        } catch (e: CancellationException) {
            cleanupChunks(partFile)
            throw e
        } catch (e: Exception) {
            cleanupChunks(partFile)
            // 首要地址的分块下载失败时，用**其余候选**走单线程重试一遍。
            // 多镜像回退不能因为「走的是分块路径」就失效——用户看到的应当
            // 与单线程路径一致的容错能力。分块失败已经清理干净，这里从零重下。
            val rest = urls.drop(1)
            if (rest.isNotEmpty() && e !is CancellationException) {
                AppLogger.w(
                    "MultiPart",
                    "multipart failed on ${urls.first()}, falling back to ${rest.size} other mirror(s): ${e.message}"
                )
                return@withContext try {
                    single.download(distro, partFile, rest, onProgress)
                } catch (e2: Exception) {
                    AppLogger.e("MultiPart", "all mirrors failed id=${distro.id}", e2)
                    // 抛出**最初**的失败原因：它来自优选过的首选地址，信息量更大
                    throw e
                }
            }
            AppLogger.e("MultiPart", "multipart failed, cleaning chunks", e)
            // 分块下载失败**不**自动降级为「同一地址的单线程重下」：可能是网络瞬断，
            // 而单线程重下整个文件在慢网下代价很大。保留 .part 让用户重试时
            // 走单线程 Range 续传。（上面的镜像回退是换地址，与这里不同。）
            throw e
        }
    }

    /**
     * 用 HEAD 探测长度与 Range 支持。
     * HEAD 不可用时退化为 `Range: bytes=0-0` + 读 `Content-Range` 的总长。
     */
    private fun probeLength(url: String, distro: DistroInfo): Long {
        try {
            val head = client.newCall(Request.Builder().url(url).head().build()).execute()
            head.use { resp ->
                if (resp.isSuccessful) {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val ranges = resp.header("Accept-Ranges")
                    if (len > 0) {
                        AppLogger.i("MultiPart", "HEAD len=$len accept-ranges=$ranges")
                        // Accept-Ranges 缺失时不能直接判死：不少 CDN 不返回它但支持 Range。
                        // 真正的判定放在首块请求收到 206 还是 200。
                        return len
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w("MultiPart", "HEAD failed: ${e.message}")
        }
        // 退化：清单里的 size 只是展示值，不能当作可信总长，这里仅用于
        // 决定「是否值得分块」；真正合并时的校验用各块实际字节之和。
        return if (distro.size > 0) distro.size else -1L
    }

    /**
     * 并发下载全部分块。每块独立一个文件，进度用 [AtomicLong] 汇总。
     *
     * 采用「先全部 async，再 awaitAll」；任意一块抛出（含 CancellationException）
     * 会取消兄弟协程并向上传播，由调用方统一清理。
     */
    private suspend fun downloadChunks(
        url: String,
        partFile: File,
        total: Long,
        threads: Int,
        onProgress: (Long, Long) -> Unit
    ) = coroutineScope {
        val chunkSize = (total + threads - 1) / threads
        val doneBytes = AtomicLong(0L)

        (0 until threads).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize - 1, total - 1)
            async(Dispatchers.IO) {
                if (start > end) return@async
                val chunk = chunkFile(partFile, index)
                downloadChunk(url, chunk, start, end, doneBytes, total, onProgress)
            }
        }.awaitAll()
    }

    /**
     * 下载单块。
     *
     * ⚠️ 若服务端对该请求返回 200（而非 206），说明它忽略了 Range 头，
     * 返回的是**整个文件**。此时若照写，这一块会包含全量数据，
     * 合并后必然损坏——因此必须直接抛错，由调用方清理并重试。
     */
    private fun downloadChunk(
        url: String,
        chunkFile: File,
        start: Long,
        end: Long,
        doneBytes: AtomicLong,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        if (cancelled) throw CancellationException("下载已取消")

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity")
            .get()
            .build()

        val expected = end - start + 1
        // 分块复用（续传）必须同时满足**三个**条件，缺一不可：
        //   1. 长度等于本块应有的长度；
        //   2. 来源 URL 与上次一致；
        //   3. 总长度与上次一致。
        //
        // ⚠️ 只用长度判断是危险的：分块文件按 `<id>.<fmt>.part.<index>` 命名，
        // 会跨应用重启、跨镜像留存。若换了镜像（测速排序本身就不稳定），
        // 而新镜像的文件恰好字节数相同但内容不同（同一版本的重新构建），
        // 旧的完成块会被当成有效数据直接参与合并——总长度校验也能通过，
        // 于是产出一个**损坏但长度正确**的归档。Debian/Ubuntu 在清单里
        // `sha256` 为空，没有任何完整性网兜底，所以必须在这里挡住。
        val marker = chunkMarker(chunkFile)
        val reusable = chunkFile.isFile &&
            chunkFile.length() == expected &&
            marker.isFile &&
            marker.readText() == chunkToken(url, total)
        if (reusable) {
            doneBytes.addAndGet(expected)
            onProgress(doneBytes.get(), total)
            AppLogger.i("MultiPart", "chunk[$start-$end] reused (${expected}B, same source)")
            return
        }
        // 来源不符或缺少标记：丢弃重下，绝不复用
        if (chunkFile.isFile && !reusable) {
            AppLogger.i("MultiPart", "chunk[$start-$end] discarded (source/length mismatch)")
            chunkFile.delete()
        }
        marker.writeText(chunkToken(url, total))

        client.newCall(request).execute().use { resp ->
            if (resp.code != 206) {
                throw IOException(
                    "分块下载要求 206，实际 ${resp.code}（服务端不支持 Range）: $url"
                )
            }
            val body = resp.body ?: throw IOException("空响应体: $url")
            chunkFile.parentFile?.mkdirs()
            var written = 0L
            body.byteStream().use { input ->
                FileOutputStream(chunkFile, false).use { out ->
                    val buf = ByteArray(CHUNK_BUFFER)
                    var lastEmit = 0L
                    while (true) {
                        if (cancelled) throw CancellationException("下载已取消")
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            out.write(buf, 0, n)
                            written += n
                            doneBytes.addAndGet(n.toLong())
                        }
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                            lastEmit = now
                            onProgress(doneBytes.get(), total)
                        }
                    }
                    out.flush()
                }
            }
            if (written != expected) {
                // 块不完整：删掉它，让重试时整块重下，避免合并出损坏文件
                chunkFile.delete()
                throw IOException("分块不完整 $written/$expected ($start-$end)")
            }
        }
    }

    /**
     * 按序合并分块 → [partFile]，并**校验总长度**。
     *
     * 长度不符必须整份丢弃：残缺的归档在解压阶段会报出难以理解的
     * tar 错误，远不如在这里明确失败。
     */
    private fun mergeChunks(partFile: File, total: Long, threads: Int) {
        val merged = File(partFile.parentFile, "${partFile.name}.merge")
        if (merged.exists() && !merged.delete()) {
            throw IOException("无法清理上次的合并残留: ${merged.absolutePath}")
        }
        try {
            FileOutputStream(merged, false).use { out ->
                val buf = ByteArray(CHUNK_BUFFER)
                for (i in 0 until threads) {
                    val chunk = chunkFile(partFile, i)
                    if (!chunk.isFile) {
                        // 空块是合法的（当 threads 多于内容所需时），跳过
                        continue
                    }
                    chunk.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) out.write(buf, 0, n)
                        }
                    }
                }
                out.flush()
            }
            val actual = merged.length()
            if (actual != total) {
                merged.delete()
                throw IOException("合并后长度不符 $actual/$total，已丢弃（请重试）")
            }
            // 原子替换：先删目标再 rename，避免 renameTo 在目标存在时行为不确定
            if (partFile.exists() && !partFile.delete()) {
                merged.delete()
                throw IOException("无法替换旧的 ${partFile.name}")
            }
            if (!merged.renameTo(partFile)) {
                merged.delete()
                throw IOException("合并结果无法重命名为 ${partFile.name}")
            }
        } catch (e: Exception) {
            merged.delete()
            throw e
        }
        cleanupChunks(partFile)
    }

    /** 删除全部分块临时文件；取消与失败路径都必须调用（§8.19） */
    private fun cleanupChunks(partFile: File) {
        for (i in 0 until MAX_THREADS) {
            val f = chunkFile(partFile, i)
            if (f.exists() && !f.delete()) {
                AppLogger.w("MultiPart", "无法删除分块 ${f.name}")
            }
            // 标记文件必须一起删：留下孤儿标记不会被复用逻辑误用
            // （长度检查会先失败），但会永远占着目录
            val m = chunkMarker(f)
            if (m.exists()) m.delete()
        }
    }

    /** 分块临时文件命名 `<id>.<format>.part.<index>`（§8.19 规定的格式） */
    private fun chunkFile(partFile: File, index: Int): File =
        File(partFile.parentFile, "${partFile.name}.$index")

    /** 分块来源标记文件：记录「这块是从哪个 URL、哪个总长度下下来的」 */
    private fun chunkMarker(chunkFile: File): File =
        File(chunkFile.parentFile, "${chunkFile.name}.src")

    /** 标记内容：URL + 总长度，任一变化即视为不可复用 */
    private fun chunkToken(url: String, total: Long): String = "$url|$total"

    companion object {
        /** 分块数上限：再多也不会更快，反而更容易被服务端限流 */
        const val MAX_THREADS = 8

        /** 小于 8MB 的文件分块得不偿失（连接建立开销占比过高） */
        private const val MIN_MULTIPART_BYTES = 8L * 1024 * 1024

        private const val CHUNK_BUFFER = 128 * 1024
        private const val PROGRESS_INTERVAL_MS = 200L

        /**
         * 扫描并清理某版本**两个存储位置**的所有分块残留。
         *
         * `RootfsManager.deletePartFiles` 会连同 `.part` 一起清（含分块后缀），
         * 这里只清分块而保留单线程 `.part`，供「保持断点但放弃多线程残留」的场景使用。
         */
        fun cleanupChunksFor(manager: RootfsManager, id: String) {
            val prefix = "$id."
            for (root in manager.allSandboxDirs()) {
                val files = root.listFiles() ?: continue
                for (f in files) {
                    if (f.isFile && f.name.startsWith(prefix) &&
                        f.name.contains(".part.") && f.delete()
                    ) {
                        AppLogger.i("MultiPart", "cleaned chunk ${f.name}")
                    }
                }
            }
        }
    }
}
