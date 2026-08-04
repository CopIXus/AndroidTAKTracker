package com.copix.androidtaktracker.core.mesh

import com.copix.androidtaktracker.core.config.MeshSaSettings
import com.copix.androidtaktracker.core.util.RedactedLogger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.time.Instant
import java.util.Collections

/**
 * ATAK-compatible Mesh SA UDP multicast sender (239.2.3.1:6969 by default). Interface selection is
 * simplified relative to the Windows client: Auto picks the first up, non-loopback IPv4 interface
 * (Wi-Fi/Ethernet on most Android devices); an explicit interface name may be configured instead.
 */
class MeshSaBroadcaster(private val log: RedactedLogger) {
    private val gate = Object()
    private var socket: MulticastSocket? = null
    private var settings: MeshSaSettings = MeshSaSettings()
    private var groupAddress: InetAddress? = null

    var lastSendUtc: Instant? = null
        private set

    /** Human-readable adapter used for send, e.g. "wlan0 (192.168.1.10)". */
    var lastInterfaceDescription: String? = null
        private set

    /** Non-null when Auto fell back to a non-ideal NIC or no usable NIC exists. */
    var lastInterfaceWarning: String? = null
        private set

    var lastErrorCode: String? = null
        private set

    val isReady: Boolean get() = synchronized(gate) { socket != null }

    fun applySettings(newSettings: MeshSaSettings) {
        settings = newSettings
        rebind()
    }

    fun rebind() {
        synchronized(gate) {
            try {
                socket?.close()
            } catch (_: Exception) { /* ignore */ }
            socket = null
            groupAddress = null
            lastInterfaceDescription = null
            lastInterfaceWarning = null

            if (!settings.enabled) return

            try {
                val address = InetAddress.getByName(settings.multicastAddress)
                groupAddress = address

                val selection = selectInterface(settings.networkInterface)
                if (selection == null) {
                    lastErrorCode = "NoUsableInterface"
                    lastInterfaceDescription = "none"
                    lastInterfaceWarning = "No usable IPv4 adapter for Mesh SA multicast."
                    log.warn("Mesh", "Mesh bind failed: no usable IPv4 interface.")
                    return
                }

                val newSocket = MulticastSocket(0)
                newSocket.timeToLive = 1
                newSocket.networkInterface = selection.nic
                newSocket.joinGroup(InetSocketAddress(address, settings.multicastPort), selection.nic)

                lastInterfaceDescription = "${selection.nic.displayName} (${selection.ipv4.hostAddress})"
                socket = newSocket
                lastErrorCode = null
                log.info(
                    "Mesh",
                    "Mesh SA bound (${settings.multicastAddress}:${settings.multicastPort}) via " +
                        "$lastInterfaceDescription.",
                )
            } catch (ex: Exception) {
                lastErrorCode = ex.javaClass.simpleName
                log.warn("Mesh", "Mesh bind failed: ${ex.javaClass.simpleName}")
            }
        }
    }

    fun trySend(cotXml: String): Boolean {
        val currentSocket: MulticastSocket?
        val address: InetAddress?
        synchronized(gate) {
            currentSocket = socket
            address = groupAddress
        }

        if (currentSocket == null || address == null || !settings.enabled) return false

        return try {
            val bytes = cotXml.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, address, settings.multicastPort)
            currentSocket.send(packet)
            lastSendUtc = Instant.now()
            lastErrorCode = null
            true
        } catch (ex: Exception) {
            lastErrorCode = ex.javaClass.simpleName
            log.warn("Mesh", "Mesh send failed: ${ex.javaClass.simpleName}")
            false
        }
    }

    /** Interface names for the settings picker: "Auto" plus every up, non-loopback IPv4 NIC. */
    fun listInterfaces(): List<String> =
        listOf("Auto") + candidateInterfaces().map { it.displayName }

    private data class InterfaceSelection(val nic: NetworkInterface, val ipv4: InetAddress)

    private fun selectInterface(preference: String): InterfaceSelection? {
        val nics = candidateInterfaces()
        val auto = preference.isBlank() || preference.equals("Auto", ignoreCase = true)

        val nic = if (auto) {
            nics.firstOrNull()
        } else {
            nics.firstOrNull { it.name.equals(preference, ignoreCase = true) || it.displayName.equals(preference, ignoreCase = true) }
                ?: nics.firstOrNull { it.displayName.contains(preference, ignoreCase = true) }
        } ?: return null

        val ipv4 = primaryIpv4(nic) ?: return null
        return InterfaceSelection(nic, ipv4)
    }

    private fun candidateInterfaces(): List<NetworkInterface> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && primaryIpv4(it) != null }
            .sortedBy { scoreForAuto(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun primaryIpv4(nic: NetworkInterface): InetAddress? =
        Collections.list(nic.inetAddresses)
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

    /** Lower score = preferred for Auto. Wi-Fi/Ethernet win; mobile data / VPN tunnels lose. */
    private fun scoreForAuto(nic: NetworkInterface): Int {
        val name = nic.name.lowercase()
        return when {
            name.startsWith("wlan") || name.startsWith("eth") -> 0
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("radio") -> 50
            name.startsWith("tun") || name.startsWith("ppp") || name.contains("vpn") -> 100
            else -> 10
        }
    }

    fun close() {
        synchronized(gate) {
            try {
                socket?.close()
            } catch (_: Exception) { /* ignore */ }
            socket = null
        }
    }
}
