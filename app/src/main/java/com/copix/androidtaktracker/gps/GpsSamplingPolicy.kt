package com.copix.androidtaktracker.gps

/**
 * Whether the fused provider should be running at all. GNSS is the dominant battery cost,
 * and while CoT is suppressed (operator pause, MDM remote pause, ATAK owning presence) a
 * fix would only be thrown away.
 */
object GpsSamplingPolicy {
    fun shouldSample(paused: Boolean, remotePauseRequested: Boolean, deferringToAtak: Boolean): Boolean {
        if (paused || remotePauseRequested) return false
        if (deferringToAtak) return false
        return true
    }
}
