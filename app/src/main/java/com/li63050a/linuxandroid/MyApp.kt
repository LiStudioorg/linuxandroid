package com.li63050a.linuxandroid

import java.io.File

/**
 * 「我的系统」列表项：一个**已安装**的 rootfs。
 *
 * 与 [DistroInfo]（清单里的「可下载版本」）刻意分开：
 *  - [DistroInfo] 描述「能下载什么」，来源是 assets 清单；
 *  - [MyApp] 描述「本机装了什么」，来源是**扫描存储目录**。
 *
 * 分开之后，用户自定义放入 rootfs 的发行版也能出现在列表里，
 * 而且切换存储位置后列表会自然反映该位置的真实内容。
 *
 * @property id          目录名，如 `alpine-3.20`
 * @property name        UI 展示名；能匹配到清单时用清单名，否则用目录名
 * @property shell       guest shell 绝对路径；清单缺失时按探测结果兜底
 * @property dir         rootfs 根目录
 * @property sizeBytes   磁盘占用（递归统计，可能为 0 表示未统计）
 * @property fromManifest 是否能在清单里找到对应版本（false 表示清单里没有这个版本）
 * @property manifestLoaded 清单本身是否成功加载。为 false 时 fromManifest=false
 *            **不代表**这是「自定义 rootfs」，而是清单没读到，UI 文案必须区分，
 *            否则会把自己装的系统误报成用户手工放入的目录
 */
data class MyApp(
    val id: String,
    val name: String,
    val shell: String,
    val dir: File,
    val sizeBytes: Long,
    val fromManifest: Boolean,
    val manifestLoaded: Boolean = false
) {
    /** 对应的清单版本（若存在），用于启动时复用下载/校验等逻辑 */
    var distro: DistroInfo? = null
}

/**
 * 扫描某个 rootfs 根目录，找出所有已安装的系统。
 *
 * 判定标准：`<root>/<id>/.installed` 存在。
 * 该标记由 [RootfsManager.finalizeInstall] 在原子切换成功后写入，
 * 因此「有标记 = 目录内容完整」，不会把解压到一半的 `tmp_*` 目录误认成已安装。
 *
 * 不递归扫全部子目录再比对，而是直接列 rootfs 根下的一层：安装目录永远是
 * 根的直接子目录，这样几毫秒就能扫完，即使装了十几个系统也无感。
 */
object InstalledScanner {

    fun scan(rootfsRoot: File, shellFallbacks: List<String>): List<MyApp> {
        if (!rootfsRoot.isDirectory) return emptyList()
        val children = rootfsRoot.listFiles() ?: return emptyList()
        return children
            .asSequence()
            .filter { it.isDirectory && !it.name.endsWith(".old") }
            .filter { File(it, RootfsManager.MARKER).isFile }
            .map { dir ->
                val shell = detectShell(dir, shellFallbacks)
                MyApp(
                    id = dir.name,
                    name = dir.name,
                    shell = shell,
                    dir = dir,
                    sizeBytes = 0L,
                    fromManifest = false
                )
            }
            // 目录名排序，保证列表顺序稳定（HashMap 的 listFiles 顺序不保证）
            .sortedBy { it.id }
            .toList()
    }

    /**
     * 推断 guest shell。
     * 优先清单给出的 [shellFallbacks]（按优先级排列），其次探测 rootfs 里的常见路径。
     * 自定义 rootfs 两种都可能落空，此时返回 `/bin/sh`：
     * Alpine/BusyBox 一定有它，其他发行版也几乎都有，是最安全的兜底。
     */
    private fun detectShell(dir: File, shellFallbacks: List<String>): String {
        for (candidate in shellFallbacks) {
            if (File(dir, candidate.removePrefix("/")).isFile) return candidate
        }
        for (candidate in PROBE_SHELLS) {
            if (File(dir, candidate.removePrefix("/")).isFile) return candidate
        }
        return DEFAULT_SHELL
    }

    private val PROBE_SHELLS = listOf("/bin/bash", "/bin/sh", "/usr/bin/bash", "/usr/bin/sh")
    private const val DEFAULT_SHELL = "/bin/sh"
}
