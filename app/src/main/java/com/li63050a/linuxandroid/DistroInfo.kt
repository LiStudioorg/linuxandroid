package com.li63050a.linuxandroid

/**
 * 发行版家族（主卡片）：Alpine / Debian / Ubuntu。
 */
data class DistroFamily(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val versions: List<DistroInfo>
)

/**
 * 某个发行版下的一个具体版本，对应 assets/rootfs_manifest.json 中 versions[] 的一项。
 *
 * @property id         唯一标识，同时用作 rootfs 目录名，如 ubuntu-24.04
 * @property familyId   所属发行版 id（alpine/debian/ubuntu）
 * @property name       UI 显示的版本名，如 24.04 LTS
 * @property size       预计字节数（进度兜底展示，不参与完整性判断）
 * @property url        主下载地址
 * @property mirrors    备用镜像
 * @property sha256     小写十六进制；空串跳过校验
 * @property format     tar.gz / tar.xz
 * @property defaultShell guest 内 shell 绝对路径
 */
data class DistroInfo(
    val id: String,
    val familyId: String,
    val name: String,
    val size: Long,
    val url: String,
    val mirrors: List<String>,
    val sha256: String,
    val format: String,
    val defaultShell: String
) {
    val allUrls: List<String>
        get() = buildList {
            if (url.isNotBlank()) add(url)
            addAll(mirrors)
        }
}
