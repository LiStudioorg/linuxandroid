package com.li63050a.linuxandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主界面：DrawerLayout 五屏
 * （我的系统 / 终端 / 发行版家族 / 版本列表 / 设置）。
 *
 * 职责边界：
 *  - 本类**不拥有** shell 会话。会话归 [SessionManager] + [ProotService]，
 *    Activity 只是观察者，随时可被销毁重建而不影响后台执行。
 *  - 安装（下载/解压）仍在本类内完成，因为它需要与版本列表 UI 强交互；
 *    会话启动则交给服务，两者通过 [activeInstallId] 与 [SessionManager] 解耦。
 *
 * **多实例（§8.17）**：可以同时有多个实例在跑。本类用 [focusedId] 表示
 * 「终端页当前显示哪一个实例的输出」——这只是**视图焦点**，不是所有权：
 * 切走焦点不会影响任何实例的运行。`SessionManager.Observer` 的所有回调都带
 * 实例 id，本类据此过滤，避免 A 实例的输出串到 B 实例的终端里。
 *
 * 存储位置（内部 `/data/data/<pkg>` / 外部 `/Android/data/<pkg>`）由
 * [RootfsManager] 解析，切换只影响之后安装的系统，本类负责如实提示。
 */
class MainActivity : AppCompatActivity(), SessionManager.Observer {

    private enum class Screen { MY_APPS, TERMINAL, DISTROS, VERSIONS, SETTINGS }

    private val scope = MainScope()
    private lateinit var manager: RootfsManager
    private lateinit var prefs: AppPrefs
    private lateinit var instanceStore: InstanceStore
    private val downloader = RootfsDownloader()
    private val multiDownloader = MultiPartDownloader(downloader)

    /**
     * 终端页当前显示的实例 id（**只是视图焦点，不是所有权**）。
     *
     * 多实例下可以同时有多个 shell 在跑，但终端一次只渲染一个。切换焦点
     * 不会启动/结束任何进程——想结束必须显式走 `ProotService.stopIntent(id)`（§8.13）。
     *
     * 为 null 表示还没有任何实例被关注过。
     */
    private var focusedId: String? = null

    private lateinit var drawer: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var viewTerminal: View
    private lateinit var viewDistros: View
    private lateinit var viewVersions: View
    private lateinit var viewMyApps: View
    private lateinit var viewSettings: View

    private lateinit var editSearch: EditText
    private lateinit var tvOutput: TextView
    private lateinit var scrollOutput: ScrollView
    private lateinit var editCommand: EditText
    private lateinit var btnSend: MaterialButton

    private lateinit var recyclerDistros: RecyclerView
    private lateinit var distroAdapter: DistroAdapter
    private lateinit var recyclerVersions: RecyclerView
    private lateinit var versionAdapter: VersionAdapter
    private lateinit var tvVersionsHeader: TextView

    private lateinit var recyclerMyApps: RecyclerView
    private lateinit var myAppAdapter: MyAppAdapter
    private lateinit var tvMyAppsLocation: TextView
    private lateinit var groupMyAppsEmpty: View
    private lateinit var tvMyAppsEmpty: TextView
    private lateinit var bannerOtherLocation: View
    private lateinit var tvBannerOtherLocation: TextView

    private lateinit var radioInternal: android.widget.RadioButton
    private lateinit var radioExternal: android.widget.RadioButton
    private lateinit var tvStorageCurrent: TextView
    private lateinit var switchAutoStart: MaterialSwitch
    private lateinit var tvAutoStartTarget: TextView

    private var families: List<DistroFamily> = emptyList()
    private var currentFamily: DistroFamily? = null
    private var screen = Screen.MY_APPS

    private var installJob: Job? = null
    private var activeInstallId: String? = null
    private var lastDownloadPct = 0
    private var distroCache: Map<String, DistroInfo> = emptyMap()
    private var myApps: List<MyApp> = emptyList()

    private var modifierArmed: Modifier = Modifier.NONE
    private lateinit var btnCtrl: MaterialButton
    private lateinit var btnAlt: MaterialButton

    /** CTRL / ALT 待命修饰键；两者互斥 */
    private enum class Modifier { NONE, CTRL, ALT }

    private val outputRaw = StringBuilder()
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        manager = RootfsManager(this)
        instanceStore = InstanceStore.of(this)
        prefs = AppPrefs(this)

        bindViews()

