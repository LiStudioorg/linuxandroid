package com.li63050a.linuxandroid

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp 下载器：
 * 1. 断点续传 —— 写入 <id>.<format>.part，已有字节时携带 Range 头；
 * 2. 多镜像回退 —— 主源失败后依次尝试 mirrors，.part 保留；
 * 3. 进度回调 —— 至少 200ms 节流一次。
 */
class RootfsDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {

    @Volatile
    private var activeCall: Call? = null

    private val contentRangeRegex = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")

    /** 取消当前网络请求（配合协程取消，用于中断阻塞中的 execute） */
    fun cancel() {
        activeCall?.cancel()
    }

    /**
     * 依次尝试 [DistroInfo.allUrls] 下载到 [partFile]。
     * 全部地址失败才抛出最后一个异常；部分失败时保留 .part 供续传。
     * 返回下载完成的 part 文件。
     */
    suspend fun download(
        distro: DistroInfo,
        partFile: File,
        onProgress: (done: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (url in distro.allUrls) {
            ensureActive() // 已取消则不再尝试下一镜像
            try {
                downloadFrom(url, distro, partFile, onProgress)
                return@withContext partFile
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive() // 主动 cancel 导致的 IOException 在这里转为 CancellationException
                lastError = e
            }
        }
        throw lastError ?: IOException("没有可用的下载地址")
    }

    /** 单个地址的完整下载流程。 */
    private fun downloadFrom(
        url: String,
        distro: DistroInfo,
        partFile: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val existing = if (partFile.exists()) partFile.length() else 0L
        val builder = Request.Builder()
            .url(url)
            .get()
            // 关闭透明 gzip，保证 Range 字节偏移与文件内容一致
            .header("Accept-Encoding", "identity")
        if (existing > 0) {
            builder.header("Range", "bytes=$existing-")
        }

        val call = client.newCall(builder.build())
        activeCall = call
        try {
            call.execute().use { response ->
                // Range 起点已越过文件末尾：视为已下载完整
                if (response.code == 416) {
                    val len = partFile.length()
                    onProgress(len, len)
                    return
                }
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: $url")
                }
                val body = response.body ?: throw IOException("空响应体: $url")

                var append = false
                var base = 0L
                // 服务器声明的真实总字节数（用于完整性判断，-1 表示未知）
                var verifyTotal = -1L

                when (response.code) {
                    206 -> {
                        val match = response.header("Content-Range")
                            ?.let(contentRangeRegex::find)
                        val start = match?.groupValues?.get(1)?.toLongOrNull() ?: existing
                        when {
                            start == 0L -> {
                                append = false
                                base = 0L
                            }
                            start == existing -> {
                                append = true
                                base = existing
                            }
                            else -> throw IOException(
                                "Content-Range 起点不匹配(期望 $existing 实际 $start): $url"
                            )
                        }
                        val totalStr = match?.groupValues?.get(3)
                        verifyTotal = totalStr
                            ?.takeIf { it != "*" }
                            ?.toLongOrNull() ?: -1L
                    }
                    else -> {
                        // 200：服务器忽略 Range 或不支持断点，从头覆盖写入
                        append = false
                        base = 0L
                        verifyTotal = body.contentLength().takeIf { it > 0 } ?: -1L
                    }
                }

                // UI 展示用总长：真实总长未知时回退到清单里的预计大小
                val uiTotal = if (verifyTotal > 0) verifyTotal
                else if (distro.size > 0) distro.size
                else -1L

                partFile.parentFile?.mkdirs()
                var done = base
                var lastEmit = 0L
                body.byteStream().use { input ->
                    FileOutputStream(partFile, append).use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                out.write(buf, 0, n)
                                done += n
                            }
                            val now = SystemClock.uptimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                lastEmit = now
                                onProgress(done, uiTotal)
                            }
                        }
                        out.flush()
                    }
                }
                onProgress(done, uiTotal)

                // 服务器声明了总长却提前断开：视为不完整，交给下一镜像续传
                if (verifyTotal > 0 && done < verifyTotal) {
                    throw IOException("下载不完整 $done/$verifyTotal: $url")
                }
            }
        } finally {
            activeCall = null
        }
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 200L
    }
}
