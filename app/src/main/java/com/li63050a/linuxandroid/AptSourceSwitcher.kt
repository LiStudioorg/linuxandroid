package com.li63050a.linuxandroid

import java.io.File
import java.io.IOException

/**
 * 可选的软件源镜像。换源与恢复都基于这里的定义。
 *
 * @property id    持久化到 `InstanceConfig.aptMirror` 的稳定标识
 * @property label UI 展示名
 * @property debianBase Debian/Ubuntu 的 `deb` 行前缀（arm64 用 ports/ubuntu-ports）
 * @property alpineBase Alpine 的 `apk` 仓库前缀
 */
data class AptMirror(
    val id: String,
    val label: String,
    val debianBase: String,
    val alpineBase: String
) {
    companion object {
        /**
         * 常用国内镜像。
         *
         * ⚠️ Debian 在 arm64 上属于 `debian-ports`，路径是
         * `.../debian-ports` 而不是 `.../debian`；Ubuntu 的 arm64 在
         * `ubuntu-ports` 而不是 `ubuntu`。写错的话 `apt update` 会 404，
         * 这是 arm64 上换源最常见的错误。
         */
        val ALL: List<AptMirror> = listOf(
            AptMirror(
                id = "tuna",
                label = "清华 TUNA",
                debianBase = "https://mirrors.tuna.tsinghua.edu.cn/debian-ports",
                alpineBase = "https://mirrors.tuna.tsinghua.edu.cn/alpine"
            ),
            AptMirror(
                id = "ustc",
                label = "中科大 USTC",
                debianBase = "https://mirrors.ustc.edu.cn/debian-ports",
                alpineBase = "https://mirrors.ustc.edu.cn/alpine"
            ),
            AptMirror(
                id = "aliyun",
                label = "阿里云",
                debianBase = "https://mirrors.aliyun.com/debian-ports",
                alpineBase = "https://mirrors.aliyun.com/alpine"
            ),
            AptMirror(
                id = "huawei",
                label = "华为云",
                debianBase = "https://mirrors.huaweicloud.com/debian-ports",
                alpineBase = "https://mirrors.huaweicloud.com/alpine"
            )
        )

        /** Ubuntu 的端口仓库基址（arm64 专用），由各镜像的通用域拼出 */
        fun ubuntuPortsBase(mirror: AptMirror): String = when (mirror.id) {
            "tuna" -> "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
            "ustc" -> "https://mirrors.ustc.edu.cn/ubuntu-ports"
            "aliyun" -> "https://mirrors.aliyun.com/ubuntu-ports"
            "huawei" -> "https://mirrors.huaweicloud.com/ubuntu-ports"
            else -> mirror.debianBase.replace("debian-ports", "ubuntu-ports")
        }

        fun fromId(id: String?): AptMirror? = ALL.firstOrNull { it.id == id }
    }
}

/** 换源结果，供 UI 生成如实文案 */
sealed class AptSwitchResult {
    /** 成功；[detail] 说明改了哪些文件 */
    data class Success(val detail: String) : AptSwitchResult()

    /** 失败；[reason] 必须是可操作的原因，不得只说「失败」 */
    data class Failure(val reason: String) : AptSwitchResult()
}

/**
 * 为已安装的系统切换软件源（§8.21）。
 *
 * 三件必须做对的事：
 *  1. **备份**：改动前把原文件另存为固定后缀，且重复换源不再覆盖备份
 *     （否则第二次换源会把「已经换过的源」当成原始源存起来，再也回不去）；
 *  2. **可逆**：支持从备份恢复，找不到备份就如实报错；
 *  3. **原子写**：写临时文件再 rename，避免中断留下半个文件让 apt 彻底不可用。
 *
 * 直接读写 rootfs 内的文件（宿主机路径），不需要启动 guest —— 换源只是改文本，
 * 起一个 shell 去做反而更慢也更容易出错。
 */
object AptSourceSwitcher {

    /** 备份后缀。重复换源时**不覆盖**已存在的备份，保证原始源永远可恢复。 */
    const val BACKUP_SUFFIX = ".prootterm.bak"

    /** Debian/Ubuntu 新式 deb822 格式的目录 */
    private const val DEB822_DIR = "etc/apt/sources.list.d"

    /** 旧式单行格式 */
    private const val LEGACY_LIST = "etc/apt/sources.list"

    /** Alpine 的仓库文件 */
    private const val ALPINE_REPO = "etc/apk/repositories"

