package com.self.zai.phone.tools

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.self.zai.assistant.ZaiNotificationListenerService
import com.self.zai.phone.AppDirectory
import com.self.zai.pi.ToolExecutionResult
import com.self.zai.shell.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

/**
 * Live Android implementation of [SystemAssistantBackend] using native OS providers and services.
 */
class AndroidSystemAssistantBackend(
    context: Context,
    private val appDirectory: AppDirectory,
    private val shizuku: ShizukuBridge? = null,
) : SystemAssistantBackend {

    private val appContext = context.applicationContext

    override suspend fun getDeviceStatus(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA).format(Date())

        val battIntent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mediaVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val ringVol = am.getStreamVolume(AudioManager.STREAM_RING)
        val ringMax = am.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
        val ringerMode = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "静音"
            AudioManager.RINGER_MODE_VIBRATE -> "振动"
            else -> "响铃"
        }

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val netLabel = when {
            caps == null -> "无网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            else -> "已联网"
        }

        val isLocked = (appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isKeyguardLocked == true
        val clipPreview = if (isLocked) {
            null
        } else {
            withContext(Dispatchers.Main) {
                runCatching {
                    val cb = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()?.trim()?.take(120)
                }.getOrNull()
            }
        }

        val summary = buildString {
            appendLine("当前时间：$nowStr")
            if (pct >= 0) appendLine("电量：$pct%${if (charging) "（充电中）" else ""}")
            appendLine("声音模式：$ringerMode；媒体音量 $mediaVol/$mediaMax；铃声音量 $ringVol/$ringMax")
            appendLine("网络：$netLabel")
            if (!clipPreview.isNullOrBlank()) {
                appendLine("剪贴板预览：$clipPreview")
            }
        }.trim()

        ToolExecutionResult(summary)
    }

    override suspend fun setAlarm(
        hour: Int,
        minute: Int,
        message: String?,
        days: List<Int>,
    ): ToolExecutionResult = withContext(Dispatchers.Main) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!message.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            if (days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        }
        runCatching {
            appContext.startActivity(intent)
            val timeStr = String.format(Locale.US, "%02d:%02d", hour, minute)
            val note = message?.let { "（$it）" }.orEmpty()
            ToolExecutionResult("已通过系统时钟设置闹钟：$timeStr$note")
        }.getOrElse { err ->
            ToolExecutionResult("设置闹钟失败：${err.message}", isError = true)
        }
    }

    override suspend fun setTimer(seconds: Int, message: String?): ToolExecutionResult = withContext(Dispatchers.Main) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!message.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
        }
        runCatching {
            appContext.startActivity(intent)
            val human = if (seconds >= 60 && seconds % 60 == 0) "${seconds / 60} 分钟" else "${seconds} 秒"
            val note = message?.let { "（$it）" }.orEmpty()
            ToolExecutionResult("已启动倒计时：$human$note")
        }.getOrElse { err ->
            ToolExecutionResult("启动倒计时失败：${err.message}", isError = true)
        }
    }

    override suspend fun queryCalendar(daysAhead: Int): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ToolExecutionResult("尚未授予日历读取权限，请先在系统应用权限中允许 Zai 读取日历。", isError = true)
        }

        val startMs = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val endMs = startMs + daysAhead.toLong() * 24L * 3600L * 1000L

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
        )

        val fmtDay = SimpleDateFormat("MM-dd E HH:mm", Locale.CHINA)
        val fmtEnd = SimpleDateFormat("HH:mm", Locale.CHINA)
        val fmtAllDay = SimpleDateFormat("MM-dd E (全天)", Locale.CHINA)
        val items = mutableListOf<String>()

        runCatching {
            appContext.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && items.size < 30) {
                    val title = cursor.getString(0)?.trim().orEmpty().ifEmpty { "(无标题)" }
                    val begin = cursor.getLong(1)
                    val end = cursor.getLong(2)
                    val loc = cursor.getString(3)?.trim().orEmpty()
                    val allDay = cursor.getInt(4) == 1
                    val timeLabel = if (allDay) {
                        fmtAllDay.format(Date(begin))
                    } else {
                        "${fmtDay.format(Date(begin))}~${fmtEnd.format(Date(end))}"
                    }
                    items += buildString {
                        append("- ").append(timeLabel).append(" ").append(title)
                        if (loc.isNotEmpty()) append(" @").append(loc)
                    }
                }
            }
        }.onFailure { err ->
            return@withContext ToolExecutionResult("查询日历失败：${err.message}", isError = true)
        }

        if (items.isEmpty()) {
            ToolExecutionResult("未来 $daysAhead 天内暂无日历日程。")
        } else {
            ToolExecutionResult("未来 $daysAhead 天共找到 ${items.size} 条日程：\n${items.joinToString("\n")}")
        }
    }

    override suspend fun createCalendarEvent(
        title: String,
        startTimeRaw: String,
        durationMinutes: Int,
        location: String?,
        description: String?,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ToolExecutionResult("尚未授予日历写入权限，请先在系统应用权限中允许 Zai 修改日历。", isError = true)
        }

        val startMs = parseLocalTimestamp(startTimeRaw)
            ?: return@withContext ToolExecutionResult(
                "无法解析开始时间「$startTimeRaw」，请使用 'yyyy-MM-dd HH:mm' 格式（例如 2026-11-26 09:30）。",
                isError = true,
            )
        val endMs = startMs + durationMinutes.toLong() * 60_000L

        val calId = findPrimaryWritableCalendarId()
            ?: return@withContext ToolExecutionResult("未找到可写入的本地或账号日历。", isError = true)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description)
        }

        runCatching {
            val inserted = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: error("系统日历拒绝写入")
            val eventId = inserted.lastPathSegment?.toLongOrNull()
            if (eventId != null) {
                val reminder = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, 15)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                runCatching { appContext.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder) }
            }
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            ToolExecutionResult("已创建日程「$title」：${fmt.format(Date(startMs))}（持续 ${durationMinutes} 分钟，已设提前 15 分钟提醒）")
        }.getOrElse { err ->
            ToolExecutionResult("创建日程失败：${err.message}", isError = true)
        }
    }

    override suspend fun setFlashlight(on: Boolean): ToolExecutionResult = withContext(Dispatchers.IO) {
        runCatching {
            val camMgr = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val torchCamId = camMgr.cameraIdList.firstOrNull { id ->
                val chars = camMgr.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val isBack = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                hasFlash && isBack
            } ?: camMgr.cameraIdList.firstOrNull { id ->
                camMgr.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return@withContext ToolExecutionResult("当前设备未找到可用的闪光灯。", isError = true)

            camMgr.setTorchMode(torchCamId, on)
            ToolExecutionResult(if (on) "已打开手电筒。" else "已关闭手电筒。")
        }.getOrElse { err ->
            ToolExecutionResult("手电筒控制失败：${err.message}", isError = true)
        }
    }

    override suspend fun controlMedia(action: String): ToolExecutionResult = withContext(Dispatchers.Main) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            when (action) {
                "play_pause" -> {
                    dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    ToolExecutionResult("已发送播放/暂停指令。")
                }
                "next" -> {
                    dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT)
                    ToolExecutionResult("已切换到下一首。")
                }
                "previous" -> {
                    dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    ToolExecutionResult("已切换到上一首。")
                }
                "volume_up" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    ToolExecutionResult("已调大媒体音量至 $cur/$max。")
                }
                "volume_down" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    ToolExecutionResult("已调小媒体音量至 $cur/$max。")
                }
                "mute" -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    ToolExecutionResult("已将媒体音量静音。")
                }
                else -> ToolExecutionResult("不支持的媒体操作：$action", isError = true)
            }
        }.getOrElse { err ->
            ToolExecutionResult("媒体控制失败：${err.message}", isError = true)
        }
    }

    override suspend fun findContacts(query: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ToolExecutionResult("尚未授予通讯录读取权限，请先在系统应用权限中允许 Zai 读取联系人。", isError = true)
        }

        val results = LinkedHashMap<String, MutableSet<String>>()
        runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf("%$query%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < 10) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    val num = cursor.getString(1)?.trim().orEmpty()
                    if (name.isNotEmpty() && num.isNotEmpty()) {
                        results.getOrPut(name) { LinkedHashSet() }.add(num)
                    }
                }
            }
        }.onFailure { err ->
            return@withContext ToolExecutionResult("查找联系人失败：${err.message}", isError = true)
        }

        if (results.isEmpty()) {
            ToolExecutionResult("通讯录中未找到包含「$query」的联系人。")
        } else {
            val lines = results.entries.joinToString("\n") { (name, nums) -> "- $name: ${nums.joinToString(", ")}" }
            ToolExecutionResult("找到 ${results.size} 位联系人：\n$lines")
        }
    }

    override suspend fun readNotifications(query: String?, limit: Int): ToolExecutionResult = withContext(Dispatchers.IO) {
        val svc = ZaiNotificationListenerService.instance
            ?: return@withContext ToolExecutionResult(
                "尚未开启 Zai 通知读取权限。请前往 Zai 设置 → 系统助手与语音 → 通知读取权限 中开启。",
                isError = true,
            )
        val raw = svc.snapshotRecentNotifications(limit = 40)
        val filtered = raw.filter { item ->
            if (query.isNullOrBlank()) return@filter true
            val appLabel = appDirectory.resolveAppLabel(item.packageName)
            appLabel.contains(query, ignoreCase = true) ||
                item.title.contains(query, ignoreCase = true) ||
                item.text.contains(query, ignoreCase = true)
        }.take(limit)

        if (filtered.isEmpty()) {
            ToolExecutionResult(if (query.isNullOrBlank()) "当前通知栏暂无可读通知。" else "未找到包含「$query」的近期通知。")
        } else {
            val lines = filtered.joinToString("\n") { item ->
                val appLabel = appDirectory.resolveAppLabel(item.packageName)
                item.formatLine(appLabel)
            }
            ToolExecutionResult("当前共 ${filtered.size} 条通知：\n$lines")
        }
    }

    override suspend fun openSystemSetting(target: String, state: String?): ToolExecutionResult {
        // Optional Shizuku instant toggle for Wi-Fi / Bluetooth without opening UI at all
        if (state != null && shizuku != null && shizuku.status.value.isReady) {
            val cmd = when (target) {
                "wifi" -> if (state == "on") "svc wifi enable" else "svc wifi disable"
                "bluetooth" -> if (state == "on") "svc bluetooth enable" else "svc bluetooth disable"
                else -> null
            }
            if (cmd != null) {
                val execRes = shizuku.exec(cmd, timeoutMs = 4_000)
                if (execRes.isSuccess) {
                    val name = if (target == "wifi") "Wi-Fi" else "蓝牙"
                    val action = if (state == "on") "开启" else "关闭"
                    return ToolExecutionResult("DIRECT_DONE:已通过 Shizuku 直接${action}${name}。")
                }
            }
        }

        val action = when (target) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "notifications" -> Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "network" -> Settings.ACTION_WIRELESS_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "about" -> Settings.ACTION_DEVICE_INFO_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        return withContext(Dispatchers.Main) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                appContext.startActivity(intent)
                ToolExecutionResult("已直达系统设置页 ($target)")
            }.getOrElse {
                runCatching {
                    appContext.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ToolExecutionResult("已打开系统设置首页（设备不支持直达 $target 子页）")
                }.getOrElse { err ->
                    ToolExecutionResult("打开系统设置失败：${err.message}", isError = true)
                }
            }
        }
    }

    override suspend fun openUri(uri: String, packageName: String?): ToolExecutionResult = withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!packageName.isNullOrBlank()) setPackage(packageName)
        }
        runCatching {
            appContext.startActivity(intent)
            ToolExecutionResult("已通过 URI 打开：$uri")
        }.getOrElse { err ->
            ToolExecutionResult("无法打开链接「$uri」：未安装对应应用或链接无效（${err.message}）。", isError = true)
        }
    }

    private fun dispatchMediaKey(am: AudioManager, keyCode: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun findPrimaryWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        var fallbackId: Long? = null
        appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val primary = cursor.getInt(1) == 1
                if (primary) return id
                if (fallbackId == null) fallbackId = id
            }
        }
        return fallbackId
    }

    companion object {
        fun parseLocalTimestamp(raw: String): Long? {
            val trimmed = raw.trim()
            trimmed.toLongOrNull()?.let { num ->
                return if (num > 10_000_000_000L) num else num * 1000L
            }
            val patterns = listOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm",
                "yyyy/MM/dd HH:mm",
            )
            for (pattern in patterns) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        isLenient = false
                        timeZone = TimeZone.getDefault()
                    }.parse(trimmed)
                }.getOrNull()
                if (parsed != null) return parsed.time
            }
            return null
        }
    }
}
