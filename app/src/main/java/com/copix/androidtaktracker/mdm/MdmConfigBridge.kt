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
import com.copix.androidtaktracker.core.tak.EnrollmentService
import com.copix.androidtaktracker.core.util.RedactedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Reads Android Enterprise managed configurations (RestrictionsManager) and optional
 * Headwind MDM preference keys via reflection so the Headwind library is not a hard dependency.
 *
 * Precedence: MDM > Portal > local/QR.
 */
class MdmConfigBridge(
    private val context: Context,
    private val log: RedactedLogger,
    private val enrollment: EnrollmentService,
    private val scope: CoroutineScope,
) {
    private val _managedKeys = MutableStateFlow<Set<String>>(emptySet())
    val managedKeys: StateFlow<Set<String>> = _managedKeys

    private val _mdmPresent = MutableStateFlow(false)
    val mdmPresent: StateFlow<Boolean> = _mdmPresent

    private var pauseRequested = false
    fun isRemotePauseRequested(): Boolean = pauseRequested

    private val restrictionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            onConfigUpdated?.invoke()
        }
    }

    var onConfigUpdated: (() -> Unit)? = null

    fun start() {
        val filter = IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(restrictionsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(restrictionsReceiver, filter)
        }
        // Headwind config + custom push types (attracker-config / attracker-pause).
        try {
            val hwFilter = IntentFilter("com.hmdm.push.configUpdated")
            hwFilter.addAction("com.hmdm.push.attracker-config")
            hwFilter.addAction("com.hmdm.push.attracker-pause")
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(restrictionsReceiver, hwFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(restrictionsReceiver, hwFilter)
            }
        } catch (_: Exception) { /* ignore */ }
        detectHeadwind()
        tryRegisterHeadwindPushHandler()
    }

    /**
     * Best-effort Headwind [MDMPushHandler] registration via reflection (no hard dependency).
     * Custom types: `attracker-config` (enroll URL / JSON fragment) and `attracker-pause`.
     */
    private fun tryRegisterHeadwindPushHandler() {
        try {
            val headwind = Class.forName("com.hmdm.HeadwindMDM")
            val getInstance = headwind.methods.firstOrNull {
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
                            onConfigUpdated?.invoke()
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
            _mdmPresent.value = true
        } catch (_: Exception) {
            // Headwind not on classpath / API shape differs — Enterprise restrictions still work.
        }
    }

    fun stop() {
        try { context.unregisterReceiver(restrictionsReceiver) } catch (_: Exception) {}
    }

    /**
     * Apply managed config onto [config]. Returns true if anything changed.
     */
    fun applyManagedConfig(config: AppConfig): Boolean {
        val bundle = readRestrictions()
        val hw = readHeadwindPrefs()
        val keys = linkedMapOf<String, String>()
        for ((k, v) in bundle) keys[k] = v
        for ((k, v) in hw) if (k !in keys) keys[k] = v

        _managedKeys.value = keys.keys.toSet()
        if (keys.isEmpty()) return false

        var changed = false
        keys["enrollUrl"]?.let { url ->
            scope.launch(Dispatchers.IO) {
                enrollment.applyAsync(url, config)
            }
            changed = true
        }
        val host = keys["serverHost"]
        if (!host.isNullOrBlank()) {
            val port = keys["serverPort"]?.toIntOrNull() ?: 8089
            val protocol = keys["serverProtocol"] ?: "ssl"
            val existing = config.servers.firstOrNull { it.host.equals(host, true) }
            if (existing == null) {
                config.servers.add(
                    ServerProfile(
                        id = UUID.randomUUID().toString().replace("-", ""),
                        displayName = keys["serverName"] ?: host,
                        host = host,
                        port = port,
                        protocol = protocol,
                        username = keys["username"],
                    ),
                )
                keys["token"]?.let { /* stored by enroll path when present */ }
                changed = true
            } else {
                if (existing.port != port) { existing.port = port; changed = true }
                if (existing.protocol != protocol) { existing.protocol = protocol; changed = true }
            }
        }
        if (RemoteIdentityApply.apply(config, keys["callsign"], keys["team"], keys["role"]).applied) {
            changed = true
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
        return changed
    }

    fun isKeyManaged(key: String): Boolean = key in _managedKeys.value

    private fun readRestrictions(): Map<String, String> {
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
            ?: return emptyMap()
        val b = rm.applicationRestrictions ?: return emptyMap()
        val map = linkedMapOf<String, String>()
        for (k in b.keySet()) {
            val v = b.get(k)?.toString()
            if (!v.isNullOrBlank()) map[k] = v
        }
        if (map.isNotEmpty()) _mdmPresent.value = true
        return map
    }

    private fun readHeadwindPrefs(): Map<String, String> {
        // Reflection: MDMService.Preferences.get(key, default)
        return try {
            val clazz = Class.forName("com.hmdm.MDMService\$Preferences")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            val keys = listOf(
                "enrollUrl", "serverHost", "serverPort", "serverProtocol", "serverName",
                "username", "token", "callsign", "team", "role", "reportingStrategy",
                "deferToAtak", "pause",
            )
            val map = linkedMapOf<String, String>()
            for (k in keys) {
                val v = get.invoke(null, k, "") as? String
                if (!v.isNullOrBlank()) map[k] = v
            }
            if (map.isNotEmpty()) _mdmPresent.value = true
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun detectHeadwind() {
        try {
            Class.forName("com.hmdm.HeadwindMDM")
            _mdmPresent.value = true
        } catch (_: Exception) { /* not installed */ }
    }
}
