package com.copix.androidtaktracker.core.status

import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.cot.GpsSourceKind
import com.copix.androidtaktracker.core.reporting.GpsDuty
import com.copix.androidtaktracker.core.reporting.MotionState
import com.copix.androidtaktracker.core.reporting.ReportingSnapshot
import com.copix.androidtaktracker.core.reporting.SuppressReason
import com.copix.androidtaktracker.core.tak.ServerConnectionStatus
import com.copix.androidtaktracker.core.tak.TakConnectionState

/** Headline state an operator should grasp in about two seconds. */
enum class TrackingState {
    TRACKING,
    PAUSED,
    ATAK_DEFERRED,
    WAITING_FOR_LOCATION,
    TAK_DISCONNECTED,
    LOCATION_PERMISSION_REQUIRED,
    NO_SERVER_CONFIGURED,
}

enum class ConnectionState {
    NO_NETWORK,
    NO_SERVER,
    CONNECTING,
    RECONNECTING,
    /** Fail2ban guard tripped — auto-reconnect stopped until the operator retries. */
    CIRCUIT_OPEN,
    ERROR,
    CONNECTED_WAITING_FIRST_PLI,
    CONNECTED,
    DISCONNECTED,
}

enum class LocationQuality { GPS, FUSED, HELD, NETWORK_IP, WAITING, PERMISSION_REQUIRED }

/** Everything the mapper needs; all values come from runtime state, nothing is inferred in the UI. */
data class StatusInputs(
    val callsign: String,
    val paused: Boolean,
    val deferringToAtak: Boolean,
    val servers: List<ServerConnectionStatus>,
    val fix: GpsFix?,
    val reporting: ReportingSnapshot,
    val gpsSampling: Boolean,
    val adaptGpsToMotion: Boolean,
    val networkAvailable: Boolean,
    val locationPermissionGranted: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean,
    /** null = platform did not tell us. */
    val batteryOptimizationExempt: Boolean?,
    /** Headwind / MDM present — tracking continues via FGS keep-alive even if not exempt. */
    val mdmKeepAlive: Boolean = false,
)

data class OperatorStatus(
    val state: TrackingState,
    val headline: String,
    val callsign: String,
    val summary: String,
    val connection: ConnectionState,
    val connectionTitle: String,
    val connectionDetail: String?,
    val location: LocationQuality,
    val locationLine: String,
    val motionLabel: String,
    val gpsLabel: String,
    val reportingLabel: String,
    val nextReportLabel: String,
    val lastLocationLabel: String,
    val lastReportLabel: String,
    val batteryLabel: String,
    val batteryOptimizationLabel: String,
    /** Show the persistent, non-alarming "fix battery settings" prompt. */
    val batteryWarning: Boolean,
)

object TrackingStatusMapper {
    private const val GPS_ACCURACY_METERS = 30.0

    fun map(inputs: StatusInputs, nowMs: Long): OperatorStatus {
        val enabledServers = inputs.servers.filter { it.enabled }
        val connectedCount = enabledServers.count { it.state == TakConnectionState.CONNECTED }
        val connection = connectionState(inputs, enabledServers, connectedCount)
        val meshActive = inputs.reporting.meshActive
        val hasPath = connectedCount > 0 || meshActive

        val state = when {
            !inputs.locationPermissionGranted -> TrackingState.LOCATION_PERMISSION_REQUIRED
            inputs.paused -> TrackingState.PAUSED
            inputs.deferringToAtak -> TrackingState.ATAK_DEFERRED
            enabledServers.isEmpty() && !meshActive -> TrackingState.NO_SERVER_CONFIGURED
            !hasPath -> TrackingState.TAK_DISCONNECTED
            inputs.fix == null || inputs.fix.source == GpsSourceKind.NONE -> TrackingState.WAITING_FOR_LOCATION
            else -> TrackingState.TRACKING
        }

        val motionLabel = motionLabel(inputs.reporting.motion, inputs.fix)
        val location = locationQuality(inputs)
        val rep = inputs.reporting

        return OperatorStatus(
            state = state,
            headline = headline(state),
            callsign = inputs.callsign,
            summary = summary(state, connection, connectedCount, meshActive, motionLabel, rep, nowMs),
            connection = connection,
            connectionTitle = connectionTitle(connection, enabledServers.size, connectedCount),
            connectionDetail = connectionDetail(connection, enabledServers, rep, nowMs),
            location = location,
            locationLine = locationLine(location, inputs.fix, nowMs),
            motionLabel = motionLabel,
            gpsLabel = gpsLabel(inputs),
            reportingLabel = reportingLabel(rep),
            nextReportLabel = DurationFormat.countdown(rep.nextDueEpochMs, nowMs),
            lastLocationLabel = DurationFormat.age(inputs.fix?.timestamp?.toEpochMilli() ?: 0L, nowMs),
            lastReportLabel = DurationFormat.age(rep.lastPliEpochMs, nowMs),
            batteryLabel = batteryLabel(inputs.batteryPercent, inputs.charging),
            batteryOptimizationLabel = when {
                inputs.batteryOptimizationExempt == true -> "Exempt"
                inputs.mdmKeepAlive && inputs.batteryOptimizationExempt == false ->
                    "Not exempt — MDM keep-alive still running"
                inputs.batteryOptimizationExempt == false -> "Android may restrict background tracking"
                else -> "Unknown"
            },
            batteryWarning = inputs.batteryOptimizationExempt == false && !inputs.mdmKeepAlive,
        )
    }

