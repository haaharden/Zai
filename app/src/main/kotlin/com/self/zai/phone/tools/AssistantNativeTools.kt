package com.self.zai.phone.tools

import com.self.zai.pi.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Abstract OS backend for instant, non-UI personal assistant tools so all tool logic stays
 * cleanly separated and directly unit-testable on the JVM.
 */
interface SystemAssistantBackend {
    suspend fun getDeviceStatus(): ToolExecutionResult
    suspend fun setAlarm(hour: Int, minute: Int, message: String?, days: List<Int>): ToolExecutionResult
    suspend fun setTimer(seconds: Int, message: String?): ToolExecutionResult
    suspend fun queryCalendar(daysAhead: Int): ToolExecutionResult
    suspend fun createCalendarEvent(
        title: String,
        startTimeRaw: String,
        durationMinutes: Int,
        location: String?,
        description: String?,
    ): ToolExecutionResult
    suspend fun setFlashlight(on: Boolean): ToolExecutionResult
    suspend fun controlMedia(action: String): ToolExecutionResult
    suspend fun findContacts(query: String): ToolExecutionResult
    suspend fun readNotifications(query: String?, limit: Int): ToolExecutionResult
    suspend fun openSystemSetting(target: String, state: String? = null): ToolExecutionResult =
        ToolExecutionResult("当前运行环境不支持系统设置直达 ($target)。", isError = true)
    suspend fun openUri(uri: String, packageName: String? = null): ToolExecutionResult =
        ToolExecutionResult("当前运行环境不支持直接打开链接 ($uri)。", isError = true)
}

object UnavailableSystemAssistantBackend : SystemAssistantBackend {
    private fun unsupported(name: String) =
        ToolExecutionResult("当前运行环境不支持原生工具 $name。", isError = true)

    override suspend fun getDeviceStatus() = unsupported("get_device_status")
    override suspend fun setAlarm(hour: Int, minute: Int, message: String?, days: List<Int>) = unsupported("set_alarm")
    override suspend fun setTimer(seconds: Int, message: String?) = unsupported("set_timer")
    override suspend fun queryCalendar(daysAhead: Int) = unsupported("calendar_query")
    override suspend fun createCalendarEvent(
        title: String,
        startTimeRaw: String,
        durationMinutes: Int,
        location: String?,
        description: String?,
    ) = unsupported("calendar_create")
    override suspend fun setFlashlight(on: Boolean) = unsupported("flashlight")
    override suspend fun controlMedia(action: String) = unsupported("media_control")
    override suspend fun findContacts(query: String) = unsupported("find_contact")
    override suspend fun readNotifications(query: String?, limit: Int) = unsupported("read_notifications")
}

fun assistantNativeTools(backend: SystemAssistantBackend): List<PhoneTool> = listOf(
    GetDeviceStatusTool(backend),
    SetAlarmTool(backend),
    SetTimerTool(backend),
    CalendarQueryTool(backend),
    CalendarCreateTool(backend),
    FlashlightTool(backend),
    MediaControlTool(backend),
    FindContactTool(backend),
    ReadNotificationsTool(backend),
    OpenSystemSettingTool(backend),
    OpenUriTool(backend),
)

private class GetDeviceStatusTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "get_device_status"
    override val label = "查询设备状态"
    override val description = "即时获取当前系统日期时间、电量与充电状态、音量与静音模式、网络状态及剪贴板文本摘要，无需操作屏幕。"
    override val interactsWithScreen = false

    override fun humanLabel(args: JSONObject): String = "查询设备状态"
    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult =
        backend.getDeviceStatus()
}

