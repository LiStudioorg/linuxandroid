package com.li63050a.linuxandroid

import android.content.Context
import org.json.JSONObject

/**
 * 解析 assets/rootfs_manifest.json（version 2：发行版 → 多版本嵌套结构）。
 * 使用 Android 内置 org.json。
 */
object ManifestLoader {

    const val ASSET_NAME = "rootfs_manifest.json"

    /** 当前支持的清单结构版本（顶层 version 字段） */
    const val SUPPORTED_VERSION = 2

    /** 解压器支持的格式，直接复用 RootfsExtractor 常量，避免两处漂移 */
    private val SUPPORTED_FORMATS = setOf(
        RootfsExtractor.FORMAT_TAR_GZ,
        RootfsExtractor.FORMAT_TAR_XZ
    )

    fun load(context: Context): List<DistroFamily> {
        val text = context.assets.open(ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return parse(text)
    }

    fun parse(json: String): List<DistroFamily> {
        val root = JSONObject(json)

        // 校验顶层结构版本：不认识的结构必须早失败，而不是悄悄解析出半份清单
        val version = root.optInt("version", -1)
        if (version != SUPPORTED_VERSION) {
            throw IllegalArgumentException(
                "不支持的清单 version=$version（期望 $SUPPORTED_VERSION），请更新应用或清单"
            )
        }

        val array = root.getJSONArray("distros")
        val result = ArrayList<DistroFamily>(array.length())
        for (i in 0 until array.length()) {
            val fo = array.getJSONObject(i)
            val familyId = fo.getString("id")
            val versionsArr = fo.getJSONArray("versions")
            val versions = ArrayList<DistroInfo>(versionsArr.length())
            for (j in 0 until versionsArr.length()) {
                val vo = versionsArr.getJSONObject(j)
                val mirrorsArr = vo.optJSONArray("mirrors")
                val mirrors = ArrayList<String>()
                if (mirrorsArr != null) {
                    for (k in 0 until mirrorsArr.length()) {
                        mirrors.add(mirrorsArr.getString(k))
                    }
                }
                val format = vo.getString("format")
                if (format !in SUPPORTED_FORMATS) {
                    throw IllegalArgumentException(
                        "版本 ${vo.getString("id")} 的 format=\"$format\" 不受支持" +
                            "（支持 ${SUPPORTED_FORMATS.joinToString("/")}）"
                    )
                }
                versions.add(
                    DistroInfo(
                        id = vo.getString("id"),
                        familyId = familyId,
                        name = vo.getString("name"),
                        size = vo.optLong("size", 0L),
                        url = vo.getString("url"),
                        mirrors = mirrors,
                        sha256 = vo.optString("sha256", ""),
                        format = format,
                        defaultShell = vo.getString("defaultShell")
                    )
                )
            }
            result.add(
                DistroFamily(
                    id = familyId,
                    name = fo.getString("name"),
                    icon = fo.optString("icon", familyId),
                    description = fo.optString("description", ""),
                    versions = versions
                )
            )
        }
        if (result.isEmpty()) {
            throw IllegalArgumentException("清单未包含任何发行版")
        }
        return result
    }
}
