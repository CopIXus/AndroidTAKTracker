package com.copix.androidtaktracker.core.cot

import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.config.ServerProfile
import com.copix.androidtaktracker.core.identity.ActiveIdentity
import com.copix.androidtaktracker.core.identity.IdentityResolver
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Resolved CoT identity ready to serialize — callsign/team/role plus optional device metadata. */
data class CotIdentity(
    val uid: String,
    val callsign: String,
    val team: String,
    val role: String,
    val cotType: String,
    /** Optional; emitted as contact@phone only when non-blank. */
    val phone: String? = null,
    /** Optional remarks text (e.g. device model when callsign differs). */
    val remarks: String? = null,
    val platform: String = "AndroidTAKTracker",
    val version: String = "0.1.0",
    val batteryPercent: Int? = null,
)

/**
 * Builds ATAK-shaped self-SA CoT XML: contact@callsign + endpoint=*:-1:stcp, uid@Droid,
 * __group, track, takv platform=AndroidTAKTracker, precisionlocation, optional remarks,
 * status@battery. Mirrors WinTAKTracker's CotEventBuilder shape byte-for-byte where possible so
 * TAK Server / Portal treat both clients identically.
 */
object CotEventBuilder {
    const val GROUND_UNIT_TYPE = "a-f-G-U-C-I"
    const val VEHICLE_TYPE = "a-f-G-E-V"

    private val TAK_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    private val fallbackUid: String by lazy {
        "ANDROIDTAKTRACKER-" + UUID.randomUUID().toString().replace("-", "").take(12)
    }

    /**
     * Build a full CoT `<event>` XML string (including trailing newline, matching the
     * TAK streaming protocol's one-event-per-line convention).
     */
    fun build(
        fix: GpsFix,
        identity: CotIdentity,
        staleDuration: java.time.Duration,
        courseOffsetDegrees: Double = 0.0,
        deviceModel: String? = null,
        osVersion: String? = null,
    ): String {
        val now = Instant.now()
        val time = fix.timestamp
        val staleTime = now.plus(staleDuration)
        // Network IP and held fixes are approximate — use estimated how + large CE.
        val how = if (fix.isHeld || fix.source == GpsSourceKind.NETWORK_IP) "h-e" else "m-g"
        val ce = fix.accuracyMeters ?: fix.hdop?.times(5) ?: 9_999_999.0
        val le = fix.accuracyMeters ?: 9_999_999.0
        val hae = fix.altitudeMeters ?: 0.0
        val speed = fix.speedMetersPerSecond ?: 0.0
        val course = normalizeCourse((fix.courseDegrees ?: 0.0) + courseOffsetDegrees)
        val approximate = fix.isHeld || fix.source == GpsSourceKind.NETWORK_IP

        val sb = StringBuilder(512)
        sb.append("<event version=\"2.0\" uid=\"").append(escapeXml(identity.uid))
            .append("\" type=\"").append(escapeXml(identity.cotType))
            .append("\" how=\"").append(how)
            .append("\" time=\"").append(formatTakTime(time))
            .append("\" start=\"").append(formatTakTime(now))
            .append("\" stale=\"").append(formatTakTime(staleTime))
            .append("\">")
        sb.append("<point lat=\"").append(f(fix.latitude))
            .append("\" lon=\"").append(f(fix.longitude))
            .append("\" hae=\"").append(f(hae))
            .append("\" ce=\"").append(f(ce))
            .append("\" le=\"").append(f(le))
            .append("\"/>")
        sb.append("<detail>")

        // ATAK self-SA contact — endpoint=*:-1:stcp matches ATAK; servers index contacts by it.
        sb.append("<contact callsign=\"").append(escapeXml(identity.callsign)).append("\" endpoint=\"*:-1:stcp\"")
        val phone = identity.phone
        if (!phone.isNullOrBlank()) sb.append(" phone=\"").append(escapeXml(phone.trim())).append("\"")
        sb.append("/>")

        sb.append("<uid Droid=\"").append(escapeXml(identity.callsign)).append("\"/>")
        sb.append("<precisionlocation altsrc=\"").append(if (approximate) "DTED0" else "GPS")
            .append("\" geopointsrc=\"").append(if (approximate) "USER" else "GPS").append("\"/>")
        sb.append("<__group name=\"").append(escapeXml(identity.team))
            .append("\" role=\"").append(escapeXml(identity.role)).append("\"/>")
        sb.append("<track speed=\"").append(f(speed)).append("\" course=\"").append(f(course)).append("\"/>")
        sb.append("<takv platform=\"").append(escapeXml(identity.platform))
            .append("\" version=\"").append(escapeXml(identity.version))
            .append("\" device=\"").append(escapeXml(deviceModel ?: ""))
            .append("\" os=\"").append(escapeXml(osVersion ?: "")).append("\"/>")

        val remarks = identity.remarks
        if (!remarks.isNullOrBlank())
            sb.append("<remarks>").append(escapeXml(remarks.trim())).append("</remarks>")

        val battery = identity.batteryPercent
        if (battery != null)
            sb.append("<status battery=\"").append(battery).append("\"/>")

        sb.append("</detail>")
        sb.append("</event>\n")
        return sb.toString()
    }

