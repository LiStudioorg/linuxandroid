package com.li63050a.linuxandroid

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * proot 运行期共享库依赖：
 *   libtalloc.so.2、libandroid-shmem.so
 *
 * 来源为 Termux proot 的 NEEDED 依赖（proot 自身 RUNPATH 指向
 * /data/data/com.termux/files/usr/lib，仅本机 Termux 可用，其他设备必须捆绑）。
 *
 * libtalloc.so.2 文件名不以 .so 结尾，安装器解压 nativeLibrary 时会跳过，
 * 因此统一放在 assets/native/arm64-v8a/，首启时释放到 filesDir/native，
 * 再由 ProotSession 通过 LD_LIBRARY_PATH 注入。
 */
object NativeDeps {

    private const val ASSET_DIR = "native/arm64-v8a"

    private val LIBS = listOf("libtalloc.so.2", "libandroid-shmem.so")

    /** 确保依赖已释放，返回其所在目录；缺失或释放失败抛 IOException。 */
    @Throws(IOException::class)
    fun ensure(context: Context): File {
        val dir = File(context.filesDir, "native")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("无法创建运行库目录: ${dir.absolutePath}")
        }
        for (name in LIBS) {
            val out = File(dir, name)
            if (out.isFile && out.length() > 0) continue
            try {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    FileOutputStream(out).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                out.delete()
                throw IOException("释放运行库失败: $name", e)
            }
            out.setReadable(true, false)
            out.setExecutable(true, false)
        }
        return dir
    }
}
