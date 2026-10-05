package com.li63050a.linuxandroid

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置的唯一持久化入口（SharedPreferences）。
 *
 * 只存「用户意图」，不存「运行状态」——谁在运行由 [SessionManager] 管，
 * 目录是否完好由 [RootfsManager] 判断，避免同一事实两处存储后互相矛盾。
 *
 * 存的东西：
 *  1. rootfs 存储位置（[StorageLocation]）；
 *  2. 开机自启动开关 + 自启目标版本 id；
 *  3. 最近一次启动过的版本 id。
 *
 * 读取一律给默认值、不抛异常：旧版本写入的未知值不该让应用起不来。
 */
class AppPrefs(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 存储位置

    /** 用户选择的存储位置；读到未知值时退回 [StorageLocation.INTERNAL] */
    var storageLocation: StorageLocation
        get() = StorageLocation.fromKey(prefs.getString(KEY_STORAGE, null))
        set(value) = prefs.edit().putString(KEY_STORAGE, value.key).apply()

    // ------------------------------------------------------------ 开机自启动

    /** 开机后是否自动拉起某个系统的 shell */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** 自启目标版本 id（如 `alpine-3.20`）；空串表示未选择 */
    var autoStartId: String
        get() = prefs.getString(KEY_AUTO_START_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_AUTO_START_ID, value).apply()

    // ------------------------------------------------------------ 最近运行

    /** 最近一次启动过的版本 id */
    var lastVersionId: String
        get() = prefs.getString(KEY_LAST_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_ID, value).apply()

    companion object {
        private const val NAME = "prootterm_settings"
        private const val KEY_STORAGE = "storage_location"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_AUTO_START_ID = "auto_start_id"
        private const val KEY_LAST_ID = "last_version_id"
    }
}