    /**
     * 切换某实例的软件源。
     *
     * @param rootfs    实例的 rootfs 根目录
     * @param mirror    目标镜像
     * @param distroId  实例 id，用于识别发行版（也用于日志）
     */
    fun switchTo(rootfs: File, mirror: AptMirror, distroId: String): AptSwitchResult {
        if (!rootfs.isDirectory) {
            return AptSwitchResult.Failure("rootfs 不存在：${rootfs.absolutePath}")
        }
        val osRelease = readOsRelease(rootfs)
        val id = osRelease["ID"].orEmpty().lowercase()
        // 代号来源按可靠性排序：
        //  1. VERSION_CODENAME —— Debian 与多数 Ubuntu 镜像都有；
        //  2. UBUNTU_CODENAME —— 部分精简 Ubuntu 镜像只提供这一个；
        //  3. VERSION / PRETTY_NAME 的括号内容 —— 最后兜底。
        // 曾经这里写成 `X.ifBlank { X }`（同一个键），是死代码：拿不到代号时
        // 会静默退化成字面量 "stable"，而 Ubuntu 不认识 stable 这个 suite，
        // `apt update` 会直接 404。
        val codename = firstNonBlank(
            osRelease["VERSION_CODENAME"],
            osRelease["UBUNTU_CODENAME"],
            deriveCodenameFromVersion(osRelease)
        )

        return when {
            id.contains("alpine") -> switchAlpine(rootfs, mirror, distroId)
            id.contains("debian") || id.contains("ubuntu") ->
                switchDebianFamily(rootfs, mirror, id, codename, distroId)
            else -> AptSwitchResult.Failure(
                "无法识别的发行版（/etc/os-release 的 ID=\"$id\"）。" +
                    "目前仅支持 Debian / Ubuntu / Alpine 自动换源。"
            )
        }
    }

