package com.li63050a.linuxandroid

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多实例会话管理器（进程内单例的**注册表**，不是会话本身）。
 *
 * 存在的理由：shell 进程的生命周期必须**长于 Activity**。
 * 若由 Activity 直接持有，旋转屏幕/返回桌面/被回收都会连带杀死 shell，
 * 「保持运行」和「开机自启动」都无从谈起。
 *
 * **多实例（§8.17）**：本类用 `Map<String, SessionSlot>` 为**每个实例**维护独立的
 * 进程、输出缓冲与代次计数器。多个实例可以同时运行，互不干扰。
 * 这里刻意**没有**「当前会话」这种全局概念——那正是多实例并存的头号反模式，
 * 所有查询/操作都必须带实例 id。
 *
 * 分工：
 *  - [SessionManager]：谁在跑（按 id）、输出缓冲、状态回调、资源限制下发；
 *  - [ProotService]：前台服务，提供保活与常驻通知，是「会话应该活着」的唯一权威；
 *  - Activity：随时 [attach]/[detach] 观察者，不拥有任何会话。
 *
 * 输出缓冲在 [ProotSession] 内部，本类只做转发，避免两份状态互相漂移。
 */
object SessionManager {

    /**
     * 会话状态变化回调；所有回调都在主线程。
     *
     * ⚠️ 与原单例时代的差异：所有回调都带 `id`，观察者必须按 id 过滤，
     * 否则 A 实例的输出会印到 B 实例的终端里。
     */
    interface Observer {
        fun onSessionState(id: String, state: State, versionName: String?)
        fun onOutput(id: String, text: String)
        fun onExit(id: String, code: Int, reason: ProotSession.ExitReason)
    }

    enum class State {
        /** 该实例没有会话 */
        IDLE,

        /** 正在准备 rootfs / 启动进程 */
        STARTING,

        /** 进程在跑 */
        RUNNING,

        /** 正在结束 */
        STOPPING
    }

    /**
     * 一个实例的运行期状态槽。
     *
     * 每个槽有**自己的** `epoch`：这是多实例能安全并存的关键。
     * 旧单例实现只有一个全局 epoch，两个实例同时启动时会互相把对方判成
     * 「已被取消」而误杀。现在取消只影响被取消的那个实例。
     */
    private class SessionSlot(val id: String) {
        val epoch = AtomicInteger(0)

        @Volatile
        var state: State = State.IDLE

        @Volatile
        var session: ProotSession? = null

        @Volatile
        var versionName: String? = null

        /** 该实例最后一次启动失败/异常的原因，供 UI 一次性展示 */
        @Volatile
        var lastError: String? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 全部实例槽。用 `ConcurrentHashMap` 而不是普通 HashMap：
     * [startBlocking] 在 IO 线程创建槽，UI 线程同时在读，普通 HashMap
     * 并发读写会结构性损坏。
     */
    private val slots = java.util.concurrent.ConcurrentHashMap<String, SessionSlot>()

    /** 当前唯一观察者（同一时刻只有一个 Activity 存活，无需多播） */
    @Volatile
    private var observer: Observer? = null

    private fun slot(id: String): SessionSlot =
        slots.computeIfAbsent(id) { SessionSlot(it) }

    // ------------------------------------------------------------ 查询

    /** 某实例的状态；不存在的实例视为 IDLE */
    fun stateOf(id: String): State = slots[id]?.state ?: State.IDLE

    /** 某实例是否在跑 */
    fun isRunning(id: String): Boolean {
        val s = slots[id] ?: return false
        return s.state == State.RUNNING && s.session?.isRunning == true
    }

    /** 是否有任意实例在跑（通知栏文案、服务是否需要保活用） */
    fun hasAnyRunning(): Boolean = slots.values.any { it.session?.isRunning == true }

    /** 当前全部运行中的实例 id（已排序，便于 UI 稳定展示与测试断言） */
    fun runningIds(): List<String> =
        slots.values.filter { it.session?.isRunning == true }.map { it.id }.sorted()

    /** 某实例最后一次失败原因 */
    fun lastError(id: String): String? = slots[id]?.lastError

    /** 某实例的显示名（用于通知标题） */
    fun versionNameOf(id: String): String? = slots[id]?.versionName

    /** 某实例的输出快照；无会话时返回空串 */
    fun snapshot(id: String): String = slots[id]?.session?.snapshot().orEmpty()

    /** 某实例被丢弃的最旧输出字符数；>0 表示快照不完整 */
    fun truncatedChars(id: String): Int = slots[id]?.session?.truncatedChars ?: 0

    fun clearOutput(id: String) {
        slots[id]?.session?.clearOutput()
    }

    fun attach(o: Observer?) {
        observer = o
    }

    fun detach(o: Observer) {
        if (observer === o) observer = null
    }

    // ------------------------------------------------------------ 启动

