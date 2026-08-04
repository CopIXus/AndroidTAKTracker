package com.copix.androidtaktracker.core.tak

import com.copix.androidtaktracker.core.config.ServerProfile
import com.copix.androidtaktracker.core.util.RedactedLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

enum class TakConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

/** PKCS12 client certificate material loaded in memory (never persisted decrypted). */
class ClientCertificateMaterial(val pkcs12Bytes: ByteArray, val password: CharArray)

/** Custom trust store (PKCS12) for private CAs, or soft-accept when no store is configured. */
class TrustStoreConfig(
    val pkcs12Bytes: ByteArray? = null,
    val password: CharArray? = null,
    val allowInsecureSoftAccept: Boolean = false,
)

interface CotStreamListener {
    fun onStateChanged(client: CotStreamClient, state: TakConnectionState) {}
    fun onConnected(client: CotStreamClient, profile: ServerProfile) {}
}

/**
 * TLS (`ssl`) or cleartext TCP CoT stream client for one server. Sends CoT XML lines and replies
 * to TAK Server connection pings (`t-x-c-t` -> `t-x-c-t-r`). Reconnects with exponential backoff
 * and a circuit breaker that trips well under infra-TAK's fail2ban thresholds.
 */
class CotStreamClient(
    private val log: RedactedLogger,
    private val scope: CoroutineScope,
) {
    companion object {
        /** infra-TAK fail2ban: ~20 TLS handshake failures / 5 minutes -> ban. Stop well under that. */
        private const val MAX_CONSECUTIVE_TLS_FAILURES = 5
        private const val MAX_CONSECUTIVE_NETWORK_FAILURES = 10
        private const val IDENTICAL_ERROR_LOG_INTERVAL_MS = 2 * 60 * 1000L
        private const val CONNECT_TIMEOUT_MS = 45_000

        private val TAK_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

        private fun formatTakTime(instant: Instant): String = TAK_TIME_FORMATTER.format(instant)
    }

    @Volatile var profile: ServerProfile = ServerProfile()
        private set

    @Volatile var state: TakConnectionState = TakConnectionState.DISCONNECTED
        private set

    @Volatile var lastErrorCode: String? = null
        private set

    @Volatile var lastSendUtc: Instant? = null
        private set

    /** True when auto-reconnect stopped to avoid fail2ban / hammering — caller must retry Connect. */
    @Volatile var autoReconnectSuspended: Boolean = false
        private set

    /** When false (default), reject TLS if trust-store validation fails. When true, soft-accept. */
    @Volatile var allowInsecureTlsSoftAccept: Boolean = false

    var listener: CotStreamListener? = null

    private var clientCertificate: ClientCertificateMaterial? = null
    private var trustConfig: TrustStoreConfig = TrustStoreConfig()

    private val connectMutex = Mutex()
    private val ioGate = Object()
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var readJob: Job? = null

    private var backoffSeconds = 2
    private var consecutiveFailures = 0
    private var lastLoggedFailureKey: String? = null
    private var lastLoggedFailureUtc: Instant = Instant.MIN
    /** Set when the remote cert callback rejects — distinguishes server-trust vs client-cert faults. */
    private var pendingServerTrustReject: String? = null

    fun configureClientCertificate(material: ClientCertificateMaterial?) {
        clientCertificate = material
    }

    fun configureTrustStore(config: TrustStoreConfig) {
        trustConfig = config
    }

    /** Clear circuit-breaker so the next connect/test may retry (e.g. user toggled Connect). */
    fun clearAutoReconnectSuspend() {
        autoReconnectSuspended = false
        consecutiveFailures = 0
        backoffSeconds = 2
    }

    /** Update profile metadata without tearing down a live socket. */
    fun applyProfile(newProfile: ServerProfile) {
        profile = newProfile
    }

    suspend fun connect(target: ServerProfile) {
        connectMutex.withLock {
            profile = target
            pendingServerTrustReject = null
            setState(TakConnectionState.CONNECTING)
            disconnectCore()

            val useSsl = target.protocol.equals("ssl", ignoreCase = true)
            if (useSsl && clientCertificate == null) {
                lastErrorCode = "No client certificate — enroll first"
                noteFailure(target, lastErrorCode!!, tlsOrCert = true)
                setState(TakConnectionState.ERROR)
                throw IllegalStateException(lastErrorCode)
            }

            try {
                withContext(Dispatchers.IO) {
                    val plainSocket = Socket()
                    plainSocket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)

                    val finalSocket: Socket = if (useSsl) {
                        val sslSocket = wrapSsl(plainSocket, target)
                        sslSocket.startHandshake()
                        sslSocket
                    } else {
                        plainSocket
                    }

                    synchronized(ioGate) {
                        socket = finalSocket
                        output = finalSocket.outputStream
                        input = finalSocket.inputStream
                    }
                }

                backoffSeconds = 2
                consecutiveFailures = 0
                autoReconnectSuspended = false
                lastErrorCode = null
                setState(TakConnectionState.CONNECTED)
                listener?.onConnected(this, target)
                readJob = scope.launch { readLoop() }
                log.info("TAK", "Connected profile=${profileLabel(target)} via ${target.protocol}.")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                val human = pendingServerTrustReject ?: humanizeConnectError(ex)
                val tls = isTlsOrCertFailure(ex, human)
                noteFailure(target, human, tls)
                lastErrorCode = human
                setState(TakConnectionState.ERROR)
                disconnectCore()
                throw ex
            }
        }
    }

    suspend fun test(target: ServerProfile): Pair<Boolean, String> {
        if (target.protocol.equals("ssl", ignoreCase = true) && clientCertificate == null)
            return false to "No client certificate — enroll first (paste a Portal enroll URL) or import a .p12."

        return try {
            connect(target)
            disconnect()
            true to "Connection test passed."
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val msg = lastErrorCode ?: humanizeConnectError(ex)
            false to if (msg.startsWith("Connection test")) msg else "Connection test failed: $msg"
        }
    }

    suspend fun send(cotXml: String) {
        val out: OutputStream?
        synchronized(ioGate) { out = output }
        if (out == null || state != TakConnectionState.CONNECTED)
            throw IllegalStateException("Not connected.")

        withContext(Dispatchers.IO) {
            val text = if (cotXml.endsWith("\n")) cotXml else cotXml + "\n"
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        lastSendUtc = Instant.now()
    }

    suspend fun disconnect() {
        setState(TakConnectionState.DISCONNECTED)
        disconnectCore()
    }

    suspend fun reconnectWithBackoff(target: ServerProfile) {
        while (scope.isActive && target.enabled) {
            if (autoReconnectSuspended) {
                setState(TakConnectionState.ERROR)
                return
            }

            setState(TakConnectionState.RECONNECTING)
            try {
                connect(target)
                return
            } catch (ex: CancellationException) {
                return
            } catch (ex: Exception) {
                val human = lastErrorCode ?: humanizeConnectError(ex)
                val tls = isTlsOrCertFailure(ex, human)
                val max = if (tls) MAX_CONSECUTIVE_TLS_FAILURES else MAX_CONSECUTIVE_NETWORK_FAILURES

                if (consecutiveFailures >= max) {
                    suspendAutoReconnect(target, human, tls)
                    return
                }

                val delaySeconds = if (tls) backoffSeconds.coerceIn(15, 120) else backoffSeconds.coerceAtMost(60)
                backoffSeconds = (backoffSeconds * 2).coerceAtMost(if (tls) 120 else 60)
                try {
                    delay(delaySeconds * 1000L)
                } catch (ce: CancellationException) {
                    return
                }
            }
        }
    }

    private fun wrapSsl(plainSocket: Socket, target: ServerProfile): SSLSocket {
        val cert = clientCertificate ?: throw IllegalStateException("No client certificate — enroll first")
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(cert.pkcs12Bytes.inputStream(), cert.password)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, cert.password)

        val trustManagers = buildTrustManagers(target)
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(kmf.keyManagers, trustManagers, SecureRandom())

        val socketFactory = sslContext.socketFactory
        val sslSocket = socketFactory.createSocket(plainSocket, target.host, target.port, true) as SSLSocket
        sslSocket.useClientMode = true
        return sslSocket
    }

    private fun buildTrustManagers(target: ServerProfile): Array<TrustManager> {
        val customBytes = trustConfig.pkcs12Bytes
        val baseTrustManager: X509TrustManager = if (customBytes != null) {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(customBytes.inputStream(), trustConfig.password ?: "atakatak".toCharArray())
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        } else {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        val softAccept = allowInsecureTlsSoftAccept || trustConfig.allowInsecureSoftAccept
        val wrapper = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    baseTrustManager.checkServerTrusted(chain, authType)
                } catch (ex: Exception) {
                    if (!softAccept) {
                        pendingServerTrustReject =
                            "TLS failed — TAK Server certificate not trusted (private CA / incomplete trust " +
                                "store). Re-enroll so the full CA chain is saved, or enable Diagnostics -> TLS " +
                                "soft-accept for lab CAs. (${ex.javaClass.simpleName})"
                        if (shouldLog("${target.id}|tls-reject|${ex.javaClass.simpleName}"))
                            log.warn(
                                "TAK",
                                "TLS rejected (${ex.javaClass.simpleName}) profile=${profileLabel(target)} — soft-accept disabled.",
                            )
                        throw ex
                    }

                    if (shouldLog("${target.id}|soft-accept|${ex.javaClass.simpleName}"))
                        log.warn("TAK", "TLS soft-accept (${ex.javaClass.simpleName}) profile=${profileLabel(target)}.")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = baseTrustManager.acceptedIssuers
        }

        return arrayOf(wrapper)
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(8192)
        val pending = StringBuilder()
        try {
            while (scope.isActive) {
                val inp: InputStream?
                synchronized(ioGate) { inp = input }
                if (inp == null) break

                val n = withContext(Dispatchers.IO) {
                    try {
                        inp.read(buffer)
                    } catch (ex: IOException) {
                        -1
                    }
                }
                if (n <= 0) break

                pending.append(String(buffer, 0, n, Charsets.UTF_8))
                // Cap backlog so a noisy peer cannot grow memory unbounded.
                if (pending.length > 256_000) pending.delete(0, pending.length - 64_000)

                processInbound(pending)
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            lastErrorCode = "Stream ended (${ex.javaClass.simpleName})"
            logConnectionFailure(profile, lastErrorCode!!)
        }

        if (state == TakConnectionState.CONNECTED) setState(TakConnectionState.DISCONNECTED)
    }

    /** Reply to TAK Server connection tests (`t-x-c-t` -> `t-x-c-t-r`); other inbound CoT is ignored. */
    private suspend fun processInbound(pending: StringBuilder) {
        while (true) {
            val text = pending.toString()
            val start = text.indexOf("<event", ignoreCase = true)
            if (start < 0) {
                pending.clear()
                return
            }
            if (start > 0) pending.delete(0, start)

            val text2 = pending.toString()
            val end = text2.indexOf("</event>", ignoreCase = true)
            if (end < 0) return

            val endIndex = end + "</event>".length
            val xml = text2.substring(0, endIndex)
            pending.delete(0, endIndex)

            if (!xml.contains("t-x-c-t", ignoreCase = true)) continue
            // Avoid treating our own pong as a new ping.
            if (xml.contains("t-x-c-t-r", ignoreCase = true)) continue

            try {
                val pong = buildPingResponse(xml)
                if (pong != null) send(pong)
            } catch (ex: Exception) {
                if (shouldLog("${profile.id}|ping|${ex.javaClass.simpleName}"))
                    log.warn("TAK", "Ping response failed: ${ex.javaClass.simpleName}")
            }
        }
    }

    private fun buildPingResponse(pingXml: String): String? {
        val typeMatch = Regex("""type\s*=\s*"([^"]*)"""").find(pingXml) ?: return null
        if (!typeMatch.groupValues[1].equals("t-x-c-t", ignoreCase = true)) return null

        val uid = Regex("""uid\s*=\s*"([^"]*)"""").find(pingXml)?.groupValues?.get(1) ?: "pong"
        val now = Instant.now()
        val sb = StringBuilder()
        sb.append("<event version=\"2.0\" uid=\"").append(CotXmlEscape.attr(uid))
            .append("\" type=\"t-x-c-t-r\" how=\"h-g-i-g-o\" time=\"").append(formatTakTime(now))
            .append("\" start=\"").append(formatTakTime(now))
            .append("\" stale=\"").append(formatTakTime(now.plusSeconds(20)))
            .append("\"><point lat=\"0.0\" lon=\"0.0\" hae=\"0.0\" ce=\"9999999.0\" le=\"9999999.0\"/><detail/></event>\n")
        return sb.toString()
    }

    private fun noteFailure(target: ServerProfile, error: String, tlsOrCert: Boolean) {
        consecutiveFailures++
        logConnectionFailure(target, error)
    }

    private fun suspendAutoReconnect(target: ServerProfile, lastError: String, tlsOrCert: Boolean) {
        autoReconnectSuspended = true
        lastErrorCode = if (tlsOrCert) {
            "$lastError — stopped auto-reconnect after $consecutiveFailures failures to avoid infra-TAK " +
                "fail2ban (TLS probes). Fix the certificate/enrollment, then toggle Connect or use Test."
        } else {
            "$lastError — stopped auto-reconnect after $consecutiveFailures failures. " +
                "Check network/DNS/firewall (or fail2ban ban), then toggle Connect or use Test."
        }
        logConnectionFailure(target, lastErrorCode!!)
        log.error(
            "TAK",
            "Auto-reconnect suspended for '${profileLabel(target)}' after $consecutiveFailures failures " +
                "(tlsOrCert=$tlsOrCert). Manual retry required.",
        )
        setState(TakConnectionState.ERROR)
    }

    private fun humanizeConnectError(ex: Throwable): String = when (ex) {
        is IllegalStateException -> ex.message ?: "Invalid state"
        is SocketTimeoutException -> "Connection timed out (server unreachable or TLS handshake stalled)"
        is SSLHandshakeException ->
            "TLS authentication failed — client certificate rejected by the TAK Server, or the server " +
                "certificate was not trusted. Re-enroll or enable Diagnostics soft-accept. Repeated retries " +
                "can trigger fail2ban."
        is SSLException -> "TLS error (${ex.javaClass.simpleName}): ${ex.message}"
        is GeneralSecurityException ->
            "Client certificate could not be loaded (bad password or key store). Fix the .p12 before retrying."
        is IOException -> "Network error: ${ex.message}"
        else -> ex.javaClass.simpleName
    }

    private fun isTlsOrCertFailure(ex: Throwable, human: String? = null): Boolean {
        if (ex is SSLException || ex is GeneralSecurityException || ex is SocketTimeoutException) return true
        val h = human ?: ex.message ?: ""
        return h.contains("TLS", ignoreCase = true) ||
            h.contains("certificate", ignoreCase = true) ||
            h.contains("handshake", ignoreCase = true)
    }

    private fun logConnectionFailure(target: ServerProfile, error: String) {
        if (!shouldLog("${target.id}|$error")) return
        log.error("TAK", "Server '${profileLabel(target)}' id=${shortId(target.id)} connection failed: $error")
    }

    private fun shouldLog(key: String): Boolean {
        val now = Instant.now()
        if (key == lastLoggedFailureKey &&
            Duration.between(lastLoggedFailureUtc, now).toMillis() < IDENTICAL_ERROR_LOG_INTERVAL_MS
        ) {
            return false
        }
        lastLoggedFailureKey = key
        lastLoggedFailureUtc = now
        return true
    }

    private fun profileLabel(target: ServerProfile): String =
        target.displayName.takeIf { it.isNotBlank() } ?: shortId(target.id)

    private fun shortId(id: String): String = if (id.isEmpty()) "?" else if (id.length <= 8) id else id.take(8)

    private suspend fun disconnectCore() {
        readJob?.let {
            try {
                it.cancelAndJoin()
            } catch (_: Exception) { /* ignore */ }
        }
        readJob = null

        synchronized(ioGate) {
            try { input?.close() } catch (_: Exception) { /* ignore */ }
            try { output?.close() } catch (_: Exception) { /* ignore */ }
            try { socket?.close() } catch (_: Exception) { /* ignore */ }
            input = null
            output = null
            socket = null
        }
    }

    private fun setState(newState: TakConnectionState) {
        state = newState
        listener?.onStateChanged(this, newState)
    }
}

/** Minimal XML attribute escaping shared by ping-response building (avoids a hard dep on cot module). */
internal object CotXmlEscape {
    fun attr(value: String): String {
        val sb = StringBuilder(value.length)
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
