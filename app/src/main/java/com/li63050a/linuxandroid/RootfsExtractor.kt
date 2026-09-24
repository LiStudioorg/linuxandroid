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
 * tar / tar.gz / tar.xz 解压器（API 24 安全）。
 * 关键点：符号链接、权限位、硬链接、路径穿越防护。
 * 失败时把具体出错条目写入 AppLogger。
 */
object RootfsExtractor {

    const val FORMAT_TAR_GZ = "tar.gz"
    const val FORMAT_TAR_XZ = "tar.xz"

    private const val COPY_BUFFER = 128 * 1024

    @Throws(IOException::class)
    fun extract(
        archive: File,
        format: String,
        destDir: File,
        onEntry: ((processed: Long, name: String) -> Unit)? = null
    ) {
        AppLogger.i("Extractor", "start ${archive.name} -> ${destDir.absolutePath} format=$format")
        if (!archive.isFile) throw IOException("压缩包不存在: ${archive.absolutePath}")
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            throw IOException("无法创建目标目录: ${destDir.absolutePath}")
        }

        val destCanonical = destDir.canonicalFile
        val destPrefix = destCanonical.path + File.separator

        val dirModes = mutableListOf<Pair<File, Int>>()
        val pendingLinks = mutableListOf<Pair<TarArchiveEntry, File>>()
        var processed = 0L

        try {
            FileInputStream(archive).use { rawInput ->
                openDecompressor(rawInput, format).use { decompressed ->
                    TarArchiveInputStream(decompressed).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            val outFile = File(destCanonical, entry.name.removePrefix("/"))
                            val outCanonical = outFile.canonicalFile
                            if (outCanonical.path != destCanonical.path &&
                                !outCanonical.path.startsWith(destPrefix)
                            ) {
                                AppLogger.w("Extractor", "skip traversal: ${entry.name}")
                                continue
                            }

                            try {
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
                                        try {
                                            Os.symlink(entry.linkName, outFile.absolutePath)
                                        } catch (ex: Exception) {
                                            AppLogger.e(
                                                "Extractor",
                                                "symlink failed: ${entry.name} -> ${entry.linkName}",
                                                ex
                                            )
                                            throw IOException(
                                                "符号链接失败: ${entry.name} -> ${entry.linkName}",
                                                ex
                                            )
                                        }
                                    }

                                    entry.isLink -> {
                                        ensureParent(outFile)
                                        deleteNoFollow(outFile)
                                        pendingLinks.add(entry to outFile)
                                    }

                                    else -> {
                                        ensureParent(outFile)
                                        deleteNoFollow(outFile)
                                        try {
                                            FileOutputStream(outFile).use { out ->
                                                val buf = ByteArray(COPY_BUFFER)
                                                while (true) {
                                                    val n = tar.read(buf)
                                                    if (n < 0) break
                                                    if (n > 0) out.write(buf, 0, n)
                                                }
                                            }
                                        } catch (ex: IOException) {
                                            AppLogger.e(
                                                "Extractor",
                                                "write failed: ${entry.name}",
                                                ex
                                            )
                                            throw ex
                                        }
                                        val mode = entry.mode and MODE_MASK
                                        if (mode != 0) {
                                            try {
                                                Os.chmod(outFile.absolutePath, mode)
                                            } catch (ex: Exception) {
                                                AppLogger.w(
                                                    "Extractor",
                                                    "chmod failed ${entry.name}: ${ex.message}"
                                                )
                                            }
                                        }
                                    }
                                }
                            } catch (ex: IOException) {
                                throw ex
                            } catch (ex: Exception) {
                                AppLogger.e("Extractor", "entry failed: ${entry.name}", ex)
                                throw IOException("解压条目失败: ${entry.name}", ex)
                            }

                            processed++
                            onEntry?.invoke(processed, entry.name)
                        }
                    }
                }
            }

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
                    } catch (ex: Exception) {
                        AppLogger.w("Extractor", "hardlink retry ${entry.name}: ${ex.message}")
                    }
                }
            }

            for (i in dirModes.indices.reversed()) {
                val (dir, mode) = dirModes[i]
                try {
                    Os.chmod(dir.absolutePath, mode)
                } catch (_: Exception) {
                }
            }
        } catch (ex: Exception) {
            AppLogger.e("Extractor", "FAILED after $processed entries", ex)
            throw ex
        }
        AppLogger.i("Extractor", "done $processed entries -> ${destDir.absolutePath}")
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

    private const val MODE_MASK = 0x1FF
}
