package com.copix.androidtaktracker.mdm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.copix.androidtaktracker.core.util.RedactedLogger
import com.hmdm.IMdmApi

/**
 * Binds to the Headwind MDM agent via the documented Plugin API
 * (`com.hmdm.action.Connect` → [IMdmApi]). Preferences are empty until
 * [isConnected] is true — reflection without a bind never works.
 */
class HeadwindAgentClient(
    private val context: Context,
    private val log: RedactedLogger,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var api: IMdmApi? = null
    private var connection: ServiceConnection? = null
    @Volatile private var mustRun = false
    @Volatile private var bound = false

    val isConnected: Boolean get() = api != null

    fun isAgentInstalled(): Boolean =
        AGENT_PACKAGES.any { installed(it) }

    fun connect(): Boolean {
        mustRun = true
        if (isConnected) return true
        return bind()
    }

    fun disconnect() {
        mustRun = false
        main.removeCallbacksAndMessages(null)
        val conn = connection
        connection = null
        api = null
        bound = false
        if (conn != null) {
            try { context.unbindService(conn) } catch (_: Exception) { }
        }
    }

    fun getPreference(attr: String): String? {
        val svc = api ?: return null
        return try {
            svc.queryAppPreference(context.packageName, attr)?.takeIf { it.isNotBlank() }
        } catch (ex: Exception) {
            log.warn("MDM", "Headwind preference read failed for $attr: ${ex.javaClass.simpleName}")
            null
        }
    }

    fun getDeviceId(): String? {
        val svc = api ?: return null
        return try {
            svc.queryConfig()?.getString(KEY_DEVICE_ID)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (ex: Exception) {
            log.warn("MDM", "Headwind device id read failed: ${ex.javaClass.simpleName}")
            null
        }
    }

    private fun bind(): Boolean {
        if (bound) return true
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                api = IMdmApi.Stub.asInterface(service)
                log.info("MDM", "Headwind agent bound.")
                onConnected()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                api = null
                bound = false
                log.warn("MDM", "Headwind agent disconnected.")
                onDisconnected()
                if (mustRun) scheduleReconnect(RECONNECT_FIRST_MS)
            }
        }
        connection = conn
        for (pkg in AGENT_PACKAGES) {
            val intent = Intent(SERVICE_ACTION).setPackage(pkg)
            val ok = try {
                context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            } catch (ex: Exception) {
                log.warn("MDM", "Headwind bind failed ($pkg): ${ex.javaClass.simpleName}")
                false
            }
            if (ok) {
                bound = true
                return true
            }
        }
        connection = null
        return false
    }

    private fun scheduleReconnect(delayMs: Long) {
        main.postDelayed({
            if (!mustRun || isConnected) return@postDelayed
            if (!bind()) scheduleReconnect(RECONNECT_NEXT_MS)
        }, delayMs)
    }

    private fun installed(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val SERVICE_ACTION = "com.hmdm.action.Connect"
        const val KEY_DEVICE_ID = "DEVICE_ID"
        const val NOTIFICATION_CONFIG_UPDATED = "com.hmdm.push.configUpdated"
        val AGENT_PACKAGES = listOf("com.hmdm.launcher", "ru.headwind.kiosk")
        private const val RECONNECT_FIRST_MS = 5_000L
        private const val RECONNECT_NEXT_MS = 60_000L
    }
}