private class SetAlarmTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "set_alarm"
    override val label = "设置闹钟"
    override val description = "直接调用系统时钟设置闹钟（无需手动打开时钟 App）。hour 为 24 小时制 0~23，minute 为 0~59，可选 message 备注与 days 重复星期（1=周日, 2=周一..7=周六）。"
    override val interactsWithScreen = false
    override val requiredParams = listOf("hour", "minute")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("hour", JSONObject().put("type", "integer").put("description", "小时 (0~23)"))
        .put("minute", JSONObject().put("type", "integer").put("description", "分钟 (0~59)"))
        .put("message", JSONObject().put("type", "string").put("description", "可选：闹钟标签备注"))
        .put(
            "days",
            JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("type", "integer"))
                .put("description", "可选：重复日期列表，1=周日, 2=周一, 3=周二, 4=周三, 5=周四, 6=周五, 7=周六"),
        )

    override fun humanLabel(args: JSONObject): String {
        val h = args.optInt("hour", 0).coerceIn(0, 23)
        val m = args.optInt("minute", 0).coerceIn(0, 59)
        return String.format(Locale.US, "设置闹钟 %02d:%02d", h, m)
    }

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolExecutionResult("无效闹钟时间：hour 需在 0~23，minute 需在 0~59。", isError = true)
        }
        val msg = args.optString("message").trim().ifEmpty { null }
        val daysArr = args.optJSONArray("days")
        val days = buildList {
            if (daysArr != null) {
                for (i in 0 until daysArr.length()) {
                    val d = daysArr.optInt(i, -1)
                    if (d in 1..7) add(d)
                }
            }
        }
        return backend.setAlarm(hour, minute, msg, days)
    }
}

private class SetTimerTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "set_timer"
    override val label = "设置倒计时"
    override val description = "直接启动系统倒计时（计时器）。seconds 为倒计时总秒数，可选 message 备注。"
    override val interactsWithScreen = false
    override val requiredParams = listOf("seconds")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("seconds", JSONObject().put("type", "integer").put("description", "倒计时秒数（如 5 分钟传 300）"))
        .put("message", JSONObject().put("type", "string").put("description", "可选：倒计时备注"))

    override fun humanLabel(args: JSONObject): String {
        val sec = args.optInt("seconds", 0).coerceAtLeast(0)
        return if (sec >= 60 && sec % 60 == 0) "设置 ${sec / 60} 分钟倒计时" else "设置 ${sec} 秒倒计时"
    }

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val seconds = args.optInt("seconds", -1)
        if (seconds <= 0 || seconds > 86_400) {
            return ToolExecutionResult("倒计时秒数需在 1 ~ 86400 之间。", isError = true)
        }
        val msg = args.optString("message").trim().ifEmpty { null }
        return backend.setTimer(seconds, msg)
    }
}

private class CalendarQueryTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "calendar_query"
    override val label = "查询日历日程"
    override val description = "读取手机系统日历中今天及未来指定天数内的日程安排。"
    override val interactsWithScreen = false

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("days", JSONObject().put("type", "integer").put("description", "查询未来天数，默认 7，最大 30"))

    override fun humanLabel(args: JSONObject): String = "查询日历日程"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val days = args.optInt("days", 7).coerceIn(1, 30)
        return backend.queryCalendar(days)
    }
}

private class CalendarCreateTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "calendar_create"
    override val label = "创建日历日程"
    override val description = "在手机系统日历中新增一条日程。start_time 格式建议为 'yyyy-MM-dd HH:mm'（本地时间），可先调用 get_device_status 确认当前日期。"
    override val interactsWithScreen = false
    override val requiredParams = listOf("title", "start_time")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("title", JSONObject().put("type", "string").put("description", "日程标题"))
        .put("start_time", JSONObject().put("type", "string").put("description", "开始时间，格式 yyyy-MM-dd HH:mm"))
        .put("duration_minutes", JSONObject().put("type", "integer").put("description", "持续分钟数，默认 60"))
        .put("location", JSONObject().put("type", "string").put("description", "可选：地点"))
        .put("description", JSONObject().put("type", "string").put("description", "可选：备注说明"))

    override fun humanLabel(args: JSONObject): String = "添加日程「${args.optString("title").take(14)}」"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val title = args.optString("title").trim()
        val start = args.optString("start_time").trim()
        if (title.isEmpty() || start.isEmpty()) {
            return ToolExecutionResult("请提供日程标题 title 和开始时间 start_time。", isError = true)
        }
        val duration = args.optInt("duration_minutes", 60).coerceIn(5, 1440)
        val location = args.optString("location").trim().ifEmpty { null }
        val desc = args.optString("description").trim().ifEmpty { null }
        return backend.createCalendarEvent(title, start, duration, location, desc)
    }
}

