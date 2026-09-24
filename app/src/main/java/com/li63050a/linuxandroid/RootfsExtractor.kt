package com.li63050a.linuxandroid

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * tar / tar.gz / tar.xz 解压器。
 *
 * 关键点：
 * 1. 符号链接：Os.symlink 原样写入 linkName（绝对目标由 proot 在 guest 内解析）；
 * 2. 权限位：Os.chmod(path, entry.mode and 0x1FF)，目录延迟到全部解压后统一还原，
 *    避免只读目录导致后续条目写入失败；
 * 3. 硬链接：Os.link，主循环收集后多轮重试（处理目标后出现/链式引用）；
 * 4. 路径穿越：条目名去前导 "/" 拼接后做 canonicalPath 前缀校验。
 */
object RootfsExtractor {

    const val FORMAT_TAR_GZ = "tar.gz"
    const val FORMAT_TAR_XZ = "tar.xz"

    private const val COPY_BUFFER = 128 * 1024

    /**
     * 将 [archive] 解压到 [destDir]（调用方负责先创建/清理 destDir）。
     * [onEntry] 每处理完一个条目回调一次（条目序号, 条目名），运行在调用线程。
     */
    @Throws(IOException::class)
    fun extract(
        archive: File,
        format: String,
        destDir: File,
        onEntry: ((processed: Long, name: String) -> Unit)? = null
    ) {
        if (!archive.isFile) throw IOException("压缩包不存在: ${archive.absolutePath}")
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            throw IOException("无法创建目标目录: ${destDir.absolutePath}")
        }

        val destCanonical = destDir.canonicalFile
        val destPrefix = destCanonical.path + File.separator

        // 目录 -> 最终权限；解压结束后逆序统一 chmod
        val dirModes = mutableListOf<Pair<File, Int>>()
        // 待创建的硬链接；主循环结束后反复重试
        val pendingLinks = mutableListOf<Pair<TarArchiveEntry, File>>()
        var processed = 0L

        FileInputStream(archive).use { rawInput ->
            openDecompressor(rawInput, format).use { decompressed ->
                TarArchiveInputStream(decompressed).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break

                        // 防路径穿越：去前导 "/"，canonical 必须仍在 destDir 内
                        val outFile = File(destCanonical, entry.name.removePrefix("/"))
                        val outCanonical = outFile.canonicalFile
                        if (outCanonical.path != destCanonical.path &&
                            !outCanonical.path.startsWith(destPrefix)
                        ) {
                            continue // 越界条目，跳过
                        }

                        when {
                            entry.isDirectory -> {
                                if (!outFile.isDirectory &&
                                    !outFile.mkdirs() &&
                                    !outFile.isDirectory
                                ) {
                                    continue
                                }
                                val mode = entry.mode and MODE_MASK
                                if (mode != 0) dirModes.add(outFile to mode)
                            }

                            entry.isSymbolicLink -> {
                                ensureParent(outFile)
                                deleteNoFollow(outFile)
                                // linkName 原样写入，不改写目标
                                Os.symlink(entry.linkName, outFile.absolutePath)
                            }

                            entry.isLink -> {
                                ensureParent(outFile)
                                deleteNoFollow(outFile)
                                pendingLinks.add(entry to outFile)
                            }

                            else -> { // 普通文件
                                ensureParent(outFile)
                                deleteNoFollow(outFile)
                                FileOutputStream(outFile).use { out ->
                                    val buf = ByteArray(COPY_BUFFER)
                                    while (true) {
                                        val n = tar.read(buf)
                                        if (n < 0) break
                                        if (n > 0) out.write(buf, 0, n)
                                    }
                                }
                                val mode = entry.mode and MODE_MASK
                                if (mode != 0) Os.chmod(outFile.absolutePath, mode)
                            }
                        }

                        processed++
                        onEntry?.invoke(processed, entry.name)
                    }
                }
            }
        }

        // 硬链接：反复重试直至无进展（兼容目标条目在后面出现与链式引用）
        var progress = true
        while (progress && pendingLinks.isNotEmpty()) {
            progress = false
            val iterator = pendingLinks.iterator()
            while (iterator.hasNext()) {
                val (entry, outFile) = iterator.next()
                val target = File(destCanonical, entry.linkName.removePrefix("/"))
                if (!target.exists()) continue
                try {
                    deleteNoFollow(outFile)
                    Os.link(target.absolutePath, outFile.absolutePath)
                    iterator.remove()
                    progress = true
                } catch (_: Exception) {
                    // 留待下一轮
                }
            }
        }

        // 目录权限统一还原（逆序：子目录先于父目录，此时已无写入）
        for (i in dirModes.indices.reversed()) {
            val (dir, mode) = dirModes[i]
            try {
                Os.chmod(dir.absolutePath, mode)
            } catch (_: Exception) {
                // 个别目录失败不影响整体可用性
            }
        }
    }

    private fun openDecompressor(input: InputStream, format: String): InputStream =
        when (format) {
            FORMAT_TAR_GZ, "gz", "tgz" -> GzipCompressorInputStream(input)
            FORMAT_TAR_XZ, "xz" -> XZCompressorInputStream(input)
            else -> throw IOException("不支持的压缩格式: $format")
        }

    private fun ensureParent(file: File) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
    }

    /**
     * 删除文件/符号链接本身，不跟随符号链接。
     * 使用 Os.lstat（API 21+），兼容 minSdk 24；不用 java.nio.file（API 26+）。
     */
    private fun deleteNoFollow(file: File) {
        val path = file.absolutePath
        try {
            Os.lstat(path)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return
            throw IOException("lstat 失败: $path", e)
        }
        try {
            Os.remove(path)
        } catch (e: ErrnoException) {
            throw IOException("删除失败: $path", e)
        }
    }

    /** 只保留 rwx 三位权限（与需求约定 entry.mode and 0x1FF 一致） */
    private const val MODE_MASK = 0x1FF
}
