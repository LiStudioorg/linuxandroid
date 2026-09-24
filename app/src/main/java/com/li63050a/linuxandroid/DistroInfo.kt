package com.li63050a.linuxandroid

/**
 * 单个发行版的清单条目，对应 assets/rootfs_manifest.json 中的一个对象。
 *
 * @property id         唯一标识，同时用作 rootfs 目录名
 * @property name       UI 显示名
 * @property description 简要描述
 * @property size       预计字节数（服务器未返回总长时作进度兜底，仅展示用）
 * @property url        主下载地址
 * @property mirrors    备用镜像地址，主源失败后依次尝试
 * @property sha256     小写十六进制校验值；空串表示跳过校验
 * @property format     压缩格式："tar.gz" 或 "tar.xz"
 * @property defaultShell guest 内的 shell 绝对路径
 */
data class DistroInfo(
    val id: String,
    val name: String,
    val description: String,
    val size: Long,
    val url: String,
    val mirrors: List<String>,
    val sha256: String,
    val format: String,
    val defaultShell: String
) {
    /** 候选下载地址：主源在前，备用镜像依次在后 */
    val allUrls: List<String>
        get() = buildList {
            add(url)
            addAll(mirrors)
        }
}
