package com.copix.androidtaktracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.copix.androidtaktracker.MainActivity
import com.copix.androidtaktracker.R
import com.copix.androidtaktracker.core.identity.IdentityResolver
import com.copix.androidtaktracker.host.TrackingHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class TrackingForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val host = TrackingHost.get(this)
        host.start()
        updateWakeLock(host.config.value.startup.preventSleepWhileTracking)

        // Keep the notification text (callsign / paused) and the sleep wake-lock live —
        // previously both were fixed at service start until the next restart.
        serviceScope.launch {
            combine(host.config, host.paused) { c, p -> c.startup.preventSleepWhileTracking to p }
                .collect {
                    updateWakeLock(it.first)
                    refreshNotification()
                }
        }
    }

    private fun updateWakeLock(enabled: Boolean) {
        if (enabled && wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidTAKTracker:Tracking").also {
                it.setReferenceCounted(false)
                it.acquire(10 * 60 * 60 * 1000L)
            }
        } else if (!enabled && wakeLock != null) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun refreshNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — the original FGS notification stays as-is.
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        TrackingHost.get(this).stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val host = TrackingHost.get(this)
        val callsign = IdentityResolver.resolve(host.config.value).callsign
        val paused = host.paused.value
        val defer = host.atak.shouldDefer(host.config.value.atak.deferToAtak)
        val status = when {
            paused -> "Paused"
            defer -> "Deferring to ATAK"
            else -> "Tracking"
        }
        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroidTAKTracker")
            .setContentText("$status · $callsign")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_ID = "tracking"
        const val NOTIFICATION_ID = 42

        fun start(context: android.content.Context) {
            val i = Intent(context, TrackingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
