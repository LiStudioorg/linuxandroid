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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 持久 PRoot shell 会话：
 *
 * libproot.so -r <rootfs> -0 -w /root \
 *   -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random <shell>
 *
 * 必设环境变量：PROOT_TMP_DIR / PROOT_LOADER / LD_LIBRARY_PATH（均绝对路径）。
 * 启动命令与输出写入 AppLogger。
 *
 * **生命周期**：本类只负责「一个 shell 进程 + 它的输出缓冲」，
 * 不持有 Activity。进程的所有权归 [SessionManager]（多会话管理器），
 * 由 [ProotService] 前台服务驱动存活，因此 Activity 销毁不会杀死 shell。
 *
 * **多实例**：每个实例一个独立的 `ProotSession` 对象，彼此没有任何共享状态
 * （各自的进程、输出缓冲、Listener）。多个实例可同时运行（§8.17）。
 * Activity 通过 [detach] + [attach] 订阅其中一个，并用 [snapshot] 补齐断档输出。
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
    private val versionId: String,
    private val versionName: String,
    private val limits: ResourceLimits = ResourceLimits.UNLIMITED,
    listener: Listener? = null
) {

    interface Listener {
        /** 任意线程回调；实现方需自行切换到主线程 */
        fun onOutput(text: String)

        /**
         * 进程结束（正常退出 / 被 [destroy] / 启动失败后自毁）。
         * @param code 退出码；-1 表示被强制结束
         * @param reason 结束原因，供 UI 生成可读文案
         */
        fun onExit(code: Int, reason: ExitReason)
    }

    enum class ExitReason { NORMAL, DESTROYED, START_FAILED }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var listener: Listener? = listener

    /** 防止 normal exit 与 destroy 两条路径重复回调 onExit */
    private val exitNotified = AtomicBoolean(false)

    /**
     * 会话完整输出（容量上限 [MAX_OUTPUT_CHARS]，超出丢弃最旧一半）。
     * 所有写入都在主线程（readLoop 通过 mainHandler.post），因此无需加锁。
     */
    private val buffer = StringBuilder()

    /**
     * 被截掉的历史前缀长度，供 UI 提示用户输出不完整。
     * 写入在主线程，读取可能来自任意线程，故声明为 `@Volatile`
     * （Int 的读写本身是原子的，volatile 只保证可见性）。
     */
    @Volatile
    var truncatedChars: Int = 0
        private set

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

    /**
     * 当前会话输出的只读快照。
     *
     * **线程约定**：`buffer` 是 `StringBuilder`（非线程安全），所有读写都被
     * 限制在主线程——`readLoop` 通过 `mainHandler.post` 追加，本方法在非主线程
     * 调用时也会 `post` 回去取。若直接跨线程读，会与追加竞争导致内容损坏。
     */
    fun snapshot(): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return buffer.toString()
        }
        // 非主线程：把读取也排进主线程队列，保证与追加串行
        val holder = arrayOf("")
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            holder[0] = buffer.toString()
            latch.countDown()
        }
        return try {
            // 有上限地等待，避免主线程卡死时永久阻塞调用者
            if (!latch.await(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // 超时：主线程没能在限期内处理。此时**不能**假装读到空内容
                // （那会让 UI 把一个正在运行的会话显示成「无输出」），
                // 改为如实报告“读取超时”。
                AppLogger.w(TAG, "snapshot timed out on worker thread")
                SNAPSHOT_TIMEOUT_MARK
            } else {
                holder[0]
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            SNAPSHOT_TIMEOUT_MARK
        }
    }

    /**
     * 在会话缓冲里追加一段文本（供 UI 书写「[ProotTerm] …」这类本地提示，
     * 它们不属于进程输出，但必须和进程输出一起被搜索/滚动/截断）。
     */
    fun appendLocal(text: String) {
        if (text.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendInternal(text)
        } else {
            mainHandler.post { appendInternal(text) }
        }
    }

    /** 换观察者：Activity 换实例时先 [detach] 旧的，再 [attach] 新的 */
    fun attach(l: Listener?) {
        listener = l
    }

    /**
     * 解除观察者。
     * **不清空输出缓冲**：Activity 销毁后重建时还要靠快照恢复历史；
     * 用户主动关闭终端时由调用方显式调用 [clearOutput]。
     */
    fun detach() {
        listener = null
    }

    /** 清空输出缓冲；与 [snapshot] 同样受主线程约定保护 */
    fun clearOutput() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            buffer.setLength(0)
            truncatedChars = 0
        } else {
            mainHandler.post {
                buffer.setLength(0)
                truncatedChars = 0
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
        if (!prootTmpDir.isDirectory && !prootTmpDir.mkdirs() && !prootTmpDir.isDirectory) {
            throw IOException("无法创建 PROOT_TMP_DIR: ${prootTmpDir.absolutePath}")
        }

        // 资源限制的落地方式：让 proot 执行 `/bin/sh -c '<ulimit …>; exec <shell>'`。
        //
        // ⚠️ 这里必须传 **3 个独立 argv**（`/bin/sh`、`-c`、脚本文本），
        // 不能把整段脚本拼成**一个**字符串。proot 不是 `env`/`nice`，它不会
        // 替你调用 shell：它把最后一个参数当**可执行文件路径**解析，失败即报
        // `'…' not found (root = …, $PATH=…)`。曾经写成单字符串的版本会让
        // 「凡是设置了资源限制的会话一律启动失败」。
        //
        // 脚本末尾的 `exec` 不可省略（§8.18）——没有它进程树会多一层 `sh -c`，
        // destroy() 只能杀掉中间层，真 shell 会变孤儿并继续占用 rootfs。
        val script = limits.toScript(shell)
        val guestArgs = limits.toGuestCommand(shell)
        if (script != null) {
            AppLogger.i("Proot", "limits $versionId: ${limits.summary()} -> sh -c '$script'")
        }

        val command = buildList {
            add(prootBin.absolutePath)
            add("-r"); add(rootfs.absolutePath)
            add("-0")
            add("-w"); add("/root")
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("/dev/urandom:/dev/random")
            addAll(guestArgs)   // 无限制时是 [shell]，有限制时是 [/bin/sh, -c, script]
        }

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
        // 让 guest 内的程序知道自己在容器里，部分脚本据此调整行为
        env["PROOTTERM_VERSION"] = versionId

        AppLogger.i("Proot", "start $versionId cmd=${command.joinToString(" ")}")
        AppLogger.i(
            "Proot",
            "env PROOT_TMP_DIR=${env["PROOT_TMP_DIR"]} PROOT_LOADER=${env["PROOT_LOADER"]} LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}"
        )

        val p = try {
            pb.start()
        } catch (e: IOException) {
            AppLogger.e("Proot", "ProcessBuilder.start failed $versionId", e)
            throw IOException(explainStartFailure(e), e)
        }
        process = p
        writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8).buffered()
        exitNotified.set(false)

        Thread({ readLoop(p) }, "proot-stdout").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 把 ProcessBuilder 的原始错误翻译成用户能理解的原因。
     * 外部存储位置（/Android/data）在 Android 11+ 上由 FUSE 提供，
     * 通常不允许执行 rootfs 里的二进制，这里给出可操作的提示。
     */
    private fun explainStartFailure(e: IOException): String {
        val msg = e.message.orEmpty()
        val permissionDenied = msg.contains("denied", ignoreCase = true) ||
            msg.contains("EACCES", ignoreCase = true) ||
            msg.contains("Permission", ignoreCase = true)
        return if (permissionDenied) {
            "无法执行 PRoot 二进制（权限被拒绝）。\n" +
                "若 rootfs 安装在「外部存储 /Android/data」，该位置在 Android 11+ 上" +
                "通常不允许执行文件。请在设置页把存储位置改为「内部存储」后重新安装。\n" +
                "原始错误：$msg"
        } else {
            "启动 PRoot 失败：$msg"
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
                    // 输出/错误流合并（redirectErrorStream）。
                    // 不在此处写 AppLogger：交互式命令（如 `cat 大文件`）会刷爆
                    // 1MB 日志文件并把真正有用的启动/失败信息挤掉；
                    // 需要完整输出时由「分享日志」或终端自身的输出区提供。
                    mainHandler.post {
                        appendInternal(chunk)
                        listener?.onOutput(chunk)
                    }
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
        AppLogger.i("Proot", "exit $versionId code=$code")
        mainHandler.post {
            // destroy() 已经通知过就不再重复；START_FAILED 也是
            if (exitNotified.compareAndSet(false, true)) {
                listener?.onExit(code, ExitReason.NORMAL)
            }
        }
    }

    private fun appendInternal(text: String) {
        buffer.append(text)
        if (buffer.length > MAX_OUTPUT_CHARS) {
            val drop = buffer.length - MAX_OUTPUT_CHARS / 2
            buffer.delete(0, drop)
            truncatedChars += drop
        }
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
        AppLogger.i("Proot", "destroy requested $versionId")
        process = null
        synchronized(this) {
            runCatching { writer?.close() }
            writer = null
        }
        // 主动销毁要立刻通知 UI，不等 readLoop 观察到 EOF
        if (exitNotified.compareAndSet(false, true)) {
            mainHandler.post { listener?.onExit(-1, ExitReason.DESTROYED) }
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

    companion object {
        private const val TAG = "ProotSession"

        /** 单会话输出上限：超出后丢弃最旧一半，避免长命令把内存吃满 */
        private const val MAX_OUTPUT_CHARS = 200_000

        /** 非主线程读快照的最长等待；超时如实报告而非伪装成空输出 */
        private const val SNAPSHOT_TIMEOUT_MS = 2_000L

        /** 快照读取超时的哨兵文本（非空，避免被 UI 误判为「无输出」） */
        const val SNAPSHOT_TIMEOUT_MARK = "[ProotTerm] 读取会话输出超时，请稍后重试\n"
    }
}
