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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 主界面：DrawerLayout 三页导航（终端 / 发行版管理 / 设置）。
 * - 终端页：顶部搜索（过滤输出）+ 深色终端 + 底部命令输入；
 * - 发行版页：RecyclerView 卡片，下载进度与启动操作；
 * - 设置页：应用信息 + GitHub 链接 + 清空终端。
 */
class MainActivity : AppCompatActivity() {

    private enum class Screen { TERMINAL, DISTROS, SETTINGS }

    private val scope = MainScope()
    private lateinit var manager: RootfsManager
    private val downloader = RootfsDownloader()
    private var distros: List<DistroInfo> = emptyList()

    private lateinit var drawer: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var viewTerminal: View
    private lateinit var viewDistros: View
    private lateinit var viewSettings: View

    private lateinit var editSearch: EditText
    private lateinit var tvOutput: TextView
    private lateinit var scrollOutput: ScrollView
    private lateinit var editCommand: EditText
    private lateinit var btnSend: MaterialButton

    private lateinit var recyclerDistros: RecyclerView
    private lateinit var distroAdapter: DistroAdapter

    private var installJob: Job? = null
    private var session: ProotSession? = null

    /** 终端原始输出缓冲（搜索时基于它过滤） */
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
        viewSettings = findViewById(R.id.view_settings)
        editSearch = findViewById(R.id.edit_search)
        tvOutput = findViewById(R.id.tv_output)
        scrollOutput = findViewById(R.id.scroll_output)
        editCommand = findViewById(R.id.edit_command)
        btnSend = findViewById(R.id.btn_send)
        recyclerDistros = findViewById(R.id.recycler_distros)

        // 发行版清单 + 卡片列表
        distros = try {
            ManifestLoader.load(this)
        } catch (e: Exception) {
            Toast.makeText(this, "清单解析失败: ${e.message}", Toast.LENGTH_LONG).show()
            emptyList()
        }
        distroAdapter = DistroAdapter(distros) { onDistroClick(it) }
        recyclerDistros.layoutManager = LinearLayoutManager(this)
        recyclerDistros.adapter = distroAdapter
        distros.forEach { d ->
            if (manager.isInstalled(d.id)) {
                distroAdapter.setState(d.id, DistroAdapter.State.Installed)
            }
        }

