package com.li63050a.linuxandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启动入口。
 *
 * 收到 `BOOT_COMPLETED` 后，若用户在设置页开启了自启动且选定了系统，
 * 就通过 [ProotService] 把该系统的 shell 在前台服务里跑起来。
 *
 * 设计要点：
 *  - **只读配置、不做决策**：真正的存在性校验（清单里有没有这个版本、
 *    rootfs 装没装）全部交给 [ProotService]，因为广播有 10 秒执行预算，
 *    而且 rootfs 可能装在外部存储上，开机瞬间未必已挂载。
 *  - 不做任何阻塞操作，立即返回，避免 `BroadcastReceiver` ANR。
 *  - `LOCKED_BOOT_COMPLETED` 不处理：那时用户还没解锁，
 *    应用私有目录处于加密锁定状态，读配置会拿到错误结果。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val app = context.applicationContext
        val prefs = AppPrefs(app)

        if (!prefs.autoStart) {
            AppLogger.i(TAG, "boot: auto start disabled, skip")
            return
        }
        val id = prefs.autoStartId
        if (id.isBlank()) {
            AppLogger.i(TAG, "boot: auto start enabled but no target, skip")
            return
        }

        // 外部存储可能尚未挂载完成；若 rootfs 在外部，稍等再交给服务判断
        val manager = RootfsManager(app)
        if (manager.locationFallback) {
            AppLogger.w(
                TAG,
                "boot: external storage not ready, session will fall back to internal dir"
            )
        }

        AppLogger.i(TAG, "boot: auto start $id (action=$action)")
        val launched = ProotService.launch(app, ProotService.autoStartIntent(app, id))
        if (!launched) {
            AppLogger.w(TAG, "boot: service launch refused; user may need to open app once")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
