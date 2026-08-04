package com.copix.androidtaktracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.copix.androidtaktracker.host.TrackingHost

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val host = TrackingHost.get(context)
        if (host.config.value.startup.startOnBoot) {
            TrackingForegroundService.start(context)
        }
    }
}
