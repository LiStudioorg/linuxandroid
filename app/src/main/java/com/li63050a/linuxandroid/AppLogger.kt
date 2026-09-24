package com.li63050a.linuxandroid

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量日志系统：
 * - 格式：`yyyy-MM-dd HH:mm:ss.SSS | 级别 | 标签 | 消息（含异常堆栈）`
 * - 三级输出：内存环形缓冲 + filesDir/logs/app.log（1MB 滚动 .old，不覆盖旧日志）+ Logcat
 * - 线程安全；闪退时由 App 的 UncaughtExceptionHandler 先写文件再杀进程
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val MAX_MEMORY_ENTRIES = 2000
    private const val MAX_FILE_BYTES = 1024L * 1024L

    private val lock = Any()
    private val entries = ArrayDeque<String>()
    private var logDir: File? = null
    private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(filesDir: File) {
        synchronized(lock) {
            try {
                val dir = File(filesDir, "logs")
                if (!dir.isDirectory) dir.mkdirs()
                logDir = dir
                logFile = File(dir, "app.log")
            } catch (e: Exception) {
                Log.e(TAG, "init log file failed", e)
            }
        }
    }

    fun d(tag: String, msg: String) = append('D', tag, msg, null)
    fun i(tag: String, msg: String) = append('I', tag, msg, null)
    fun w(tag: String, msg: String) = append('W', tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable? = null) = append('E', tag, msg, tr)

    private fun append(level: Char, tag: String, msg: String, tr: Throwable?) {
        val time = timeFormat.format(Date())
        val levelName = when (level) {
            'E' -> "ERROR"
            'W' -> "WARN"
            'D' -> "DEBUG"
            else -> "INFO"
        }
        val line = "$time | $levelName | $tag | $msg"
        val full = if (tr != null) {
            line + "\n" + Log.getStackTraceString(tr).trimEnd()
        } else {
            line
        }
        synchronized(lock) {
            entries.addLast(full)
            while (entries.size > MAX_MEMORY_ENTRIES) entries.removeFirst()
            when (level) {
                'E' -> Log.e(tag, msg, tr)
                'W' -> Log.w(tag, msg, tr)
                'D' -> Log.d(tag, msg)
                else -> Log.i(tag, msg)
            }
            try {
                val f = logFile ?: return
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    val old = File(f.parentFile, "app.log.old")
                    old.delete()
                    f.renameTo(old)
                }
                FileOutputStream(f, true).use { out ->
                    out.write((full + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                Log.e(TAG, "write log file failed", e)
            }
        }
    }

    /** 当前内存中的全部日志（供日志页展示） */
    fun snapshot(): String = synchronized(lock) {
        entries.joinToString("\n")
    }

    /**
     * 确保可分享的日志文件存在；若文件尚未创建，把内存缓冲落盘。
     */
    fun ensureLogFile(): File {
        synchronized(lock) {
            var f = logFile
            if (f == null) {
                val dir = logDir ?: File(
                    System.getProperty("java.io.tmpdir") ?: "/data/local/tmp",
                    "logs"
                )
                if (!dir.isDirectory) dir.mkdirs()
                logDir = dir
                f = File(dir, "app.log")
                logFile = f
            }
            if (!f.exists() || f.length() == 0L) {
                try {
                    f.parentFile?.mkdirs()
                    val pending = entries.joinToString("\n")
                    if (pending.isNotEmpty()) {
                        FileOutputStream(f, true).use { out ->
                            out.write((pending + "\n").toByteArray(Charsets.UTF_8))
                        }
                    } else {
                        f.createNewFile()
                    }
                } catch (_: Exception) {
                }
            }
            return f
        }
    }

    /** 清空内存与文件 */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            try {
                logFile?.delete()
                val dir = logDir
                if (dir != null) File(dir, "app.log.old").delete()
            } catch (_: Exception) {
            }
        }
    }
}
