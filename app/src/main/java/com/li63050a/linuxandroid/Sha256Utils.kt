package com.li63050a.linuxandroid

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** 流式 SHA256 计算，避免大文件一次性读入内存。 */
object Sha256Utils {

    private const val BUFFER_SIZE = 128 * 1024

    fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** [expected] 为空白时视为不校验，直接返回 true。 */
    fun matches(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return hashFile(file).equals(expected.trim(), ignoreCase = true)
    }
}
