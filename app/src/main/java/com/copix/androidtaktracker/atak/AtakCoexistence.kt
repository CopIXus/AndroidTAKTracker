package com.copix.androidtaktracker.atak

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Detects ATAK install/activity so ReportingEngine can defer PLI and avoid duplicate CoT.
 */
class AtakCoexistence(private val context: Context) {
    companion object {
        val ATAK_PACKAGES = listOf("com.atakmap.app.civ", "com.atakmap.app")
    }

    private val _installed = MutableStateFlow(false)
    private val _running = MutableStateFlow(false)
    private val _heardOnMesh = MutableStateFlow(false)

    val installed: StateFlow<Boolean> = _installed
    val running: StateFlow<Boolean> = _running
    val heardOnMesh: StateFlow<Boolean> = _heardOnMesh

    @Volatile private var lastHeardEpochMs = 0L

    fun refreshInstalled() {
        val pm = context.packageManager
        _installed.value = ATAK_PACKAGES.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    fun noteHeardOnMesh() {
        lastHeardEpochMs = System.currentTimeMillis()
        _heardOnMesh.value = true
    }

    fun tickHeardExpiry(graceMs: Long = 60_000) {
        if (_heardOnMesh.value && System.currentTimeMillis() - lastHeardEpochMs > graceMs) {
            _heardOnMesh.value = false
        }
    }

    fun refreshRunning(): Boolean {
        if (!hasUsageAccess()) {
            _running.value = false
            return false
        }
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 15_000, now)
        val event = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                foreground = event.packageName
            }
        }
        // Also treat recent use as "running" for ATAK (background SA)
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 120_000, now)
        val recentlyUsed = stats?.any { s ->
            s.packageName in ATAK_PACKAGES && s.lastTimeUsed >= now - 120_000
        } == true
        _running.value = (foreground in ATAK_PACKAGES) || recentlyUsed
        return _running.value
    }

    fun shouldDefer(mode: String): Boolean {
        refreshInstalled()
        tickHeardExpiry()
        return when (mode.lowercase()) {
            "off" -> false
            "whenheardonmesh" -> _heardOnMesh.value
            else -> { // WhenRunning
                if (!_installed.value) false
                else refreshRunning()
            }
        }
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun AppOpsManager.checkOpNoMode(op: String, uid: Int, pkg: String): Int =
        checkOpNoThrow(op, uid, pkg)
}