    fun headline(state: TrackingState): String = when (state) {
        TrackingState.TRACKING -> "TRACKING"
        TrackingState.PAUSED -> "PAUSED"
        TrackingState.ATAK_DEFERRED -> "ATAK ACTIVE — TRACKER DEFERRED"
        TrackingState.WAITING_FOR_LOCATION -> "WAITING FOR LOCATION"
        TrackingState.TAK_DISCONNECTED -> "TAK DISCONNECTED"
        TrackingState.LOCATION_PERMISSION_REQUIRED -> "LOCATION PERMISSION REQUIRED"
        TrackingState.NO_SERVER_CONFIGURED -> "NO TAK SERVER"
    }

    private fun connectionState(
        inputs: StatusInputs,
        enabled: List<ServerConnectionStatus>,
        connectedCount: Int,
    ): ConnectionState {
        if (enabled.isEmpty()) return ConnectionState.NO_SERVER
        if (connectedCount > 0) {
            return if (inputs.reporting.lastPliEpochMs == 0L) ConnectionState.CONNECTED_WAITING_FIRST_PLI
            else ConnectionState.CONNECTED
        }
        if (!inputs.networkAvailable) return ConnectionState.NO_NETWORK
        if (enabled.any { it.autoReconnectSuspended }) return ConnectionState.CIRCUIT_OPEN
        if (enabled.any { it.state == TakConnectionState.CONNECTING }) return ConnectionState.CONNECTING
        if (enabled.any { it.state == TakConnectionState.RECONNECTING }) return ConnectionState.RECONNECTING
        if (enabled.any { it.state == TakConnectionState.ERROR }) return ConnectionState.ERROR
        return ConnectionState.DISCONNECTED
    }

    private fun connectionTitle(c: ConnectionState, enabled: Int, connected: Int): String {
        val noun = if (enabled > 1) "TAK Servers" else "TAK Server"
        return when (c) {
            ConnectionState.NO_SERVER -> "No TAK server configured"
            ConnectionState.NO_NETWORK -> "No network"
            ConnectionState.CONNECTING -> "$noun · Connecting"
            ConnectionState.RECONNECTING -> "$noun · Reconnecting"
            ConnectionState.CIRCUIT_OPEN -> "$noun · Connection paused"
            ConnectionState.ERROR -> "$noun · Error"
            ConnectionState.CONNECTED_WAITING_FIRST_PLI, ConnectionState.CONNECTED ->
                if (enabled > 1) "$noun · $connected of $enabled connected" else "$noun · Connected"
            ConnectionState.DISCONNECTED -> "$noun · Disconnected"
        }
    }

    private fun connectionDetail(
        c: ConnectionState,
        enabled: List<ServerConnectionStatus>,
        rep: ReportingSnapshot,
        nowMs: Long,
    ): String? {
        val firstError = enabled.firstOrNull { !it.lastErrorCode.isNullOrBlank() }?.lastErrorCode
        return when (c) {
            ConnectionState.CONNECTED -> "Last report · ${DurationFormat.age(rep.lastPliEpochMs, nowMs)}"
            ConnectionState.CONNECTED_WAITING_FIRST_PLI -> "Waiting for first position report"
            ConnectionState.NO_NETWORK -> "Waiting for Wi‑Fi or mobile data"
            ConnectionState.NO_SERVER -> "Add a server or scan an enrollment QR"
            ConnectionState.CIRCUIT_OPEN -> {
                val tls = enabled.any { it.autoReconnectSuspended && looksLikeTls(it.lastErrorCode) }
                if (tls) {
                    "Connection paused after repeated TLS failures — use Test after correcting server settings"
                } else {
                    "Connection paused after repeated connection failures — check network, then use Test on Servers"
                }
            }
            ConnectionState.CONNECTING -> null
            ConnectionState.RECONNECTING, ConnectionState.ERROR, ConnectionState.DISCONNECTED -> firstError
        }
    }

    private fun looksLikeTls(error: String?): Boolean {
        val e = error?.lowercase() ?: return false
        return e.contains("tls") || e.contains("ssl") || e.contains("certificate") || e.contains("cert ")
    }

