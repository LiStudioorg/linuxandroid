package com.li63050a.linuxandroid

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException

/**
 * 目录规划与安装状态管理（按版本 id 区分目录，按存储位置区分根目录）。
 *
 * 两种存储位置（[StorageLocation]）使用**完全相同**的布局：
 *
 * ```
 * <sandbox>/
 *   rootfs/<versionId>/         最终 rootfs（含 .installed）
 *   rootfs/<versionId>.old/     升级安装的回滚备份，成功后删除
 *   tmp_<versionId>/            解压临时目录
 *   <versionId>.<format>.part   断点续传
 *   proot_tmp/                  PROOT_TMP_DIR（关键路径，必须应用私有可执行）
 *   native/                     NativeDeps 释放的运行库
 *   logs/                       AppLogger
 * ```
 *
 * 内部位置 = `context.filesDir`（`/data/data/<pkg>`）；
 * 外部位置 = `context.getExternalFilesDir(null)`（`/Android/data/<pkg>`）。
 *
 * ⚠️ proot 会执行 `rootfs/` 内部的可执行文件。外部位置在 Android 11+ 上
 * 由 FUSE 提供，通常不允许执行文件；本模块因此不主动禁止外部位置，而是在
 * 启动失败时由 [ProotService] 给出明确提示。内置位置无此限制。
 */
class RootfsManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = AppPrefs(appContext)

    /** 当前生效的存储位置（用户选择，可能已被外部因素变得不可用） */
    var location: StorageLocation = prefs.storageLocation
        private set

    /** 当前生效的沙箱根目录 */
    var sandboxDir: File = resolveSandbox(location)
        private set

    /**
     * 当前存储位置是否真的可用（外部位置未挂载时为 false）。
     * 不可用时 [sandboxDir] 已降级到内置位置，UI 必须明确告知用户，
     * 否则用户会以为数据存在外部，实际存在内部。
     */
    var locationFallback: Boolean = false
        private set

    private fun resolveSandbox(wanted: StorageLocation): File {
        val dir = StorageLocation.sandboxDir(appContext, wanted)
        return if (dir != null && wanted.isAvailable(dir)) {
            locationFallback = false
            dir
        } else {
            locationFallback = wanted != StorageLocation.INTERNAL
            appContext.filesDir
        }
    }

    /**
     * 切换存储位置。**仅影响后续安装**：已安装的 rootfs 留在原位置不动，
     * 由 UI 负责提示用户需要重新安装才能迁移（跨文件系统 rename 不可靠，
     * 逐文件复制几十万小文件既慢又可能中断，不做自动迁移）。
     *
     * @return true 表示位置已生效；false 表示目标位置不可用，仍留在原位置
     */
    fun setLocation(wanted: StorageLocation): Boolean {
        val dir = StorageLocation.sandboxDir(appContext, wanted)
        if (dir == null || !wanted.isAvailable(dir)) {
            AppLogger.w("RootfsManager", "location unavailable: $wanted")
            return false
        }
        location = wanted
        sandboxDir = dir
        locationFallback = false
        prefs.storageLocation = wanted
        AppLogger.i("RootfsManager", "location -> $wanted (${dir.absolutePath})")
        return true
    }

    /** 运行期依赖（NativeDeps / AppLogger / proot_tmp）是否保存在内部位置 */
    val isExternal: Boolean get() = sandboxDir.absolutePath != appContext.filesDir.absolutePath

    /** 另一位置的沙箱根（外部不可用时为 null），用于「在另一位置是否已安装」的探测 */
    fun otherLocation(): StorageLocation =
        if (location == StorageLocation.INTERNAL) StorageLocation.EXTERNAL
        else StorageLocation.INTERNAL

    fun rootfsRoot(): File = location.rootfsRoot(sandboxDir)

    /** 最终安装目录（按版本 id） */
    fun distroDir(id: String): File = File(rootfsRoot(), id)

    fun tempDir(id: String): File = File(sandboxDir, "tmp_$id")

    fun partFile(id: String, format: String): File = File(sandboxDir, "$id.$format.part")

    /**
     * PROOT_TMP_DIR。**必须留在内部位置**：proot 会在此目录里写出并执行
     * 临时文件，外部位置在 Android 11+ 上通常不可执行。
     */
    fun prootTmpDir(): File = File(appContext.filesDir, "proot_tmp")

    /** 日志目录固定放内部位置，卸载应用前始终可读，且与存储位置选择解耦 */
    fun logsDir(): File = File(appContext.filesDir, "logs")

    /** 运行库目录固定放内部位置：LD_LIBRARY_PATH 指向的位置需要可读可执行 */
    fun nativeDir(): File = File(appContext.filesDir, "native")

    /** 该具体版本是否已安装（检查 rootfs/<id>/.installed） */
    fun isInstalled(id: String): Boolean = File(distroDir(id), MARKER).isFile

    /**
     * 该版本是否安装**在另一个存储位置**。
     * 用于切换位置后给出「需重新安装」的准确提示，而不是笼统说没装。
     */
    fun isInstalledElsewhere(id: String): Boolean {
        val other = otherLocation()
        val dir = StorageLocation.sandboxDir(appContext, other) ?: return false
        if (!other.isAvailable(dir)) return false
        return File(other.rootfsRoot(dir), id).let { File(it, MARKER).isFile }
    }

    /** 可用空间字节数；检测失败返回 -1（不阻断下载） */
    fun availableBytes(): Long = try {
        val stat = StatFs(sandboxDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) {
        AppLogger.w("RootfsManager", "StatFs failed: ${e.message}")
        -1L
    }

    /**
     * 下载前空间检查：压缩包 + 解压后约 4 倍 + 64MB 余量。
     * 检查的是**目标存储位置**所在分区，而不是固定 filesDir。
     * @return null 表示空间足够，否则为错误消息
     */
    fun checkSpaceFor(id: String, compressedSize: Long): String? {
        val need = if (compressedSize > 0) {
            compressedSize + compressedSize * 4 + 64L * 1024 * 1024
        } else {
            64L * 1024 * 1024
        }
        val avail = availableBytes()
        if (avail < 0) return null // 未知则放行
        return if (avail < need) {
            "空间不足（${location.label}）：需要约 ${need / 1024 / 1024}MB，" +
                "可用 ${avail / 1024 / 1024}MB（$id）"
        } else {
            null
        }
    }

    /** 旧版本备份目录（升级安装时的回滚点） */
    fun backupDir(id: String): File = File(rootfsRoot(), "$id.old")

    /**
     * 解压成功后的原子切换，且失败可回滚：
     *   1. temp → dest（若 dest 已存在，先把 dest 挪到 backup）
     *   2. 成功后删除 backup，写 .installed
     *   3. 任一步失败则把 backup 挪回 dest，旧安装不丢
     *
     * temp 与 dest 必须同处一个存储位置，否则 renameTo 会跨文件系统失败；
     * 调用方（[MainActivity.install] / [ProotService]）保证两者都来自本对象。
     */
    @Throws(IOException::class)
    fun finalizeInstall(id: String, temp: File, shell: String? = null): File {
        if (!temp.isDirectory || temp.list()?.isEmpty() != false) {
            throw IOException("解压结果为空: ${temp.absolutePath}")
        }
        val dest = distroDir(id)
        val backup = backupDir(id)
        dest.parentFile?.mkdirs()

        // 清掉上次失败可能残留的备份，避免 rename 目标已存在
        if (backup.exists() && !backup.deleteRecursively()) {
            throw IOException("无法清理旧备份: ${backup.absolutePath}")
        }

        val hadPrevious = dest.exists()
        if (hadPrevious && !dest.renameTo(backup)) {
            throw IOException("无法暂存旧目录: ${dest.absolutePath} -> ${backup.absolutePath}")
        }

        if (!temp.renameTo(dest)) {
            // 回滚：把旧目录挪回原位，尽量恢复到调用前的状态
            if (hadPrevious) {
                val restored = backup.renameTo(dest)
                if (!restored) {
                    AppLogger.e(
                        "RootfsManager",
                        "回滚失败，旧安装残留在 ${backup.absolutePath}，需手动恢复为 ${dest.absolutePath}"
                    )
                }
            }
            throw IOException("原子重命名失败: ${temp.absolutePath} -> ${dest.absolutePath}")
        }

        // 新目录已就位，清理备份与标记
        if (hadPrevious) backup.deleteRecursively()
        File(dest, MARKER).createNewFile()
        // 把清单声明的 shell 记下来：清单解析失败时（走网络/资源异常、或用户
        // 离线场景）后续就无从得知该用哪个 shell，只能瞎猜 /bin/sh，会把
        // 自己装的系统误报成「自定义 rootfs」。落盘一份就永远准确。
        if (!shell.isNullOrBlank()) {
            try {
                File(dest, SHELL_MARKER).writeText(shell)
            } catch (e: IOException) {
                // 记不下来不影响运行，扫描时会退回探测
                AppLogger.w("RootfsManager", "写 $SHELL_MARKER 失败: ${e.message}")
            }
        }
        AppLogger.i("RootfsManager", "finalize $id -> ${dest.absolutePath}")
        return dest
    }

    /** 读取安装时记录的 shell；没有记录或读失败返回 null */
    fun recordedShell(id: String): String? = try {
        val f = File(distroDir(id), SHELL_MARKER)
        if (f.isFile) f.readText().trim().ifBlank { null } else null
    } catch (e: IOException) {
        null
    }

    fun cleanupTemp(id: String) {
        val temp = tempDir(id)
        if (temp.exists()) temp.deleteRecursively()
    }

    fun uninstall(id: String) {
        AppLogger.i("RootfsManager", "uninstall $id @ $location")
        distroDir(id).deleteRecursively()
        backupDir(id).deleteRecursively()
        cleanupTemp(id)
        deletePartFiles(id)
    }

    /**
     * 删除该版本的 `.part` 断点文件。
     * 不写死 `tar.gz`/`tar.xz`：按文件名前缀扫描，将来清单新增格式也能覆盖。
     * 两个存储位置都扫一遍，避免切换位置后旧断点永远残留。
     */
    fun deletePartFiles(id: String) {
        val prefix = "$id."
        for (dir in allSandboxDirs()) {
            dir.listFiles()?.forEach { f ->
                // 同时覆盖 `<id>.<format>.part` 与分块临时文件
                // `<id>.<format>.part.<index>`：分块残留会让下次下载
                // 误判「这块已下完」而合并出损坏文件。
                if (f.isFile && f.name.startsWith(prefix) && f.name.contains(".part")) {
                    if (f.delete()) {
                        AppLogger.i("RootfsManager", "removed part ${f.parentFile?.name}/${f.name}")
                    }
                }
            }
        }
    }

    /**
     * 两个存储位置的沙箱根目录（去重）。
     * 供「两个位置都要清理」的场景使用，例如删除断点与分块残留。
     */
    fun allSandboxDirs(): Set<File> = buildSet {
        add(sandboxDir)
        StorageLocation.sandboxDir(appContext, otherLocation())?.let { add(it) }
    }

    companion object {
        const val MARKER = ".installed"

        /** 记录清单声明的 guest shell，避免清单不可用时只能猜测 */
        const val SHELL_MARKER = ".shell"
    }
}

/** 存储位置的展示名，UI 与日志共用，避免两处硬编码 */
val StorageLocation.label: String
    get() = when (this) {
        StorageLocation.INTERNAL -> "内部存储 /data/data"
        StorageLocation.EXTERNAL -> "外部存储 /Android/data"
    }