        families = try {
            ManifestLoader.load(this)
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "manifest load failed", e)
            Toast.makeText(this, "清单解析失败: ${e.message}", Toast.LENGTH_LONG).show()
            emptyList()
        }
        distroCache = families.flatMap { it.versions }.associateBy { it.id }

        distroAdapter = DistroAdapter(families) { openVersions(it) }
        recyclerDistros.layoutManager = LinearLayoutManager(this)
        recyclerDistros.adapter = distroAdapter

        setupVersionList()
        setupMyApps()
        setupNavigation()
        setupTerminal()
        setupHotkeys()
        setupSettings()

        showScreen(Screen.MY_APPS)
        AppLogger.i(
            "MainActivity",
            "onCreate families=${families.size} location=${manager.location} fallback=${manager.locationFallback}"
        )
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        drawer = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)
        viewTerminal = findViewById(R.id.view_terminal)
        viewDistros = findViewById(R.id.view_distros)
        viewVersions = findViewById(R.id.view_versions)
        viewMyApps = findViewById(R.id.view_myapps)
        viewSettings = findViewById(R.id.view_settings)
        editSearch = findViewById(R.id.edit_search)
        tvOutput = findViewById(R.id.tv_output)
        scrollOutput = findViewById(R.id.scroll_output)
        editCommand = findViewById(R.id.edit_command)
        btnSend = findViewById(R.id.btn_send)
        recyclerDistros = findViewById(R.id.recycler_distros)
        recyclerVersions = findViewById(R.id.recycler_versions)
        tvVersionsHeader = findViewById(R.id.tv_versions_header)
        btnCtrl = findViewById(R.id.btn_key_ctrl)
        btnAlt = findViewById(R.id.btn_key_alt)
        recyclerMyApps = findViewById(R.id.recycler_myapps)
        tvMyAppsLocation = findViewById(R.id.tv_location)
        groupMyAppsEmpty = findViewById(R.id.group_empty)
        tvMyAppsEmpty = findViewById(R.id.tv_empty)
        bannerOtherLocation = findViewById(R.id.banner_other_location)
        tvBannerOtherLocation = findViewById(R.id.tv_banner_other_location)
        radioInternal = findViewById(R.id.radio_storage_internal)
        radioExternal = findViewById(R.id.radio_storage_external)
        tvStorageCurrent = findViewById(R.id.tv_storage_current)
        switchAutoStart = findViewById(R.id.switch_autostart)
        tvAutoStartTarget = findViewById(R.id.tv_autostart_target)
    }

    // ================================================================== 生命周期

    override fun onStart() {
        super.onStart()
        SessionManager.attach(this)
        // 回到前台时先用会话快照刷新一次，弥补 detach 期间可能错过的输出
        restoreFromSession()
        refreshMyApps()
    }

    override fun onStop() {
        SessionManager.detach(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 必须显式 setIntent：否则本实例后续的 getIntent() 仍返回最初启动它的
        // Intent，通知带过来的 action 会被丢掉（singleTask 下尤其容易踩）。
        setIntent(intent)
        // 从常驻通知点进来：直接落到终端页
        if (intent.action == ProotService.ACTION_OPEN_TERMINAL) {
            // 有任意实例在跑就落到终端；焦点优先给正在跑的那个
            val running = SessionManager.runningIds()
            if (running.isNotEmpty()) {
                if (focusedId == null || focusedId !in running) focusedId = running.first()
                renderSessionOutput()
                showScreen(Screen.TERMINAL)
            } else {
                showScreen(Screen.MY_APPS)
                toast(getString(R.string.status_session_closed))
            }
        }
    }

    // ================================================================== 我的系统

    private fun setupMyApps() {
        myAppAdapter = MyAppAdapter(
            onLaunch = { launchMyApp(it) },
            onOpenTerminal = { showScreen(Screen.TERMINAL) },
            onUninstall = { confirmUninstallMyApp(it) },
            onShowInfo = { showMyAppInfo(it) }
        )
        recyclerMyApps.layoutManager = LinearLayoutManager(this)
        recyclerMyApps.adapter = myAppAdapter
        findViewById<MaterialButton>(R.id.btn_goto_distros).setOnClickListener {
            showScreen(Screen.DISTROS)
        }
        findViewById<MaterialButton>(R.id.btn_banner_switch).setOnClickListener {
            switchToOtherLocation()
        }
    }

    /**
     * 重新扫描当前存储位置下已安装的系统。
     * 扫描是纯目录列举（rootfs 根下一层），开销极小，可以直接在主线程做；
     * 但磁盘占用统计需要递归，放到 IO 线程后再回填，避免大 rootfs 卡 UI。
     */
    private fun refreshMyApps() {
        val root = manager.rootfsRoot()
        // manifestLoaded 为 false 表示清单读取失败（而非「这个系统不在清单里」），
        // 两者对用户的含义完全不同，UI 必须能区分
        val manifestLoaded = distroCache.isNotEmpty()
        val fallbacks = buildList {
            // 清单里同 id 版本的 shell 优先级最高，其次是各家族常见 shell
            distroCache.keys.forEach { id -> distroCache[id]?.let { add(it.defaultShell) } }
            add("/bin/bash")
            add("/bin/sh")
        }
        myApps = InstalledScanner.scan(root, fallbacks).map { app ->
            // 安装时记录的 shell 最准（清单不可用时依然有效）
            val recorded = manager.recordedShell(app.id)
            val fromManifest = distroCache[app.id]
            app.copy(
                name = fromManifest?.name ?: app.id,
                shell = recorded ?: fromManifest?.defaultShell ?: app.shell,
                fromManifest = fromManifest != null,
                manifestLoaded = manifestLoaded
            ).also { it.distro = fromManifest }
        }

        myAppAdapter.submit(myApps)
        // 多实例：把所有运行中的实例一起交给适配器打徽标，而不是只标一个
        myAppAdapter.setRunningIds(SessionManager.runningIds().toSet())

        val running = SessionManager.runningIds().isNotEmpty()
        val locationText = if (manager.locationFallback) {
            getString(R.string.storage_fallback_warning)
        } else {
            getString(R.string.myapps_location_fmt, manager.sandboxDir.absolutePath)
        }
        tvMyAppsLocation.text = locationText

        // 常驻提示条：只要另一个存储位置还有系统就显示，
        // **与当前列表是否为空解耦**（否则两个位置各有系统时用户只能看到一半）
        val otherCount = countInstalledInOtherLocation()
        if (otherCount > 0) {
            val other = manager.otherLocation()
            bannerOtherLocation.visibility = View.VISIBLE
            tvBannerOtherLocation.text = getString(
                R.string.myapps_other_location_fmt,
                otherCount,
                other.label
            )
        } else {
            bannerOtherLocation.visibility = View.GONE
        }

        // 空列表分支：区分「一个都没装」与「装在另一个位置」
        val empty = myApps.isEmpty()
        groupMyAppsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        recyclerMyApps.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            if (otherCount > 0) {
                tvMyAppsEmpty.text = getString(R.string.myapps_none_in_location)
            } else {
                tvMyAppsEmpty.text = getString(R.string.myapps_empty)
            }
        }

        // 异步补齐占用空间，不阻塞列表首帧
        if (myApps.isNotEmpty()) {
            scope.launch {
                val withSize = withContext(Dispatchers.IO) {
                    myApps.map { it.copy(sizeBytes = dirSize(it.dir)) }
                }
                if (withSize != myApps) {
                    myApps = withSize
                    myAppAdapter.submit(myApps)
                    myAppAdapter.setRunningIds(SessionManager.runningIds().toSet())
                }
            }
        }

        updateStatusForSession()
        AppLogger.d("MainActivity", "myApps=${myApps.size} running=${SessionManager.runningIds()}")
    }

    private fun countInstalledInOtherLocation(): Int {
        val other = manager.otherLocation()
        val dir = StorageLocation.sandboxDir(this, other) ?: return 0
        if (!other.isAvailable(dir)) return 0
        val root = other.rootfsRoot(dir)
        return root.listFiles()
            ?.count { it.isDirectory && File(it, RootfsManager.MARKER).isFile }
            ?: 0
    }

    /** 递归统计目录占用；失败返回 0（列表里就不显示大小） */
    private fun dirSize(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    // 不跟随符号链接：rootfs 里有大量指向目录的链接，
                    // 跟随会死循环或把宿主机目录算进来
                    if (!isSymlink(child)) stack.addLast(child)
                } else {
                    total += child.length()
                }
            }
        }
        return total
    }

    /**
     * 用 lstat 判断是否符号链接。
     * `File.isDirectory` 会跟随符号链接，而 rootfs 里存在大量指向目录的链接，
     * 跟随统计会死循环或把宿主机目录算进来，所以必须用 lstat 自己判断。
     *
     * 注意括号：Kotlin 的 `and` 是中缀函数，`a and b == c` 会解析成
     * `a and (b == c)`，必须显式加括号才是「取文件类型位再比较」。
     */
    private fun isSymlink(file: File): Boolean = try {
        val mode = android.system.Os.lstat(file.absolutePath).st_mode
        (mode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFLNK
    } catch (_: Exception) {
        false
    }

    // ================================================================== 安装/卸载

    private fun setupVersionList() {
        versionAdapter = VersionAdapter(
            emptyList(),
            onDownload = { install(it) },
            onStart = { launchDistro(it) },
            onUninstall = { confirmUninstallDistro(it) },
            onCancelInstall = { cancelInstall(it) }
        )
        recyclerVersions.layoutManager = LinearLayoutManager(this)
        recyclerVersions.adapter = versionAdapter
    }

    private fun install(d: DistroInfo) {
        if (installJob?.isActive == true || activeInstallId != null) {
            toast(getString(R.string.toast_install_busy))
            return
        }
        val spaceErr = manager.checkSpaceFor(d.id, d.size)
        if (spaceErr != null) {
            toast(spaceErr)
            AppLogger.w("Install", "space check failed: $spaceErr")
            return
        }

        activeInstallId = d.id
        val part = manager.partFile(d.id, d.format)
        manager.cleanupTemp(d.id)
        versionAdapter.setState(d.id, VersionAdapter.State.Downloading(0))
        setStatus("准备下载 ${d.name} …")
        AppLogger.i("Install", "begin ${d.id} url=${d.url} target=${manager.sandboxDir}")

        installJob = scope.launch {
            try {
                // ---- 1. 决定候选地址（§8.20）----
                // 自定义源优先参与，但清单地址始终保留作保底：用户填错地址不该
                // 导致完全无法下载。
                val cfg = instanceStore.get(d.id)
                val custom = cfg.mirrorOverride.takeIf { it.isNotBlank() }
                var candidates = buildList {
                    custom?.let { add(it) }
                    addAll(d.allUrls)
                }.distinct()

                // 自动优选：并发测速后按延迟重排。
                // 测速本身可能失败（全部不可达 / 超时），此时 rank() 会原样返回，
                // 不会阻断下载（§8.20）。
                if (cfg.preferAutoMirror && candidates.size > 1) {
                    setStatus("正在测速选择最快镜像 …")
                    candidates = MirrorProbeService.rank(candidates)
                    setStatus("已选择镜像：${candidates.first()}")
                    AppLogger.i("Install", "ranked mirrors for ${d.id}: $candidates")
                }

                // ---- 2. 下载 ----
                // 多线程分块只对首要地址做：不同镜像的文件不一定字节一致，
                // 混用会导致合并出损坏归档。
                val primary = candidates.first()
                multiDownloader.download(
                    distro = d,
                    partFile = part,
                    urls = candidates,
                    threads = cfg.safeThreads
                ) { done, total ->
                    runOnUiThread {
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            lastDownloadPct = pct
                            versionAdapter.setState(
                                d.id,
                                VersionAdapter.State.Downloading(pct),
                                VersionAdapter.PAYLOAD_PROGRESS
                            )
                            setStatus(
                                "下载 ${d.name} … $pct%（%.1f / %.1f MB）"
                                    .format(done / MB, total / MB)
                            )
                        } else {
                            setStatus("下载 ${d.name} … %.1f MB".format(done / MB))
                        }
                    }
                }
                AppLogger.i("Install", "downloaded ${d.id} from $primary")

                if (d.sha256.isNotBlank()) {
                    setStatus("校验 SHA256 …")
                    val actual = withContext(Dispatchers.IO) { Sha256Utils.hashFile(part) }
                    if (!actual.equals(d.sha256.trim(), ignoreCase = true)) {
                        part.delete()
                        throw IllegalStateException("SHA256 校验失败\n期望 ${d.sha256}\n实际 $actual")
                    }
                }

                val temp = manager.tempDir(d.id)
                manager.cleanupTemp(d.id)
                temp.mkdirs()
                setStatus("解压 ${d.name} …")
                RootfsExtractor.extract(part, d.format, temp) { count, _ ->
                    runOnUiThread { setStatus("解压 ${d.name} … 已处理 $count 项") }
                }

                // 把清单声明的 shell 一并落盘（.shell），后续即使清单不可用
                // 也能准确识别这是哪个系统、该用哪个 shell
                withContext(Dispatchers.IO) {
                    manager.finalizeInstall(d.id, temp, d.defaultShell)
                }
                part.delete()
                versionAdapter.setState(d.id, VersionAdapter.State.Installed)
                setStatus(getString(R.string.install_done_fmt, d.name))
                refreshMyApps()
                AppLogger.i("Install", "done ${d.id}")
            } catch (e: CancellationException) {
                // 取消时清掉分块残留：它们无法被单线程续传复用，
                // 留着只会占用空间并在下次下载时被误判为「已下完」
                withContext(Dispatchers.IO) { MultiPartDownloader.cleanupChunksFor(manager, d.id) }
                versionAdapter.setState(d.id, VersionAdapter.State.Idle)
                setStatus("已取消（断点已保留，可重新下载）")
                throw e
            } catch (e: Exception) {
                AppLogger.e("Install", "failed ${d.id}", e)
                withContext(Dispatchers.IO) { MultiPartDownloader.cleanupChunksFor(manager, d.id) }
                manager.cleanupTemp(d.id)
                versionAdapter.setState(d.id, VersionAdapter.State.Idle)
                setStatus(getString(R.string.install_failed_fmt, e.message ?: e.javaClass.simpleName))
                toast(getString(R.string.install_failed_fmt, e.message ?: e.javaClass.simpleName))
            } finally {
                activeInstallId = null
                // 任何结束路径（成功/失败/取消）都恢复按钮可用，避免卡在「取消中」
                if (versionAdapter.stateOf(d.id) is VersionAdapter.State.Cancelling) {
                    versionAdapter.setState(d.id, VersionAdapter.State.Idle)
                }
            }
        }
    }

    /**
     * 取消进行中的安装：先置「取消中」防止重复点击，
     * 再 cancel 协程 + 结束活动 OkHttp Call（两者缺一，阻塞中的 IO 不会及时中断）。
     */
    private fun cancelInstall(d: DistroInfo) {
        if (activeInstallId != d.id) return
        versionAdapter.setState(d.id, VersionAdapter.State.Cancelling)
        setStatus("正在取消 ${d.name} …")
        downloader.cancel()
        // 多线程下载器有自己的分块请求与临时文件，必须一起取消，
        // 否则取消后仍有分块在写、且残留文件不会被清理（§8.19）
        multiDownloader.cancel()
        installJob?.cancel()
    }

    private fun confirmUninstallDistro(d: DistroInfo) {
        confirmUninstall(d.id, d.name)
    }

    private fun confirmUninstallMyApp(app: MyApp) {
        confirmUninstall(app.id, app.name)
    }

    private fun confirmUninstall(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_uninstall_title)
            .setMessage(R.string.dlg_uninstall_message)
            .setPositiveButton(R.string.btn_uninstall_confirm) { _, _ ->
                try {
                    // 卸载正在运行的系统前先结束会话，否则 proot 会持有已删除的目录。
                    // 多实例下只结束这一个，其他实例继续跑（§8.17）。
                    if (SessionManager.isRunning(id)) {
                        ProotService.launch(this, ProotService.stopIntent(this, id))
                    }
                    // 顺手清掉分块下载残留与实例配置，避免卸载后留下孤儿数据
                    MultiPartDownloader.cleanupChunksFor(manager, id)
                    instanceStore.remove(id)
                    if (focusedId == id) focusedId = null
                    manager.uninstall(id)
                    versionAdapter.setState(id, VersionAdapter.State.Idle)
                    setStatus("$name 已卸载")
                    refreshMyApps()
                } catch (e: Exception) {
                    AppLogger.e("MainActivity", "uninstall failed $id", e)
                    toast("卸载失败: ${e.message}")
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showMyAppInfo(app: MyApp) {
        val size = if (app.sizeBytes > 0) {
            "%.1f MB".format(app.sizeBytes / MB)
        } else {
            "统计中"
        }
        val msg = buildString {
            append("目录：${app.dir.absolutePath}\n")
            append("shell：${app.shell}\n")
            append("占用：$size\n")
            append("存储位置：${manager.location.label}")
            append(
                "\n" + getString(
                    R.string.limits_summary_fmt,
                    instanceStore.get(app.id).limits.summary()
                )
            )
            if (!app.fromManifest) {
                append(
                    if (app.manifestLoaded) {
                        // 清单读到了，但确实没有这个 id：真的是用户自己放进去的
                        "\n\n该目录不在发行版清单中，属本地已有的自定义 rootfs。"
                    } else {
                        // 清单没读到：不能断言它是自定义的，如实说明
                        "\n\n发行版清单未能加载，无法确认该系统的版本信息。"
                    }
                )
            }
        }
        AlertDialog.Builder(this)
            .setTitle(app.name)
            .setMessage(msg)
            .setPositiveButton(R.string.btn_open_terminal) { _, _ ->
                if (SessionManager.isRunning(app.id)) {
                    showScreen(Screen.TERMINAL)
                } else {
                    launchMyApp(app)
                }
            }
            .setNeutralButton(R.string.btn_instance_settings) { _, _ ->
                showInstanceSettings(app)
            }
            .setNegativeButton(R.string.btn_uninstall) { _, _ -> confirmUninstallMyApp(app) }
            .show()
    }

    // ================================================================== 实例设置

    /**
     * 单实例设置对话框：资源限制 + 镜像偏好 + 下载线程数 + 换源（§8.17/§8.18/§8.20/§8.21）。
     *
     * 一次性呈现全部实例级配置，因为它们都是「改完重启会话生效」的同一类设置，
     * 拆成多个入口只会让用户反复找。全局设置（存储位置、开机自启动）仍在设置页。
     */
    private fun showInstanceSettings(app: MyApp) {
        val view = layoutInflater.inflate(R.layout.dialog_instance_settings, null)
        val cfg = instanceStore.get(app.id)

        val editMemory = view.findViewById<EditText>(R.id.edit_limit_memory)
        val editProcesses = view.findViewById<EditText>(R.id.edit_limit_processes)
        val editOpenFiles = view.findViewById<EditText>(R.id.edit_limit_open_files)
        val editCpu = view.findViewById<EditText>(R.id.edit_limit_cpu)
        val editStack = view.findViewById<EditText>(R.id.edit_limit_stack)
        val btnPreset = view.findViewById<MaterialButton>(R.id.btn_limits_preset)
        val switchAutoMirror = view.findViewById<MaterialSwitch>(R.id.switch_mirror_auto)
        val editMirror = view.findViewById<EditText>(R.id.edit_mirror_custom)
        val btnSpeedtest = view.findViewById<MaterialButton>(R.id.btn_mirror_speedtest)
        val tvProbe = view.findViewById<TextView>(R.id.tv_mirror_probe_result)
        val editThreads = view.findViewById<EditText>(R.id.edit_download_threads)
        val tvAptStatus = view.findViewById<TextView>(R.id.tv_apt_status)
        val btnAptSwitch = view.findViewById<MaterialButton>(R.id.btn_apt_switch)
        val btnAptRestore = view.findViewById<MaterialButton>(R.id.btn_apt_restore)

        /** 把内存中的配置回填到表单 */
        fun fill(limits: ResourceLimits, mirror: String, auto: Boolean, threads: Int) {
            // 0 显示为空串而不是 "0"：占位文本本身就是「不限制」，
            // 显式填 0 会让用户以为必须写数字
            editMemory.setText(if (limits.memoryMb > 0) limits.memoryMb.toString() else "")
            editProcesses.setText(if (limits.maxProcesses > 0) limits.maxProcesses.toString() else "")
            editOpenFiles.setText(if (limits.maxOpenFiles > 0) limits.maxOpenFiles.toString() else "")
            editCpu.setText(if (limits.cpuSeconds > 0) limits.cpuSeconds.toString() else "")
            editStack.setText(if (limits.stackMb > 0) limits.stackMb.toString() else "")
            editMirror.setText(mirror)
            switchAutoMirror.isChecked = auto
            editThreads.setText(threads.toString())
        }

        fill(cfg.limits, cfg.mirrorOverride, cfg.preferAutoMirror, cfg.safeThreads)

        fun refreshAptStatus() {
            val m = AptMirror.fromId(instanceStore.get(app.id).aptMirror)
            tvAptStatus.text = if (m == null) {
                getString(R.string.apt_not_switched)
            } else {
                getString(R.string.apt_switched_fmt, m.label)
            }
        }
        refreshAptStatus()

        // 预设：选中后直接回填表单，用户仍可继续微调
        btnPreset.setOnClickListener {
            val labels = ResourceLimits.PRESETS.map { it.first }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.limits_preset)
                .setItems(labels) { _, which ->
                    val p = ResourceLimits.PRESETS[which].second
                    fill(
                        p,
                        editMirror.text.toString(),
                        switchAutoMirror.isChecked,
                        editThreads.text.toString().toIntOrNull() ?: cfg.safeThreads
                    )
                }
                .show()
        }

        // 测速：对「自定义源 + 清单内全部镜像」一起探测，让用户看到真实排序
        btnSpeedtest.setOnClickListener {
            val candidates = buildMirrorCandidates(app, editMirror.text.toString())
            if (candidates.isEmpty()) {
                toast(getString(R.string.mirror_probe_failed))
                return@setOnClickListener
            }
            btnSpeedtest.isEnabled = false
            tvProbe.visibility = View.VISIBLE
            tvProbe.text = getString(R.string.mirror_speedtesting)
            scope.launch {
                val ranked = MirrorProbeService.probe(candidates)
                btnSpeedtest.isEnabled = true
                tvProbe.text = if (ranked.isEmpty()) {
                    getString(R.string.mirror_probe_failed)
                } else {
                    ranked.joinToString("\n") {
                        getString(R.string.mirror_probe_result_fmt, it.url, it.latencyMs)
                    }
                }
            }
        }

        // 换源：先选镜像，再落盘
        btnAptSwitch.setOnClickListener {
            val labels = AptMirror.ALL.map { it.label }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.apt_mirror_pick)
                .setItems(labels) { _, which ->
                    applyAptMirror(app, AptMirror.ALL[which]) { refreshAptStatus() }
                }
                .show()
        }

        btnAptRestore.setOnClickListener {
            restoreAptMirror(app) { refreshAptStatus() }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_limits_title_fmt, app.name))
            .setView(view)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                saveInstanceSettings(
                    app = app,
                    editMemory = editMemory,
                    editProcesses = editProcesses,
                    editOpenFiles = editOpenFiles,
                    editCpu = editCpu,
                    editStack = editStack,
                    editMirror = editMirror,
                    switchAutoMirror = switchAutoMirror,
                    editThreads = editThreads
                )
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * 读取表单并保存。任一字段非法就整体放弃（提示后让用户改），
     * **不做部分保存**——半套限制比不限制更难排查。
     */
    private fun saveInstanceSettings(
        app: MyApp,
        editMemory: EditText,
        editProcesses: EditText,
        editOpenFiles: EditText,
        editCpu: EditText,
        editStack: EditText,
        editMirror: EditText,
        switchAutoMirror: MaterialSwitch,
        editThreads: EditText
    ) {
        fun readInt(e: EditText): Int? {
            val t = e.text.toString().trim()
            if (t.isEmpty()) return 0
            return t.toIntOrNull()?.takeIf { it >= 0 }
        }
        val memory = readInt(editMemory)
        val processes = readInt(editProcesses)
        val openFiles = readInt(editOpenFiles)
        val cpu = readInt(editCpu)
        val stack = readInt(editStack)
        val threads = readInt(editThreads)
        if (memory == null || processes == null || openFiles == null ||
            cpu == null || stack == null || threads == null
        ) {
            toast(getString(R.string.limits_invalid))
            return
        }
        val mirror = instanceStore.normalizeMirror(editMirror.text.toString())
        if (mirror == null) {
            // 只允许 http/https：提示后不保存，避免把 file:// 之类写进配置
            toast(getString(R.string.mirror_custom_invalid))
            return
        }

        val limits = ResourceLimits(
            memoryMb = memory,
            maxProcesses = processes,
            maxOpenFiles = openFiles,
            cpuSeconds = cpu,
            stackMb = stack
        )
        instanceStore.put(
            instanceStore.get(app.id).copy(
                limits = limits,
                mirrorOverride = mirror,
                preferAutoMirror = switchAutoMirror.isChecked,
                downloadThreads = threads.coerceIn(1, MultiPartDownloader.MAX_THREADS)
            )
        )
        AppLogger.i(
            "MainActivity",
            "instance ${app.id} saved limits=${limits.summary()} mirror='$mirror' " +
                "auto=${switchAutoMirror.isChecked} threads=${threads.coerceIn(1, MultiPartDownloader.MAX_THREADS)}"
        )
        toast(getString(R.string.limits_saved))
        refreshMyApps()
    }

    /**
     * 组装用于测速的候选地址：自定义源（若有）优先，其后是清单里的 url + mirrors。
     *
     * 自定义源作为**额外候选**参与排序，而不是替换清单：清单里的地址是
     * 已知可用的保底，用户填的自定义源可能拼错或已下线，不能因此失去保底。
     */
    private fun buildMirrorCandidates(app: MyApp, customInput: String): List<String> {
        val list = ArrayList<String>()
        instanceStore.normalizeMirror(customInput)?.takeIf { it.isNotEmpty() }?.let {
            list.add(it)
        }
        val d = distroCache[app.id]
        if (d != null) {
            list.addAll(d.allUrls)
        }
        return list.distinct()
    }

    /**
     * 执行换源。换源直接改 rootfs 内的文本文件，不需要启动 guest。
     *
     * 正在运行的实例也能换（文件系统是共享的），但 guest 内的 apt 可能已缓存
     * 旧的源列表，因此必须提示用户重启会话。
     */
    private fun applyAptMirror(app: MyApp, mirror: AptMirror, onDone: () -> Unit) {
        if (SessionManager.isRunning(app.id)) {
            toast(getString(R.string.apt_running_warning))
        }
        scope.launch {
            val rootfs = manager.distroDir(app.id)
            val result = withContext(Dispatchers.IO) {
                AptSourceSwitcher.switchTo(rootfs, mirror, app.id)
            }
            when (result) {
                is AptSwitchResult.Success -> {
                    instanceStore.updateAptMirror(app.id, mirror.id)
                    toast(getString(R.string.apt_switch_ok_fmt, result.detail))
                    AppLogger.i("MainActivity", "apt switch ok ${app.id}: ${result.detail}")
                }
                is AptSwitchResult.Failure -> {
                    // 失败不写配置：否则界面会显示「已换源」但文件其实没改
                    toast(getString(R.string.apt_switch_failed_fmt, result.reason))
                    AppLogger.w("MainActivity", "apt switch failed ${app.id}: ${result.reason}")
                }
            }
            onDone()
        }
    }

    /** 恢复到发行版原始源 */
    private fun restoreAptMirror(app: MyApp, onDone: () -> Unit) {
        scope.launch {
            val rootfs = manager.distroDir(app.id)
            val result = withContext(Dispatchers.IO) {
                AptSourceSwitcher.restoreOriginal(rootfs)
            }
            when (result) {
                is AptSwitchResult.Success -> {
                    instanceStore.updateAptMirror(app.id, null)
                    toast(getString(R.string.apt_restore_ok_fmt, result.detail))
                }
                is AptSwitchResult.Failure -> {
                    toast(getString(R.string.apt_restore_failed_fmt, result.reason))
                }
            }
            onDone()
        }
    }

    // ================================================================== 会话启动

    /** 从「我的系统」列表启动 */
    private fun launchMyApp(app: MyApp) {
        if (!manager.isInstalled(app.id)) {
            toast(getString(R.string.toast_not_installed))
            refreshMyApps()
            return
        }
        if (SessionManager.isRunning(app.id)) {
            renderSessionOutput()
            showScreen(Screen.TERMINAL)
            return
        }
        startSession(app.id, app.name)
    }

    /** 从「版本列表」启动 */
    private fun launchDistro(d: DistroInfo) {
        if (!manager.isInstalled(d.id)) {
            toast(getString(R.string.toast_not_installed))
            return
        }
        if (SessionManager.isRunning(d.id)) {
            renderSessionOutput()
            showScreen(Screen.TERMINAL)
            return
        }
        startSession(d.id, d.name)
    }

    /**
     * 通过前台服务启动会话。进程创建在服务里完成，
     * 这样即使本 Activity 立刻被销毁，启动流程也会走完。
     */
    private fun startSession(id: String, name: String) {
        // 多实例：启动新实例**不再**结束其他实例（§8.17）。
        // 若同一实例已在跑，[launchMyApp]/[launchDistro] 已经提前返回，
        // 因此这里只需提示用户「又开了一个」。
        val others = SessionManager.runningIds().filter { it != id }
        if (others.isNotEmpty()) {
            AppLogger.i("MainActivity", "starting $id alongside ${others.joinToString()}")
            toast(getString(R.string.toast_instances_parallel, others.size))
        }
        // 终端焦点切到新启动的实例
        focusedId = id
        prefs.lastVersionId = id
        requestNotificationPermissionIfNeeded()

        // 先切 UI，让用户马上看到终端与「正在启动」状态
        outputRaw.setLength(0)
        appendOutput("[ProotTerm] 正在启动 $name …\n")
        appendOutput("[ProotTerm] rootfs=${manager.distroDir(id).absolutePath}\n")
        appendOutput("[ProotTerm] 非交互 shell 无提示符/无回显属正常，可输入 sh -i 进入交互模式\n\n")
        renderOutput()
        showScreen(Screen.TERMINAL)

        val launched = ProotService.launch(this, ProotService.startIntent(this, id))
        if (!launched) {
            appendOutput("[ProotTerm] 无法启动前台服务（可能被系统限制后台运行）\n")
            setStatus(getString(R.string.start_failed_fmt, "前台服务启动被拒绝"))
        }
    }

    /**
     * API 33+ 需要运行时授予 POST_NOTIFICATIONS，否则前台服务通知不可见。
     * 拒绝也不阻断：服务仍会运行，只是用户看不到常驻通知。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            } catch (e: Exception) {
                AppLogger.w("MainActivity", "notif permission request failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------- SessionManager 回调
    //
    // ⚠️ 多实例下每个回调都带 `id`。**必须**先判断是不是当前焦点实例再动终端，
    // 否则并行的另一个实例的输出会串进本实例的终端缓冲，用户看到的是混合内容。

    override fun onSessionState(id: String, state: SessionManager.State, versionName: String?) {
        // 焦点实例的状态才驱动顶栏与终端文案
        if (id == focusedId || focusedId == null) {
            when (state) {
                SessionManager.State.STARTING -> setStatus("正在启动 ${versionName ?: id} …")
                SessionManager.State.RUNNING -> setStatus(
                    getString(R.string.status_session_running_fmt, versionName ?: "")
                )
                SessionManager.State.STOPPING -> setStatus("正在结束会话 …")
                SessionManager.State.IDLE -> updateStatusForSession()
            }
        }
        // 焦点实例进入 RUNNING 时重放它的输出，清掉启动期间的临时提示
        if (id == focusedId && state == SessionManager.State.RUNNING &&
            outputRaw.isNotEmpty() && searchQuery.isEmpty()
        ) {
            renderSessionOutput()
        }
        refreshMyApps()
    }

    override fun onOutput(id: String, text: String) {
        // 只渲染焦点实例的输出；其他实例的输出仍留在各自的会话缓冲里，
        // 切过去时用 renderSessionOutput() 从缓冲重放，不会丢失。
        if (id != focusedId) return
        if (screen != Screen.TERMINAL) return
        appendOutput(text)
    }

    override fun onExit(id: String, code: Int, reason: ProotSession.ExitReason) {
        if (id == focusedId) {
            when (reason) {
                ProotSession.ExitReason.START_FAILED -> {
                    val err = SessionManager.lastError(id) ?: "未知错误"
                    appendOutput("\n[ProotTerm] 启动失败：$err\n")
                    setStatus(getString(R.string.start_failed_fmt, err))
                }
                ProotSession.ExitReason.DESTROYED -> {
                    appendOutput("\n[ProotTerm] 会话已结束\n")
                    setStatus(getString(R.string.status_session_closed))
                }
                ProotSession.ExitReason.NORMAL -> {
                    appendOutput("\n[ProotTerm] shell 已退出 code=$code\n")
                    setStatus("shell 已退出 code=$code")
                }
            }
        } else {
            // 非焦点实例退出：只提示，不污染终端
            AppLogger.i("MainActivity", "background instance $id exited code=$code")
        }
        refreshMyApps()
    }

    /**
     * 用会话快照重建终端显示。
     *
     * 用于 Activity 重建、从后台回到前台：这两条路径下 `outputRaw` 可能是空的
     * （进程被回收重建）或是过期的（本实例启动到一半），都必须以
     * [SessionManager] 的**焦点实例**缓冲为准，否则「界面显示的」与「会话实际有的」会漂移。
     *
     * 只在会话确实存在或正在启动时才覆盖，避免把
     * [startSession] 刚写好的启动提示抹掉。
     */
    private fun restoreFromSession() {
        // 焦点实例没在跑时，尝试挑一个正在跑的实例接管终端
        if (focusedId == null || SessionManager.stateOf(focusedId!!) == SessionManager.State.IDLE) {
            focusedId = SessionManager.runningIds().firstOrNull() ?: focusedId
        }
        // 焦点实例处于 IDLE 且没有别的实例在跑：没什么可恢复的
        val fid = focusedId ?: return
        if (SessionManager.stateOf(fid) == SessionManager.State.IDLE) return
        // 注意：不要因为 snapshot 为空就提前返回。会话刚 `clearOutput()` 过、
        // 或刚 fork 还没吐字符时，快照本来就是空的，此时应当照常重绘。
        renderSessionOutput()
    }

    private fun renderSessionOutput() {
        outputRaw.setLength(0)
        val fid = focusedId
        if (fid == null) {
            outputRaw.append(getString(R.string.terminal_empty))
            renderOutput()
            return
        }
        val snap = SessionManager.snapshot(fid)
        when {
            snap.isNotEmpty() -> {
                outputRaw.append(snap)
                if (outputRaw.length > MAX_OUTPUT_CHARS) {
                    outputRaw.delete(0, outputRaw.length - MAX_OUTPUT_CHARS / 2)
                }
            }
            // 会话结束且没有内容：显示占位提示
            SessionManager.stateOf(fid) == SessionManager.State.IDLE ->
                outputRaw.append(getString(R.string.terminal_empty))
            // 会话在跑但暂时没有输出：保持空白，**绝不能**塞占位提示，
            // 否则终端会在会话运行中显示「输出将显示在这里」，看起来像已断开
            else -> Unit
        }
        renderOutput()
    }

    private fun updateStatusForSession() {
        val fid = focusedId
        setStatus(
            if (fid != null && SessionManager.isRunning(fid)) {
                getString(
                    R.string.status_session_running_fmt,
                    SessionManager.versionNameOf(fid) ?: fid
                )
            } else {
                val all = SessionManager.runningIds()
                // 有实例在跑但都不是焦点（比如刚切过焦点）：如实说明总数
                if (all.isNotEmpty()) {
                    getString(R.string.status_instances_running_fmt, all.size)
                } else {
                    getString(R.string.status_idle)
                }
            }
        )
    }

    // ================================================================== 导航

    private fun setupNavigation() {
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_myapps -> {
                    refreshMyApps()
                    showScreen(Screen.MY_APPS)
                }
                R.id.nav_terminal -> {
                    renderSessionOutput()
                    showScreen(Screen.TERMINAL)
                }
                R.id.nav_distros -> showScreen(Screen.DISTROS)
                R.id.nav_settings -> {
                    refreshSettings()
                    showScreen(Screen.SETTINGS)
                }
                R.id.nav_logs -> {
                    try {
                        startActivity(Intent(this, LogActivity::class.java))
                    } catch (e: Exception) {
                        AppLogger.e("MainActivity", "open logs failed", e)
                        toast("打开日志失败: ${e.message}")
                    }
                }
            }
            drawer.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun showScreen(target: Screen) {
        screen = target
        viewTerminal.visibility = if (target == Screen.TERMINAL) View.VISIBLE else View.GONE
        viewDistros.visibility = if (target == Screen.DISTROS) View.VISIBLE else View.GONE
        viewVersions.visibility = if (target == Screen.VERSIONS) View.VISIBLE else View.GONE
        viewMyApps.visibility = if (target == Screen.MY_APPS) View.VISIBLE else View.GONE
        viewSettings.visibility = if (target == Screen.SETTINGS) View.VISIBLE else View.GONE

        val menuId = when (target) {
            Screen.MY_APPS -> R.id.nav_myapps
            Screen.TERMINAL -> R.id.nav_terminal
            Screen.DISTROS, Screen.VERSIONS -> R.id.nav_distros
            Screen.SETTINGS -> R.id.nav_settings
        }
        navView.setCheckedItem(menuId)
        val familyName = if (target == Screen.VERSIONS) currentFamily?.name else null
        toolbar.title = familyName ?: getString(
            when (target) {
                Screen.MY_APPS -> R.string.nav_myapps
                Screen.TERMINAL -> R.string.nav_terminal
                Screen.DISTROS, Screen.VERSIONS -> R.string.nav_distros
                Screen.SETTINGS -> R.string.nav_settings
            }
        )
        if (target == Screen.TERMINAL) {
            scrollOutput.post { scrollOutput.fullScroll(View.FOCUS_DOWN) }
        }
        if (target == Screen.SETTINGS) refreshSettings()
    }

    private fun openVersions(family: DistroFamily) {
        currentFamily = family
        versionAdapter = VersionAdapter(
            family.versions,
            onDownload = { install(it) },
            onStart = { launchDistro(it) },
            onUninstall = { confirmUninstallDistro(it) },
            onCancelInstall = { cancelInstall(it) }
        )
        recyclerVersions.adapter = versionAdapter
        tvVersionsHeader.text = buildString {
            append(getString(R.string.versions_header_fmt, family.name))
            append('\n')
            append(
                getString(
                    R.string.versions_download_target_fmt,
                    manager.sandboxDir.absolutePath
                )
            )
        }
        family.versions.forEach { v ->
            when {
                manager.isInstalled(v.id) ->
                    versionAdapter.setState(v.id, VersionAdapter.State.Installed)
                manager.isInstalledElsewhere(v.id) ->
                    versionAdapter.setState(
                        v.id,
                        VersionAdapter.State.InstalledElsewhere(manager.otherLocation().label)
                    )
                activeInstallId == v.id ->
                    versionAdapter.setState(
                        v.id,
                        VersionAdapter.State.Downloading(lastDownloadPct),
                        VersionAdapter.PAYLOAD_PROGRESS
                    )
            }
        }
        showScreen(Screen.VERSIONS)
    }

    // ================================================================== 终端

    private fun setupTerminal() {
        btnSend.setOnClickListener { sendCommand() }
        editCommand.setOnEditorActionListener { _, actionId, event ->
            // 输入框用的是 actionNone，正常情况下不会收到 IME 动作；
            // 但外接键盘（蓝牙/USB）的 Enter 会走这里，必须同样发送命令。
            val isEnter = event != null &&
                event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                isEnter
            ) {
                sendCommand()
                true
            } else {
                false
            }
        }
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                Unit

            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderOutput()
            }
        })
        hardenImeForTerminal()
    }

    /**
     * 加固命令输入框，确保任何输入法都不会切到「安全键盘」。
     *
     * XML 里的 `inputType=textVisiblePassword` 已解决绝大部分情况，
     * 但部分 ROM 的输入法还会看 `imeOptions`、`privateImeOptions` 与
     * 自动填充提示。这里在运行期再钉死一遍：
     *  - 清空 privateImeOptions（有些输入法通过它识别密码场景）；
     *  - 关闭自动纠错 / 联想 / 大写首字母（终端命令绝不能被改写）；
     *  - importantForAutofill=no，避免被当成账号密码框；
     *  - 不显示 IME 的全屏提取界面，避免遮住终端。
     */
    private fun hardenImeForTerminal() {
        editCommand.apply {
            setPrivateImeOptions(null)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            isSingleLine = true
            setHorizontallyScrolling(true)
        }
    }

    private fun setupHotkeys() {
        findViewById<MaterialButton>(R.id.btn_key_esc).setOnClickListener { sendRawKey(KEY_ESC) }
        btnCtrl.setOnClickListener { setModifier(if (modifierArmed == Modifier.CTRL) Modifier.NONE else Modifier.CTRL) }
        btnAlt.setOnClickListener { setModifier(if (modifierArmed == Modifier.ALT) Modifier.NONE else Modifier.ALT) }
        findViewById<MaterialButton>(R.id.btn_key_tab).setOnClickListener { sendRawKey("\t") }
        findViewById<MaterialButton>(R.id.btn_key_enter).setOnClickListener { sendRawKey("\n") }
        findViewById<MaterialButton>(R.id.btn_key_pipe).setOnClickListener { sendRawKey("|") }
        findViewById<MaterialButton>(R.id.btn_key_slash).setOnClickListener { sendRawKey("/") }
        findViewById<MaterialButton>(R.id.btn_key_up).setOnClickListener { sendRawKey(KEY_UP) }
        findViewById<MaterialButton>(R.id.btn_key_down).setOnClickListener { sendRawKey(KEY_DOWN) }
        findViewById<MaterialButton>(R.id.btn_key_left).setOnClickListener { sendRawKey(KEY_LEFT) }
        findViewById<MaterialButton>(R.id.btn_key_right).setOnClickListener { sendRawKey(KEY_RIGHT) }
        findViewById<MaterialButton>(R.id.btn_key_home).setOnClickListener { sendRawKey(KEY_HOME) }
        findViewById<MaterialButton>(R.id.btn_key_end).setOnClickListener { sendRawKey(KEY_END) }
        findViewById<MaterialButton>(R.id.btn_key_pgup).setOnClickListener { sendRawKey(KEY_PGUP) }
        findViewById<MaterialButton>(R.id.btn_key_pgdn).setOnClickListener { sendRawKey(KEY_PGDN) }

        buildLetterRow()
        setModifier(Modifier.NONE)
    }

    /**
     * 动态生成 a~z 组合键按钮（26 个手写 XML 过于冗长）。
     * 仅在 CTRL / ALT 待命时随 scroll_ctrl_letters 一起显示。
     */
    private fun buildLetterRow() {
        val group = findViewById<LinearLayout>(R.id.group_ctrl_letters)
        val margin = (6 * resources.displayMetrics.density).toInt()
        for (ch in 'a'..'z') {
            // 从模板 inflate：style="@style/App.Key" 走 XML 解析，
            // 与 ESC/CTRL/TAB 按钮外观一致（不能靠构造函数传 style，见模板注释）
            val btn = layoutInflater.inflate(R.layout.item_ctrl_key, group, false) as MaterialButton
            btn.text = ch.uppercaseChar().toString()
            (btn.layoutParams as? LinearLayout.LayoutParams)?.let {
                if (ch != 'a') it.marginStart = margin
            }
            btn.setOnClickListener {
                when (modifierArmed) {
                    // CTRL+A -> 0x01 ... CTRL+Z -> 0x1A
                    Modifier.CTRL -> sendRawKey(
                        ((ch.uppercaseChar().code - 'A'.code) + 1).toChar().toString()
                    )
                    // ALT+A -> ESC 前缀 + 字母（readline / vim 的 Meta 组合键约定）
                    Modifier.ALT -> sendRawKey(KEY_ESC + ch)
                    Modifier.NONE -> Unit
                }
                setModifier(Modifier.NONE)
            }
            group.addView(btn)
        }
    }

    /** 修饰键待命态：按钮高亮 + 字母行显隐 + 副标题提示 */
    private fun setModifier(armed: Modifier) {
        modifierArmed = armed
        btnCtrl.alpha = if (armed == Modifier.CTRL) 0.6f else 1f
        btnAlt.alpha = if (armed == Modifier.ALT) 0.6f else 1f
        findViewById<View>(R.id.scroll_ctrl_letters).visibility =
            if (armed == Modifier.NONE) View.GONE else View.VISIBLE
        when (armed) {
            Modifier.CTRL -> setStatus(getString(R.string.status_ctrl_armed))
            Modifier.ALT -> setStatus(getString(R.string.status_alt_armed))
            Modifier.NONE -> updateStatusForSession()
        }
    }

    private fun sendCommand() {
        val cmd = editCommand.text.toString()
        if (cmd.isBlank()) {
            setStatus(getString(R.string.send_empty))
            return
        }
        val fid = focusedId
        if (fid == null || !SessionManager.isRunning(fid)) {
            setStatus("请先在「我的系统」启动一个系统")
            showScreen(Screen.MY_APPS)
            return
        }
        try {
            SessionManager.sendCommand(fid, cmd)
            appendOutput("$ $cmd\n")
            editCommand.text.clear()
        } catch (e: Exception) {
            setStatus("发送失败：${e.message}")
        }
    }

    private fun sendRawKey(data: String) {
        val fid = focusedId
        if (fid == null || !SessionManager.isRunning(fid)) {
            setStatus("请先启动一个系统")
            return
        }
        try {
            SessionManager.sendRaw(fid, data)
        } catch (e: Exception) {
            setStatus("发送失败：${e.message}")
        }
    }

    // ================================================================== 设置页

    private fun setupSettings() {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) {
            null
        }
        findViewById<TextView>(R.id.tv_settings_version).text =
            "${getString(R.string.settings_version)}：${info?.versionName ?: "?"}"
        findViewById<TextView>(R.id.tv_settings_package).text =
            "${getString(R.string.settings_package)}：$packageName"
        findViewById<TextView>(R.id.tv_settings_abi).text =
            "${getString(R.string.settings_abi)}：arm64-v8a"
        findViewById<TextView>(R.id.tv_settings_sdk).text =
            "${getString(R.string.settings_sdk)}：API 24 ~ 36（Android 7.0 ~ 16）"

        findViewById<TextView>(R.id.tv_github).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
            } catch (e: Exception) {
                toast("无法打开浏览器: ${e.message}")
            }
        }

        findViewById<MaterialButton>(R.id.btn_clear_terminal).setOnClickListener {
            focusedId?.let { SessionManager.clearOutput(it) }
            outputRaw.setLength(0)
            renderOutput()
            toast("终端输出已清空")
        }

        // ---- 存储位置 ----
        findViewById<RadioGroup>(R.id.group_storage).setOnCheckedChangeListener { _, checkedId ->
            val wanted = when (checkedId) {
                R.id.radio_storage_external -> StorageLocation.EXTERNAL
                else -> StorageLocation.INTERNAL
            }
            onStorageSelected(wanted)
        }

        // ---- 开机自启动 ----
        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.autoStart = checked
            if (checked && prefs.autoStartId.isBlank()) {
                // 开关打开但还没选系统：立刻引导选择，避免留下「开了但不生效」的状态
                toast(getString(R.string.autostart_need_target))
                pickAutoStartTarget()
            } else if (!checked) {
                toast(getString(R.string.autostart_cleared))
            }
            refreshSettings()
        }
        tvAutoStartTarget.setOnClickListener { pickAutoStartTarget() }
    }

    private fun refreshSettings() {
        // 存储位置：先同步单选状态，再写「当前实际路径」
        val location = manager.location
        val target = if (location == StorageLocation.EXTERNAL) R.id.radio_storage_external
        else R.id.radio_storage_internal
        if (findViewById<RadioGroup>(R.id.group_storage).checkedRadioButtonId != target) {
            findViewById<RadioGroup>(R.id.group_storage).check(target)
        }
        val externalAvailable = manager.let {
            val dir = StorageLocation.sandboxDir(this, StorageLocation.EXTERNAL)
            dir != null && StorageLocation.EXTERNAL.isAvailable(dir)
        }
        radioExternal.isEnabled = externalAvailable
        radioExternal.alpha = if (externalAvailable) 1f else 0.5f
        findViewById<TextView>(R.id.tv_storage_external_desc).alpha =
            if (externalAvailable) 1f else 0.5f

        val currentText = buildString {
            if (manager.locationFallback) {
                append(getString(R.string.storage_fallback_warning))
                append('\n')
            }
            append(
                getString(
                    R.string.storage_current_fmt,
                    location.label,
                    manager.sandboxDir.absolutePath
                )
            )
            if (!externalAvailable) {
                append('\n')
                append(getString(R.string.storage_unavailable))
            }
        }
        tvStorageCurrent.text = currentText
        findViewById<TextView>(R.id.tv_settings_rootfs).text =
            "${getString(R.string.settings_rootfs)}：${manager.rootfsRoot().absolutePath}"

        // 开机自启动：开关状态 + 目标名
        if (switchAutoStart.isChecked != prefs.autoStart) {
            switchAutoStart.isChecked = prefs.autoStart
        }
        val targetId = prefs.autoStartId
        val targetName = targetId.takeIf { it.isNotBlank() }
            ?.let { id -> myApps.firstOrNull { it.id == id }?.name ?: id }
            ?: getString(R.string.autostart_none)
        tvAutoStartTarget.text = "${getString(R.string.autostart_pick)}：$targetName"
    }

    private fun onStorageSelected(wanted: StorageLocation) {
        if (wanted == manager.location && !manager.locationFallback) return

        val dir = StorageLocation.sandboxDir(this, wanted)
        if (dir == null || !wanted.isAvailable(dir)) {
            toast(getString(R.string.toast_storage_unavailable))
            // 把单选拨回当前真实位置，避免 UI 与实际不一致
            refreshSettings()
            return
        }

        val installedHere = myApps.size
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_switch_title)
            .setMessage(
                getString(R.string.storage_switch_message) +
                    if (installedHere > 0) "\n\n当前位置有 $installedHere 个已安装的系统。" else ""
            )
            .setPositiveButton(R.string.storage_switch_confirm) { _, _ ->
                // 切换存储位置前，结束**当前存储位置下**运行中的实例。
                //
                // ⚠️ 这里刻意只按 id 结束，**不再**追加一个不带 id 的
                // `stopIntent(this)`。那个不带 id 的意图语义是「结束全部」，
                // 会把 rootfs 位于**另一个**存储位置、完全不受本次切换影响的
                // 实例也一起杀掉（§8.17 多实例隔离）。
                var stopped = 0
                for (rid in SessionManager.runningIds()) {
                    // 只有 rootfs 在旧位置上的实例才需要停下
                    if (manager.isInstalled(rid) || focusedId == rid) {
                        ProotService.launch(this, ProotService.stopIntent(this, rid))
                        stopped++
                    }
                }
                if (stopped > 0) {
                    AppLogger.i("MainActivity", "stopped $stopped instance(s) before storage switch")
                    toast(getString(R.string.status_session_closed))
                }
                if (manager.setLocation(wanted)) {
                    toast(getString(R.string.toast_storage_switched, wanted.label))
                    refreshMyApps()
                    refreshSettings()
                } else {
                    toast(getString(R.string.toast_storage_unavailable))
                    refreshSettings()
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> refreshSettings() }
            .setOnCancelListener { refreshSettings() }
            .show()
    }

    private fun switchToOtherLocation() {
        val other = manager.otherLocation()
        radioInternal.post {
            if (other == StorageLocation.EXTERNAL) radioExternal.isChecked = true
            else radioInternal.isChecked = true
        }
    }

    /**
     * 选择自启动目标系统。
     * 只列出**当前存储位置已安装**的系统：自启动发生在开机瞬间，
     * 此时外部存储可能尚未挂载，指向内部位置的系统最可靠，
     * 但也不阻止用户选择外部的（服务会如实报「未安装」）。
     */
    private fun pickAutoStartTarget() {
        refreshMyApps()
        if (myApps.isEmpty()) {
            toast(getString(R.string.autostart_no_installed))
            switchAutoStart.isChecked = false
            prefs.autoStart = false
            refreshSettings()
            return
        }
        val names = myApps.map { it.name }.toTypedArray()
        // indexOfFirst 找不到时本来就返回 -1（= 不预选任何一项），无需再矫正
        val checked = myApps.indexOfFirst { it.id == prefs.autoStartId }
        AlertDialog.Builder(this)
            .setTitle(R.string.autostart_pick_title)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val app = myApps[which]
                prefs.autoStartId = app.id
                prefs.autoStart = true
                toast(getString(R.string.autostart_set_fmt, app.name))
                refreshSettings()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                // 取消且从未选过目标 -> 把开关退回去，不留「开了但没目标」的状态
                if (prefs.autoStartId.isBlank()) {
                    prefs.autoStart = false
                    switchAutoStart.isChecked = false
                    refreshSettings()
                }
            }
            .show()
    }

    // ================================================================== 返回键

    @Deprecated("保持与旧行为一致；AGENTS.md 未要求切换到 OnBackPressedDispatcher")
    override fun onBackPressed() {
        when {
            drawer.isDrawerOpen(GravityCompat.START) -> {
                drawer.closeDrawer(GravityCompat.START)
                return
            }
            screen == Screen.VERSIONS -> {
                showScreen(Screen.DISTROS)
                return
            }
            screen == Screen.TERMINAL && focusedId?.let { SessionManager.isRunning(it) } == true -> {
                showExitDialog()
                return
            }
            screen != Screen.MY_APPS -> {
                refreshMyApps()
                showScreen(Screen.MY_APPS)
                return
            }
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_exit_title)
            .setMessage(R.string.dlg_exit_message)
            .setPositiveButton(R.string.btn_keep_alive) { _, _ ->
                // 不 destroy 会话：前台服务继续保活，通知常驻
                refreshMyApps()
                showScreen(Screen.MY_APPS)
                toast(getString(R.string.status_session_kept))
            }
            .setNegativeButton(R.string.btn_close_terminal) { _, _ ->
                // 只结束终端正在显示的那个实例。其他实例继续在后台跑
                // （用户在对话框里选的是「结束会话」，不是「结束全部」）。
                // ⚠️ 曾经这里还多调了一次不带 id 的 stopIntent(this)，
                // 那会结束**所有**实例，把用户想保留的后台系统一并杀掉。
                val target = focusedId
                if (target != null) {
                    ProotService.launch(this, ProotService.stopIntent(this, target))
                }
                outputRaw.setLength(0)
                renderOutput()
                showScreen(Screen.MY_APPS)
                setStatus(getString(R.string.status_session_closed))
                refreshMyApps()
            }
            .setNeutralButton(R.string.btn_cancel, null)
            .show()
    }

    // ================================================================== 输出渲染

    private fun setStatus(msg: String) {
        toolbar.subtitle = msg
    }

    private fun appendOutput(text: String) {
        if (text.isEmpty()) return
        outputRaw.append(text)
        if (outputRaw.length > MAX_OUTPUT_CHARS) {
            outputRaw.delete(0, outputRaw.length - MAX_OUTPUT_CHARS / 2)
        }
        renderOutput()
    }

    private fun renderOutput() {
        val raw = outputRaw.toString()
        val shown = if (searchQuery.isEmpty()) {
            raw
        } else {
            raw.lineSequence()
                .filter { it.contains(searchQuery, ignoreCase = true) }
                .joinToString("\n")
        }
        tvOutput.text = if (shown.isNotEmpty()) {
            // 输出被截断时在顶部说明，避免用户以为命令没有输出
            if (searchQuery.isEmpty() && (focusedId?.let { SessionManager.truncatedChars(it) } ?: 0) > 0) {
                getString(R.string.output_truncated) + "\n" + shown
            } else {
                shown
            }
        } else {
            getString(R.string.terminal_empty)
        }
        scrollOutput.post { scrollOutput.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        // 只清理「安装任务」这类与 UI 强绑定的工作。
        // **绝不 destroy 会话**：会话由前台服务保活，Activity 销毁不该影响它。
        installJob?.let {
            it.cancel()
            downloader.cancel()
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 200_000
        private const val MB = 1024.0 * 1024.0
        private const val REQ_NOTIF = 9001
        private const val KEY_ESC = "\u001b"
        private const val KEY_UP = "\u001b[A"
        private const val KEY_DOWN = "\u001b[B"
        private const val KEY_RIGHT = "\u001b[C"
        private const val KEY_LEFT = "\u001b[D"
        private const val KEY_HOME = "\u001b[H"
        private const val KEY_END = "\u001b[F"
        private const val KEY_PGUP = "\u001b[5~"
        private const val KEY_PGDN = "\u001b[6~"
        private const val GITHUB_URL = "https://github.com/LiStudioorg/linuxandroid/"
    }
}