        // 侧边栏
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_terminal -> showScreen(Screen.TERMINAL)
                R.id.nav_distros -> showScreen(Screen.DISTROS)
                R.id.nav_settings -> showScreen(Screen.SETTINGS)
            }
            drawer.closeDrawer(GravityCompat.START)
            true
        }
        showScreen(Screen.TERMINAL)
        toolbar.subtitle = getString(R.string.status_idle)

        // 终端输入
        btnSend.setOnClickListener { sendCommand() }
        editCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }
        // 顶部搜索框：过滤终端输出行
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderOutput()
            }
        })

        setupSettings()
    }

    // ------------------------------------------------------------------ 导航

    private fun showScreen(screen: Screen) {
        viewTerminal.visibility = if (screen == Screen.TERMINAL) View.VISIBLE else View.GONE
        viewDistros.visibility = if (screen == Screen.DISTROS) View.VISIBLE else View.GONE
        viewSettings.visibility = if (screen == Screen.SETTINGS) View.VISIBLE else View.GONE
        val menuId = when (screen) {
            Screen.TERMINAL -> R.id.nav_terminal
            Screen.DISTROS -> R.id.nav_distros
            Screen.SETTINGS -> R.id.nav_settings
        }
        navView.setCheckedItem(menuId)
        toolbar.title = getString(
            when (screen) {
                Screen.TERMINAL -> R.string.nav_terminal
                Screen.DISTROS -> R.string.nav_distros
                Screen.SETTINGS -> R.string.nav_settings
            }
        )
        if (screen == Screen.TERMINAL) {
            scrollOutput.post { scrollOutput.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ------------------------------------------------------------------ 发行版卡片

    private fun onDistroClick(d: DistroInfo) {
        if (installJob?.isActive == true) {
            setStatus("已有下载/安装任务进行中…")
            return
        }
        if (manager.isInstalled(d.id)) {
            startShell(d)
            showScreen(Screen.TERMINAL)
        } else {
            install(d)
        }
    }

    /** 下载 → SHA256 校验 → 解压到 tmp → 原子切换为最终目录 */
    private fun install(d: DistroInfo) {
        val part = manager.partFile(d.id, d.format)
        manager.cleanupTemp(d.id)
        distroAdapter.setState(d.id, DistroAdapter.State.Downloading(0))
        setStatus("准备下载 ${d.name} …")

        installJob = scope.launch {
            try {
                // 1) 下载（断点续传 + 多镜像）
                downloader.download(d, part) { done, total ->
                    runOnUiThread {
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            distroAdapter.setState(d.id, DistroAdapter.State.Downloading(pct))
                            setStatus(
                                "下载 ${d.name} … $pct%（%.1f / %.1f MB）"
                                    .format(done / MB, total / MB)
                            )
                        } else {
                            setStatus("下载 ${d.name} … %.1f MB".format(done / MB))
                        }
                    }
                }

                // 2) SHA256（清单为空跳过）
                if (d.sha256.isNotBlank()) {
                    setStatus("校验 SHA256 …")
                    val actual = Sha256Utils.hashFile(part)
                    if (!actual.equals(d.sha256.trim(), ignoreCase = true)) {
                        part.delete()
                        throw IllegalStateException("SHA256 校验失败\n期望 ${d.sha256}\n实际 $actual")
                    }
                }

                // 3) 解压到临时目录
                val temp = manager.tempDir(d.id)
                manager.cleanupTemp(d.id)
                temp.mkdirs()
                setStatus("解压 ${d.name} …")
                RootfsExtractor.extract(part, d.format, temp) { count, _ ->
                    runOnUiThread {
                        setStatus("解压 ${d.name} … 已处理 $count 项")
                    }
                }

                // 4) 原子切换 + .installed，清理 .part
                manager.finalizeInstall(d.id, temp)
                part.delete()
                distroAdapter.setState(d.id, DistroAdapter.State.Installed)
                setStatus("${d.name} 安装完成，点击「启动」")
            } catch (e: CancellationException) {
                distroAdapter.setState(d.id, DistroAdapter.State.Idle)
                setStatus("已取消（断点已保留，可重新下载）")
                throw e
            } catch (e: Exception) {
                manager.cleanupTemp(d.id)
                distroAdapter.setState(d.id, DistroAdapter.State.Idle)
                setStatus("失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------ PRoot shell

    private fun startShell(d: DistroInfo) {
        if (session?.isRunning == true) {
            setStatus("${d.name} 的 shell 正在运行")
            return
        }
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
            setStatus("${d.name} 运行中，输入命令并发送")
        } catch (e: Exception) {
            appendOutput("[ProotTerm] 启动失败: ${e.message}\n")
            setStatus("启动失败：${e.message}")
        }
    }

    private fun sendCommand() {
        val cmd = editCommand.text.toString()
        val s = session
        if (s == null || !s.isRunning) {
            setStatus("请先在「发行版管理」启动 shell")
            return
        }
        try {
            s.sendCommand(cmd)
            // 非 PTY 无回显，本地回显一行
            appendOutput("$ $cmd\n")
            editCommand.text.clear()
            hideKeyboard()
        } catch (e: Exception) {
            setStatus("发送失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------ 设置页

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

        // GitHub 仓库链接
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

    // ------------------------------------------------------------------ 终端输出

    private fun setStatus(msg: String) {
        toolbar.subtitle = msg
    }

    private fun appendOutput(text: String) {
        if (text.isEmpty()) return
        outputRaw.append(text)
        // 超长截断防 OOM
        if (outputRaw.length > MAX_OUTPUT_CHARS) {
            outputRaw.delete(0, outputRaw.length - MAX_OUTPUT_CHARS / 2)
        }
        renderOutput()
    }

    /** 渲染：有搜索词时按行过滤，否则显示全部；并滚动到底部 */
    private fun renderOutput() {
        val raw = outputRaw.toString()
        val shown = if (searchQuery.isEmpty()) {
            raw
        } else {
            raw.lineSequence()
                .filter { it.contains(searchQuery, ignoreCase = true) }
                .joinToString("\n")
        }
        tvOutput.text = shown
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
