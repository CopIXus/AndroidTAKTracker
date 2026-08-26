package com.copix.androidtaktracker.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.os.Build
import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.config.ServerProfile
import com.copix.androidtaktracker.core.identity.RemoteIdentityApply
import com.copix.androidtaktracker.core.mdm.MdmSettingsApply
import com.copix.androidtaktracker.core.tak.EnrollmentApplyResult
import com.copix.androidtaktracker.core.tak.EnrollmentService
import com.copix.androidtaktracker.core.util.RedactedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class MdmApplyResult(
    val configChanged: Boolean,
    val enrollResult: EnrollmentApplyResult? = null,
)

/**
 * Reads Android Enterprise managed configurations ([RestrictionsManager]) and Headwind
 * Application Settings via a bound [HeadwindAgentClient] (Plugin API).
 *
 * Precedence: MDM > Portal > local/QR.
 */
class MdmConfigBridge(
    private val context: Context,
    private val log: RedactedLogger,
    private val enrollment: EnrollmentService,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    private val _managedKeys = MutableStateFlow<Set<String>>(emptySet())
    val managedKeys: StateFlow<Set<String>> = _managedKeys

    private val _mdmPresent = MutableStateFlow(false)
    val mdmPresent: StateFlow<Boolean> = _mdmPresent

    private var pauseRequested = false
    fun isRemotePauseRequested(): Boolean = pauseRequested

    fun isKeyManaged(key: String): Boolean = key in _managedKeys.value

    /** True when Headwind is present or a callsign key was pushed (Portal must not overwrite). */
    fun ownsIdentity(): Boolean =
        _mdmPresent.value || "callsign" in _managedKeys.value ||
            "team" in _managedKeys.value || "role" in _managedKeys.value

    private val restrictionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            onConfigUpdated?.invoke()
        }
    }

    var onConfigUpdated: (() -> Unit)? = null
    /** Invoked when a Headwind `attracker-config` push carries an enroll URL / JSON fragment. */
    var onPushEnrollUrl: ((String) -> Unit)? = null

    private val headwind = HeadwindAgentClient(
        context = context,
        log = log,
        onConnected = {
            markPresent()
            onConfigUpdated?.invoke()
        },
        onDisconnected = { },
    )

    private val pushReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action.orEmpty()
            when {
                action.endsWith("attracker-pause") -> {
                    pauseRequested = true
                    log.warn("MDM", "Remote pause via push broadcast.")
                    onConfigUpdated?.invoke()
                }
                action.endsWith("attracker-config") -> {
                    val payload = intent?.getStringExtra("payload")
                        ?: intent?.getStringExtra("message")
                        ?: intent?.getStringExtra("data")
                        ?: ""
                    handleConfigPushPayload(payload)
                }
                else -> onConfigUpdated?.invoke()
            }
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(restrictionsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(restrictionsReceiver, filter)
        }
        try {
            val hwFilter = IntentFilter(HeadwindAgentClient.NOTIFICATION_CONFIG_UPDATED)
            hwFilter.addAction("com.hmdm.push.attracker-config")
            hwFilter.addAction("com.hmdm.push.attracker-pause")
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(pushReceiver, hwFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(pushReceiver, hwFilter)
            }
        } catch (_: Exception) { /* ignore */ }

        if (headwind.isAgentInstalled()) markPresent()
        if (!headwind.connect() && !headwind.isAgentInstalled()) {
            log.info("MDM", "Headwind agent not installed — Enterprise restrictions still apply.")
        }
        tryRegisterHeadwindPushHandler()
    }

    fun stop() {
        headwind.disconnect()
        try { context.unregisterReceiver(restrictionsReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(pushReceiver) } catch (_: Exception) {}
    }

    private fun handleConfigPushPayload(payload: String) {
        if (payload.isBlank()) {
            onConfigUpdated?.invoke()
            return
        }
        val trimmed = payload.trim()
        when {
            trimmed.contains("://") || trimmed.contains(',') -> onPushEnrollUrl?.invoke(trimmed)
            trimmed.startsWith("{") -> {
                val enroll = Regex(""""enrollUrl"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1)
                if (!enroll.isNullOrBlank()) onPushEnrollUrl?.invoke(enroll)
                else onConfigUpdated?.invoke()
            }
            else -> onConfigUpdated?.invoke()
        }
    }

    /**
     * Best-effort [com.hmdm.MDMPushHandler] registration when a future Headwind AAR is on
     * the classpath. The bound Plugin API + `configUpdated` broadcast are the primary path.
     */
    private fun tryRegisterHeadwindPushHandler() {
        try {
            val headwindCls = Class.forName("com.hmdm.HeadwindMDM")
            val getInstance = headwindCls.methods.firstOrNull {
                it.name == "getInstance" && it.parameterCount == 0
            } ?: return
            val instance = getInstance.invoke(null) ?: return
            val handlerClass = Class.forName("com.hmdm.MDMPushHandler")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                handlerClass.classLoader,
                arrayOf(handlerClass),
            ) { _, method, args ->
                if (method.name == "onMessageReceived" || method.name == "onPushReceived") {
                    val type = args?.getOrNull(0)?.toString().orEmpty()
                    val payload = args?.getOrNull(1)?.toString().orEmpty()
                    when {
                        type.contains("attracker-pause", ignoreCase = true) -> {
                            pauseRequested = true
                            log.warn("MDM", "Remote pause requested via Headwind push.")
                            onConfigUpdated?.invoke()
                        }
                        type.contains("attracker-config", ignoreCase = true) || payload.isNotBlank() -> {
                            log.info("MDM", "Headwind config push received.")
                            handleConfigPushPayload(payload)
                        }
                    }
                }
                null
            }
            val register = instance.javaClass.methods.firstOrNull { m ->
                m.name.contains("register", ignoreCase = true) &&
                    m.parameterTypes.any { it.name.contains("MDMPush") }
            }
            register?.invoke(instance, proxy)
            markPresent()
        } catch (_: Exception) {
            // Official Headwind Java helper is not on the classpath — AIDL bind is enough.
        }
    }

    /**
     * Apply managed config onto [config]. Enrollment uses [EnrollmentService.enrollManual]
     * so credentials never land in a constructed URL.
     */
    suspend fun applyManagedConfig(config: AppConfig): MdmApplyResult {
        val bundle = readRestrictions()
        val hw = readHeadwindPrefs()
        val merged = linkedMapOf<String, String>()
        for ((k, v) in bundle) merged[k] = v
        for ((k, v) in hw) if (k !in merged) merged[k] = v

        val keys = MdmSettingsApply.normalize(merged, headwind.getDeviceId())
        _managedKeys.value = keys.keys.toSet()
        if (keys.isNotEmpty()) markPresent()
        if (keys.isEmpty()) return MdmApplyResult(false)

        var changed = false
        var enrollResult: EnrollmentApplyResult? = null

        if (RemoteIdentityApply.apply(config, keys["callsign"], keys["team"], keys["role"]).applied) {
            changed = true
            config.userIdentity.setupPromptDismissed = true
        }

        keys["enrollUrl"]?.let { url ->
            enrollResult = enrollment.applyAsync(url, config)
            if (enrollResult?.success == true) changed = true
        }

        val host = keys["serverHost"]
        if (!host.isNullOrBlank() && enrollResult == null) {
            val port = MdmSettingsApply.parsePort(keys["serverPort"], 8089)
            val enrollPort = MdmSettingsApply.parsePort(keys["enrollPort"], 8446)
            val protocol = keys["serverProtocol"] ?: "ssl"
            val existing = config.servers.firstOrNull { it.host.equals(host, true) }
            val user = keys["username"]
            val secret = MdmSettingsApply.credential(keys)
            when {
                MdmSettingsApply.shouldEnroll(existing, user, secret) -> {
                    enrollResult = enrollment.enrollManual(
                        host = host,
                        username = user!!,
                        password = secret!!,
                        config = config,
                        streamPort = port,
                        enrollPort = enrollPort,
                    )
                    if (enrollResult?.success == true) changed = true
                    else log.warn("MDM", "Managed enroll failed: ${enrollResult?.message}")
                }
                existing == null -> {
                    config.servers.add(
                        ServerProfile(
                            id = UUID.randomUUID().toString().replace("-", ""),
                            displayName = keys["serverName"] ?: host,
                            host = host,
                            port = port,
                            protocol = protocol,
                            username = user,
                        ),
                    )
                    changed = true
                }
                else -> {
                    if (existing.port != port) { existing.port = port; changed = true }
                    if (existing.protocol != protocol) { existing.protocol = protocol; changed = true }
                }
            }
        }

        keys["reportingStrategy"]?.let {
            if (config.reporting.strategy != it) {
                config.reporting.strategy = it
                changed = true
            }
        }
        keys["deferToAtak"]?.let {
            if (config.atak.deferToAtak != it) {
                config.atak.deferToAtak = it
                changed = true
            }
        }
        keys["pause"]?.let {
            pauseRequested = it.equals("true", true) || it == "1"
        }
        return MdmApplyResult(changed, enrollResult)
    }

    private fun readRestrictions(): Map<String, String> {
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
            ?: return emptyMap()
        val b = rm.applicationRestrictions ?: return emptyMap()
        val map = linkedMapOf<String, String>()
        for (k in b.keySet()) {
            val v = b.get(k)?.toString()
            if (!v.isNullOrBlank()) map[k] = v
        }
        if (map.isNotEmpty()) markPresent()
        return map
    }

    private fun readHeadwindPrefs(): Map<String, String> {
        if (!headwind.isConnected) return emptyMap()
        val map = linkedMapOf<String, String>()
        for (k in MdmSettingsApply.PREFERENCE_KEYS) {
            val v = headwind.getPreference(k)
            if (!v.isNullOrBlank()) map[k] = v
        }
        if (map.isNotEmpty()) markPresent()
        return map
    }

    private fun markPresent() {
        _mdmPresent.value = true
    }
}
