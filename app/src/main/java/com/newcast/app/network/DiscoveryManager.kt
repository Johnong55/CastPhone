package com.newcast.app.network

import android.content.Context
import android.util.Log
import com.newcast.app.model.DiscoveredPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Bidirectional UDP Discovery over Hotspot (AP/Client both directions) and Wi-Fi LAN.
 *
 * - Sender runs [HostDiscoveryAdvertiser]: broadcasts host beacons on all active Wi-Fi/Hotspot
 *   interfaces AND immediately answers direct PROBE packets via unicast.
 * - Monitor runs [MonitorDiscoveryScanner]: listens for host beacons AND actively sends PROBE
 *   packets to subnet broadcasts + Hotspot gateway (dhcpServerAddress), so discovery succeeds
 *   in < 1 second regardless of which phone hosts the Hotspot.
 */
class HostDiscoveryAdvertiser(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isAccessibilityReady: () -> Boolean
) {
    private var job: Job? = null
    private var socket: DatagramSocket? = null

    @Volatile
    private var beaconsPaused: Boolean = false

    fun setBeaconsPaused(paused: Boolean) {
        beaconsPaused = paused
    }

    fun start() {
        stop()
        job = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(CastPorts.DISCOVERY_UDP))
                }
                socket = sock

                val recvBuf = ByteArray(1024)
                var lastBroadcastMs = 0L

                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (!beaconsPaused && now - lastBroadcastMs >= 1100L) {
                        lastBroadcastMs = now
                        sendBeacons(sock)
                    }

                    try {
                        val targetTimeout = if (beaconsPaused) 2500 else 650
                        if (sock.soTimeout != targetTimeout) {
                            sock.soTimeout = targetTimeout
                        }
                        val packet = DatagramPacket(recvBuf, recvBuf.size)
                        sock.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(text)
                        if (json.optString("type") == "NEWCAST_PROBE") {
                            val reply = buildBeaconPayload()
                            if (reply != null) {
                                val bytes = reply.toByteArray(Charsets.UTF_8)
                                val unicast = DatagramPacket(
                                    bytes,
                                    bytes.size,
                                    packet.address,
                                    packet.port
                                )
                                sock.send(unicast)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Normal polling tick
                    } catch (_: Exception) {
                        // Ignore malformed packets
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Host discovery advertiser stopped: ${e.message}")
            } finally {
                closeQuietly()
            }
        }
    }

    private fun sendBeacons(sock: DatagramSocket) {
        val snapshot = NetworkHelper.inspectLocalNetwork(context)
        val targets = linkedSetOf<String>()

        // Directed subnet broadcasts on every Wi-Fi / Hotspot AP interface
        snapshot.interfaces.forEach { iface ->
            iface.broadcastAddress?.let { targets.add(it) }
        }
        // Unicast directly to Hotspot gateway if Sender is joined to Monitor's Hotspot
        snapshot.gatewayIp?.let { targets.add(it) }
        targets.add("255.255.255.255")

        val primaryIp = snapshot.primaryIp ?: return
        val modeLabel = snapshot.linkType.label

        for (target in targets) {
            try {
                // Pick the interface IP that matches the target subnet when available
                val advertisedIp = snapshot.interfaces.firstOrNull { it.broadcastAddress == target }?.ipAddress
                    ?: primaryIp
                val payload = buildBeaconJson(advertisedIp, modeLabel).toByteArray(Charsets.UTF_8)
                val dest = InetAddress.getByName(target)
                sock.send(DatagramPacket(payload, payload.size, dest, CastPorts.DISCOVERY_UDP))
            } catch (_: Exception) {
                // Best-effort across interfaces
            }
        }
    }

    private fun buildBeaconPayload(): String? {
        val snapshot = NetworkHelper.inspectLocalNetwork(context)
        val ip = snapshot.primaryIp ?: return null
        return buildBeaconJson(ip, snapshot.linkType.label)
    }

    private fun buildBeaconJson(ip: String, modeLabel: String): String {
        return JSONObject().apply {
            put("type", "NEWCAST_HOST")
            put("name", NetworkHelper.getDeviceDisplayName())
            put("ip", ip)
            put("port", CastPorts.CONTROL_TCP)
            put("mode", modeLabel)
            put("acc", isAccessibilityReady())
        }.toString()
    }

    fun stop() {
        job?.cancel()
        job = null
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    companion object {
        private const val TAG = "NewCastDiscovery"
    }
}