    private fun summary(
        state: TrackingState,
        connection: ConnectionState,
        connectedCount: Int,
        meshActive: Boolean,
        motionLabel: String,
        rep: ReportingSnapshot,
        nowMs: Long,
    ): String {
        val parts = mutableListOf<String>()
        parts += when {
            connectedCount > 0 -> "Connected"
            meshActive -> "Mesh SA"
            connection == ConnectionState.RECONNECTING -> "Reconnecting"
            connection == ConnectionState.CONNECTING -> "Connecting"
            connection == ConnectionState.NO_NETWORK -> "No network"
            connection == ConnectionState.CIRCUIT_OPEN -> "Connection paused"
            connection == ConnectionState.NO_SERVER -> "No server"
            else -> "Disconnected"
        }
        when (state) {
            TrackingState.PAUSED -> parts += "Reports paused"
            TrackingState.ATAK_DEFERRED -> parts += "ATAK owns presence"
            TrackingState.LOCATION_PERMISSION_REQUIRED -> parts += "Grant location permission"
            TrackingState.WAITING_FOR_LOCATION -> parts += "Waiting for GPS"
            else -> {
                parts += motionLabel
                if (rep.strategy.equals("Constant", ignoreCase = true)) {
                    parts += "Constant ${DurationFormat.interval(rep.intervalSeconds)}"
                } else if (rep.motion == MotionState.STATIONARY) {
                    if (rep.batteryStretched) parts += "Battery Saver"
                    parts += "Next report ~${DurationFormat.countdown(rep.nextDueEpochMs, nowMs)}"
                } else {
                    parts += "Dynamic ${DurationFormat.interval(rep.intervalSeconds)}"
                    if (rep.batteryStretched) parts += "Battery Saver"
                }
            }
        }
        return parts.joinToString(" · ")
    }

    private fun motionLabel(motion: MotionState, fix: GpsFix?): String = when (motion) {
        MotionState.UNKNOWN -> if (fix == null) "Acquiring" else "Settling"
        MotionState.STATIONARY -> "Stationary"
        MotionState.WALKING -> "Walking"
        MotionState.DRIVING -> "Driving"
    }

    private fun gpsLabel(inputs: StatusInputs): String {
        if (!inputs.locationPermissionGranted) return "Permission required"
        if (!inputs.gpsSampling) {
            return when (inputs.reporting.suppressed) {
                SuppressReason.PAUSED -> "Off (paused)"
                SuppressReason.ATAK_DEFER -> "Off (ATAK active)"
                else -> "Off"
            }
        }
        if (!inputs.adaptGpsToMotion) return "High accuracy (always)"
        return when (inputs.reporting.gpsDuty) {
            GpsDuty.HIGH -> "High accuracy"
            GpsDuty.BALANCED -> "Balanced power"
            GpsDuty.LOW -> "Low power"
        }
    }

    private fun reportingLabel(rep: ReportingSnapshot): String = when (rep.suppressed) {
        SuppressReason.PAUSED -> "Paused"
        SuppressReason.ATAK_DEFER -> "Deferred to ATAK"
        SuppressReason.NO_PATH -> "No connection"
        SuppressReason.NONE -> {
            if (rep.intervalSeconds <= 0L) "—"
            else "Every ${DurationFormat.interval(rep.intervalSeconds)}" +
                if (rep.strategy.equals("Constant", ignoreCase = true)) " (constant)" else ""
        }
    }

    private fun locationQuality(inputs: StatusInputs): LocationQuality {
        if (!inputs.locationPermissionGranted) return LocationQuality.PERMISSION_REQUIRED
        val fix = inputs.fix ?: return LocationQuality.WAITING
        return when {
            fix.source == GpsSourceKind.NONE -> LocationQuality.WAITING
            fix.source == GpsSourceKind.NETWORK_IP -> LocationQuality.NETWORK_IP
            fix.isHeld || fix.source == GpsSourceKind.HELD -> LocationQuality.HELD
            inputs.reporting.gpsDuty == GpsDuty.HIGH &&
                (fix.accuracyMeters ?: Double.MAX_VALUE) <= GPS_ACCURACY_METERS -> LocationQuality.GPS
            else -> LocationQuality.FUSED
        }
    }

    private fun locationLine(q: LocationQuality, fix: GpsFix?, nowMs: Long): String {
        val age = fix?.let { DurationFormat.age(it.timestamp.toEpochMilli(), nowMs) }
        val acc = fix?.accuracyMeters?.let { accuracyLabel(it) }
        fun join(vararg p: String?) = p.filterNotNull().joinToString(" · ")
        return when (q) {
            LocationQuality.PERMISSION_REQUIRED -> "Location permission required"
            LocationQuality.WAITING -> "Waiting for location"
            LocationQuality.GPS -> join("GPS", acc, ageOld(age))
            LocationQuality.FUSED -> join("Fused", acc, ageOld(age))
            LocationQuality.HELD -> join("Held fix", acc, ageOld(age))
            LocationQuality.NETWORK_IP -> join("Network/IP estimate", acc, ageOld(age))
        }
    }

    private fun ageOld(age: String?): String? = when {
        age == null -> null
        age == "Just now" -> age
        age.endsWith(" ago") -> age.removeSuffix(" ago") + " old"
        else -> age
    }

    private fun accuracyLabel(meters: Double): String = when {
        meters.isNaN() || meters <= 0.0 -> "±?"
        meters >= 1_000.0 -> "±${"%.0f".format(meters / 1_000.0)} km"
        else -> "±${"%.0f".format(meters)} m"
    }

    private fun batteryLabel(percent: Int?, charging: Boolean): String {
        if (percent == null) return "Unknown"
        return if (charging) "$percent% · Charging" else "$percent%"
    }
}
