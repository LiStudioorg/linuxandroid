package com.li63050a.linuxandroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * 日志页：展示 AppLogger 日志；支持刷新 / 清空 / 复制 / 一键分享（FileProvider）。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = getString(R.string.nav_logs)

        tvLogs = findViewById(R.id.tv_logs)
        scrollLogs = findViewById(R.id.scroll_logs)

        findViewById<MaterialButton>(R.id.btn_log_share).setOnClickListener { shareLogs() }
        findViewById<MaterialButton>(R.id.btn_log_refresh).setOnClickListener { render() }
        findViewById<MaterialButton>(R.id.btn_log_clear).setOnClickListener {
            AppLogger.clear()
            render()
            Toast.makeText(this, R.string.toast_log_cleared, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btn_log_copy).setOnClickListener { copyLogs() }

        render()
    }

    private fun render() {
        val text = try {
            AppLogger.snapshot().ifEmpty {
                val f = File(File(filesDir, "logs"), "app.log")
                if (f.isFile) f.readText(Charsets.UTF_8) else ""
            }
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
        tvLogs.text = text.ifEmpty { getString(R.string.logs_empty) }
        scrollLogs.post { scrollLogs.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyLogs() {
        try {
            val text = tvLogs.text.toString()
            if (text.isEmpty()) return
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("linuxandroid-log", text))
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLogger.e("LogActivity", "copy failed", e)
            Toast.makeText(this, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 一键分享：必须走 FileProvider content:// URI，禁止 file://。
     * 以 text/plain 附带日志文件（可另附纯文本正文兜底）。
     */
    private fun shareLogs() {
        try {
            val file = AppLogger.ensureLogFile()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            AppLogger.i("LogActivity", "share uri=$uri size=${file.length()}")

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_log_subject))
                // 正文附一份摘要，部分应用忽略附件时仍可看
                val preview = try {
                    file.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    AppLogger.snapshot()
                }
                putExtra(Intent.EXTRA_TEXT, preview.take(30_000))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.btn_share_log)))
        } catch (e: Exception) {
            AppLogger.e("LogActivity", "share failed", e)
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