private class FlashlightTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "flashlight"
    override val label = "控制手电筒"
    override val description = "直接打开或关闭手机后置手电筒。"
    override val interactsWithScreen = false
    override val requiredParams = listOf("on")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("on", JSONObject().put("type", "boolean").put("description", "true 为打开，false 为关闭"))

    override fun humanLabel(args: JSONObject): String =
        if (args.optBoolean("on", true)) "打开手电筒" else "关闭手电筒"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult =
        backend.setFlashlight(args.optBoolean("on", true))
}

private class MediaControlTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "media_control"
    override val label = "媒体与音量控制"
    override val description = "控制当前音乐/媒体播放或媒体音量：play_pause（播放/暂停）、next（下一首）、previous（上一首）、volume_up（调大媒体音量）、volume_down（调小媒体音量）、mute（静音媒体）。"
    override val interactsWithScreen = false
    override val requiredParams = listOf("action")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put(
            "action",
            JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(listOf("play_pause", "next", "previous", "volume_up", "volume_down", "mute"))),
        )

    override fun humanLabel(args: JSONObject): String = when (args.optString("action")) {
        "play_pause" -> "播放/暂停媒体"
        "next" -> "切换下一首"
        "previous" -> "切换上一首"
        "volume_up" -> "调大媒体音量"
        "volume_down" -> "调小媒体音量"
        "mute" -> "静音媒体"
        else -> "控制媒体播放"
    }

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult =
        backend.controlMedia(args.optString("action").trim().lowercase())
}

private class FindContactTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "find_contact"
    override val label = "查找联系人"
    override val description = "按姓名关键词在手机通讯录中查找联系人姓名与电话号码。"
    override val interactsWithScreen = false
    override val requiredParams = listOf("query")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("query", JSONObject().put("type", "string").put("description", "联系人姓名关键词"))

    override fun humanLabel(args: JSONObject): String = "查找联系人「${args.optString("query").take(12)}」"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return ToolExecutionResult("请提供联系人姓名关键词 query。", isError = true)
        return backend.findContacts(q)
    }
}

private class ReadNotificationsTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "read_notifications"
    override val label = "读取最近通知"
    override val description = "读取手机通知栏中当前未清除的通知摘要（需在 Zai 设置中开启通知读取权限）。"
    override val interactsWithScreen = false

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("query", JSONObject().put("type", "string").put("description", "可选：按应用或关键词筛选"))
        .put("limit", JSONObject().put("type", "integer").put("description", "最多返回条数，默认 15"))

    override fun humanLabel(args: JSONObject): String = "读取最近通知"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val q = args.optString("query").trim().ifEmpty { null }
        val limit = args.optInt("limit", 15).coerceIn(1, 40)
        return backend.readNotifications(q, limit)
    }
}