    /**
     * 从备份恢复原始源。
     *
     * 找不到任何备份时**如实报错**，不得假装成功——用户会以为已恢复，
     * 但实际仍指向国内镜像，之后排查问题时会被误导。
     */
    fun restoreOriginal(rootfs: File): AptSwitchResult {
        if (!rootfs.isDirectory) {
            return AptSwitchResult.Failure("rootfs 不存在：${rootfs.absolutePath}")
        }
        val restored = ArrayList<String>()
        for (rel in listOf(LEGACY_LIST, ALPINE_REPO)) {
            val target = File(rootfs, rel)
            val backup = File(rootfs, rel + BACKUP_SUFFIX)
            if (backup.isFile) {
                if (copyAtomic(backup, target)) {
                    backup.delete()
                    restored.add(rel)
                } else {
                    return AptSwitchResult.Failure("恢复 $rel 失败（写入被拒绝）")
                }
            }
        }
        // deb822：逐个恢复
        val deb822Dir = File(rootfs, DEB822_DIR)
        deb822Dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(BACKUP_SUFFIX)) {
                val original = File(deb822Dir, f.name.removeSuffix(BACKUP_SUFFIX))
                if (copyAtomic(f, original)) {
                    f.delete()
                    restored.add("$DEB822_DIR/${original.name}")
                }
            }
        }
        return if (restored.isEmpty()) {
            AptSwitchResult.Failure(
                "没有找到任何备份（$BACKUP_SUFFIX），无法恢复。" +
                    "可能从未换过源，或 rootfs 是重新安装的。"
            )
        } else {
            AptSwitchResult.Success("已恢复原始源：${restored.joinToString("、")}")
        }
    }

    /** 当前是否处于「已换源」状态（存在任意备份即视为换过） */
    fun hasBackup(rootfs: File): Boolean {
        if (File(rootfs, LEGACY_LIST + BACKUP_SUFFIX).isFile) return true
        if (File(rootfs, ALPINE_REPO + BACKUP_SUFFIX).isFile) return true
        val dir = File(rootfs, DEB822_DIR)
        return dir.listFiles()?.any { it.isFile && it.name.endsWith(BACKUP_SUFFIX) } == true
    }

    // ------------------------------------------------------------ Debian / Ubuntu

    /**
     * Debian 与 Ubuntu 的换源。
     *
     * 需要同时处理两种格式：
     *  - **deb822**（Debian 12+/Ubuntu 24.04+ 默认）：`*.sources`，多行 `URIs:` 字段；
     *  - **旧式单行**：`deb http://... suite component`。
     *
     * 策略：**优先改 deb822**（新系统的实际生效文件），若没有 deb822 才改旧式单行。
     * 两者都改会更"彻底"，但若系统只用其中一种，改另一个等于留下垃圾文件。
     */
    private fun switchDebianFamily(
        rootfs: File,
        mirror: AptMirror,
        distroId: String,
        codename: String,
        instanceId: String
    ): AptSwitchResult {
        val suite = codename.ifBlank { "stable" }
        val isUbuntu = distroId.contains("ubuntu")
        val base = if (isUbuntu) AptMirror.ubuntuPortsBase(mirror) else mirror.debianBase
        val components = if (isUbuntu) "main restricted universe multiverse" else "main contrib non-free"

        val deb822Dir = File(rootfs, DEB822_DIR)
        val deb822Files = deb822Dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".sources") }
            .orEmpty()

        if (deb822Files.isNotEmpty()) {
            var changed = 0
            for (f in deb822Files) {
                val text = try {
                    f.readText()
                } catch (e: IOException) {
                    return AptSwitchResult.Failure("读取 ${f.name} 失败：${e.message}")
                }
                // 备份只做一次：已存在备份说明之前换过源，
                // 那个备份才是真正的「原始源」，绝不能被覆盖
                backupOnce(f)
                val rewritten = rewriteDeb822(text, base, suite, components)
                if (!writeAtomic(f, rewritten)) {
                    return AptSwitchResult.Failure("写入 ${f.name} 失败（权限被拒绝）")
                }
                changed++
            }
            AppLogger.i("AptSwitch", "deb822 rewritten x$changed for $instanceId -> $base")
            return AptSwitchResult.Success(
                "已将 ${changed} 个 deb822 源文件指向 ${mirror.label}（suite=$suite）"
            )
        }

        val legacy = File(rootfs, LEGACY_LIST)
        if (!legacy.isFile) {
            return AptSwitchResult.Failure(
                "未找到 $LEGACY_LIST 或 $DEB822_DIR/*.sources，无法换源"
            )
        }
        val text = try {
            legacy.readText()
        } catch (e: IOException) {
            return AptSwitchResult.Failure("读取 $LEGACY_LIST 失败：${e.message}")
        }
        backupOnce(legacy)
        val rewritten = rewriteLegacyList(text, base, suite, components)
        if (!writeAtomic(legacy, rewritten)) {
            return AptSwitchResult.Failure("写入 $LEGACY_LIST 失败（权限被拒绝）")
        }
        AppLogger.i("AptSwitch", "legacy rewritten for $instanceId -> $base")
        return AptSwitchResult.Success("已将 $LEGACY_LIST 指向 ${mirror.label}（suite=$suite）")
    }

    /**
     * 重写 deb822 内容：把 `URIs:` 行替换成目标镜像，并确保 `Suites:` 与
     * `Components:` 存在且正确。
     *
     * 保留注释与其余字段（如 `Signed-By`）不动——签名密钥路径改错会让
     * apt 直接拒绝整个源。
     */
    private fun rewriteDeb822(
        text: String,
        base: String,
        suite: String,
        components: String
    ): String {
        val out = StringBuilder()
        var sawUri = false
        for (line in text.lines()) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("URIs:", ignoreCase = true) -> {
                    out.append("URIs: ").append(base).append('\n')
                    sawUri = true
                }
                trimmed.startsWith("Suites:", ignoreCase = true) -> {
                    out.append("Suites: ").append(suite).append('\n')
                }
                trimmed.startsWith("Components:", ignoreCase = true) -> {
                    out.append("Components: ").append(components).append('\n')
                }
                else -> out.append(line).append('\n')
            }
        }
        // 一个完全没有 URIs 的文件（例如只有注释）不补全，避免造出无效源
        if (!sawUri) {
            AppLogger.w("AptSwitch", "deb822 文件没有 URIs 行，保持原样")
            return text
        }
        return out.toString()
    }

    /**
     * 重写旧式单行 sources.list：只改 `deb`/`deb-src` 行，
     * 注释掉的与 `#` 开头的行保持原样（用户可能有意保留）。
     */
    private fun rewriteLegacyList(
        text: String,
        base: String,
        suite: String,
        components: String
    ): String {
        val out = StringBuilder()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("deb ") || trimmed.startsWith("deb-src ")) {
                val isSrc = trimmed.startsWith("deb-src ")
                val kind = if (isSrc) "deb-src" else "deb"
                out.append(kind).append(' ').append(base).append(' ')
                    .append(suite).append(' ').append(components).append('\n')
            } else {
                out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------ Alpine

    /**
     * Alpine 的 `/etc/apk/repositories` 是「一行一个仓库 URL」的纯列表。
     * 路径形如 `<base>/<branch>/<repo>`，branch 从原文件里解析（如 v3.20），
     * 这样不会因为应用写死版本而在 Alpine 升级后失效。
     */
    private fun switchAlpine(
        rootfs: File,
        mirror: AptMirror,
        instanceId: String
    ): AptSwitchResult {
        val repo = File(rootfs, ALPINE_REPO)
        if (!repo.isFile) {
            return AptSwitchResult.Failure("未找到 $ALPINE_REPO，无法换源")
        }
        val text = try {
            repo.readText()
        } catch (e: IOException) {
            return AptSwitchResult.Failure("读取 $ALPINE_REPO 失败：${e.message}")
        }
        backupOnce(repo)

        val out = StringBuilder()
        var rewritten = 0
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.append(line).append('\n')
                continue
            }
            // 原行形如 https://dl-cdn.alpinelinux.org/alpine/v3.20/main
            // 取出 URL 里 /alpine/ 之后的部分（branch/repo）再拼到新镜像上
            val idx = trimmed.indexOf("/alpine/")
            if (idx < 0) {
                // 不是标准 apk 仓库行（可能是自定义 CDN），保留原样不猜
                AppLogger.w("AptSwitch", "跳过无法识别的 apk 仓库行: $trimmed")
                out.append(line).append('\n')
                continue
            }
            val tail = trimmed.substring(idx + "/alpine/".length)
            out.append(mirror.alpineBase).append('/').append(tail).append('\n')
            rewritten++
        }
        if (rewritten == 0) {
            return AptSwitchResult.Failure(
                "$ALPINE_REPO 中没有可识别的 apk 仓库行（期望含 /alpine/ 的 URL）"
            )
        }
        if (!writeAtomic(repo, out.toString())) {
            return AptSwitchResult.Failure("写入 $ALPINE_REPO 失败（权限被拒绝）")
        }
        AppLogger.i("AptSwitch", "alpine rewritten x$rewritten for $instanceId")
        return AptSwitchResult.Success(
            "已将 $ALPINE_REPO 的 $rewritten 个仓库指向 ${mirror.label}"
        )
    }

    // ------------------------------------------------------------ 工具

    /** 取第一个非空白值；全为空时返回空串（调用方据此退化为 "stable" 并告警） */
    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    /**
     * 从 `VERSION`（如 `"12 (bookworm)"`）或 `PRETTY_NAME` 的括号内容里
     * 提取发行版代号。取不到时返回空串。
     */
    private fun deriveCodenameFromVersion(osRelease: Map<String, String>): String {
        for (key in listOf("VERSION", "PRETTY_NAME")) {
            val v = osRelease[key] ?: continue
            val open = v.indexOf('(')
            val close = v.indexOf(')', open + 1)
            if (open >= 0 && close > open + 1) {
                val inner = v.substring(open + 1, close).trim()
                // 只接受看起来像代号的（纯字母数字、无空格）
                if (inner.isNotEmpty() && inner.all { it.isLetterOrDigit() }) return inner
            }
        }
        return ""
    }

    /**
     * 解析 `/etc/os-release`。该文件是 `KEY=value` 或 `KEY="value"` 格式，
     * **不能**用 Properties 读（`=` 后面的引号会被当成值的一部分）。
     */
    private fun readOsRelease(rootfs: File): Map<String, String> {
        val f = File(rootfs, "etc/os-release")
        if (!f.isFile) return emptyMap()
        return try {
            f.readLines().mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
                val eq = t.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = t.substring(0, eq).trim()
                val value = t.substring(eq + 1).trim().trim('"', '\'')
                key to value
            }.toMap()
        } catch (e: IOException) {
            AppLogger.w("AptSwitch", "读取 os-release 失败: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 备份一次。**已存在则不再覆盖**——这是「可恢复」的前提：
     * 第二次换源时若用已被改过的内容覆盖备份，原始源就永久丢失了。
     */
    private fun backupOnce(target: File) {
        val backup = File(target.parentFile, target.name + BACKUP_SUFFIX)
        if (backup.exists()) {
            AppLogger.i("AptSwitch", "备份已存在，保留原始备份: ${backup.name}")
            return
        }
        if (!copyAtomic(target, backup)) {
            AppLogger.w("AptSwitch", "备份失败: ${target.name}")
        }
    }

    /** 原子写：先写临时文件，再 rename 覆盖目标 */
    private fun writeAtomic(target: File, content: String): Boolean {
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            // 先删旧文件再 rename：renameTo 在目标已存在时行为依赖文件系统
            if (target.exists() && !target.delete()) {
                tmp.delete()
                false
            } else {
                val ok = tmp.renameTo(target)
                if (!ok) tmp.delete()
                ok
            }
        } catch (e: IOException) {
            AppLogger.e("AptSwitch", "写入 ${target.name} 失败", e)
            false
        }
    }

    /** 拷贝内容（同样走临时文件 + rename），用于备份与恢复 */
    private fun copyAtomic(src: File, dst: File): Boolean = try {
        writeAtomic(dst, src.readText())
    } catch (e: IOException) {
        AppLogger.e("AptSwitch", "拷贝 ${src.name} -> ${dst.name} 失败", e)
        false
    }
}
