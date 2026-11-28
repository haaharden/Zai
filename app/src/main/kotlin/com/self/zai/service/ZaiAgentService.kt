package com.self.zai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.self.zai.R
import com.self.zai.model.AgentStopHandler
import com.self.zai.model.ZaiComponentNames

/**
 * Foreground service active only while Zai is executing a phone task.
 * Prevents HyperOS from freezing the process mid-task and exposes a one-tap Stop action in the shade.
 */
class ZaiAgentService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        when (intent?.action) {
            ACTION_STOP_TASK -> {
                (application as? AgentStopHandler)?.stopActiveRun()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP_SERVICE -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val statusText = intent?.getStringExtra(EXTRA_STATUS).orEmpty().ifBlank { "正在操作手机" }
        acquireWakeLock()
        val notif = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zai::AgentRunLock").apply {
            setReferenceCounted(false)
            acquire(15 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Zai 任务运行状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "执行手机操控任务时的运行状态与一键停止按钮"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openPending = PendingIntent.getActivity(
            this,
            0,
            Intent().apply {
                setClassName(packageName, ZaiComponentNames.MAIN_ACTIVITY)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, ZaiAgentService::class.java).setAction(ACTION_STOP_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (statusText.contains("操作") || statusText.contains("操控")) {
            "Zai 正在操控手机"
        } else {
            "Zai 正在后台运行"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zai)
            .setContentTitle(title)
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "立即停止", stopPending)
            .build()
    }

    companion object {
        @Volatile
        var instance: ZaiAgentService? = null
            private set

        private const val CHANNEL_ID = "zai_agent_running"
        private const val NOTIF_ID = 4102
        const val ACTION_UPDATE = "com.self.zai.action.UPDATE"
        const val ACTION_STOP_TASK = "com.self.zai.action.STOP_TASK"
        const val ACTION_STOP_SERVICE = "com.self.zai.action.STOP_SERVICE"
        const val EXTRA_STATUS = "extra_status"

        fun startOrUpdate(context: Context, status: String) {
            runCatching {
                val intent = Intent(context, ZaiAgentService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_STATUS, status)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            if (instance == null) return
            runCatching {
                val intent = Intent(context, ZaiAgentService::class.java).setAction(ACTION_STOP_SERVICE)
                context.startService(intent)
            }
        }

        fun postTaskFinishedFallback(context: Context, summary: String) {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val doneChannel = NotificationChannel(
                    DONE_CHANNEL_ID,
                    "Zai 任务完成提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                )
                nm.createNotificationChannel(doneChannel)
                val openPending = PendingIntent.getActivity(
                    context,
                    2,
                    Intent().apply {
                        setClassName(context.packageName, ZaiComponentNames.MAIN_ACTIVITY)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notif = NotificationCompat.Builder(context, DONE_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_zai)
                    .setContentTitle("Zai 已完成手机操控")
                    .setContentText(summary.ifBlank { "点击返回查看结果" })
                    .setAutoCancel(true)
                    .setContentIntent(openPending)
                    .build()
                nm.notify(DONE_NOTIF_ID, notif)
            }
        }

        private const val DONE_CHANNEL_ID = "zai_task_finished"
        private const val DONE_NOTIF_ID = 4103
    }
}
