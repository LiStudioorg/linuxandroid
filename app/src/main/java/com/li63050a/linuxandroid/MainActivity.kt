package com.li63050a.linuxandroid

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 主界面编排：
 * - 三个发行版按钮（与清单按索引绑定）：下载 / 下载中 n% / 启动；
 * - 下载进度与状态栏；
 * - 文本终端输出区 + 命令输入行，驱动持久 ProotSession。
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var manager: RootfsManager
    private val downloader = RootfsDownloader()
    private var distros: List<DistroInfo> = emptyList()
    private lateinit var buttons: List<Button>
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvOutput: TextView
    private lateinit var scrollOutput: ScrollView
    private lateinit var editCommand: EditText
    private lateinit var btnSend: Button

    private var installJob: Job? = null
    private var session: ProotSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        manager = RootfsManager(this)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvOutput = findViewById(R.id.tvOutput)
        scrollOutput = findViewById(R.id.scrollOutput)
        editCommand = findViewById(R.id.editCommand)
        btnSend = findViewById(R.id.btnSend)

        distros = try {
            ManifestLoader.load(this)
        } catch (e: Exception) {
            Toast.makeText(this, "清单解析失败: ${e.message}", Toast.LENGTH_LONG).show()
            emptyList()
        }

        buttons = listOf(
            findViewById(R.id.btnDistro0),
            findViewById(R.id.btnDistro1),
            findViewById(R.id.btnDistro2)
        )
        buttons.forEachIndexed { index, button ->
            val distro = distros.getOrNull(index)
            if (distro == null) {
                button.isEnabled = false
                button.text = "—"
                return@forEachIndexed
            }
            button.setOnClickListener { onDistroClicked(distro) }
        }

        btnSend.setOnClickListener { sendCommand() }
        editCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }

        refreshButtons()
        if (distros.isNotEmpty()) {
            tvStatus.text = getString(
                R.string.status_idle
            ) + "（" + distros.joinToString("、") { it.name } + "：" +
                distros.joinToString("、") { it.description } + "）"
        }
    }

    // ---------------------------------------------------------------- 按钮状态

    private fun defaultLabel(d: DistroInfo): String =
        if (manager.isInstalled(d.id)) "启动 ${d.name}" else "下载 ${d.name}"

    private fun refreshButtons() {
        buttons.forEachIndexed { i, button ->
            val distro = distros.getOrNull(i) ?: return@forEachIndexed
            button.text = defaultLabel(distro)
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        buttons.forEach { it.isEnabled = enabled }
    }

    // ---------------------------------------------------------------- 安装流程

    private fun onDistroClicked(d: DistroInfo) {
        if (installJob?.isActive == true) {
            setStatus("已有安装任务进行中，请稍候")
            return
        }
        if (manager.isInstalled(d.id)) {
            startShell(d)
        } else {
            install(d)
        }
    }

    /**
     * 下载 → SHA256 校验 → 解压到 tmp → 原子切换为最终目录。
     * 失败时清理 tmp；.part 保留以便断点续传。
     */
    private fun install(d: DistroInfo) {
        val part = manager.partFile(d.id, d.format)
        manager.cleanupTemp(d.id)

        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        setStatus("准备下载 ${d.name} …")

        installJob = scope.launch {
            try {
                // 1) 下载（断点续传 + 多镜像）
                setStatus("下载 ${d.name} …（主源失败自动切换镜像）")
                downloader.download(d, part) { done, total ->
                    runOnUiThread { onDownloadProgress(d, done, total) }
                }

                // 2) SHA256 校验（清单为空则跳过）
                if (d.sha256.isNotBlank()) {
                    setStatus("校验 SHA256 …")
                    val actual = Sha256Utils.hashFile(part)
                    if (!actual.equals(d.sha256.trim(), ignoreCase = true)) {
                        part.delete()
                        throw IllegalStateException(
                            "SHA256 校验失败\n期望 ${d.sha256}\n实际 $actual"
                        )
                    }
                }

                // 3) 解压到临时目录（符号链接 / 权限位 / 防穿越）
                val temp = manager.tempDir(d.id)
                manager.cleanupTemp(d.id)
                temp.mkdirs()
                setStatus("解压 ${d.name} …")
                RootfsExtractor.extract(part, d.format, temp) { count, _ ->
                    runOnUiThread {
                        tvStatus.text = "解压 ${d.name} … 已处理 $count 项"
                    }
                }

                // 4) 原子切换 + .installed 标记，成功后清理 .part
                manager.finalizeInstall(d.id, temp)
                part.delete()
                setStatus("${d.name} 安装完成，点击按钮启动 shell")
            } catch (e: CancellationException) {
                setStatus("安装已取消（断点已保留，可重新下载续传）")
                throw e
            } catch (e: Exception) {
                manager.cleanupTemp(d.id) // 半成品临时目录清理；.part 保留
                setStatus("失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false
                setButtonsEnabled(true)
                refreshButtons()
            }
        }
    }

    private fun onDownloadProgress(d: DistroInfo, done: Long, total: Long) {
        val index = distros.indexOf(d)
        if (total > 0) {
            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
            progressBar.isIndeterminate = false
            progressBar.progress = pct
            tvStatus.text = "下载 ${d.name} … $pct%（%.1f / %.1f MB）"
                .format(done / MB, total / MB)
            buttons.getOrNull(index)?.text = "下载中 $pct% ${d.name}"
        } else {
            progressBar.isIndeterminate = true
            tvStatus.text = "下载 ${d.name} … %.1f MB".format(done / MB)
            buttons.getOrNull(index)?.text = "下载中 ${d.name}"
        }
    }

    // ---------------------------------------------------------------- PRoot shell

    private fun startShell(d: DistroInfo) {
        if (session?.isRunning == true) {
            setStatus("${d.name} 的 shell 正在运行")
            return
        }
        val libDir = File(applicationInfo.nativeLibraryDir)
        val proot = File(libDir, "libproot.so")
        val loader = File(libDir, "libproot-loader.so")

        tvOutput.text = ""
        appendOutput("[ProotTerm] 启动 ${d.name}\n")
        appendOutput("[ProotTerm] rootfs=${manager.distroDir(d.id).absolutePath}\n")
        appendOutput("[ProotTerm] 非交互 shell 无提示符/无回显属正常，可输入 sh -i 进入交互模式\n\n")

        // 释放 libtalloc/libandroid-shmem 等运行库；失败仅警告（个别环境可用 RUNPATH 兜底）
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
            setStatus("请先点击发行版按钮启动 shell")
            return
        }
        try {
            s.sendCommand(cmd)
            // 非 PTY 环境 shell 不回显，UI 本地回显一行
            appendOutput("$ $cmd\n")
            editCommand.text.clear()
            hideKeyboard()
        } catch (e: Exception) {
            setStatus("发送失败：${e.message}")
        }
    }

    // ---------------------------------------------------------------- UI 工具

    private fun setStatus(msg: String) {
        tvStatus.text = msg
    }

    private fun appendOutput(text: String) {
        if (text.isEmpty()) return
        val sb = StringBuilder(tvOutput.text)
        // 超长截断，防止 TextView 无限增长导致 OOM
        if (sb.length > MAX_OUTPUT_CHARS) {
            sb.delete(0, sb.length - MAX_OUTPUT_CHARS / 2)
        }
        sb.append(text)
        tvOutput.text = sb.toString()
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
    }
}
