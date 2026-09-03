package com.copix.androidtaktracker.core.status

import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.cot.GpsSourceKind
import com.copix.androidtaktracker.core.reporting.GpsDuty
import com.copix.androidtaktracker.core.reporting.MotionState
import com.copix.androidtaktracker.core.reporting.ReportingSnapshot
import com.copix.androidtaktracker.core.reporting.SuppressReason
import com.copix.androidtaktracker.core.tak.ServerConnectionStatus
import com.copix.androidtaktracker.core.tak.TakConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TrackingStatusMapperTest {

    private val now = 1_700_000_000_000L

    private fun server(
        state: TakConnectionState,
        error: String? = null,
        suspended: Boolean = false,
        enabled: Boolean = true,
    ) = ServerConnectionStatus(
        profileId = "p1",
        displayName = "Server",
        enabled = enabled,
        state = state,
        lastErrorCode = error,
        autoReconnectSuspended = suspended,
    )

    private fun fix(ageMs: Long = 4_000, accuracy: Double = 6.0, source: GpsSourceKind = GpsSourceKind.FUSED, held: Boolean = false) =
        GpsFix(
            latitude = 38.0,
            longitude = -77.0,
            accuracyMeters = accuracy,
            timestamp = Instant.ofEpochMilli(now - ageMs),
            source = source,
            isHeld = held,
        )

    private fun inputs(
        paused: Boolean = false,
        deferring: Boolean = false,
        servers: List<ServerConnectionStatus> = listOf(server(TakConnectionState.CONNECTED)),
        fix: GpsFix? = fix(),
        reporting: ReportingSnapshot = ReportingSnapshot(
            motion = MotionState.DRIVING,
            intervalSeconds = 5,
            staleSeconds = 90,
            lastPliEpochMs = now - 8_000,
            nextDueEpochMs = now - 3_000,
            gpsDuty = GpsDuty.HIGH,
            takConnected = true,
        ),
        gpsSampling: Boolean = true,
        adapt: Boolean = true,
        network: Boolean = true,
        permission: Boolean = true,
        battery: Int? = 74,
        charging: Boolean = false,
        exempt: Boolean? = true,
    ) = StatusInputs(
        callsign = "CALLSIGN",
        paused = paused,
        deferringToAtak = deferring,
        servers = servers,
        fix = fix,
        reporting = reporting,
        gpsSampling = gpsSampling,
        adaptGpsToMotion = adapt,
        networkAvailable = network,
        locationPermissionGranted = permission,
        batteryPercent = battery,
        charging = charging,
        batteryOptimizationExempt = exempt,
    )

    @Test
    fun `connected and driving reads as TRACKING with a dynamic 5 sec summary`() {
        val s = TrackingStatusMapper.map(inputs(), now)
        assertEquals(TrackingState.TRACKING, s.state)
        assertEquals("TRACKING", s.headline)
        assertEquals("CALLSIGN", s.callsign)
        assertEquals("Connected · Driving · Dynamic 5 sec", s.summary)
        assertEquals(ConnectionState.CONNECTED, s.connection)
        assertEquals("TAK Server · Connected", s.connectionTitle)
        assertEquals("Last report · 8 sec ago", s.connectionDetail)
        assertEquals(LocationQuality.GPS, s.location)
        assertEquals("GPS · ±6 m · 4 sec old", s.locationLine)
        assertEquals("Driving", s.motionLabel)
        assertEquals("High accuracy", s.gpsLabel)
        assertEquals("Every 5 sec", s.reportingLabel)
        assertEquals("Now", s.nextReportLabel)
        assertEquals("74%", s.batteryLabel)
        assertEquals("Exempt", s.batteryOptimizationLabel)
        assertFalse(s.batteryWarning)
    }

    @Test
    fun `stationary battery saver summary shows the countdown`() {
        val rep = ReportingSnapshot(
            motion = MotionState.STATIONARY,
            intervalSeconds = 270,
            staleSeconds = 555,
            batteryStretched = true,
            lastPliEpochMs = now - 135_000,
            nextDueEpochMs = now + 135_000,
            gpsDuty = GpsDuty.LOW,
            takConnected = true,
        )
        val s = TrackingStatusMapper.map(inputs(reporting = rep, battery = 18), now)
        assertEquals(TrackingState.TRACKING, s.state)
        assertEquals("Connected · Stationary · Battery Saver · Next report ~2m 15s", s.summary)
        assertEquals("Stationary", s.motionLabel)
        assertEquals("Low power", s.gpsLabel)
        assertEquals("Every 4m 30s", s.reportingLabel)
        assertEquals("2m 15s", s.nextReportLabel)
        assertEquals("2m 15s ago", s.lastReportLabel)
        assertEquals(LocationQuality.FUSED, s.location)
    }

    @Test
    fun `paused and ATAK deferred take precedence over connection`() {
        val paused = TrackingStatusMapper.map(
            inputs(paused = true, gpsSampling = false, reporting = ReportingSnapshot(suppressed = SuppressReason.PAUSED)),
            now,
        )
        assertEquals(TrackingState.PAUSED, paused.state)
        assertEquals("PAUSED", paused.headline)
        assertEquals("Off (paused)", paused.gpsLabel)
        assertEquals("Paused", paused.reportingLabel)
        assertTrue(paused.summary.contains("Reports paused"))

        val deferred = TrackingStatusMapper.map(
            inputs(deferring = true, gpsSampling = false, reporting = ReportingSnapshot(suppressed = SuppressReason.ATAK_DEFER)),
            now,
        )
        assertEquals(TrackingState.ATAK_DEFERRED, deferred.state)
        assertEquals("ATAK ACTIVE — TRACKER DEFERRED", deferred.headline)
        assertEquals("Off (ATAK active)", deferred.gpsLabel)
        assertEquals("Deferred to ATAK", deferred.reportingLabel)
    }

    @Test
    fun `waiting for location when connected but no fix`() {
        val s = TrackingStatusMapper.map(inputs(fix = null, reporting = ReportingSnapshot(takConnected = true)), now)
        assertEquals(TrackingState.WAITING_FOR_LOCATION, s.state)
        assertEquals(LocationQuality.WAITING, s.location)
        assertEquals("Waiting for location", s.locationLine)
        assertEquals("Never", s.lastLocationLabel)
        assertEquals("Acquiring", s.motionLabel)
        assertEquals(ConnectionState.CONNECTED_WAITING_FIRST_PLI, s.connection)
        assertEquals("Waiting for first position report", s.connectionDetail)
    }

    @Test
    fun `disconnected states are explained rather than a generic red label`() {
        val reconnecting = TrackingStatusMapper.map(
            inputs(servers = listOf(server(TakConnectionState.RECONNECTING, "Connection timed out"))),
            now,
        )
        assertEquals(TrackingState.TAK_DISCONNECTED, reconnecting.state)
        assertEquals(ConnectionState.RECONNECTING, reconnecting.connection)
        assertEquals("TAK Server · Reconnecting", reconnecting.connectionTitle)
        assertEquals("Connection timed out", reconnecting.connectionDetail)

        val noNetwork = TrackingStatusMapper.map(
            inputs(servers = listOf(server(TakConnectionState.DISCONNECTED)), network = false),
            now,
        )
        assertEquals(ConnectionState.NO_NETWORK, noNetwork.connection)
        assertEquals("No network", noNetwork.connectionTitle)

        val tripped = TrackingStatusMapper.map(
            inputs(
                servers = listOf(
                    server(
                        TakConnectionState.ERROR,
                        "TLS handshake failed — stopped auto-reconnect after 5 failures to avoid infra-TAK fail2ban",
                        suspended = true,
                    ),
                ),
            ),
            now,
        )
        assertEquals(ConnectionState.CIRCUIT_OPEN, tripped.connection)
        assertEquals("TAK Server · Connection paused", tripped.connectionTitle)
        assertEquals(
            "Connection paused after repeated TLS failures — use Test after correcting server settings",
            tripped.connectionDetail,
        )

        val trippedNet = TrackingStatusMapper.map(
            inputs(servers = listOf(server(TakConnectionState.ERROR, "Connection refused — stopped auto-reconnect", suspended = true))),
            now,
        )
        assertTrue(trippedNet.connectionDetail!!.contains("repeated connection failures"))
    }

    @Test
    fun `no server configured and location permission missing`() {
        val none = TrackingStatusMapper.map(inputs(servers = emptyList(), reporting = ReportingSnapshot()), now)
        assertEquals(TrackingState.NO_SERVER_CONFIGURED, none.state)
        assertEquals(ConnectionState.NO_SERVER, none.connection)
        assertEquals("No TAK server configured", none.connectionTitle)

        val noPerm = TrackingStatusMapper.map(inputs(permission = false), now)
        assertEquals(TrackingState.LOCATION_PERMISSION_REQUIRED, noPerm.state)
        assertEquals(LocationQuality.PERMISSION_REQUIRED, noPerm.location)
        assertEquals("Permission required", noPerm.gpsLabel)
    }

    @Test
    fun `mesh only path still counts as tracking`() {
        val rep = ReportingSnapshot(motion = MotionState.WALKING, intervalSeconds = 30, staleSeconds = 90, meshActive = true, lastPliEpochMs = now - 1_000, nextDueEpochMs = now + 29_000)
        val s = TrackingStatusMapper.map(inputs(servers = emptyList(), reporting = rep), now)
        assertEquals(TrackingState.TRACKING, s.state)
        assertEquals("Mesh SA · Walking · Dynamic 30 sec", s.summary)
    }

    @Test
    fun `constant strategy summary and label`() {
        val rep = ReportingSnapshot(strategy = "Constant", motion = MotionState.STATIONARY, intervalSeconds = 10, staleSeconds = 35, lastPliEpochMs = now - 2_000, nextDueEpochMs = now + 8_000, takConnected = true)
        val s = TrackingStatusMapper.map(inputs(reporting = rep), now)
        assertEquals("Connected · Stationary · Constant 10 sec", s.summary)
        assertEquals("Every 10 sec (constant)", s.reportingLabel)
    }

    @Test
    fun `location quality distinguishes held and network estimates`() {
        val held = TrackingStatusMapper.map(inputs(fix = fix(ageMs = 65_000, accuracy = 12.0, held = true)), now)
        assertEquals(LocationQuality.HELD, held.location)
        assertEquals("Held fix · ±12 m · 1m 05s old", held.locationLine)

        val ip = TrackingStatusMapper.map(inputs(fix = fix(accuracy = 5_000.0, source = GpsSourceKind.NETWORK_IP)), now)
        assertEquals(LocationQuality.NETWORK_IP, ip.location)
        assertTrue(ip.locationLine.startsWith("Network/IP estimate · ±5 km"))

        val fusedByAccuracy = TrackingStatusMapper.map(inputs(fix = fix(accuracy = 80.0)), now)
        assertEquals(LocationQuality.FUSED, fusedByAccuracy.location)
    }

    @Test
    fun `battery optimization warning only when the exemption is missing`() {
        val missing = TrackingStatusMapper.map(inputs(exempt = false, battery = 40, charging = true), now)
        assertTrue(missing.batteryWarning)
        assertEquals("Android may restrict background tracking", missing.batteryOptimizationLabel)
        assertEquals("40% · Charging", missing.batteryLabel)

        val unknown = TrackingStatusMapper.map(inputs(exempt = null, battery = null), now)
        assertFalse(unknown.batteryWarning)
        assertEquals("Unknown", unknown.batteryOptimizationLabel)
        assertEquals("Unknown", unknown.batteryLabel)
    }

    @Test
    fun `duration formatting`() {
        assertEquals("Never", DurationFormat.age(0, now))
        assertEquals("Just now", DurationFormat.age(now - 200, now))
        assertEquals("12 sec ago", DurationFormat.age(now - 12_000, now))
        assertEquals("2m 14s", DurationFormat.countdown(now + 134_000, now))
        assertEquals("Now", DurationFormat.countdown(now - 1, now))
        assertEquals("—", DurationFormat.countdown(0, now))
        assertEquals("1h 05m", DurationFormat.compact(65 * 60_000L))
        assertEquals("3 min", DurationFormat.interval(180))
        assertEquals("45 sec", DurationFormat.interval(45))
    }
}
