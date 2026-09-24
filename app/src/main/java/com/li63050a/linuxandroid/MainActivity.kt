package com.li63050a.linuxandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
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
 * 主界面：DrawerLayout 四屏（终端 / 发行版家族 / 版本列表 / 设置）。
 * - 默认落在「发行版」，点家族进入「版本选择」；
 * - 版本页负责下载 / 启动 / 卸载；
 * - 终端页有 Termux 风格快捷键栏；返回键弹「保持运行 / 关闭终端」。
 */
class MainActivity : AppCompatActivity() {

    private enum class Screen { TERMINAL, DISTROS, VERSIONS, SETTINGS }

    private val scope = MainScope()
    private lateinit var manager: RootfsManager
    private val downloader = RootfsDownloader()

    private lateinit var drawer: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var viewTerminal: View
    private lateinit var viewDistros: View
    private lateinit var viewVersions: View
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

    private var families: List<DistroFamily> = emptyList()
    private var currentVersions: List<DistroInfo> = emptyList()
    private var currentFamily: DistroFamily? = null
    private var screen = Screen.DISTROS

    private var installJob: Job? = null
    private var activeInstallId: String? = null
    private var lastDownloadPct = 0
    private var session: ProotSession? = null
    private var sessionVersionId: String? = null

    private var ctrlArmed = false
    private lateinit var btnCtrl: MaterialButton

    private val outputRaw = StringBuilder()
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        manager = RootfsManager(this)