    /**
     * 把某实例置为 STARTING，并结束它**自己的**旧会话。
     *
     * 多实例语义下有两点与旧单例实现不同：
     *  1. 只影响传入 id 的槽，其他实例的会话完全不受影响（可以并行运行）；
     *  2. 依然要销毁该实例的旧会话——`startBlocking` 会覆盖 `slot.session`，
     *     不先 destroy 就会留下无人持有、也无法从 UI 结束的孤儿 proot 进程。
     *
     * 耗时操作（NativeDeps 释放、目录检查、进程 fork）必须离开主线程，
     * 因此本方法只做状态切换，真正的启动在 [startBlocking] 里由调用方
     * 放到 IO 线程执行。
     */
    fun prepare(id: String, versionName: String) {
        val s = slot(id)
        // 只结束该实例自己的旧会话；destroy() 异步回收，但引用立刻失效
        s.session?.let {
            AppLogger.i(TAG, "prepare($id): destroying previous session of this instance")
            it.destroy()
        }
        s.session = null
        // 该实例的代次 +1：让它的在飞行启动放弃
        s.epoch.incrementAndGet()
        s.versionName = versionName
        s.lastError = null
        setState(s, State.STARTING, versionName)
    }

    /**
     * 在调用者线程上真正创建并启动进程。**必须在 IO 线程调用**。
     *
     * @param limits 该实例的资源限制（来自 `InstanceConfig`，§8.18）
     * @return `null` = 启动成功；[ABORTED] = 启动过程被取消（用户点了「结束」），
     *         此时**没有**会话在跑；其他字符串 = 具体的失败原因。
     *
     * ⚠️ 调用方必须区分 `null` 与 [ABORTED]：前者代表会话已就绪，可以开始监听
     * 会话结束；后者代表什么都没起来，按「成功」处理会留下一个永不退出的服务。
     */
    fun startBlocking(
        context: Context,
        manager: RootfsManager,
        distro: DistroInfo,
        limits: ResourceLimits = ResourceLimits.UNLIMITED
    ): String? {
        val s = slot(distro.id)
        val myEpoch = s.epoch.get()
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(libDir, "libproot.so")
        val loader = File(libDir, "libproot-loader.so")

        return try {
            val nativeDir = try {
                NativeDeps.ensure(context)
            } catch (e: Exception) {
                AppLogger.w(TAG, "NativeDeps failed: ${e.message}")
                null
            }

            // 释运行库可能耗时，期间用户可能已经点了「结束」。
            // 此时绝不能把刚 fork 的进程挂上去，否则会「复活」一个已被取消的会话。
            if (myEpoch != s.epoch.get()) {
                AppLogger.w(TAG, "start(${distro.id}) aborted: epoch changed during prepare")
                return ABORTED
            }

            val session = ProotSession(
                prootBin = proot,
                loader = loader,
                rootfs = manager.distroDir(distro.id),
                shell = distro.defaultShell,
                prootTmpDir = manager.prootTmpDir(),
                extraLibDir = nativeDir,
                versionId = distro.id,
                versionName = distro.name,
                limits = limits,
                listener = object : ProotSession.Listener {
                    override fun onOutput(text: String) {
                        // 按实例 id 转发：观察者据此决定是否渲染（§8.17）
                        mainHandler.post { observer?.onOutput(distro.id, text) }
                    }

                    override fun onExit(code: Int, reason: ProotSession.ExitReason) {
                        onSessionExit(distro.id, code, reason)
                    }
                }
            )
            session.start()

            // 再查一次代次：fork 期间用户可能已经取消。
            // 这里必须销毁刚起来的进程，否则它会成为无人持有的孤儿。
            if (myEpoch != s.epoch.get()) {
                AppLogger.w(TAG, "start(${distro.id}) cancelled after fork, destroying")
                session.destroy()
                return ABORTED
            }

            s.session = session
            s.versionName = distro.name
            setState(s, State.RUNNING, distro.name)
            AppLogger.i(
                TAG,
                "started ${distro.id} @ ${manager.location} limits=${limits.summary()}"
            )
            null
        } catch (e: Exception) {
            AppLogger.e(TAG, "start failed ${distro.id}", e)
            s.session = null
            val msg = e.message ?: e.javaClass.simpleName
            s.lastError = msg
            setState(s, State.IDLE, distro.name)
            mainHandler.post {
                observer?.onExit(distro.id, -1, ProotSession.ExitReason.START_FAILED)
            }
            msg
        }
    }

    // ------------------------------------------------------------ 交互

    /**
     * 向**指定实例**写入一行命令。无会话或写失败时抛 [java.io.IOException]，
     * 由调用方决定是提示用户还是静默忽略。
     */
    fun sendCommand(id: String, command: String) {
        val s = slots[id]?.session ?: throw java.io.IOException("实例 $id 没有运行中的会话")
        s.sendCommand(command)
    }

    /** 向指定实例写入原始控制序列（ESC/方向键/CTRL 组合键） */
    fun sendRaw(id: String, data: String) {
        val s = slots[id]?.session ?: throw java.io.IOException("实例 $id 没有运行中的会话")
        s.sendRaw(data)
    }

