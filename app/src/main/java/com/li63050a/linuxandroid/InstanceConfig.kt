package com.li63050a.linuxandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 单个实例（= 一个已安装的 rootfs）的配置。
 *
 * **实例 id 就是 rootfs 目录名**（`DistroInfo.id`），所以配置天然按实例隔离。
 * 这里刻意**不**继承任何全局单例：多实例并存的全部意义就在于每个实例有自己的
 * 一份资源限制与镜像偏好（§8.17）。
 *
 * @property id            实例 id = rootfs 目录名
 * @property displayName   用户可改的显示名；空串表示跟随清单名
 * @property limits        资源限制
 * @property mirrorOverride 自定义镜像源（仅 http/https）；空表示只用清单里的
 * @property preferAutoMirror 是否启用测速优选
 * @property downloadThreads 多线程分块下载的线程数（1~[MultiPartDownloader.MAX_THREADS]）
 * @property aptMirror     换源使用的镜像标识（见 [AptMirror]）；null 表示未换源
 */
data class InstanceConfig(
    val id: String,
    val displayName: String = "",
    val limits: ResourceLimits = ResourceLimits.UNLIMITED,
    val mirrorOverride: String = "",
    val preferAutoMirror: Boolean = true,
    val downloadThreads: Int = DEFAULT_THREADS,
    val aptMirror: String? = null
) {

    /**
     * 实际生效的下载线程数。
     *
     * 这里做一次夹取而不是信任存储值：配置文件可能被手工改过，
     * 或被旧版本写成 0/负数，直接拿去分块会除零或产生空块。
     */
    val safeThreads: Int
        get() = downloadThreads.coerceIn(1, MultiPartDownloader.MAX_THREADS)

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, displayName)
        put(KEY_LIMITS, limits.toJson())
        put(KEY_MIRROR, mirrorOverride)
        put(KEY_AUTO_MIRROR, preferAutoMirror)
        put(KEY_THREADS, downloadThreads)
        // 用 NULL 哨兵而不是移除字段：optString 对缺失与 null 都会给 ""，
        // 这里需要区分「没换过源」与「换过但记录为空」
        put(KEY_APT_MIRROR, aptMirror ?: JSONObject.NULL)
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_NAME = "display_name"
        const val KEY_LIMITS = "limits"
        const val KEY_MIRROR = "mirror_override"
        const val KEY_AUTO_MIRROR = "prefer_auto_mirror"
        const val KEY_THREADS = "download_threads"
        const val KEY_APT_MIRROR = "apt_mirror"

        /** 默认 4 线程：多数镜像在 4 并发时已接近带宽上限，再多容易被限流 */
        const val DEFAULT_THREADS = 4

        fun fromJson(o: JSONObject): InstanceConfig = InstanceConfig(
            id = o.getString(KEY_ID),
            displayName = o.optString(KEY_NAME, ""),
            limits = ResourceLimits.fromJson(o.optJSONObject(KEY_LIMITS)),
            mirrorOverride = o.optString(KEY_MIRROR, ""),
            preferAutoMirror = o.optBoolean(KEY_AUTO_MIRROR, true),
            downloadThreads = o.optInt(KEY_THREADS, DEFAULT_THREADS),
            aptMirror = if (o.isNull(KEY_APT_MIRROR)) null
            else o.optString(KEY_APT_MIRROR).ifBlank { null }
        )
    }
}

/**
 * 实例配置的持久化仓库（`filesDir/instances.json`）。
 *
 * 放在**内部** `filesDir` 而不是当前存储位置：配置很小、必须最可靠，
 * 且切换存储位置后仍要能读到（§8.14 的同一理由）。
 *
 * 写入走「临时文件 + rename」的原子替换，避免进程被杀时留下半个 JSON
 * 导致**全部**实例配置丢失。
 *
 * ⚠️ **线程安全**：本类会被**两个线程**访问——UI 线程（`MainActivity` 读写表单）与
 * IO 线程（`ProotService` 读资源限制）。因此：
 *  - 缓存用 `@Volatile` + `synchronized` 保护，不能裸用可变字段；
 *  - `persist()` 在锁内快照后写盘，避免「一边迭代一边被 put 修改」抛
 *    `ConcurrentModificationException`。
 *
 * ⚠️ **多实例注意**：`MainActivity` 与 `ProotService` 各自 new 了一个
 * `InstanceStore`，因而**各有一份缓存**。同一进程内 UI 保存的限制不会自动
 * 出现在服务已加载的缓存里。为避免「保存了却不生效」，构造时应复用同一个
 * 实例（见 [InstanceStore.of]）。
 */
