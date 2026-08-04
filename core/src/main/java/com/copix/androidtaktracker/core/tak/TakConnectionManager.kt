package com.copix.androidtaktracker.core.tak

import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.config.ConfigStore
import com.copix.androidtaktracker.core.config.ServerProfile
import com.copix.androidtaktracker.core.util.RedactedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class ServerConnectionStatus(
    val profileId: String,
    val displayName: String,
    val enabled: Boolean,
    val protocol: String = "ssl",
    val state: TakConnectionState,
    val lastErrorCode: String? = null,
    val lastSendUtc: Instant? = null,
)

interface TakConnectionListener {
    fun onStatusChanged() {}
    /** Raised when a server stream reaches [TakConnectionState.CONNECTED]. */
    fun onServerConnected(profile: ServerProfile) {}
}

/**
 * Supplies TLS material (client cert / trust store bytes) for a server profile on demand, so the
 * manager never has to know how certs are stored (Keystore-backed in the `app` module).
 */
interface ServerCredentialProvider {
    fun clientCertificate(profile: ServerProfile): ClientCertificateMaterial?
    fun trustStore(profile: ServerProfile, allowInsecureSoftAccept: Boolean): TrustStoreConfig
}

/** Multi-server CoT fan-out with per-profile reconnect. Mirrors WinTAKTracker's TakConnectionManager. */
class TakConnectionManager(
    private val store: ConfigStore,
    private val log: RedactedLogger,
    private val credentials: ServerCredentialProvider,
    private val scope: CoroutineScope,
) {
    private val clients = LinkedHashMap<String, CotStreamClient>()
    // Thread-safe independently of `gate` — cancelReconnect() is called from call sites that
    // don't (and shouldn't need to) hold the suspend-only `gate` mutex.
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val connectGates = ConcurrentHashMap<String, Mutex>()
    private val gate = Mutex()
    @Volatile private var config: AppConfig = AppConfig()

    var listener: TakConnectionListener? = null

    val anyConnected: Boolean get() = statuses().any { it.state == TakConnectionState.CONNECTED }
    val anyReconnecting: Boolean get() = statuses().any { it.state == TakConnectionState.RECONNECTING }

    fun statuses(): List<ServerConnectionStatus> = config.servers.map { p ->
        val c = clients[p.id]
        if (c != null) {
            ServerConnectionStatus(p.id, p.displayName, p.enabled, p.protocol, c.state, c.lastErrorCode, c.lastSendUtc)
        } else {
            ServerConnectionStatus(p.id, p.displayName, p.enabled, p.protocol, TakConnectionState.DISCONNECTED)
        }
    }

    suspend fun start(newConfig: AppConfig) {
        store.ensureDirectories()
        reload(newConfig)
    }

    suspend fun reload(newConfig: AppConfig) {
        store.ensureDirectories()
        config = newConfig
        val enabledIds = newConfig.servers.filter { it.enabled && it.host.isNotBlank() }.map { it.id }.toHashSet()

        val toDispose = gate.withLock {
            val stale = clients.filterKeys { it !in enabledIds }
            for (id in stale.keys.toList()) {
                cancelReconnect(id)
                clients.remove(id)
                connectGates.remove(id)
            }
            stale.values.toList()
        }

        for (c in toDispose) c.disconnect()

        for (targetProfile in newConfig.servers.filter { it.id in enabledIds }) {
            val client = gate.withLock {
                clients.getOrPut(targetProfile.id) {
                    val c = CotStreamClient(log, scope)
                    c.listener = object : CotStreamListener {
                        override fun onStateChanged(client: CotStreamClient, state: TakConnectionState) {
                            listener?.onStatusChanged()
                            if (state == TakConnectionState.CONNECTED) listener?.onServerConnected(client.profile)
                            if (state == TakConnectionState.DISCONNECTED) {
                                scope.launch { ensureReconnect(targetProfile.id) }
                            }
                        }
                    }
                    connectGates[targetProfile.id] = Mutex()
                    c
                }
            }

            val softAccept = resolveSoftAccept(newConfig, targetProfile)
            client.allowInsecureTlsSoftAccept = softAccept
            client.configureClientCertificate(credentials.clientCertificate(targetProfile))
            client.configureTrustStore(credentials.trustStore(targetProfile, softAccept))

            if (client.state == TakConnectionState.CONNECTED && !profileEndpointChanged(client.profile, targetProfile)) {
                client.applyProfile(targetProfile)
                continue
            }

            if ((client.state == TakConnectionState.CONNECTING || client.state == TakConnectionState.RECONNECTING) &&
                !profileEndpointChanged(client.profile, targetProfile)
            ) {
                continue
            }

            scope.launch { connectOrReconnect(targetProfile) }
        }

        listener?.onStatusChanged()
    }

    suspend fun sendToAll(cotXml: String): Int = sendToAll { cotXml }

    suspend fun sendToAll(cotForProfile: (ServerProfile) -> String): Int {
        val connected = gate.withLock { clients.values.filter { it.state == TakConnectionState.CONNECTED } }
        var sent = 0
        for (client in connected) {
            try {
                client.send(cotForProfile(client.profile))
                sent++
            } catch (ex: Exception) {
                log.warn("TAK", "Send failed: ${ex.javaClass.simpleName}")
            }
        }
        return sent
    }

    /** Drop every stream so the normal reconnect path re-establishes them (resume/network recovery). */
    suspend fun forceReconnect() {
        val all = gate.withLock { clients.values.toList() }
        log.info("TAK", "Force reconnect of ${all.size} stream(s) (resume/network recovery).")
        for (client in all) {
            try {
                client.disconnect()
            } catch (ex: Exception) {
                log.warn("TAK", "Force disconnect failed: ${ex.javaClass.simpleName}")
            }
        }
    }

    suspend fun testServer(profileId: String, currentConfig: AppConfig): Pair<Boolean, String> {
        val target = currentConfig.servers.firstOrNull { it.id == profileId } ?: return false to "Profile not found."
        if (target.host.isBlank()) return false to "Host is empty."

        val softAccept = resolveSoftAccept(currentConfig, target)
        val client = CotStreamClient(log, scope)
        client.allowInsecureTlsSoftAccept = softAccept
        client.configureClientCertificate(credentials.clientCertificate(target))
        client.configureTrustStore(credentials.trustStore(target, softAccept))
        return try {
            client.test(target)
        } catch (ex: Exception) {
            val detail = client.lastErrorCode
            if (!detail.isNullOrBlank()) false to detail else false to "Connection test failed: ${ex.message}"
        }
    }

    suspend fun wipeProfile(currentConfig: AppConfig, profileId: String) {
        val target = currentConfig.servers.firstOrNull { it.id == profileId } ?: return
        cancelReconnect(profileId)
        val client = gate.withLock {
            connectGates.remove(profileId)
            clients.remove(profileId)
        }
        client?.disconnect()

        deleteProfileFiles(target)
        currentConfig.servers.removeAll { it.id == profileId }
        store.save(currentConfig)
        listener?.onStatusChanged()
    }

    suspend fun wipeAll(currentConfig: AppConfig) {
        for (p in currentConfig.servers.toList()) wipeProfile(currentConfig, p.id)
    }

    suspend fun stop() {
        val all = gate.withLock {
            for (id in reconnectJobs.keys.toList()) cancelReconnect(id)
            val values = clients.values.toList()
            clients.clear()
            connectGates.clear()
            values
        }
        for (c in all) c.disconnect()
    }

    private suspend fun connectOrReconnect(target: ServerProfile) {
        val client = gate.withLock { clients[target.id] } ?: return
        val connectGate = gate.withLock { connectGates[target.id] } ?: return

        cancelReconnect(target.id)

        var startBackoff = false
        connectGate.withLock {
            if (client.state == TakConnectionState.CONNECTED && !profileEndpointChanged(client.profile, target))
                return@withLock

            val endpointChanged = profileEndpointChanged(client.profile, target)
            if (client.autoReconnectSuspended && !endpointChanged) {
                client.applyProfile(target)
                listener?.onStatusChanged()
                return@withLock
            }

            if (endpointChanged || client.autoReconnectSuspended) client.clearAutoReconnectSuspend()

            try {
                client.connect(target)
            } catch (ex: Exception) {
                startBackoff = !client.autoReconnectSuspended
            }
        }

        if (startBackoff) scope.launch { ensureReconnect(target.id) }
    }

    private suspend fun ensureReconnect(profileId: String) {
        val target = config.servers.firstOrNull { it.id == profileId && it.enabled } ?: return
        val client = gate.withLock { clients[profileId] } ?: return
        if (client.autoReconnectSuspended) return
        if (client.state == TakConnectionState.CONNECTED || client.state == TakConnectionState.CONNECTING) return

        cancelReconnect(profileId)
        val job = scope.launch { client.reconnectWithBackoff(target) }
        gate.withLock { reconnectJobs[profileId] = job }
    }

    private fun profileEndpointChanged(current: ServerProfile, next: ServerProfile): Boolean =
        !current.host.equals(next.host, ignoreCase = true) ||
            current.port != next.port ||
            !current.protocol.equals(next.protocol, ignoreCase = true) ||
            !current.clientCertFileName.orEmpty().equals(next.clientCertFileName.orEmpty(), ignoreCase = true) ||
            !current.trustStoreFileName.orEmpty().equals(next.trustStoreFileName.orEmpty(), ignoreCase = true)

    private fun cancelReconnect(profileId: String) {
        reconnectJobs.remove(profileId)?.cancel()
    }

    private fun resolveSoftAccept(currentConfig: AppConfig, target: ServerProfile): Boolean =
        target.allowInsecureTlsSoftAccept ?: currentConfig.diagnostics.allowInsecureTlsSoftAccept

    private fun deleteProfileFiles(target: ServerProfile) {
        fun del(name: String?) {
            if (name.isNullOrBlank()) return
            val f = File(store.certsDirectory, name)
            try {
                if (f.exists()) f.delete()
            } catch (_: Exception) { /* ignore */ }
        }

        del(target.clientCertFileName)
        del(target.trustStoreFileName)
        if (target.secretBlobName != null) store.deleteSecret(target.secretBlobName!!)
        if (target.certPasswordBlobName != null) store.deleteSecret(target.certPasswordBlobName!!)
        if (target.trustPasswordBlobName != null) store.deleteSecret(target.trustPasswordBlobName!!)
    }
}