    /** Normalize compass degrees into [0, 360). */
    fun normalizeCourse(degrees: Double): Double {
        var d = degrees % 360
        if (d < 0) d += 360
        return d
    }

    fun fromConfig(
        config: AppConfig,
        server: ServerProfile? = null,
        battery: Int? = null,
        deviceModel: String? = null,
        deviceName: String? = null,
    ): CotIdentity {
        val active = IdentityResolver.resolve(config, deviceName ?: deviceModel ?: "ANDROID-TRACKER")
        return fromActiveIdentity(config, active, server, battery, deviceModel)
    }

    fun fromActiveIdentity(
        config: AppConfig,
        active: ActiveIdentity,
        server: ServerProfile? = null,
        battery: Int? = null,
        deviceModel: String? = null,
    ): CotIdentity {
        val uid = config.deviceUid?.takeIf { it.isNotBlank() } ?: fallbackUid
        val callsign = server?.callsignOverride?.takeIf { it.isNotBlank() } ?: active.callsign
        return CotIdentity(
            uid = uid,
            callsign = callsign,
            team = server?.teamOverride?.takeIf { it.isNotBlank() } ?: active.team,
            role = server?.roleOverride?.takeIf { it.isNotBlank() } ?: active.role,
            cotType = active.cotType.ifBlank { GROUND_UNIT_TYPE },
            phone = active.phone.takeIf { it.isNotBlank() }?.trim(),
            remarks = buildDeviceNameRemarks(config, callsign, deviceModel),
            batteryPercent = battery,
        )
    }

    /**
     * When enabled and the PLI callsign is not the device model, note the model in remarks.
     * Prefixed so TAK Portal / Server do not treat it as the callsign.
     */
    internal fun buildDeviceNameRemarks(config: AppConfig, callsign: String, deviceModel: String?): String? {
        if (!config.reporting.includeDeviceNameInRemarks) return null
        val model = deviceModel?.trim().orEmpty()
        if (model.isEmpty()) return null
        if (callsign.trim().equals(model, ignoreCase = true)) return null
        return "Device: $model"
    }

    internal fun f(v: Double): String {
        val value = if (v.isNaN() || v.isInfinite()) 0.0 else v
        var s = String.format(Locale.ROOT, "%.7f", value)
        if (s.contains('.')) {
            s = s.trimEnd('0').trimEnd('.')
        }
        if (s.isEmpty() || s == "-0") s = "0"
        return s
    }

    internal fun formatTakTime(instant: Instant): String = TAK_TIME_FORMATTER.format(instant)

    /** Escapes the five XML predefined entities for safe use in attribute values and text nodes. */
    fun escapeXml(value: String): String {
        if (value.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return value
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
