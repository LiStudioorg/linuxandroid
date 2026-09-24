package com.li63050a.linuxandroid

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 目录规划与安装状态管理。
 *
 * filesDir/
 *   rootfs/<id>/            最终 rootfs（含 .installed 标记）
 *   tmp_<id>/               解压中的临时目录
 *   <id>.<format>.part      断点续传临时文件
 *   proot_tmp/              PROOT_TMP_DIR（宿主侧）
 */
class RootfsManager(context: Context) {

    private val filesDir: File = context.filesDir
    private val rootfsRoot = File(filesDir, "rootfs")

    /** 最终安装目录 */
    fun distroDir(id: String): File = File(rootfsRoot, id)

    /** 解压中的临时目录，成功后整体 rename 到最终目录 */
    fun tempDir(id: String): File = File(filesDir, "tmp_$id")

    /** 下载断点文件（.part） */
    fun partFile(id: String, format: String): File =
        File(filesDir, "$id.$format.part")

    /** PRoot 运行期临时目录（PROOT_TMP_DIR） */
    fun prootTmpDir(): File = File(filesDir, "proot_tmp")

    /** 最终目录存在且 .installed 标记存在才算安装完成 */
    fun isInstalled(id: String): Boolean =
        File(distroDir(id), MARKER).isFile

    /**
     * 解压成功后的原子切换：
     * 删除旧最终目录 → 将临时目录 rename 为最终目录 → 写入 .installed 标记。
     * rename 失败视为致命错误（不产生半成品状态）。
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
        return dest
    }

    /** 失败时清理半成品临时目录；.part 下载缓存保留以便断点续传 */
    fun cleanupTemp(id: String) {
        val temp = tempDir(id)
        if (temp.exists()) temp.deleteRecursively()
    }

    /** 卸载：删除 rootfs、临时目录与断点缓存 */
    fun uninstall(id: String) {
        distroDir(id).deleteRecursively()
        cleanupTemp(id)
        File(filesDir, "$id.tar.gz.part").delete()
        File(filesDir, "$id.tar.xz.part").delete()
    }

    companion object {
        const val MARKER = ".installed"
    }
}
