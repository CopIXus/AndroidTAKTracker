package com.copix.androidtaktracker.mesh

import android.content.Context
import android.net.wifi.WifiManager
import com.copix.androidtaktracker.atak.AtakCoexistence
import com.copix.androidtaktracker.core.config.MeshSaSettings
import com.copix.androidtaktracker.core.util.RedactedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Holds a [WifiManager.MulticastLock] while Mesh SA is enabled, and optionally listens for
 * foreign ATAK self-SA on the multicast group so [AtakCoexistence] can defer PLI.
 */
class MeshMulticastSupport(
    context: Context,
    private val log: RedactedLogger,
    private val atak: AtakCoexistence,
    private val scope: CoroutineScope,
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var listenJob: Job? = null
    private var listenSocket: MulticastSocket? = null

    fun apply(settings: MeshSaSettings, ourUid: String?) {
        if (settings.enabled) acquireLock() else releaseLock()
        restartListen(settings, ourUid)
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        try { listenSocket?.close() } catch (_: Exception) {}
        listenSocket = null
        releaseLock()
    }

    private fun acquireLock() {
        if (lock?.isHeld == true) return
        val wifi = this.wifi ?: return
        val created = wifi.createMulticastLock("AndroidTAKTracker-MeshSA").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        lock = created
        log.info("Mesh", "MulticastLock acquired.")
    }

    private fun releaseLock() {
        try {
            if (lock?.isHeld == true) lock?.release()
        } catch (_: Exception) { /* ignore */ }
        lock = null
    }

    private fun restartListen(settings: MeshSaSettings, ourUid: String?) {
        listenJob?.cancel()
        try { listenSocket?.close() } catch (_: Exception) {}
        listenSocket = null
        if (!settings.enabled) return

        listenJob = scope.launch(Dispatchers.IO) {
            try {
                val group = InetAddress.getByName(settings.multicastAddress)
                val socket = MulticastSocket(settings.multicastPort)
                val nic = Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { ni ->
                    ni.isUp && !ni.isLoopback &&
                        Collections.list(ni.inetAddresses).any {
                            !it.isLoopbackAddress && it.hostAddress?.contains(':') != true
                        }
                }
                if (nic != null) {
                    socket.networkInterface = nic
                    socket.joinGroup(InetSocketAddress(group, settings.multicastPort), nic)
                } else {
                    socket.joinGroup(group)
                }
                listenSocket = socket
                val buf = ByteArray(64 * 1024)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val xml = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                    if (looksLikeForeignAtakSelfSa(xml, ourUid)) {
                        atak.noteHeardOnMesh()
                    }
                }
            } catch (_: Exception) {
                // Socket closed on cancel / rebind — expected.
            }
        }
    }

    companion object {
        internal fun looksLikeForeignAtakSelfSa(xml: String, ourUid: String?): Boolean {
            if (!xml.contains("<event") || !xml.contains("contact")) return false
            if (ourUid != null && xml.contains("uid=\"$ourUid\"")) return false
            // ATAK-shaped platform or classic ANDROID-* UID — not our ANDROIDTAKTRACKER-* prefix.
            val atakish =
                xml.contains("platform=\"ATAK") ||
                    xml.contains("uid=\"ANDROID-") ||
                    xml.contains("platform=\"civtak") ||
                    xml.contains("platform=\"TAK")
            return atakish
        }
    }
}
