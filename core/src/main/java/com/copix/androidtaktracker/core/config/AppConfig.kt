package com.copix.androidtaktracker.core.config

import kotlinx.serialization.Serializable

/**
 * Application configuration, mirrors WinTAKTracker's AppConfig shape for a single-user Android
 * device (no per-Windows-user SID map — one interactive user per phone/tablet).
 * Secrets (server tokens/passwords, cert passphrases) never live in this object — see
 * [EncryptedSecretStore] and [ConfigStore.writeSecret].
 */
@Serializable
data class AppConfig(
    var version: Int = CURRENT_VERSION,
    /** Device-level CoT identity used when no per-user callsign is set. */
    var deviceIdentity: IdentitySettings = IdentitySettings(),
    /** The single interactive user's CoT identity (overrides deviceIdentity when set). */
    var userIdentity: UserIdentitySettings = UserIdentitySettings(),
    var gps: GpsSettings = GpsSettings(),
    var reporting: ReportingSettings = ReportingSettings(),
    var meshSa: MeshSaSettings = MeshSaSettings(),
    var startup: StartupSettings = StartupSettings(),
    var updates: UpdateSettings = UpdateSettings(),
    var diagnostics: DiagnosticsSettings = DiagnosticsSettings(),
    /** ATAK co-existence behavior — see [AtakSettings]. */
    var atak: AtakSettings = AtakSettings(),
    /** Server profiles (hosts/ports only in cleartext; credentials live in [EncryptedSecretStore]). */
    var servers: MutableList<ServerProfile> = mutableListOf(),
    /** Stable CoT UID for this device (generated once, e.g. ANDROIDTAKTRACKER-xxxxxxxxxxxx). */
    var deviceUid: String? = null,
    /**
     * When true (default), apply callsign/team/role from Portal / device-profile sync.
     * Disable to keep the locally-set callsign authoritative.
     */
    var applyRemoteIdentityFromPortal: Boolean = true,
) {
    companion object {
        const val CURRENT_VERSION = 3
    }

    /**
     * Migrate older configs and ensure the device callsign defaults to [deviceName] when unset.
     * Call after every load and before every save.
     */
    fun ensureIdentityDefaults(deviceName: String) {
        if (version < CURRENT_VERSION) {
            version = CURRENT_VERSION
        }
        val current = deviceIdentity.callsign.trim()
        if (current.isEmpty()) {
            deviceIdentity.callsign = deviceName
        }
    }
}

/** Device-level or shared identity fields. */
@Serializable
data class IdentitySettings(
    /** Empty means resolve to the device name at runtime / first persist. */
    var callsign: String = "",
    var team: String = "Cyan",
    var role: String = "Team Member",
    /** CoT type, e.g. a-f-G-U-C-I (Ground Unit) or a-f-G-E-V (Vehicle). */
    var cotType: String = "a-f-G-U-C-I",
    /** Optional phone for ATAK contact Call (detail/contact@phone). Empty = omit from CoT. */
    var phone: String = "",
) {
    /** Effective callsign for CoT/UI — [deviceName] when unset. */
    fun getEffectiveCallsign(deviceName: String): String =
        if (callsign.isBlank()) deviceName else callsign.trim()
}

/** The single interactive user's CoT identity (Android has one active user, not a SID map). */
@Serializable
data class UserIdentitySettings(
    var callsign: String = "",
    var team: String = "",
    var role: String = "",
    var cotType: String = "",
    /** Optional phone for ATAK contact Call (detail/contact@phone). Empty = omit from CoT. */
    var phone: String = "",
    /** True when the user dismissed the first-login callsign prompt without saving. */
    var setupPromptDismissed: Boolean = false,
) {
    val hasCallsign: Boolean get() = callsign.isNotBlank()
}

@Serializable
data class GpsSettings(
    /** FusedOnly | FusedThenNetwork | NetworkOnly */
    var sourcePriority: String = "FusedOnly",
    var lastFixHoldSeconds: Int = 30,
    /**
     * When Fused location has no fix, use approximate IP geolocation.
     * Default false for new configs (coarse; opt-in).
     */
    var enableNetworkFallback: Boolean = false,
    /** Degrees added to GPS course for PLI track@course. */
    var courseOffsetDegrees: Double = 0.0,
    /** FusedLocationProviderClient update interval, milliseconds. */
    var minIntervalMs: Long = 2_000,
    /** FusedLocationProviderClient minimum update distance, meters (0 = time-based only). */
    var minDistanceMeters: Float = 0f,
)