        toolbar = findViewById(R.id.toolbar)
        drawer = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)
        viewTerminal = findViewById(R.id.view_terminal)
        viewDistros = findViewById(R.id.view_distros)
        viewVersions = findViewById(R.id.view_versions)
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

        families = try {
            ManifestLoader.load(this)
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "manifest load failed", e)
            Toast.makeText(this, "清单解析失败: ${e.message}", Toast.LENGTH_LONG).show()
            emptyList()
        }

        distroAdapter = DistroAdapter(families) { openVersions(it) }
        recyclerDistros.layoutManager = LinearLayoutManager(this)
        recyclerDistros.adapter = distroAdapter

        setupVersionList()
        setupNavigation()
        setupTerminal()
        setupHotkeys()
        setupSettings()

        showScreen(Screen.DISTROS)
        toolbar.subtitle = getString(R.string.status_idle)
        AppLogger.i("MainActivity", "onCreate families=${families.size}")
    }

    private fun setupVersionList() {
        versionAdapter = VersionAdapter(
            emptyList(),
            onDownload = { install(it) },
            onStart = { launchVersion(it) },
            onUninstall = { confirmUninstall(it) }
        )
        recyclerVersions.layoutManager = LinearLayoutManager(this)
        recyclerVersions.adapter = versionAdapter
    }

    private fun setupNavigation() {
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_terminal -> showScreen(Screen.TERMINAL)
                R.id.nav_distros -> showScreen(Screen.DISTROS)
                R.id.nav_settings -> showScreen(Screen.SETTINGS)
                R.id.nav_logs -> {
                    try {
                        startActivity(Intent(this, LogActivity::class.java))
                    } catch (e: Exception) {
                        AppLogger.e("MainActivity", "open logs failed", e)
                        Toast.makeText(this, "打开日志失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            drawer.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun setupTerminal() {
        btnSend.setOnClickListener { sendCommand() }
        editCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderOutput()
            }
        })
    }

    private fun setupHotkeys() {
        findViewById<MaterialButton>(R.id.btn_key_esc).setOnClickListener { sendRawKey("") }
        btnCtrl.setOnClickListener {
            ctrlArmed = !ctrlArmed
            btnCtrl.alpha = if (ctrlArmed) 0.6f else 1f
            setStatus(if (ctrlArmed) "CTRL 已按下，再点字母键" else "CTRL 已取消")
        }
        findViewById<MaterialButton>(R.id.btn_key_tab).setOnClickListener { sendRawKey("\t") }
        findViewById<MaterialButton>(R.id.btn_key_up).setOnClickListener { sendRawKey("[A") }
        findViewById<MaterialButton>(R.id.btn_key_down).setOnClickListener { sendRawKey("[B") }
        findViewById<MaterialButton>(R.id.btn_key_left).setOnClickListener { sendRawKey("[D") }
        findViewById<MaterialButton>(R.id.btn_key_right).setOnClickListener { sendRawKey("[C") }

        // 字母键在 CTRL 模式下转为控制字符
        val letters = "abcdefghijklmnopqrstuvwxyz"
        for (ch in letters) {
            val id = resources.getIdentifier("btn_key_$ch", "id", packageName)
            if (id != 0) {
                findViewById<MaterialButton>(id)?.setOnClickListener {
                    if (ctrlArmed) {
                        val code = (ch.uppercaseChar().code - 'A'.code + 1).toChar()
                        sendRawKey(code.toString())
                        ctrlArmed = false
                        btnCtrl.alpha = 1f
                    } else {
                        sendRawKey(ch.toString())
                    }
                }
            }
        }
    }

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
        findViewById<TextView>(R.id.tv_settings_rootfs).text =
            "${getString(R.string.settings_rootfs)}：${File(filesDir, "rootfs").absolutePath}"

        findViewById<TextView>(R.id.tv_github).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btn_clear_terminal).setOnClickListener {
            outputRaw.setLength(0)
            renderOutput()
            setStatus("终端输出已清空")
        }
    }

    // ------------------------------------------------------------------ 导航

    private fun showScreen(target: Screen) {
        screen = target
        viewTerminal.visibility = if (target == Screen.TERMINAL) View.VISIBLE else View.GONE
        viewDistros.visibility = if (target == Screen.DISTROS) View.VISIBLE else View.GONE
        viewVersions.visibility = if (target == Screen.VERSIONS) View.VISIBLE else View.GONE
        viewSettings.visibility = if (target == Screen.SETTINGS) View.VISIBLE else View.GONE

        val menuId = when (target) {
            Screen.TERMINAL -> R.id.nav_terminal
            Screen.DISTROS, Screen.VERSIONS -> R.id.nav_distros
            Screen.SETTINGS -> R.id.nav_settings
        }
        navView.setCheckedItem(menuId)
        val familyName = if (target == Screen.VERSIONS) currentFamily?.name else null
        toolbar.title = familyName ?: getString(
            when (target) {
                Screen.TERMINAL -> R.string.nav_terminal
                Screen.DISTROS, Screen.VERSIONS -> R.string.nav_distros
                Screen.SETTINGS -> R.string.nav_settings
            }
        )
        if (target == Screen.TERMINAL) {
            scrollOutput.post { scrollOutput.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun openVersions(family: DistroFamily) {
        currentFamily = family
        currentVersions = family.versions
        versionAdapter = VersionAdapter(
            family.versions,
            onDownload = { install(it) },
            onStart = { launchVersion(it) },
            onUninstall = { confirmUninstall(it) }
        )
        recyclerVersions.adapter = versionAdapter
        tvVersionsHeader.text = getString(R.string.versions_header_fmt, family.name)
        family.versions.forEach { v ->
            if (manager.isInstalled(v.id)) {
                versionAdapter.setState(v.id, VersionAdapter.State.Installed)
            } else if (activeInstallId == v.id) {
                versionAdapter.setState(
                    v.id,
                    VersionAdapter.State.Downloading(lastDownloadPct),
                    VersionAdapter.PAYLOAD_PROGRESS
                )
            }
        }
        showScreen(Screen.VERSIONS)
    }

    // ------------------------------------------------------------------ 安装

    private fun install(d: DistroInfo) {
        if (installJob?.isActive == true || activeInstallId != null) {
            Toast.makeText(this, "已有下载/安装任务进行中…", Toast.LENGTH_SHORT).show()
            return
        }
        val spaceErr = manager.checkSpaceFor(d.id, d.size)
        if (spaceErr != null) {
            Toast.makeText(this, spaceErr, Toast.LENGTH_LONG).show()
            AppLogger.w("Install", "space check failed: $spaceErr")
            return
        }

        activeInstallId = d.id
        val part = manager.partFile(d.id, d.format)
        manager.cleanupTemp(d.id)
        versionAdapter.setState(d.id, VersionAdapter.State.Downloading(0))
        setStatus("准备下载 ${d.name} …")
        AppLogger.i("Install", "begin ${d.id} url=${d.url}")

        installJob = scope.launch {
            try {
                downloader.download(d, part) { done, total ->
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

                withContext(Dispatchers.IO) { manager.finalizeInstall(d.id, temp) }
                part.delete()
                versionAdapter.setState(d.id, VersionAdapter.State.Installed)
                setStatus("${d.name} 安装完成，点击「启动」")
                AppLogger.i("Install", "done ${d.id}")
            } catch (e: CancellationException) {
                versionAdapter.setState(d.id, VersionAdapter.State.Idle)
                setStatus("已取消（断点已保留，可重新下载）")
                throw e
            } catch (e: Exception) {
                AppLogger.e("Install", "failed ${d.id}", e)
                manager.cleanupTemp(d.id)
                versionAdapter.setState(d.id, VersionAdapter.State.Idle)
                setStatus("失败：${e.message ?: e.javaClass.simpleName}")
                Toast.makeText(
                    this@MainActivity,
                    "安装失败: ${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                activeInstallId = null
            }
        }
    }

    private fun confirmUninstall(d: DistroInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_uninstall_title)
            .setMessage(R.string.dlg_uninstall_message)
            .setPositiveButton(R.string.btn_uninstall) { _, _ ->
                try {
                    if (sessionVersionId == d.id) {
                        session?.destroy()
                        session = null
                        sessionVersionId = null
                    }
                    manager.uninstall(d.id)
                    versionAdapter.setState(d.id, VersionAdapter.State.Idle)
                    setStatus("${d.name} 已卸载")
                } catch (e: Exception) {
                    AppLogger.e("MainActivity", "uninstall failed ${d.id}", e)
                    Toast.makeText(this, "卸载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ PRoot

    private fun launchVersion(d: DistroInfo) {
        if (!manager.isInstalled(d.id)) {
            Toast.makeText(this, R.string.toast_not_installed, Toast.LENGTH_SHORT).show()
            return
        }
        if (session?.isRunning == true && sessionVersionId == d.id) {
            showScreen(Screen.TERMINAL)
            setStatus("${d.name} 的 shell 正在运行")
            return
        }
        if (session?.isRunning == true) {
            session?.destroy()
            session = null
        }
        startShell(d)
        showScreen(Screen.TERMINAL)
    }

    private fun startShell(d: DistroInfo) {
        val libDir = File(applicationInfo.nativeLibraryDir)
        val proot = File(libDir, "libproot.so")
        val loader = File(libDir, "libproot-loader.so")

        outputRaw.setLength(0)
        appendOutput("[ProotTerm] 启动 ${d.name}\n")
        appendOutput("[ProotTerm] rootfs=${manager.distroDir(d.id).absolutePath}\n")
        appendOutput("[ProotTerm] 非交互 shell 无提示符/无回显属正常，可输入 sh -i 进入交互模式\n\n")

        val nativeDir = try {
            NativeDeps.ensure(this)
        } catch (e: Exception) {
            appendOutput("[ProotTerm] 警告: ${e.message}\n")
            null
        }

        try {
            val s = ProotSession(
                prootBin = proot,
                loader = loader,
                rootfs = manager.distroDir(d.id),
                shell = d.defaultShell,
                prootTmpDir = manager.prootTmpDir(),
                extraLibDir = nativeDir,
                listener = object : ProotSession.Listener {
                    override fun onOutput(text: String) {
                        appendOutput(text)
                    }

                    override fun onExit(code: Int) {
                        appendOutput("\n[ProotTerm] shell 已退出 code=$code\n")
                        setStatus("${d.name} shell 已退出")
                    }
                }
            )
            s.start()
            session = s
            sessionVersionId = d.id
            setStatus("${d.name} 运行中，输入命令并发送")
        } catch (e: Exception) {
            AppLogger.e("Proot", "start failed ${d.id}", e)
            appendOutput("[ProotTerm] 启动失败: ${e.message}\n")
            setStatus("启动失败：${e.message}")
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendCommand() {
        val cmd = editCommand.text.toString()
        val s = session
        if (s == null || !s.isRunning) {
            setStatus("请先在「发行版」启动 shell")
            return
        }
        try {
            s.sendCommand(cmd)
            appendOutput("$ $cmd\n")
            editCommand.text.clear()
            hideKeyboard()
        } catch (e: Exception) {
            setStatus("发送失败：${e.message}")
        }
    }

    private fun sendRawKey(data: String) {
        val s = session
        if (s == null || !s.isRunning) {
            setStatus("请先启动 shell")
            return
        }
        try {
            s.sendRaw(data)
        } catch (e: Exception) {
            setStatus("发送失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------ 返回键

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
            screen == Screen.TERMINAL && session?.isRunning == true -> {
                showExitDialog()
                return
            }
        }
        super.onBackPressed()
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_exit_title)
            .setMessage(R.string.dlg_exit_message)
            .setPositiveButton(R.string.btn_keep_alive) { _, _ ->
                showScreen(Screen.DISTROS)
            }
            .setNegativeButton(R.string.btn_close_terminal) { _, _ ->
                session?.destroy()
                session = null
                sessionVersionId = null
                outputRaw.setLength(0)
                renderOutput()
                showScreen(Screen.DISTROS)
                setStatus("终端已关闭")
            }
            .setNeutralButton(R.string.btn_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 设置页 / 终端输出

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
        tvOutput.text = shown.ifEmpty { getString(R.string.terminal_empty) }
        scrollOutput.post { scrollOutput.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editCommand.windowToken, 0)
    }

    override fun onDestroy() {
        installJob?.let {
            it.cancel()
            downloader.cancel()
        }
        session?.destroy()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 200_000
        private const val MB = 1024.0 * 1024.0
        private const val GITHUB_URL = "https://github.com/LiStudioorg/linuxandroid/"
    }
}