class InstanceStore private constructor(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** 所有缓存访问的锁；`cache` 本身也在锁内读写 */
    private val lock = Any()

    @Volatile
    private var cache: MutableMap<String, InstanceConfig>? = null

    private fun map(): MutableMap<String, InstanceConfig> {
        cache?.let { return it }
        synchronized(lock) {
            // 双重检查：两个线程可能同时走到这里，先到的负责加载
            cache?.let { return it }
            val loaded = LinkedHashMap<String, InstanceConfig>()
            try {
                if (file.isFile) {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val cfg = InstanceConfig.fromJson(o)
                        loaded[cfg.id] = cfg
                    }
                }
            } catch (e: Exception) {
                // 配置坏了不能拖垮应用：退回空配置，并留下日志供排查
                AppLogger.e("InstanceStore", "读取 $FILE_NAME 失败，回退为空配置", e)
            }
            cache = loaded
            return loaded
        }
    }

    /** 取实例配置；没有记录时返回默认配置（不是 null，调用方无需判空） */
    fun get(id: String): InstanceConfig =
        map()[id] ?: InstanceConfig(id = id)

    /** 写入并落盘 */
    fun put(config: InstanceConfig) {
        synchronized(lock) {
            map()[config.id] = config
            persistLocked()
        }
    }

    /** 便捷方法：改资源限制 */
    fun updateLimits(id: String, limits: ResourceLimits) {
        put(get(id).copy(limits = limits))
    }

    /** 便捷方法：改显示名 */
    fun updateName(id: String, name: String) {
        put(get(id).copy(displayName = name))
    }

    /** 便捷方法：改镜像偏好与下载线程数 */
    fun updateMirror(
        id: String,
        override: String,
        autoMirror: Boolean,
        threads: Int = get(id).downloadThreads
    ) {
        put(
            get(id).copy(
                mirrorOverride = override,
                preferAutoMirror = autoMirror,
                downloadThreads = threads.coerceIn(1, MultiPartDownloader.MAX_THREADS)
            )
        )
    }

    /**
     * 校验自定义镜像源并规范化。
     *
     * **只允许 http/https**（§8.20）：用户可能粘贴 `file:///...` 或其它 scheme，
     * 让 OkHttp 去加载本地文件不仅无意义，还会绕过「只读写应用私有目录」的边界。
     *
     * @return 规范化后的地址；非法时返回 null（调用方负责提示）
     */
    fun normalizeMirror(input: String): String? {
        val v = input.trim()
        if (v.isEmpty()) return ""  // 空 = 不自定义，合法
        val lower = v.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        // 去掉尾部斜杠，避免与清单地址拼接时出现 `//`
        return v.trimEnd('/')
    }

    /** 记录已换的 APT 源；传 null 表示恢复回原始源 */
    fun updateAptMirror(id: String, mirror: String?) {
        put(get(id).copy(aptMirror = mirror))
    }

    /**
     * 删除实例配置。卸载系统或清理已消失的实例时调用，
     * 避免 `instances.json` 无限增长。
     */
    fun remove(id: String) {
        synchronized(lock) {
            if (map().remove(id) != null) persistLocked()
        }
    }

    /**
     * 清理「已不存在的实例」的配置。
     * @param aliveIds 当前实际存在的实例 id 集合
     * @return 被清理掉的数量
     */
    fun pruneMissing(aliveIds: Set<String>): Int {
        synchronized(lock) {
            val m = map()
            val dead = m.keys.filter { it !in aliveIds }
            if (dead.isEmpty()) return 0
            dead.forEach { m.remove(it) }
            persistLocked()
            AppLogger.i("InstanceStore", "pruned ${dead.size} stale config(s): $dead")
            return dead.size
        }
    }

    /** ⚠️ 调用方必须已持有 [lock] */
    private fun persistLocked() {
        // 在锁内先做快照再序列化：这样即便序列化较慢，也不会与别的 put 冲突
        val snapshot = map().values.sortedBy { it.id }.toList()
        val arr = JSONArray()
        // 按 id 排序，保证文件内容稳定（便于 diff 与人工查看）
        snapshot.forEach { arr.put(it.toJson()) }
        try {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString(2))
            // 原子替换：先删目标再 rename（File.renameTo 在目标存在时的行为不可靠）
            if (file.exists() && !file.delete()) {
                AppLogger.w("InstanceStore", "无法删除旧 $FILE_NAME，配置可能未更新")
            }
            if (!tmp.renameTo(file)) {
                AppLogger.e("InstanceStore", "写入 $FILE_NAME 失败（rename 未成功）", null)
            }
        } catch (e: IOException) {
            AppLogger.e("InstanceStore", "写入 $FILE_NAME 失败", e)
        }
    }

    companion object {
        const val FILE_NAME = "instances.json"

        /**
         * 进程内共享实例。
         *
         * `MainActivity` 与 `ProotService` 必须用**同一个** `InstanceStore`，
         * 否则各自持有一份缓存：UI 改了资源限制，服务读到的可能仍是旧值，
         * 表现为「保存了但没生效」。
         *
         * 用 Application Context 持有，不会泄漏 Activity。
         */
        @Volatile
        private var shared: InstanceStore? = null

        fun of(context: Context): InstanceStore {
            shared?.let { return it }
            synchronized(this) {
                shared?.let { return it }
                val created = InstanceStore(context.applicationContext)
                shared = created
                return created
            }
        }
    }
}
