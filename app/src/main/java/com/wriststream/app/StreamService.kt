package com.wriststream.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast

/**
 * 前台采集服务：
 * - 前台服务 + 常驻通知（ColorOS Watch 后台管控下的存活保障）
 * - WakeLock 防熄屏断采
 * - 组装 SensorCollector + Uploader，负责生命周期
 */
class StreamService : Service() {

    companion object {
        const val ACTION_START = "com.wriststream.app.START"
        const val ACTION_STOP = "com.wriststream.app.STOP"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "wriststream_stream"

        @Volatile var running = false
            private set
        @Volatile var currentHr = 0
        @Volatile var lastUploadAt = 0L
        @Volatile var sentCount = 0L
        @Volatile var queueSize = 0
        @Volatile var activeSensors: List<String>? = null
        @Volatile var stepCount: Long = 0

        @Volatile
        var uploader: Uploader? = null
            private set

        fun isRunning(): Boolean = running
    }

    private val binder = LocalBinder()
    private var collector: SensorCollector? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun service(): StreamService = this@StreamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCollecting()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startCollecting()
        }
        return START_STICKY
    }

    private fun startCollecting() {
        if (running) return
        running = true

        startForeground(NOTIF_ID, buildNotification())

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristStream:stream")
            .also { it.acquire() }

        val ep = Prefs.getEndpoint(this)
        val up = Uploader { ep }
        uploader = up
        up.start()

        val col = SensorCollector(this, up) { stepCount = it }
        collector = col
        col.start()
        activeSensors = col.activeSensors.toList()

        Toast.makeText(this, "采集已开始 → $ep", Toast.LENGTH_LONG).show()
    }

    private fun stopCollecting() {
        if (!running) return
        running = false
        collector?.stop()
        collector = null
        uploader?.stop()
        uploader = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Toast.makeText(this, "采集已停止", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        stopCollecting()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID, "WristStream 采集", NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WristStream 采集中")
            .setContentText("心率 ${currentHr} bpm · 已上传 ${sentCount} 条")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }
}
