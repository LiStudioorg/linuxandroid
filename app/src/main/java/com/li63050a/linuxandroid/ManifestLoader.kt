package com.li63050a.linuxandroid

import android.content.Context
import org.json.JSONObject

/**
 * 从 assets/rootfs_manifest.json 读取并解析发行版清单。
 * 使用 Android 内置 org.json，避免引入额外序列化依赖。
 */
object ManifestLoader {

    const val ASSET_NAME = "rootfs_manifest.json"

    fun load(context: Context): List<DistroInfo> {
        val text = context.assets.open(ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return parse(text)
    }

    fun parse(json: String): List<DistroInfo> {
        val root = JSONObject(json)
        val array = root.getJSONArray("distros")
        val result = ArrayList<DistroInfo>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val mirrorsArray = o.getJSONArray("mirrors")
            val mirrors = ArrayList<String>(mirrorsArray.length())
            for (j in 0 until mirrorsArray.length()) {
                mirrors.add(mirrorsArray.getString(j))
            }
            result.add(
                DistroInfo(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    description = o.optString("description", ""),
                    size = o.optLong("size", 0L),
                    url = o.getString("url"),
                    mirrors = mirrors,
                    sha256 = o.optString("sha256", ""),
                    format = o.getString("format"),
                    defaultShell = o.getString("defaultShell")
                )
            )
        }
        return result
    }
}
