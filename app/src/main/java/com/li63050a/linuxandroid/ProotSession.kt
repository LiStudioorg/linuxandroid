package com.li63050a.linuxandroid

import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * 持久 PRoot shell 会话：
 *
 * libproot.so -r <rootfs> -0 -w /root \
 *   -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random <shell>
 *
 * 必设环境变量：PROOT_TMP_DIR / PROOT_LOADER / LD_LIBRARY_PATH（均绝对路径）。
 * 启动命令与输出写入 AppLogger。
 *
 * minSdk 24：isAlive/waitFor(timeout)/destroyForcibly 走 SDK_INT 分支。
 */
class ProotSession(
    private val prootBin: File,
    private val loader: File,
    private val rootfs: File,
    private val shell: String,
    private val prootTmpDir: File,
    private val extraLibDir: File?,
    private val listener: Listener
) {

    interface Listener {
        fun onOutput(text: String)
        fun onExit(code: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    val isRunning: Boolean
        get() {
            val p = process ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.isAlive
            } else {
                try {
                    p.exitValue()
                    false
                } catch (_: IllegalThreadStateException) {
                    true
                }
            }
        }

    @Throws(IOException::class)
    fun start() {
        if (isRunning) return
        if (!prootBin.isFile) throw IOException("缺少 PRoot 二进制: ${prootBin.name}")
        if (!loader.isFile) throw IOException("缺少 PRoot loader: ${loader.name}")
        if (!rootfs.isDirectory) throw IOException("rootfs 不存在: ${rootfs.absolutePath}")

        prootBin.setExecutable(true, false)
        prootTmpDir.mkdirs()

        val command = listOf(
            prootBin.absolutePath,
            "-r", rootfs.absolutePath,
            "-0",
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            shell
        )

        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        val env = pb.environment()

        env["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
        env["PROOT_LOADER"] = loader.absolutePath
        extraLibDir?.let { dir ->
            val existing = env["LD_LIBRARY_PATH"]
            env["LD_LIBRARY_PATH"] =
                if (existing.isNullOrEmpty()) dir.absolutePath
                else "${dir.absolutePath}:$existing"
        }

        env["TERM"] = "xterm-256color"
        env["HOME"] = "/root"
        env["LANG"] = "C.UTF-8"
        env["TMPDIR"] = "/tmp"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        AppLogger.i("Proot", "start cmd=${command.joinToString(" ")}")
        AppLogger.i(
            "Proot",
            "env PROOT_TMP_DIR=${env["PROOT_TMP_DIR"]} PROOT_LOADER=${env["PROOT_LOADER"]} LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}"
        )

        val p = pb.start()
        process = p
        writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8).buffered()

        Thread({ readLoop(p) }, "proot-stdout").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(p: Process) {
        try {
            InputStreamReader(p.inputStream, Charsets.UTF_8).use { reader ->
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    // 输出/错误流合并（redirectErrorStream），完整记录到日志
                    if (chunk.isNotBlank()) {
                        AppLogger.d("ProotOut", chunk.trimEnd())
                    }
                    mainHandler.post { listener.onOutput(chunk) }
                }
            }
        } catch (e: IOException) {
            AppLogger.w("Proot", "read loop ended: ${e.message}")
        }
        val code = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
        synchronized(this) {
            runCatching { writer?.close() }
            writer = null
        }
        AppLogger.i("Proot", "exit code=$code")
        mainHandler.post { listener.onExit(code) }
    }

    /** 向 shell 标准输入写入一行命令（末尾补 \n） */
    @Throws(IOException::class)
    fun sendCommand(command: String) {
        val w = writer ?: throw IOException("Shell 未运行")
        AppLogger.d("Proot", "send: $command")
        synchronized(this) {
            w.write(command)
            w.write("\n")
            w.flush()
        }
    }

    /** 写入原始控制序列（不追加换行），供 ESC/方向键等快捷键使用 */
    @Throws(IOException::class)
    fun sendRaw(data: String) {
        val w = writer ?: throw IOException("Shell 未运行")
        synchronized(this) {
            w.write(data)
            w.flush()
        }
    }

    fun destroy() {
        val p = process ?: return
        AppLogger.i("Proot", "destroy requested")
        process = null
        synchronized(this) {
            runCatching { writer?.close() }
            writer = null
        }
        p.destroy()
        Thread({
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!p.waitFor(2, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                    }
                } else {
                    p.waitFor()
                }
            } catch (_: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { p.destroyForcibly() }
                }
            }
        }, "proot-reaper").apply {
            isDaemon = true
            start()
        }
    }
}
