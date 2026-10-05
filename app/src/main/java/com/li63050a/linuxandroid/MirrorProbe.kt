package com.li63050a.linuxandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 镜像探测结果。
 *
 * @property url     被测地址
 * @property latencyMs 连接+首字节延迟；[FAILED_LATENCY] 表示探测失败
 * @property error   失败原因（成功时为 null），仅用于日志与 UI 提示
 */
data class MirrorProbe(
    val url: String,
    val latencyMs: Long,
    val error: String? = null
) {
    val ok: Boolean get() = latencyMs != FAILED_LATENCY

    companion object {
        const val FAILED_LATENCY = Long.MAX_VALUE
    }
}

/**
 * 多镜像并发测速，用于自动优选下载源（§8.20）。
 *
 * 设计要点：
 *  1. **并发**探测所有候选；串行等待最慢的镜像会让「优选」本身比下载还慢；
 *  2. **总超时上限**：整体用 `withTimeoutOrNull` 兜住，超时的镜像标记为失败，
 *     而不是让用户一直等；
 *  3. **失败必须可回退**：全部探测失败时返回空列表，调用方回退到清单原始顺序。
 *     测速只是为了更快，绝不能因为测速失败导致用户下载不了。
 *  4. 测速结果只是**排序建议**，不是信任来源——完整性仍由 SHA256 保证。
 *
 * 探测方式用 HTTP `HEAD`（不下载正文），失败时退化为 `GET` + `Range: bytes=0-0`：
 * 部分 CDN/对象存储不支持 HEAD，直接判失败会误伤。
 */
object MirrorProbeService {

    /** 单个镜像的探测超时 */
    private const val PER_MIRROR_TIMEOUT_MS = 4_000L

    /** 整体上限；超过就按已有结果排序，未返回的算失败 */
    private const val TOTAL_TIMEOUT_MS = 6_000L

    /** 探测用的客户端：短超时，与下载用的客户端分开，避免互相影响 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PER_MIRROR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PER_MIRROR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PER_MIRROR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 并发探测所有候选地址，按延迟升序返回。
     *
     * @return 探测成功的镜像（已排序）；**全部失败时返回空列表**
     */
    suspend fun probe(urls: List<String>): List<MirrorProbe> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext emptyList()
        val distinct = urls.distinct()

        val results = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            coroutineScope {
                distinct.map { url ->
                    async { probeOne(url) }
                }.map { it.await() }
            }
        } ?: run {
            // 整体超时：降级为「已完成的那些」，未完成的按失败处理。
            // 这里不再取消后重新发起，直接把全部标记失败让调用方回退，
            // 避免用户在网络差时反复等待。
            AppLogger.w("MirrorProbe", "total timeout, falling back to manifest order")
            emptyList()
        }

        val ok = results.filter { it.ok }.sortedBy { it.latencyMs }
        val bad = results.filter { !it.ok }
        if (bad.isNotEmpty()) {
            AppLogger.i(
                "MirrorProbe",
                "unreachable: ${bad.joinToString { "${it.url}(${it.error})" }}"
            )
        }
        AppLogger.i(
            "MirrorProbe",
            "ranked: ${ok.joinToString { "${it.url}=${it.latencyMs}ms" }}"
        )
        ok
    }

    /**
     * 按探测结果重排下载地址。
     *
     * @param candidates 清单给出的原始顺序（主源在前）
     * @return 优选后的顺序；测速全失败时**原样返回** candidates
     */
    suspend fun rank(candidates: List<String>): List<String> {
        if (candidates.size <= 1) return candidates
        val ranked = probe(candidates)
        if (ranked.isEmpty()) {
            AppLogger.i("MirrorProbe", "no reachable mirror, keep manifest order")
            return candidates
        }
        // 未探测成功的排在后面（保底），成功过的按延迟排前面
        val okUrls = ranked.map { it.url }
        val rest = candidates.filter { it !in okUrls }
        return okUrls + rest
    }

    /**
     * 探测单个地址：先 HEAD，不支持则用 `Range: bytes=0-0` 的 GET。
     * 任何异常都翻译成失败结果，不向外抛——测速失败不该中断流程。
     */
    private fun probeOne(url: String): MirrorProbe {
        val start = android.os.SystemClock.elapsedRealtime()
        return try {
            val head = Request.Builder().url(url).head().build()
            val code = client.newCall(head).execute().use { it.code }
            if (code in 200..399) {
                MirrorProbe(url, android.os.SystemClock.elapsedRealtime() - start)
            } else {
                // HEAD 不被支持（405/501）或返回错误码时，退化为极小范围 GET
                probeWithRangeGet(url, start)
            }
        } catch (e: Exception) {
            probeWithRangeGet(url, start)
        }
    }

    private fun probeWithRangeGet(url: String, start: Long): MirrorProbe = try {
        val get = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        client.newCall(get).execute().use { resp ->
            // ⚠️ 必须显式关闭 body。OkHttp 只有「读完或关闭」才把连接归还连接池；
            // 只看 code 就走人会把这条连接一直挂着。HEAD 分支没这个问题
            // （HEAD 无 body），但这个退化分支是对象存储的**常见路径**，
            // 泄漏会随每次测速累积。
            resp.body?.close()
            // 200（整文件）与 206（部分内容）都说明地址可达
            if (resp.code in 200..399) {
                MirrorProbe(url, android.os.SystemClock.elapsedRealtime() - start)
            } else {
                MirrorProbe(url, MirrorProbe.FAILED_LATENCY, "HTTP ${resp.code}")
            }
        }
    } catch (e: IOException) {
        MirrorProbe(url, MirrorProbe.FAILED_LATENCY, e.message ?: "IO error")
    } catch (e: Exception) {
        MirrorProbe(url, MirrorProbe.FAILED_LATENCY, e.message ?: e.javaClass.simpleName)
    }
}
