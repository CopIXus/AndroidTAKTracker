package com.copix.androidtaktracker

import android.app.Application
import com.copix.androidtaktracker.host.TrackingHost
import com.copix.androidtaktracker.service.ServiceWatchdogWorker
import com.copix.androidtaktracker.service.TrackingForegroundService

class AndroidTakTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val host = TrackingHost.get(this)
        if (host.config.value.startup.startOnBoot) {
            TrackingForegroundService.start(this)
        }
        ServiceWatchdogWorker.schedule(this)
    }
}
