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
 * 必设环境变量：
 * - PROOT_TMP_DIR：宿主侧私有临时目录（绝对路径）
 * - PROOT_LOADER：libproot-loader.so 绝对路径
 * - LD_LIBRARY_PATH：NativeDeps 释放的 libtalloc/libandroid-shmem 搜索路径
 *
 * stdout/stderr 合并读取，后台线程解码 UTF-8 后经主线程回调；
 * 非 PTY 下 shell 无提示符、不回显，由 UI 侧做本地回显。
 *
 * minSdk 24 兼容：Process.isAlive / waitFor(timeout) / destroyForcibly
 * 均为 API 26+，以下用 Build.VERSION 分支，低版本走 exitValue/无限 waitFor。
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

    /** 输出与退出事件回调（均派发在主线程） */
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
                // API 24/25：exitValue() 抛异常表示仍在运行
                try {
                    p.exitValue()
                    false
                } catch (_: IllegalThreadStateException) {
                    true
                }
            }
        }

    /** 启动 shell；缺二进制/缺 rootfs 抛 IOException，由 UI 展示 */
    @Throws(IOException::class)
    fun start() {
        if (isRunning) return
        if (!prootBin.isFile) throw IOException("缺少 PRoot 二进制: ${prootBin.name}")
        if (!loader.isFile) throw IOException("缺少 PRoot loader: ${loader.name}")
        if (!rootfs.isDirectory) throw IOException("rootfs 不存在: ${rootfs.absolutePath}")

        prootBin.setExecutable(true, false) // 兜底确保可执行
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

        // —— proot 运行期依赖（必须是宿主绝对路径） ——
        env["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
        env["PROOT_LOADER"] = loader.absolutePath
        // libtalloc/libandroid-shmem 搜索路径（覆盖 proot 内置的 Termux RUNPATH 失效场景）
        extraLibDir?.let { dir ->
            val existing = env["LD_LIBRARY_PATH"]
            env["LD_LIBRARY_PATH"] =
                if (existing.isNullOrEmpty()) dir.absolutePath
                else "${dir.absolutePath}:$existing"
        }

        // —— 透传给 guest shell 的环境 ——
        env["TERM"] = "xterm-256color"
        env["HOME"] = "/root"
        env["LANG"] = "C.UTF-8"
        env["TMPDIR"] = "/tmp"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        val p = pb.start()
        process = p
        writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8).buffered()

        Thread({ readLoop(p) }, "proot-stdout").apply {
            isDaemon = true
            start()
        }
    }

    /** 后台线程：持续读取合并后的 stdout/stderr，直到 EOF 再等待退出 */
    private fun readLoop(p: Process) {
        try {
            InputStreamReader(p.inputStream, Charsets.UTF_8).use { reader ->
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    mainHandler.post { listener.onOutput(chunk) }
                }
            }
        } catch (_: IOException) {
            // 进程被销毁时的正常路径
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
        mainHandler.post { listener.onExit(code) }
    }

    /** 向 shell 标准输入写入一行命令（末尾补 \n） */
    @Throws(IOException::class)
    fun sendCommand(command: String) {
        val w = writer ?: throw IOException("Shell 未运行")
        synchronized(this) {
            w.write(command)
            w.write("\n")
            w.flush()
        }
    }

    /** 结束会话：先关 stdin 让 shell 自行退出，2 秒后强杀（低版本仅 destroy） */
    fun destroy() {
        val p = process ?: return
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
                    // API 24/25 无带超时的 waitFor / destroyForcibly
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
