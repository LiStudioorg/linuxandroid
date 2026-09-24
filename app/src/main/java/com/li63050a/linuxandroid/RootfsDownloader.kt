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
 * 全程 Dispatchers.IO；关键节点写入 AppLogger。
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

    fun cancel() {
        try {
            activeCall?.cancel()
            AppLogger.w("Downloader", "cancel requested")
        } catch (e: Exception) {
            AppLogger.e("Downloader", "cancel failed", e)
        }
    }

    suspend fun download(
        distro: DistroInfo,
        partFile: File,
        onProgress: (done: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        AppLogger.i(
            "Downloader",
            "start id=${distro.id} existing=${partFile.length()} urls=${distro.allUrls.size}"
        )
        for (url in distro.allUrls) {
            ensureActive()
            try {
                downloadFrom(url, distro, partFile, onProgress)
                AppLogger.i("Downloader", "done id=${distro.id} bytes=${partFile.length()}")
                return@withContext partFile
            } catch (e: CancellationException) {
                AppLogger.w("Downloader", "cancelled id=${distro.id}")
                throw e
            } catch (e: Exception) {
                ensureActive()
                AppLogger.e("Downloader", "failed id=${distro.id} url=$url", e)
                lastError = e
            }
        }
        val err = lastError ?: IOException("没有可用的下载地址")
        AppLogger.e("Downloader", "all urls failed id=${distro.id}", err)
        throw err
    }

    private fun downloadFrom(
        url: String,
        distro: DistroInfo,
        partFile: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val existing = if (partFile.exists()) partFile.length() else 0L
        AppLogger.i("Downloader", "GET $url (resume=$existing)")
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept-Encoding", "identity")
        if (existing > 0) {
            builder.header("Range", "bytes=$existing-")
        }

        val call = client.newCall(builder.build())
        activeCall = call
        try {
            call.execute().use { response ->
                AppLogger.i("Downloader", "HTTP ${response.code} $url")
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
                        verifyTotal = match?.groupValues?.get(3)
                            ?.takeIf { it != "*" }
                            ?.toLongOrNull() ?: -1L
                    }
                    else -> {
                        append = false
                        base = 0L
                        verifyTotal = body.contentLength().takeIf { it > 0 } ?: -1L
                    }
                }

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