@Serializable
data class ReportingSettings(
    /** Dynamic | Constant */
    var strategy: String = "Dynamic",
    var reliableStationarySeconds: Int = 180,
    var unreliableStationarySeconds: Int = 30,
    var reliableMinSeconds: Int = 5,
    var reliableMaxMoveSeconds: Int = 20,
    var unreliableMinSeconds: Int = 5,
    var unreliableMaxMoveSeconds: Int = 20,
    var constantIntervalSeconds: Int = 10,
    /**
     * When true, add "Device: {model}" to CoT detail/remarks so peers can see which device
     * reported (not a bare model string — some Portal UIs mis-read bare remarks as callsign).
     */
    var includeDeviceNameInRemarks: Boolean = false,
)

@Serializable
data class MeshSaSettings(
    var enabled: Boolean = true,
    /** Always | OnlyWhenDisconnected — new installs default to OnlyWhenDisconnected. */
    var mode: String = "OnlyWhenDisconnected",
    var multicastAddress: String = "239.2.3.1",
    var multicastPort: Int = 6969,
    /** Auto | an explicit network interface display name. */
    var networkInterface: String = "Auto",
)

@Serializable
data class StartupSettings(
    /** Start tracking automatically after BOOT_COMPLETED. Android default: on. */
    var startOnBoot: Boolean = true,
    /** Hold a partial wake lock while actively tracking (foreground service). */
    var preventSleepWhileTracking: Boolean = false,
)

@Serializable
data class UpdateSettings(
    var automaticallyDownloadAndInstall: Boolean = false,
    var releasesApiUrl: String = "https://api.github.com/repos/CopIXus/AndroidTAKTracker/releases/latest",
    /** UTC timestamp of the last successful or failed update check (ISO 8601). */
    var lastCheckedUtc: String? = null,
    /** Newest version reported by the last successful check when an update was available. */
    var lastAvailableVersion: String? = null,
)

@Serializable
data class DiagnosticsSettings(
    /** Minimum log level written to disk. Default Error keeps devices quiet. */
    var logLevel: String = "Error",
    /** Max total size of rotated log files in megabytes (trim oldest / truncate). */
    var maxLogSizeMb: Int = 30,
    /**
     * When false (default), TLS soft-accept is disabled: trust-store validation must succeed
     * or the connection is rejected. When true, soft-accept with a warn log (lab CAs).
     */
    var allowInsecureTlsSoftAccept: Boolean = false,
)

/** ATAK co-existence behavior when ATAK is also installed on this device. */
@Serializable
data class AtakSettings(
    /** Off | WhenRunning | WhenHeardOnMesh */
    var deferToAtak: String = "WhenRunning",
) {
    companion object {
        const val OFF = "Off"
        const val WHEN_RUNNING = "WhenRunning"
        const val WHEN_HEARD_ON_MESH = "WhenHeardOnMesh"
    }
}

@Serializable
data class ServerProfile(
    var id: String = java.util.UUID.randomUUID().toString().replace("-", ""),
    var displayName: String = "Server",
    var enabled: Boolean = true,
    /** Placeholder host only in samples — runtime values stay local. */
    var host: String = "",
    var port: Int = 8089,
    /** ssl | tcp */
    var protocol: String = "ssl",
    var username: String? = null,
    var callsignOverride: String? = null,
    var teamOverride: String? = null,
    var roleOverride: String? = null,
    /** Blob name of the encrypted secret (token/password), if any. */
    var secretBlobName: String? = null,
    var certPasswordBlobName: String? = null,
    var trustPasswordBlobName: String? = null,
    var clientCertFileName: String? = null,
    var trustStoreFileName: String? = null,
    var cloudTakUrl: String? = null,
    /** Per-profile override for TLS soft-accept. Null = use [DiagnosticsSettings.allowInsecureTlsSoftAccept]. */
    var allowInsecureTlsSoftAccept: Boolean? = null,
)
