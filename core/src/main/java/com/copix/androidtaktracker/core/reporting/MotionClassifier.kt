package com.copix.androidtaktracker.core.reporting

import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.cot.GpsSourceKind

/** Operator motion as the tracker currently understands it. */
enum class MotionState {
    /** No usable fix yet, or settling after start. */
    UNKNOWN,
    /** Anchored: has not relocated [MotionPolicy.DEFAULT_SIGNIFICANT_MOVE_METERS]+ since settling. */
    STATIONARY,
    /** Relocating on foot / slow (below [MotionPolicy.WALKING_SPEED_MPH]). */
    WALKING,
    /** Vehicle speeds (at or above [MotionPolicy.WALKING_SPEED_MPH]). */
    DRIVING,
}

data class MotionDecision(
    val state: MotionState,
    /** True on the tick where an anchored (stationary) operator started moving again. */
    val transitionedToMoving: Boolean,
    val gpsDuty: GpsDuty,
    /** Displacement from the stationary anchor; null when not anchored. */
    val metersFromAnchor: Double?,
)

/**
 * Displacement-anchored motion state machine shared by Dynamic CoT and adaptive GPS.
 *
 * Speed alone is a poor stationary detector (GPS jitter reads 0–3 mph while parked) and a
 * poor movement detector (balanced-power fixes often carry no speed at all). So:
 *
 * - We become STATIONARY only after failing to relocate [significantMoveMeters] for
 *   [stationaryHoldMs] (longer after driving so a stoplight does not drop GNSS).
 * - Once anchored we stay STATIONARY until displacement from the anchor exceeds the
 *   threshold or GPS reports vehicle speed — either one is a stationary → moving
 *   transition that the reporting engine turns into an ASAP send and a HIGH GPS duty.
 * - While moving, speed picks WALKING vs DRIVING; HIGH GPS duty is held for
 *   [gpsDowngradeHoldMs] after the last vehicle-speed sample.
 */
class MotionClassifier(
    private val stationaryHoldMs: Long = MotionPolicy.STATIONARY_HOLD_MS,
    private val drivingStopHoldMs: Long = MotionPolicy.DRIVING_STOP_HOLD_MS,
    private val gpsDowngradeHoldMs: Long = MotionPolicy.GPS_DOWNGRADE_HOLD_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var state: MotionState = MotionState.UNKNOWN
        private set

    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN

    private var candidateLat = Double.NaN
    private var candidateLon = Double.NaN
    private var candidateSinceMs = -1L
    private var candidateFromDriving = false

    private var lastHighSpeedMs = Long.MIN_VALUE / 2
    private var duty = GpsDuty.HIGH

    fun reset() {
        state = MotionState.UNKNOWN
        clearAnchor()
        clearCandidate()
        lastHighSpeedMs = Long.MIN_VALUE / 2
        duty = GpsDuty.HIGH
    }

    fun observe(fix: GpsFix?, significantMoveMeters: Double = MotionPolicy.DEFAULT_SIGNIFICANT_MOVE_METERS): MotionDecision {
        val now = clock()
        val threshold = MotionPolicy.effectiveSignificantMove(significantMoveMeters)

        if (fix == null || fix.isHeld || fix.source == GpsSourceKind.NETWORK_IP || fix.source == GpsSourceKind.NONE) {
            // No fresh position: keep whatever we knew. Stationary stays cheap; anything
            // else keeps GNSS up so we can find the operator again.
            duty = if (state == MotionState.STATIONARY) GpsDuty.LOW else GpsDuty.HIGH
            return MotionDecision(state, false, duty, anchorDistance(fix))
        }

        val speed = MotionPolicy.sanitizeSpeedMph(fix.speedMph)
        val vehicleSpeed = speed >= MotionPolicy.WALKING_SPEED_MPH
        if (vehicleSpeed) lastHighSpeedMs = now

        var transitioned = false
        if (isAnchored()) {
            val moved = MotionPolicy.haversineMeters(anchorLat, anchorLon, fix.latitude, fix.longitude)
            if (moved >= threshold || vehicleSpeed) {
                transitioned = true
                clearAnchor()
                clearCandidate()
                // Snap GNSS back on so the next samples carry a real speed.
                lastHighSpeedMs = now
                state = if (vehicleSpeed) MotionState.DRIVING else MotionState.WALKING
            } else {
                state = MotionState.STATIONARY
                duty = GpsDuty.LOW
                return MotionDecision(state, false, duty, moved)
            }
        } else {
            trackSettling(fix, now, threshold, vehicleSpeed)
            if (isAnchored()) {
                state = MotionState.STATIONARY
                duty = GpsDuty.LOW
                return MotionDecision(state, false, duty, 0.0)
            }
            val slow = speed < MotionPolicy.STATIONARY_SPEED_MPH
            state = when {
                vehicleSpeed -> MotionState.DRIVING
                // Settling (stoplight, pausing on foot): keep the last known motion until anchored.
                slow && state != MotionState.UNKNOWN && state != MotionState.STATIONARY -> state
                slow -> MotionState.UNKNOWN
                state == MotionState.DRIVING && now - lastHighSpeedMs < gpsDowngradeHoldMs -> MotionState.DRIVING
                else -> MotionState.WALKING
            }
        }

        duty = when (state) {
            MotionState.DRIVING, MotionState.UNKNOWN -> GpsDuty.HIGH
            MotionState.WALKING ->
                if (now - lastHighSpeedMs < gpsDowngradeHoldMs) GpsDuty.HIGH else GpsDuty.BALANCED
            MotionState.STATIONARY -> GpsDuty.LOW
        }
        return MotionDecision(state, transitioned, duty, null)
    }

    /**
     * Settling detector: the candidate position is where we last "were"; every time we move
     * [threshold]+ from it (or hit vehicle speed) it resets. If it survives the hold window,
     * it becomes the stationary anchor.
     */
    private fun trackSettling(fix: GpsFix, now: Long, threshold: Double, vehicleSpeed: Boolean) {
        if (vehicleSpeed) {
            clearCandidate()
            return
        }
        if (candidateSinceMs < 0L) {
            startCandidate(fix, now)
            return
        }
        val moved = MotionPolicy.haversineMeters(candidateLat, candidateLon, fix.latitude, fix.longitude)
        if (moved >= threshold) {
            startCandidate(fix, now)
            return
        }
        val hold = if (candidateFromDriving) drivingStopHoldMs else stationaryHoldMs
        if (now - candidateSinceMs >= hold) {
            anchorLat = candidateLat
            anchorLon = candidateLon
            clearCandidate()
        }
    }

    private fun startCandidate(fix: GpsFix, now: Long) {
        candidateLat = fix.latitude
        candidateLon = fix.longitude
        candidateSinceMs = now
        candidateFromDriving = state == MotionState.DRIVING
    }

    private fun clearCandidate() {
        candidateLat = Double.NaN
        candidateLon = Double.NaN
        candidateSinceMs = -1L
        candidateFromDriving = false
    }

    private fun clearAnchor() {
        anchorLat = Double.NaN
        anchorLon = Double.NaN
    }

    private fun isAnchored(): Boolean = !anchorLat.isNaN() && !anchorLon.isNaN()

    private fun anchorDistance(fix: GpsFix?): Double? {
        if (!isAnchored()) return null
        if (fix == null) return 0.0
        return MotionPolicy.haversineMeters(anchorLat, anchorLon, fix.latitude, fix.longitude)
    }
}