    private fun onSessionExit(id: String, code: Int, reason: ProotSession.ExitReason) {
        // 槽可能在退出回调到达前被移除（卸载/清理），此时忽略即可
        val s = slots[id] ?: return
        AppLogger.i(TAG, "exit id=$id code=$code reason=$reason")
        s.session = null
        // 退出后清掉 versionName：否则通知标题会继续显示一个已不存在的会话名
        setState(s, State.IDLE, null)
        mainHandler.post { observer?.onExit(id, code, reason) }
    }

    /**
     * 结束**指定实例**的会话（若有）。
     *
     * `destroy()` 会异步回收进程，但状态立刻切到 IDLE：UI 不该等内核收尸，
     * 而 `onExit` 回调由 [ProotSession] 里的 `exitNotified` 保证只触发一次。
     *
     * @return true 表示该实例确实有会话被结束
     */
    fun stop(id: String): Boolean {
        val s = slots[id] ?: return false
        // 代次 +1：让该实例仍在飞行中的 startBlocking 放弃启动。
        // 只自增它自己的代次，其他实例不受影响——这是多实例的关键。
        s.epoch.incrementAndGet()
        val session = s.session
        s.session = null
        if (session == null) {
            setState(s, State.IDLE, null)
            return false
        }
        setState(s, State.STOPPING, s.versionName)
        session.destroy()
        setState(s, State.IDLE, null)
        return true
    }

    /**
     * 结束**全部**实例的会话。
     *
     * ⚠️ 只应在用户显式选择「全部结束」时调用（例如通知栏的批量操作）。
     * `onDestroy` 里调用它是被 §8.13 明令禁止的。
     *
     * @return 被结束的实例数量
     */
    fun stopAll(): Int = slots.keys.toList().count { stop(it) }

    /** 实例被卸载时调用：结束其会话并移除槽，避免 `slots` 无限增长 */
    fun forget(id: String) {
        stop(id)
        slots.remove(id)
        AppLogger.i(TAG, "forgot instance $id")
    }

    /**
     * 启动流程在**未走到 [prepare]** 时就失败时调用，把失败交到 UI。
     *
     * `ProotService` 的「清单里没这个版本」「还没安装」两条早退路径发生在此前，
     * 那时状态机还停在 IDLE，观察者不会收到任何回调，UI 会永远卡在
     * 「正在启动…」。这个方法补上一次终态通知。
     */
    fun notifyStartFailed(id: String, message: String) {
        val s = slot(id)
        s.lastError = message
        setState(s, State.IDLE, null)
        mainHandler.post {
            observer?.onExit(id, -1, ProotSession.ExitReason.START_FAILED)
        }
    }

    private fun setState(s: SessionSlot, next: State, name: String?) {
        s.state = next
        s.versionName = name
        mainHandler.post { observer?.onSessionState(s.id, next, name) }
    }

    // ------------------------------------------------------------ 通知文案
    //
    // 文案全部走 strings.xml（AGENTS.md §4.5：不硬编码中文到状态栏/通知），
    // 但 SessionManager 是纯单例、没有 Context，因此需要由 Application
    // 在启动时注入一次。用 Application 级 Context 不会泄漏 Activity。

    private var appContext: Context? = null

    /** 由 [App] 在 onCreate 调用；注入失败时下面的文案会退回英文常量 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun str(resId: Int, fallback: String): String =
        appContext?.getString(resId) ?: fallback

    /**
     * 常驻通知的标题。
     *
     * 多实例下通知是**一条汇总通知**而不是每个实例一条：Android 的通知栏
     * 空间宝贵，且前台服务本身只能有一条常驻通知。因此标题列出并行实例数量，
     * 点开则回到「我的系统」页由用户挑选要操作哪个实例。
     */
    fun notificationTitle(): String {
        val running = runningIds()
        return when (running.size) {
            0 -> "ProotTerm"
            1 -> "ProotTerm：${versionNameOf(running[0]) ?: running[0]}"
            else -> "ProotTerm：${running.size} 个实例运行中"
        }
    }

    fun notificationText(): String {
        val running = runningIds()
        if (running.isNotEmpty()) {
            return if (running.size == 1) {
                str(R.string.notif_running, "Session running")
            } else {
                running.joinToString("、")
            }
        }
        // 按「最需要用户注意」的优先级挑文案：先看有没有正在结束的，
        // 再看正在启动的，最后才是真的空闲。
        slots.values.firstOrNull { it.state == State.STOPPING }?.let {
            return str(R.string.notif_stopping, "Stopping session")
        }
        if (slots.values.any { it.state == State.STARTING }) {
            return str(R.string.notif_starting, "Starting session")
        }
        return str(R.string.notif_idle, "No running session")
    }

    /** 通知点击后回到终端页（MainActivity 是 singleTask，会用 onNewIntent 收到） */
    fun openTerminalIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ProotService.ACTION_OPEN_TERMINAL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    private const val TAG = "SessionManager"

    /**
     * [startBlocking] 的「已取消」哨兵返回值。
     *
     * 不能复用 `null`——`null` 的语义是「启动成功」，若把取消也返回 `null`，
     * `ProotService` 会误以为会话已就绪，从而去挂监听、写 `lastVersionId`，
     * 留下一个永远等不到会话结束的服务实例。
     */
    const val ABORTED = "\u0000aborted\u0000"
}
