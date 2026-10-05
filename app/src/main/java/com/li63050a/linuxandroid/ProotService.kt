package com.li63050a.linuxandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * PRoot 会话的前台服务：**会话存活与否的唯一权威**。
 *
 * 为什么必须是前台服务：普通后台进程会被系统随时回收，「保持运行」与
 * 「开机自启动」都要求 shell 在应用不在前台时继续执行，只有前台服务
 * （带常驻通知）能在 Android 8+ 的后台执行限制下稳定存活。
 *
 * 三条入口，行为一致：
 *  1. [ACTION_START]：用户在版本页点「启动」；
 *  2. [ACTION_AUTO_START]：`BootReceiver` 收到 `BOOT_COMPLETED` 后拉起；
 *  3. [ACTION_STOP]：用户主动关闭终端 / 关闭自启动开关。
 *
 * 启动流程刻意**先建通知再干活**：Android 8+ 要求 startForeground 必须在
 * startForegroundService 后 5 秒内调用，否则 ANR；而准备 rootfs 可能更慢。
 */
class ProotService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJob: Job? = null

    /** 每个实例的配置（资源限制等）来源；懒加载 */
    private val instanceStore by lazy { InstanceStore.of(this) }

    /**
     * 进行中的启动任务，按实例 id 跟踪。
     *
     * 旧实现用单个 `startJob` 字段，多实例并发启动时后一个会 cancel 掉前一个。
     * 这里按 id 存，各自独立；启动完成后自行移除。
     */
    private val startJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 可带 EXTRA_INSTANCE_ID 只结束某一个实例；不带则结束全部
                val target = intent.getStringExtra(EXTRA_INSTANCE_ID)
                if (target.isNullOrBlank()) {
                    val n = SessionManager.stopAll()
                    AppLogger.i(TAG, "stop requested (all), stopped $n instance(s)")
                    stopSelfSafely()
                } else {
                    // 多实例下不能结束服务：其他实例可能还在跑（§8.17）。
                    // 只结束目标实例，通知文案随剩余会话刷新。
                    val stopped = SessionManager.stop(target)
                    AppLogger.i(TAG, "stop requested ($target), stopped=$stopped")
                    if (SessionManager.hasAnyRunning()) {
                        updateNotification(SessionManager.notificationText())
                    } else {
                        stopSelfSafely()
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_START, ACTION_AUTO_START -> {
                val id = intent.getStringExtra(EXTRA_VERSION_ID).orEmpty()
                if (id.isBlank()) {
                    AppLogger.w(TAG, "start without version id")
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                startForegroundCompat(SessionManager.notificationText())
                startSession(id, intent.action == ACTION_AUTO_START)
                return START_STICKY
            }

            else -> {
                // 服务被系统重启但没带 action：无事可做，直接结束
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }
    }

    private fun startSession(id: String, isAutoStart: Boolean) {
        // 该实例已在跑：只需确认前台状态，不重启进程。
        // 注意这里只判断**目标实例**，别的实例在跑不影响本次启动（§8.17）。
        if (SessionManager.isRunning(id)) {
            AppLogger.i(TAG, "instance $id already running, skip")
            return
        }
        // 多实例下不能用单个 startJob 字段：并发启动两个实例时，
        // 后一个会 cancel 掉前一个正在进行的启动流程。
        // 按实例 id 跟踪；同一实例重复点击则取消上一次未完成的启动。
        startJobs.remove(id)?.cancel()
        // `scope.launch` 返回后协程可能已经开始执行，因此**不能**在协程体内
        // 引用外层 `val job`（Kotlin 的初始化顺序会读到 null）。
        // 用 CoroutineStart.LAZY 拿到 Job 句柄、登记、再 start()，这样
        // 早退路径（distro==null / 未安装）也已被登记，不会有过期引用。
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val manager = RootfsManager(this@ProotService)
            val prefs = AppPrefs(this@ProotService)

            val distro = try {
                ManifestLoader.load(this@ProotService)
                    .asSequence()
                    .flatMap { it.versions.asSequence() }
                    .firstOrNull { it.id == id }
            } catch (e: Exception) {
                AppLogger.e(TAG, "manifest load failed for auto start", e)
                null
            }

            if (distro == null) {
                AppLogger.w(TAG, "version $id not in manifest, abort")
                // 必须显式通知 UI：这条路径在 SessionManager.prepare() 之前，
                // 状态机还停在 IDLE，观察者收不到任何回调，界面会永远卡在
                // 「正在启动…」。notifyStartFailed 补一次终态回调。
                SessionManager.notifyStartFailed(
                    id,
                    getString(R.string.start_failed_not_found_fmt, id)
                )
                stopSelfSafely()
                return@launch
            }
            if (!manager.isInstalled(distro.id)) {
                AppLogger.w(TAG, "version $id not installed, abort")
                SessionManager.notifyStartFailed(
                    distro.id,
                    getString(
                        if (isAutoStart) R.string.start_failed_not_installed_autostart_fmt
                        else R.string.start_failed_not_installed_fmt,
                        distro.name
                    )
                )
                stopSelfSafely()
                return@launch
            }

            // 每个实例的资源限制来自 InstanceConfig（§8.18）。
            // 读配置要在 IO 之前完成：InstanceStore 首次会读文件。
            val limits = kotlinx.coroutines.withContext(Dispatchers.IO) {
                instanceStore.get(distro.id).limits
            }

            SessionManager.prepare(distro.id, distro.name)
            updateNotification(SessionManager.notificationText())

            // 真正 fork 进程，必须在 IO 线程
            val err = kotlinx.coroutines.withContext(Dispatchers.IO) {
                SessionManager.startBlocking(this@ProotService, manager, distro, limits)
            }

            if (err == SessionManager.ABORTED) {
                // 启动期间用户点了「结束」：什么都没起来。
                // 按成功处理会留下一个永远等不到会话结束的服务（通知常驻不消）。
                // 但**不能**无条件 stopSelf：可能还有别的实例在跑。
                AppLogger.i(TAG, "start(${distro.id}) aborted by user")
                SessionManager.notifyStartFailed(
                    distro.id,
                    getString(R.string.start_aborted_by_user)
                )
                releaseIfIdle()
                return@launch
            }

            if (err != null) {
                AppLogger.e(TAG, "start failed: $err")
                // ⚠️ 不要在这里再 notifyStartFailed：startBlocking 的 catch 分支
                // 已经 setState(IDLE) + 发过 onExit(START_FAILED) 了，再发一次会让
                // 终端里出现两条一模一样的「启动失败」。只更新通知文案即可。
                // 同理不能无条件停服务：可能还有别的实例在跑。
                updateNotification(getString(R.string.notif_start_failed_fmt, err))
                releaseIfIdle()
                return@launch
            }

            prefs.lastVersionId = distro.id
            if (isAutoStart) {
                AppLogger.i(TAG, "auto started ${distro.id}")
            }
            updateNotification(SessionManager.notificationText())
            // 会话已交给 SessionManager，服务本身只需保持前台状态；
            // 进程退出时对应槽会置为 IDLE，通知随之失去意义，
            // 因此这里挂一个轻量探针，等**全部**会话结束后自动收掉服务。
            watchSessionEnd()
        }
        // 登记完再启动，保证协程跑起来时句柄已就位
        startJobs[id] = job
        job.invokeOnCompletion { startJobs.remove(id, job) }
        job.start()
    }

    /**
     * 没有实例在跑时就收掉服务；还有实例在跑则只刷新通知。
     *
     * 多实例的关键点：单个实例启动失败/被取消**不等于**服务该结束——
     * 无条件 stopSelfSafely 会把其他正在运行的实例连通知一起清掉。
     */
    private fun releaseIfIdle() {
        if (SessionManager.hasAnyRunning()) {
            AppLogger.i(TAG, "still running: ${SessionManager.runningIds()}, keep service")
            updateNotification(SessionManager.notificationText())
        } else {
            stopSelfSafely()
        }
    }

    /**
     * 全部会话结束后自动结束服务，避免留下「通知还在但没有会话」的僵尸状态。
     *
     * 注意等待条件是 `hasAnyRunning()` 而不是「某个实例 IDLE」——
     * 多实例下一个实例退出时其他实例可能仍在跑，此时收服务会误杀它们。
     */
    private fun watchSessionEnd() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (SessionManager.hasAnyRunning()) {
                kotlinx.coroutines.delay(WATCH_INTERVAL_MS)
            }
            AppLogger.i(TAG, "all sessions ended, stopping service")
            stopSelfSafely()
        }
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "onDestroy")
        scope.cancel()
        startJobs.clear()
        // ⚠️ 这里**故意不调用 SessionManager.stopAll() / stop()**（AGENTS.md §8.13）。
        //
        // onDestroy 不等于「用户要结束会话」：系统在内存紧张、OEM 后台清理、
        // 前台服务类型被回收时都会销毁服务，而这恰恰是本项目引入前台服务要
        // 扛住的场景。若在这里杀会话，一旦服务被回收就会连 shell 一起杀掉，
        // 「后台保持运行」与「开机自启动」全部失效。
        //
        // 会话的唯一权威结束点是 ACTION_STOP（见 onStartCommand 的 ACTION_STOP
        // 分支），用户从通知栏点「结束」时才走到那里。
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            // LOW：常驻通知不该发出声音或震动，用户只是被告知「有个会话在跑」
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            SessionManager.openTerminalIntent(this),
            pendingIntentFlags()
        )
        val stop = PendingIntent.getService(
            this,
            REQ_STOP,
            // 不带 EXTRA_INSTANCE_ID：通知栏的「结束」结束全部实例，
            // 与按钮文案「结束」的语义一致（单实例场景下无差别）
            Intent(this, ProotService::class.java).setAction(ACTION_STOP),
            pendingIntentFlags()
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(SessionManager.notificationTitle())
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(open)
            .addAction(
                // 图标参数是 @DrawableRes Int（基本类型），传 null 会落到
                // Notification.Action.Builder(Icon,...) 重载上，重载解析易出错且
                // Icon 类本身是 API 23+。这里显式给资源 id，规避歧义。
                Notification.Action.Builder(
                    R.drawable.ic_terminal,
                    getString(R.string.notif_action_stop),
                    stop
                ).build()
            )
            .setOngoing(true)
            .setShowWhen(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setVisibility(Notification.VISIBILITY_PUBLIC)
                }
            }
            .build()
    }

    /** API 23 起 PendingIntent 必须显式声明可变性，否则部分 ROM 抛异常 */
    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+ 必须声明前台服务类型，否则抛 SecurityException
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        try {
            mgr.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            AppLogger.w(TAG, "notify failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ProotService"
        private const val CHANNEL_ID = "proot_session"
        private const val NOTIF_ID = 1001
        private const val REQ_OPEN = 100
        private const val REQ_STOP = 101
        private const val WATCH_INTERVAL_MS = 1500L

        const val EXTRA_VERSION_ID = "version_id"

        /**
         * 只结束某一个实例。带上它时 [ACTION_STOP] 只结束该实例，
         * 服务本身在其他实例仍在运行时会继续存活（§8.17）。
         */
        const val EXTRA_INSTANCE_ID = "instance_id"

        const val ACTION_START = "com.li63050a.linuxandroid.action.START_SESSION"
        const val ACTION_STOP = "com.li63050a.linuxandroid.action.STOP_SESSION"
        const val ACTION_AUTO_START = "com.li63050a.linuxandroid.action.AUTO_START"
        const val ACTION_OPEN_TERMINAL = "com.li63050a.linuxandroid.action.OPEN_TERMINAL"

        /** 启动会话（用户在版本页点「启动」） */
        fun startIntent(context: Context, versionId: String): Intent =
            Intent(context, ProotService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VERSION_ID, versionId)
            }

        /** 开机自启动拉起会话 */
        fun autoStartIntent(context: Context, versionId: String): Intent =
            Intent(context, ProotService::class.java).apply {
                action = ACTION_AUTO_START
                putExtra(EXTRA_VERSION_ID, versionId)
            }

        /** 结束全部实例 */
        fun stopIntent(context: Context): Intent =
            Intent(context, ProotService::class.java).setAction(ACTION_STOP)

        /** 只结束指定实例（多实例下其他实例不受影响） */
        fun stopIntent(context: Context, instanceId: String): Intent =
            Intent(context, ProotService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }

        /**
         * 兼容式启动：API 26+ 用 startForegroundService，低版本用 startService。
         * 捕获异常并记日志，避免开机广播里抛异常导致系统认为应用崩溃。
         */
        fun launch(context: Context, intent: Intent): Boolean = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "launch service failed: ${intent.action}", e)
            false
        }
    }
}