private class OpenSystemSettingTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "open_system_setting"
    override val label = "直达系统设置"
    override val description = "一步直达 Android 指定系统设置页面（无需先打开设置再层层查找）：支持 wifi、bluetooth、display、sound、battery、storage、location、apps、notifications、accessibility、security、date、language、network、airplane、developer、about、main。对 wifi/bluetooth 传 state='on'/'off' 时，若 Shizuku 可用会直接免界面毫秒级切换，否则直达对应设置页。"
    override val interactsWithScreen = true
    override val requiredParams = listOf("target")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put(
            "target",
            JSONObject()
                .put("type", "string")
                .put(
                    "enum",
                    JSONArray(
                        listOf(
                            "wifi", "bluetooth", "display", "sound", "battery", "storage",
                            "location", "apps", "notifications", "accessibility", "security",
                            "date", "language", "network", "airplane", "developer", "about", "main",
                        ),
                    ),
                )
                .put("description", "目标系统设置页面"),
        )
        .put(
            "state",
            JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(listOf("on", "off")))
                .put("description", "可选：仅 wifi/bluetooth 支持，请求直接开关"),
        )

    override fun humanLabel(args: JSONObject): String {
        val target = args.optString("target", "main")
        val state = args.optString("state").trim().lowercase()
        val label = SETTING_LABELS[target] ?: target
        return when (state) {
            "on" -> "开启 $label"
            "off" -> "关闭 $label"
            else -> "直达$label 设置"
        }
    }

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val target = args.optString("target").trim().lowercase()
        if (target.isEmpty()) return ToolExecutionResult("请指定 target 系统设置项。", isError = true)
        val state = args.optString("state").trim().lowercase().takeIf { it == "on" || it == "off" }
        val prevPkg = ctx.channel.currentForegroundPackage()
        val res = backend.openSystemSetting(target, state)
        if (res.isError) return res
        // If Shizuku toggled directly without opening UI, return immediately with zero UI wait
        if (res.text.startsWith("DIRECT_DONE:")) {
            return ToolExecutionResult(res.text.removePrefix("DIRECT_DONE:").trim())
        }
        ctx.channel.waitForUiStable(
            minWaitMs = 180L,
            quietWindowMs = 160L,
            maxWaitMs = 1_400L,
            waitForPackageChangeFrom = prevPkg.takeIf { it.isNotBlank() && it != "com.android.settings" },
        )
        return ctx.observeScreen(prefix = res.text)
    }

    companion object {
        private val SETTING_LABELS = mapOf(
            "wifi" to "Wi-Fi",
            "bluetooth" to "蓝牙",
            "display" to "显示与亮度",
            "sound" to "声音与振动",
            "battery" to "电池",
            "storage" to "存储空间",
            "location" to "定位服务",
            "apps" to "应用管理",
            "notifications" to "通知管理",
            "accessibility" to "无障碍",
            "security" to "安全与隐私",
            "date" to "日期与时间",
            "language" to "语言与输入法",
            "network" to "移动网络",
            "airplane" to "飞行模式",
            "developer" to "开发者选项",
            "about" to "关于手机",
            "main" to "系统设置",
        )
    }
}

private class OpenUriTool(private val backend: SystemAssistantBackend) : PhoneTool {
    override val name = "open_uri"
    override val label = "直达链接或应用页"
    override val description = "通过公开 URI / DeepLink 一步打开网页、地图地点搜索（如 geo:0,0?q=故宫、androidamap://poi?sourceApplication=Zai&keywords=咖啡）、拨号/邮件或 App 深链，省去多次界面跳转。"
    override val interactsWithScreen = true
    override val requiredParams = listOf("uri")

    override fun parametersSchema(): JSONObject = JSONObject()
        .put("uri", JSONObject().put("type", "string").put("description", "目标 URI 或 DeepLink"))
        .put("package_name", JSONObject().put("type", "string").put("description", "可选：限定处理该链接的应用包名"))

    override fun humanLabel(args: JSONObject): String = "打开链接 ${args.optString("uri").take(22)}"

    override suspend fun execute(ctx: PhoneToolContext, args: JSONObject): ToolExecutionResult {
        val rawUri = args.optString("uri").trim()
        if (rawUri.isEmpty()) return ToolExecutionResult("请提供要打开的 uri。", isError = true)
        val scheme = rawUri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme.isEmpty() || scheme in BLOCKED_SCHEMES || scheme !in ALLOWED_SCHEMES) {
            return ToolExecutionResult(
                "不支持或不安全的 URI 协议「$scheme」，仅允许 https/http/geo/tel/mailto/sms 及常见 App 公开 DeepLink。",
                isError = true,
            )
        }
        val pkg = args.optString("package_name").trim().ifEmpty { null }
        val prevPkg = ctx.channel.currentForegroundPackage()
        val res = backend.openUri(rawUri, pkg)
        if (res.isError) return res
        ctx.channel.waitForUiStable(
            minWaitMs = 220L,
            quietWindowMs = 180L,
            maxWaitMs = 1_800L,
            waitForPackageChangeFrom = prevPkg.takeIf { it.isNotBlank() && it != pkg },
        )
        return ctx.observeScreen(prefix = res.text)
    }

    companion object {
        private val BLOCKED_SCHEMES = setOf("file", "content", "javascript", "intent", "data", "android-app")
        private val ALLOWED_SCHEMES = setOf(
            "https", "http", "geo", "tel", "mailto", "sms", "smsto",
            "androidamap", "amapuri", "baidumap", "qqmap",
            "bilibili", "zhihu", "xhsdiscover", "snssdk1128",
            "tbopen", "openapp.jdmobile", "dianping", "imeituan", "ctrip",
            "weixin", "alipays",
        )
    }
}

