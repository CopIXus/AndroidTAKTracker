package com.copix.androidtaktracker.host

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.copix.androidtaktracker.BuildConfig
import com.copix.androidtaktracker.atak.AtakCoexistence
import com.copix.androidtaktracker.config.AndroidEncryptedSecretStore
import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.config.ConfigStore
import com.copix.androidtaktracker.core.config.ServerProfile
import com.copix.androidtaktracker.core.mesh.MeshSaBroadcaster
import com.copix.androidtaktracker.core.portal.DeviceProfileSync
import com.copix.androidtaktracker.core.portal.ServerCertificateProvider
import com.copix.androidtaktracker.core.reporting.ReportingEngine
import com.copix.androidtaktracker.core.tak.ClientCertificateMaterial
import com.copix.androidtaktracker.core.tak.EnrollmentService
import com.copix.androidtaktracker.core.tak.ServerCredentialProvider
import com.copix.androidtaktracker.core.tak.TakConnectionListener
import com.copix.androidtaktracker.core.tak.TakConnectionManager
import com.copix.androidtaktracker.core.tak.TakConnectionState
import com.copix.androidtaktracker.core.tak.TrustStoreConfig
import com.copix.androidtaktracker.core.update.GitHubUpdateService
import com.copix.androidtaktracker.core.update.UpdateCheckResult
import com.copix.androidtaktracker.core.update.UpdateService
import com.copix.androidtaktracker.core.util.LogLevel
import com.copix.androidtaktracker.core.util.RedactedLogger
import com.copix.androidtaktracker.gps.FusedGpsRepository
import com.copix.androidtaktracker.mdm.MdmConfigBridge
import com.copix.androidtaktracker.mesh.MeshMulticastSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class TrackingHost private constructor(private val appContext: Context) {
    private val store = ConfigStore(
        rootDir = File(appContext.filesDir, "store"),
        secretStore = AndroidEncryptedSecretStore(appContext),
        deviceNameProvider = { Build.MODEL ?: "Android" },
    )
    val log: RedactedLogger = AndroidLogger(store.logsDirectory)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _config = MutableStateFlow(ensureDeviceUid(store.load()))
    val config: StateFlow<AppConfig> = _config

    val gps = FusedGpsRepository(appContext)
    val atak = AtakCoexistence(appContext)
    val mesh = MeshSaBroadcaster(log)
    private val meshMulticast = MeshMulticastSupport(appContext, log, atak, scope)

    private val credentials = object : ServerCredentialProvider, ServerCertificateProvider {
        override fun clientCertificate(profile: ServerProfile): ClientCertificateMaterial? {
            val name = profile.clientCertFileName ?: return null
            val file = File(store.certsDirectory, name)
            if (!file.exists()) return null
            val pass = profile.certPasswordBlobName?.let { store.readSecret(it) } ?: ""
            return ClientCertificateMaterial(file.readBytes(), pass.toCharArray())
        }

        override fun trustStore(profile: ServerProfile, allowInsecureSoftAccept: Boolean): TrustStoreConfig {
            val name = profile.trustStoreFileName
            if (name.isNullOrBlank()) {
                return TrustStoreConfig(allowInsecureSoftAccept = allowInsecureSoftAccept)
            }
            val file = File(store.certsDirectory, name)
            if (!file.exists()) return TrustStoreConfig(allowInsecureSoftAccept = allowInsecureSoftAccept)
            val pass = profile.trustPasswordBlobName?.let { store.readSecret(it) } ?: ""
            return TrustStoreConfig(file.readBytes(), pass.toCharArray(), allowInsecureSoftAccept)
        }

        override fun trustStorePkcs12(profile: ServerProfile): Pair<ByteArray, CharArray>? {
            val name = profile.trustStoreFileName ?: return null
            val file = File(store.certsDirectory, name)
            if (!file.exists()) return null
            val pass = profile.trustPasswordBlobName?.let { store.readSecret(it) } ?: ""
            return file.readBytes() to pass.toCharArray()
        }
    }

    private val tak = TakConnectionManager(store, log, credentials, scope)

    val enrollment = EnrollmentService(store, log)
    val mdm = MdmConfigBridge(appContext, log, enrollment, scope)
    private val portal = DeviceProfileSync(credentials, log)
    val updates: UpdateService = GitHubUpdateService(
        settings = { _config.value.updates },
        log = log,
        currentVersion = BuildConfig.VERSION_NAME,
    )

    private val reporting = ReportingEngine(
        log = log,
        tak = tak,
        mesh = mesh,
        configProvider = { _config.value },
        fixProvider = { gps.fix.value },
        paused = { _paused.value || mdm.isRemotePauseRequested() },
        deferringToAtak = { atak.shouldDefer(_config.value.atak.deferToAtak) },
        batteryPercent = { readBattery() },
        deviceModel = { Build.MODEL ?: "Android" },
        osVersion = { "Android ${Build.VERSION.RELEASE}" },
        appVersion = { BuildConfig.VERSION_NAME },
    )

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    private val _serverStates = MutableStateFlow<Map<String, TakConnectionState>>(emptyMap())
    val serverStates: StateFlow<Map<String, TakConnectionState>> = _serverStates

    private val _lastUpdate = MutableStateFlow<UpdateCheckResult?>(null)
    val lastUpdate: StateFlow<UpdateCheckResult?> = _lastUpdate

    private val _settingsUnlocked = MutableStateFlow(true)
    val settingsUnlocked: StateFlow<Boolean> = _settingsUnlocked

    val lastPliEpochMs: Long get() = reporting.lastPliEpochMs

    init {
        store.save(_config.value)
        applyLogLevel(_config.value)
        tak.listener = object : TakConnectionListener {
            override fun onStatusChanged() {
                _serverStates.value = tak.statuses().associate { it.profileId to it.state }
            }

            override fun onServerConnected(profile: ServerProfile) {
                reporting.requestAsap()
                scope.launch {
                    portal.trySync(profile, _config.value) { cfg ->
                        store.save(cfg)
                        _config.value = ensureDeviceUid(store.load())
                        reporting.noteIdentityChanged()
                    }
                }
            }
        }
    }

    fun start() {
        mdm.start()
        mdm.onConfigUpdated = { scope.launch { reloadFromMdm() } }
        scope.launch {
            reloadFromMdm()
            applyRuntime()
            reporting.start()
            while (true) {
                kotlinx.coroutines.delay(5_000)
                atak.refreshInstalled()
                atak.refreshRunning()
                _serverStates.value = tak.statuses().associate { it.profileId to it.state }
            }
        }
    }

    fun stop() {
        reporting.stop()
        scope.launch { tak.stop() }
        gps.stop()
        meshMulticast.stop()
        mdm.stop()
    }

    fun saveConfig(mutate: (AppConfig) -> Unit) {
        val cfg = _config.value
        mutate(cfg)
        store.save(cfg)
        _config.value = ensureDeviceUid(store.load())
        applyLogLevel(_config.value)
        scope.launch { applyRuntime() }
        reporting.noteIdentityChanged()
    }

    fun persist() {
        store.save(_config.value)
    }

    suspend fun enroll(input: String) = enrollment.applyAsync(input, _config.value).also {
        if (it.success) {
            store.save(_config.value)
            _config.value = ensureDeviceUid(store.load())
            applyRuntime()
            reporting.noteIdentityChanged()
        }
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
    }

    fun unlockSettings(password: String?): Boolean {
        val stored = store.readSecret("settings-lock")
        if (stored.isNullOrBlank()) {
            _settingsUnlocked.value = true
            return true
        }
        val ok = stored == hash(password ?: "")
        _settingsUnlocked.value = ok
        return ok
    }

    fun setSettingsLock(password: String?) {
        if (password.isNullOrBlank()) {
            store.deleteSecret("settings-lock")
            _settingsUnlocked.value = true
        } else {
            store.writeSecret("settings-lock", hash(password))
            _settingsUnlocked.value = false
        }
    }

    suspend fun checkUpdates(): UpdateCheckResult {
        val r = updates.check()
        _lastUpdate.value = r
        val cfg = _config.value
        cfg.updates.lastCheckedUtc = java.time.Instant.now().toString()
        cfg.updates.lastAvailableVersion = if (r.updateAvailable) r.latestVersion else null
        store.save(cfg)
        return r
    }

    private suspend fun reloadFromMdm() {
        val cfg = _config.value
        if (mdm.applyManagedConfig(cfg)) {
            store.save(cfg)
            _config.value = ensureDeviceUid(store.load())
            applyRuntime()
            reporting.noteIdentityChanged()
        }
    }

    private suspend fun applyRuntime() {
        val cfg = _config.value
        mesh.applySettings(cfg.meshSa)
        meshMulticast.apply(cfg.meshSa, cfg.deviceUid)
        gps.applySettings(cfg.gps)
        tak.start(cfg)
        _serverStates.value = tak.statuses().associate { it.profileId to it.state }
    }

    private fun applyLogLevel(cfg: AppConfig) {
        val level = when (cfg.diagnostics.logLevel.lowercase()) {
            "debug", "trace" -> LogLevel.DEBUG
            "information", "info" -> LogLevel.INFORMATION
            "warning", "warn" -> LogLevel.WARNING
            else -> LogLevel.ERROR
        }
        log.setMinLevel(level)
        log.setMaxTotalSizeMb(cfg.diagnostics.maxLogSizeMb)
    }

    private fun ensureDeviceUid(cfg: AppConfig): AppConfig {
        if (cfg.deviceUid.isNullOrBlank()) {
            val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
            cfg.deviceUid = "ANDROIDTAKTRACKER-" +
                androidId.filter { it.isLetterOrDigit() }.take(16)
                    .ifBlank { java.util.UUID.randomUUID().toString().replace("-", "").take(12) }
            store.save(cfg)
        }
        return cfg
    }

    private fun readBattery(): Int? {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct else null
    }

    private fun hash(password: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(password.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile private var instance: TrackingHost? = null

        fun get(context: Context): TrackingHost {
            return instance ?: synchronized(this) {
                instance ?: TrackingHost(context.applicationContext).also { instance = it }
            }
        }
    }
}
