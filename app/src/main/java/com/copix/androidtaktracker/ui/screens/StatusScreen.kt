package com.copix.androidtaktracker.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.copix.androidtaktracker.core.identity.IdentityResolver
import com.copix.androidtaktracker.core.status.StatusInputs
import com.copix.androidtaktracker.core.status.TrackingStatusMapper
import com.copix.androidtaktracker.host.TrackingHost
import com.copix.androidtaktracker.ui.SettingsSection
import com.copix.androidtaktracker.ui.components.BatteryStatusCard
import com.copix.androidtaktracker.ui.components.ConnectionStatusCard
import com.copix.androidtaktracker.ui.components.LocationStatusCard
import com.copix.androidtaktracker.ui.components.TrackingIntelligenceCard
import com.copix.androidtaktracker.ui.components.TrackingStatusCard
import kotlinx.coroutines.delay

/**
 * Operational dashboard. Every value comes from runtime state ([TrackingHost.reportingSnapshot],
 * GPS fix, server statuses, [TrackingHost.deviceState]) run through the pure
 * [TrackingStatusMapper]; the screen only ticks a clock so ages and countdowns stay live.
 */
@Composable
fun StatusScreen(host: TrackingHost, onNavigate: (SettingsSection) -> Unit) {
    val config by host.config.collectAsState()
    val fix by host.gps.fix.collectAsState()
    val paused by host.paused.collectAsState()
    val statuses by host.serverStatuses.collectAsState()
    val reporting by host.reportingSnapshot.collectAsState()
    val device by host.deviceState.collectAsState()
    val mdmPresent by host.mdm.mdmPresent.collectAsState()
    val allowPause by host.mdm.allowOperatorPause.collectAsState()
    val remotePause by host.mdm.remotePause.collectAsState()
    val unlocked by host.settingsUnlocked.collectAsState()
    val ctx = LocalContext.current

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LifecycleResumeEffect(Unit) {
        // Operator may have just come back from the battery / permission settings screens.
        host.refreshDeviceState()
        onPauseOrDispose { }
    }

    val identity = IdentityResolver.resolve(config)
    val deferring = host.atak.shouldDefer(config.atak.deferToAtak)
    val status = TrackingStatusMapper.map(
        StatusInputs(
            callsign = identity.callsign,
            paused = paused || remotePause,
            deferringToAtak = deferring,
            servers = statuses.values.toList(),
            fix = fix,
            reporting = reporting,
            gpsSampling = device.gpsSampling,
            adaptGpsToMotion = config.gps.adaptToMotion,
            networkAvailable = device.networkAvailable,
            locationPermissionGranted = device.locationPermissionGranted,
            batteryPercent = device.batteryPercent,
            charging = device.charging,
            batteryOptimizationExempt = device.batteryOptimizationExempt,
            mdmKeepAlive = mdmPresent,
        ),
        nowMs = now,
    )

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val effectivePaused = paused || remotePause
        val pauseEnabled = host.canOperatorPause()
        val pauseHint = when {
            remotePause -> "Paused by MDM"
            mdmPresent && !allowPause -> "Pause is disabled by MDM"
            host.isSettingsLocked && !unlocked -> "Settings are locked"
            else -> null
        }
        TrackingStatusCard(
            status = status,
            paused = effectivePaused,
            pauseEnabled = pauseEnabled,
            pauseHint = pauseHint,
            onTogglePause = { host.setPaused(!paused) },
        )
        TrackingIntelligenceCard(status)
        ConnectionStatusCard(status, onOpenServers = { onNavigate(SettingsSection.Servers) })
        LocationStatusCard(
            status = status,
            onFixPermission = {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                }
                runCatching { ctx.startActivity(i) }
            },
        )
        BatteryStatusCard(
            status = status,
            charging = device.charging,
            onFixBatterySettings = {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                }
                runCatching { ctx.startActivity(i) }
            },
        )
    }
}
