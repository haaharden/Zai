package com.self.zai.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Resolves installed launchable apps by Chinese/English display name, alias, or package name.
 * Uses a short TTL cache to avoid repeated PackageManager IPC queries within a multi-step run.
 */
interface AppDirectory {
    fun resolveAppLabel(packageName: String): String
    fun findLaunchableApp(query: String): Pair<String, String>?
    fun queryLaunchableApps(query: String): List<Pair<String, String>>
}

class AndroidAppDirectory(private val context: Context) : AppDirectory {

    @Volatile
    private var cachedApps: List<Pair<String, String>> = emptyList()

    @Volatile
    private var cacheTimestampMs: Long = 0L

    override fun resolveAppLabel(packageName: String): String {
        if (packageName.isBlank()) return "未知应用"
        return runCatching {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(packageName)
    }

    override fun findLaunchableApp(query: String): Pair<String, String>? {
        val aliasPkg = COMMON_APP_ALIASES[query.lowercase()]
        if (aliasPkg != null) {
            val installed = runCatching {
                val ai = context.packageManager.getApplicationInfo(aliasPkg, 0)
                aliasPkg to context.packageManager.getApplicationLabel(ai).toString()
            }.getOrNull()
            if (installed != null) return installed
        }
        return queryLaunchableApps(query).firstOrNull()
    }

    override fun queryLaunchableApps(query: String): List<Pair<String, String>> {
        val all = loadAllLaunchableApps()
        if (query.isBlank()) return all.sortedBy { it.second.lowercase() }
        val q = query.lowercase()
        return all.filter { (pkg, label) ->
            label.lowercase().contains(q) || pkg.lowercase().contains(q)
        }.sortedBy { (pkg, label) ->
            when {
                label.equals(query, ignoreCase = true) || pkg.equals(query, ignoreCase = true) -> 0
                label.lowercase().startsWith(q) -> 1
                else -> 2
            }
        }
    }

    private fun loadAllLaunchableApps(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val cached = cachedApps
        if (cached.isNotEmpty() && now - cacheTimestampMs < CACHE_TTL_MS) {
            return cached
        }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val fresh = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { info ->
                    info.activityInfo.packageName to info.loadLabel(pm).toString().trim()
                }
                .distinctBy { it.first }
                .filterNot { it.first == context.packageName }
        }.getOrDefault(emptyList())
        cachedApps = fresh
        cacheTimestampMs = now
        return fresh
    }

    companion object {
        private const val CACHE_TTL_MS = 15_000L

        private val COMMON_APP_ALIASES = mapOf(
            "微信" to "com.tencent.mm",
            "wechat" to "com.tencent.mm",
            "支付宝" to "com.eg.android.AlipayGphone",
            "alipay" to "com.eg.android.AlipayGphone",
            "设置" to "com.android.settings",
            "settings" to "com.android.settings",
            "抖音" to "com.ss.android.ugc.aweme",
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "美团" to "com.sankuai.meituan",
            "高德地图" to "com.autonavi.minimap",
            "高德" to "com.autonavi.minimap",
            "小红书" to "com.xingin.xhs",
            "哔哩哔哩" to "tv.danmaku.bili",
            "b站" to "tv.danmaku.bili",
            "bilibili" to "tv.danmaku.bili",
            "qq" to "com.tencent.mobileqq",
            "网易云音乐" to "com.netease.cloudmusic",
        )
    }
}
