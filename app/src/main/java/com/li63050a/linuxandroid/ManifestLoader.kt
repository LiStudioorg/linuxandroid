package com.li63050a.linuxandroid

import android.content.Context
import org.json.JSONObject

/**
 * 解析 assets/rootfs_manifest.json（version 2：发行版 → 多版本嵌套结构）。
 * 使用 Android 内置 org.json。
 */
object ManifestLoader {

    const val ASSET_NAME = "rootfs_manifest.json"

    fun load(context: Context): List<DistroFamily> {
        val text = context.assets.open(ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return parse(text)
    }

    fun parse(json: String): List<DistroFamily> {
        val root = JSONObject(json)
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
                versions.add(
                    DistroInfo(
                        id = vo.getString("id"),
                        familyId = familyId,
                        name = vo.getString("name"),
                        size = vo.optLong("size", 0L),
                        url = vo.getString("url"),
                        mirrors = mirrors,
                        sha256 = vo.optString("sha256", ""),
                        format = vo.getString("format"),
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
        return result
    }
}
