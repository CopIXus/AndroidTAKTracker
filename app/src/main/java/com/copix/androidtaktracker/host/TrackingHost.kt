package com.copix.androidtaktracker.host

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.copix.androidtaktracker.BuildConfig
import com.copix.androidtaktracker.atak.AtakCoexistence
import com.copix.androidtaktracker.config.AndroidEncryptedSecretStore
import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.config.ConfigStore
import com.copix.androidtaktracker.core.config.ServerProfile
import com.copix.androidtaktracker.core.cot.CotEventBuilder
import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.cot.GpsSourceKind
import com.copix.androidtaktracker.core.diagnostics.StatusExporter
import com.copix.androidtaktracker.core.identity.IdentityResolver
import com.copix.androidtaktracker.core.mesh.MeshSaBroadcaster
import com.copix.androidtaktracker.core.portal.DeviceProfileSync
import com.copix.androidtaktracker.core.portal.ServerCertificateProvider
import com.copix.androidtaktracker.core.reporting.ReportingEngine
import com.copix.androidtaktracker.core.tak.ClientCertificateMaterial
import com.copix.androidtaktracker.core.tak.EnrollmentApplyResult
import com.copix.androidtaktracker.core.tak.EnrollmentService
import com.copix.androidtaktracker.core.tak.MartiCertMaterial
import com.copix.androidtaktracker.core.tak.ServerConnectionStatus
import com.copix.androidtaktracker.core.tak.ServerCredentialProvider
import com.copix.androidtaktracker.core.tak.TakConnectionListener
import com.copix.androidtaktracker.core.tak.TakConnectionManager
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
import java.time.Duration
import java.time.Instant

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
            // ATAK / SoftCert / Marti PKCS12 password is almost always "atakatak" when unset.
            val pass = resolveCertPassword(profile)
            return ClientCertificateMaterial(file.readBytes(), pass.toCharArray())
        }

        override fun trustStore(profile: ServerProfile, allowInsecureSoftAccept: Boolean): TrustStoreConfig {
            val name = profile.trustStoreFileName
            if (name.isNullOrBlank()) {
                return TrustStoreConfig(allowInsecureSoftAccept = allowInsecureSoftAccept)
            }
            val file = File(store.certsDirectory, name)
            if (!file.exists()) return TrustStoreConfig(allowInsecureSoftAccept = allowInsecureSoftAccept)
            return TrustStoreConfig(file.readBytes(), resolveTrustPassword(profile).toCharArray(), allowInsecureSoftAccept)
        }

        override fun trustStorePkcs12(profile: ServerProfile): Pair<ByteArray, CharArray>? {
            val name = profile.trustStoreFileName ?: return null
            val file = File(store.certsDirectory, name)
            if (!file.exists()) return null
            return file.readBytes() to resolveTrustPassword(profile).toCharArray()
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

    private val _serverStatuses = MutableStateFlow<Map<String, ServerConnectionStatus>>(emptyMap())
    val serverStatuses: StateFlow<Map<String, ServerConnectionStatus>> = _serverStatuses

    /** Last enroll / SoftCert / QR result — kept so Servers can show it after leaving the scanner. */
    private val _lastEnrollFeedback = MutableStateFlow<EnrollmentApplyResult?>(null)
    val lastEnrollFeedback: StateFlow<EnrollmentApplyResult?> = _lastEnrollFeedback

    private val _lastUpdate = MutableStateFlow<UpdateCheckResult?>(null)
    val lastUpdate: StateFlow<UpdateCheckResult?> = _lastUpdate

    private val _settingsUnlocked = MutableStateFlow(store.readSecret("settings-lock").isNullOrBlank())
    val settingsUnlocked: StateFlow<Boolean> = _settingsUnlocked

    val lastPliEpochMs: Long get() = reporting.lastPliEpochMs
    val isSettingsLocked: Boolean get() = !store.readSecret("settings-lock").isNullOrBlank()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        store.save(_config.value)
        applyLogLevel(_config.value)
        tak.listener = object : TakConnectionListener {
            override fun onStatusChanged() {
                publishServerStatuses()
            }

            override fun onServerConnected(profile: ServerProfile) {
                reporting.requestAsap()
                scope.launch {
                    // MDM owns identity when those keys are managed — skip Portal overwrite.
                    val managed = mdm.managedKeys.value
                    if ("callsign" in managed || "team" in managed || "role" in managed) return@launch
                    portal.trySync(profile, _config.value) { cfg ->
                        store.save(cfg)
                        _config.value = ensureDeviceUid(store.load())
                        reporting.noteIdentityChanged()
                    }
                }
            }

            override fun onFileShareCot(profile: ServerProfile, cotXml: String) {
                scope.launch {
                    val managed = mdm.managedKeys.value
                    if ("callsign" in managed || "team" in managed || "role" in managed) return@launch
                    portal.tryHandleFileShareCot(profile, _config.value, cotXml) { cfg ->
                        store.save(cfg)
                        _config.value = ensureDeviceUid(store.deepCopy(cfg))
                        reporting.noteIdentityChanged()
                    }
                }
            }
        }
    }

    fun start() {
        mdm.start()
        mdm.onConfigUpdated = { scope.launch { reloadFromMdm() } }
        mdm.onPushEnrollUrl = { url ->
            scope.launch {
                enroll(url)
            }
        }
        registerNetworkCallback()
        scope.launch {
            reloadFromMdm()
            applyRuntime()
            reporting.start()
            while (true) {
                kotlinx.coroutines.delay(5_000)
                atak.refreshInstalled()
                atak.refreshRunning()
                publishServerStatuses()
            }
        }
    }

    fun stop() {
        reporting.stop()
        unregisterNetworkCallback()
        scope.launch { tak.stop() }
        gps.stop()
        meshMulticast.stop()
        mdm.stop()
    }

    fun saveConfig(mutate: (AppConfig) -> Unit): Boolean {
        if (!_settingsUnlocked.value && isSettingsLocked) {
            log.warn("Config", "Settings locked — edit rejected.")
            return false
        }
        // Deep-copy first: mutating the StateFlow-held data class in place + assigning an equal
        // reload is ignored by MutableStateFlow (equality), which left the callsign screen stuck.
        val cfg = store.deepCopy(_config.value)
        mutate(cfg)
        store.save(cfg)
        _config.value = ensureDeviceUid(store.deepCopy(cfg))
        applyLogLevel(_config.value)
        scope.launch { applyRuntime() }
        reporting.noteIdentityChanged()
        return true
    }

    fun persist() {
        store.save(_config.value)
    }

    suspend fun enroll(input: String): EnrollmentApplyResult {
        if (!_settingsUnlocked.value && isSettingsLocked) {
            return EnrollmentApplyResult(false, "Settings are locked.").also { _lastEnrollFeedback.value = it }
        }
        return enrollment.applyAsync(input, _config.value).also {
            _lastEnrollFeedback.value = it
            if (it.success) {
                store.save(_config.value)
                _config.value = ensureDeviceUid(store.load())
                applyRuntime()
                reporting.noteIdentityChanged()
            }
        }
    }

    fun importSoftCertZip(bytes: ByteArray): EnrollmentApplyResult {
        if (!_settingsUnlocked.value && isSettingsLocked) {
            return EnrollmentApplyResult(false, "Settings are locked.").also { _lastEnrollFeedback.value = it }
        }
        val r = enrollment.importSoftCertZip(bytes, _config.value)
        _lastEnrollFeedback.value = r
        if (r.success) {
            _config.value = ensureDeviceUid(store.load())
            scope.launch { applyRuntime() }
            reporting.noteIdentityChanged()
        }
        return r
    }

    fun clearEnrollFeedback() {
        _lastEnrollFeedback.value = null
    }

    fun readRecentLogs(maxBytes: Int = 64 * 1024): String =
        (log as AndroidLogger).readRecentText(maxBytes)

    fun setPaused(value: Boolean) {
        _paused.value = value
    }

    fun exportStatusJson(): String = StatusExporter.export(
        config = _config.value,
        serverStates = tak.statuses(),
        fix = gps.fix.value,
        paused = _paused.value || mdm.isRemotePauseRequested(),
        deferringToAtak = atak.shouldDefer(_config.value.atak.deferToAtak),
        appVersion = BuildConfig.VERSION_NAME,
        lastPliEpochMs = reporting.lastPliEpochMs,
    )

    fun sendTestMeshSa(): Boolean {
        val cfg = _config.value
        val active = IdentityResolver.resolve(cfg, Build.MODEL ?: "Android")
        val fix = gps.fix.value ?: GpsFix(
            latitude = 0.0,
            longitude = 0.0,
            timestamp = Instant.now(),
            source = GpsSourceKind.NETWORK_IP,
            isHeld = true,
        )
        val identity = CotEventBuilder.fromActiveIdentity(
            cfg, active, null, readBattery(), Build.MODEL,
        ).copy(version = BuildConfig.VERSION_NAME)
        val xml = CotEventBuilder.build(
            fix,
            identity,
            Duration.ofSeconds(60),
            cfg.gps.courseOffsetDegrees,
            Build.MODEL,
            "Android ${Build.VERSION.RELEASE}",
        )
        return mesh.trySend(xml)
    }

    suspend fun downloadAndInstallUpdate(): String {
        if (mdm.mdmPresent.value) return "Auto-update disabled under MDM — update via your EMM."
        if (Build.VERSION.SDK_INT >= 26 && !appContext.packageManager.canRequestPackageInstalls()) {
            appContext.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            return "Allow “Install unknown apps” for AndroidTAKTracker, then tap Download & install again."
        }
        val check = _lastUpdate.value ?: updates.check().also { _lastUpdate.value = it }
        if (!check.updateAvailable || check.downloadUrl.isNullOrBlank()) {
            return check.error ?: "No update available."
        }
        val bytes = updates.download(check.downloadUrl!!) ?: return "Download failed."
        if (!updates.verifySha256(bytes, check.sha256Expected)) return "SHA-256 mismatch — install aborted."
        val dir = File(appContext.cacheDir, "updates").also { it.mkdirs() }
        val apk = File(dir, "AndroidTAKTracker.apk")
        apk.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(intent)
        return "Opening installer for ${check.latestVersion}."
    }

    private fun registerNetworkCallback() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { tak.forceReconnect() }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    scope.launch { tak.forceReconnect() }
                }
            }
        }
        networkCallback = cb
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
        } catch (ex: Exception) {
            log.warn("Net", "Network callback register failed: ${ex.javaClass.simpleName}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
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
        publishServerStatuses()
    }

    private fun publishServerStatuses() {
        _serverStatuses.value = tak.statuses().associateBy { it.profileId }
    }

    /** Matches WinTAKTracker CotStreamClient trust/cert password resolution (atakatak default). */
    private fun resolveCertPassword(profile: ServerProfile): String {
        val fromBlob = profile.certPasswordBlobName?.let { store.readSecret(it) }
        return fromBlob?.takeIf { it.isNotEmpty() } ?: MartiCertMaterial.DEFAULT_P12_PASSWORD
    }

    private fun resolveTrustPassword(profile: ServerProfile): String {
        val trustBlob = profile.trustPasswordBlobName?.let { store.readSecret(it) }
        if (!trustBlob.isNullOrEmpty()) return trustBlob
        return resolveCertPassword(profile)
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
