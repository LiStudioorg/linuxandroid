package com.li63050a.linuxandroid

import android.app.Application
import android.os.Build
import android.os.Process
import java.io.File

/**
 * 全局 Application：初始化日志 + 安装未捕获异常处理器。
 * 闪退前先把完整堆栈写入 filesDir/logs/app.log，进程退出后日志仍在。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(filesDir)
        installUncaughtHandler()
        AppLogger.i(TAG, "App started, sdk=${Build.VERSION.SDK_INT}")
    }

    private fun installUncaughtHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLogger.e(
                    TAG,
                    "Uncaught exception on thread ${thread.name}",
                    throwable
                )
            } catch (_: Exception) {
                // 日志失败也不能挡在杀进程前
            } finally {
                try {
                    // 给文件写入留一点时间
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                }
                try {
                    if (default != null) {
                        default.uncaughtException(thread, throwable)
                    } else {
                        Process.killProcess(Process.myPid())
                        Runtime.getRuntime().exit(10)
                    }
                } catch (_: Exception) {
                    Process.killProcess(Process.myPid())
                    Runtime.getRuntime().exit(10)
                }
            }
        }
    }

    companion object {
        private const val TAG = "App"
    }
}
