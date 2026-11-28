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
import com.self.zai.model.LiveNotificationSnapshotRegistry
import com.self.zai.model.NotificationSnapshotSource
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
 * Live Android implementation of [SystemAssistantBackend] using native OS providers and services.
 */
class AndroidSystemAssistantBackend(
    context: Context,
    private val appDirectory: AppDirectory,
    private val shizuku: ShizukuBridge? = null,
    private val notificationSource: NotificationSnapshotSource = LiveNotificationSnapshotRegistry,
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
        if (!notificationSource.isConnected()) {
            return@withContext ToolExecutionResult(
                "尚未开启 Zai 通知读取权限。请前往 Zai 设置 → 系统助手与语音 → 通知读取权限 中开启。",
                isError = true,
            )
        }
        val raw = notificationSource.snapshotRecentNotifications(limit = 40)
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