class MonitorDiscoveryScanner(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    private var job: Job? = null
    private var listenSocket: DatagramSocket? = null
    private var probeSocket: DatagramSocket? = null

    fun start() {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val found = LinkedHashMap<String, DiscoveredPeer>()
            val localIps = NetworkHelper.inspectLocalNetwork(context).allIps.toSet()

            try {
                // Listen socket bound to DISCOVERY_UDP receives host broadcasts
                val lSock = try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 350
                        bind(InetSocketAddress(CastPorts.DISCOVERY_UDP))
                    }
                } catch (_: Exception) {
                    // Fallback ephemeral port if bound elsewhere; probe replies still arrive here!
                    DatagramSocket().apply {
                        broadcast = true
                        soTimeout = 350
                    }
                }
                listenSocket = lSock

                val pSock = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 250
                }
                probeSocket = pSock

                val recvBuf = ByteArray(1024)
                var lastProbeMs = 0L

                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (now - lastProbeMs >= 900L) {
                        lastProbeMs = now
                        sendProbes(pSock)
                        // Expire stale peers after 4.5s
                        val changed = found.entries.removeIf { now - it.value.lastSeenMs > 4500L }
                        if (changed) {
                            _peers.value = found.values.sortedByDescending { it.lastSeenMs }
                        }
                    }

                    // Drain responses on both probe socket (unicast replies) and listen socket (broadcasts)
                    receiveOnce(pSock, recvBuf, localIps, found)
                    receiveOnce(lSock, recvBuf, localIps, found)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Monitor discovery stopped: ${e.message}")
            } finally {
                closeQuietly()
            }
        }
    }

    private fun receiveOnce(
        sock: DatagramSocket,
        buf: ByteArray,
        localIps: Set<String>,
        found: LinkedHashMap<String, DiscoveredPeer>
    ) {
        try {
            val packet = DatagramPacket(buf, buf.size)
            sock.receive(packet)
            val senderIp = packet.address.hostAddress ?: return
            if (senderIp in localIps) return

            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val json = JSONObject(text)
            if (json.optString("type") != "NEWCAST_HOST") return

            val advertisedIp = json.optString("ip").takeIf { it.isNotBlank() } ?: senderIp
            // Prefer the actual packet source address because it is guaranteed routable from this monitor!
            val effectiveIp = if (senderIp != "0.0.0.0" && !senderIp.startsWith("127.")) senderIp else advertisedIp

            val peer = DiscoveredPeer(
                deviceName = json.optString("name", "Pixel Camera"),
                ipAddress = effectiveIp,
                controlPort = json.optInt("port", CastPorts.CONTROL_TCP),
                linkMode = json.optString("mode", "Hotspot / Wi-Fi"),
                accessibilityReady = json.optBoolean("acc", false),
                lastSeenMs = System.currentTimeMillis()
            )
            found[effectiveIp] = peer
            _peers.value = found.values.sortedByDescending { it.lastSeenMs }
        } catch (_: SocketTimeoutException) {
            // Normal timeout tick
        } catch (_: Exception) {
            // Ignore malformed packet
        }
    }

    private fun sendProbes(sock: DatagramSocket) {
        val snapshot = NetworkHelper.inspectLocalNetwork(context)
        val targets = linkedSetOf<String>()

        // Highest priority: Hotspot host gateway IP (works even if broadcast is filtered!)
        snapshot.gatewayIp?.let { targets.add(it) }
        snapshot.interfaces.forEach { iface ->
            iface.broadcastAddress?.let { targets.add(it) }
        }
        targets.add("255.255.255.255")

        val probeBytes = JSONObject().apply {
            put("type", "NEWCAST_PROBE")
            put("name", NetworkHelper.getDeviceDisplayName())
        }.toString().toByteArray(Charsets.UTF_8)

        for (target in targets) {
            try {
                val dest = InetAddress.getByName(target)
                sock.send(DatagramPacket(probeBytes, probeBytes.size, dest, CastPorts.DISCOVERY_UDP))
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeQuietly()
        _peers.value = emptyList()
    }

    private fun closeQuietly() {
        try { listenSocket?.close() } catch (_: Exception) {}
        try { probeSocket?.close() } catch (_: Exception) {}
        listenSocket = null
        probeSocket = null
    }

    companion object {
        private const val TAG = "NewCastDiscovery"
    }
}
