package com.li63050a.linuxandroid

import android.os.StatFs
import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 目录规划与安装状态管理（按版本 id 区分目录）。
 *
 * filesDir/
 *   rootfs/<versionId>/       最终 rootfs（含 .installed），如 rootfs/ubuntu-24.04/
 *   tmp_<versionId>/           解压临时目录
 *   <versionId>.<format>.part  断点续传
 *   proot_tmp/                 PROOT_TMP_DIR
 *   logs/                      AppLogger
 */
class RootfsManager(context: Context) {

    private val filesDir: File = context.filesDir
    private val rootfsRoot = File(filesDir, "rootfs")

    /** 最终安装目录（按版本 id） */
    fun distroDir(id: String): File = File(rootfsRoot, id)

    fun tempDir(id: String): File = File(filesDir, "tmp_$id")

    fun partFile(id: String, format: String): File =
        File(filesDir, "$id.$format.part")

    fun prootTmpDir(): File = File(filesDir, "proot_tmp")

    /** 该具体版本是否已安装（检查 rootfs/<id>/.installed） */
    fun isInstalled(id: String): Boolean =
        File(distroDir(id), MARKER).isFile

    /** 可用空间字节数；检测失败返回 -1（不阻断下载） */
    fun availableBytes(): Long = try {
        val stat = StatFs(filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) {
        AppLogger.w("RootfsManager", "StatFs failed: ${e.message}")
        -1L
    }

    /**
     * 下载前空间检查：压缩包 + 解压后约 4 倍 + 64MB 余量。
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
            "空间不足：需要约 ${need / 1024 / 1024}MB，可用 ${avail / 1024 / 1024}MB（$id）"
        } else {
            null
        }
    }

    /**
     * 解压成功后的原子切换：删旧目录 → rename → 写 .installed。
     */
    @Throws(IOException::class)
    fun finalizeInstall(id: String, temp: File): File {
        if (!temp.isDirectory || temp.list()?.isEmpty() != false) {
            throw IOException("解压结果为空: ${temp.absolutePath}")
        }
        val dest = distroDir(id)
        if (dest.exists() && !dest.deleteRecursively()) {
            throw IOException("无法删除旧目录: ${dest.absolutePath}")
        }
        dest.parentFile?.mkdirs()
        if (!temp.renameTo(dest)) {
            throw IOException("原子重命名失败: ${temp.absolutePath} -> ${dest.absolutePath}")
        }
        File(dest, MARKER).createNewFile()
        AppLogger.i("RootfsManager", "finalize $id -> ${dest.absolutePath}")
        return dest
    }

    fun cleanupTemp(id: String) {
        val temp = tempDir(id)
        if (temp.exists()) temp.deleteRecursively()
    }

    fun uninstall(id: String) {
        AppLogger.i("RootfsManager", "uninstall $id")
        distroDir(id).deleteRecursively()
        cleanupTemp(id)
        File(filesDir, "$id.tar.gz.part").delete()
        File(filesDir, "$id.tar.xz.part").delete()
    }

    companion object {
        const val MARKER = ".installed"
    }
}
