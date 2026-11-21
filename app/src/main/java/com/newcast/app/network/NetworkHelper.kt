package com.newcast.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.newcast.app.model.LocalNetworkInterfaceInfo
import com.newcast.app.model.LocalNetworkSnapshot
import com.newcast.app.model.NetworkLinkType
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkHelper {

    private val excludedPrefixes = listOf(
        "lo", "rmnet", "ccmni", "tun", "tap", "dummy", "v4-rmnet", "epdg", "iwlan", "sit", "ip6tnl"
    )

    private val hotspotInterfacePrefixes = listOf(
        "ap", "swlan", "softap", "wlan1", "wlan2", "tether"
    )

    fun inspectLocalNetwork(context: Context): LocalNetworkSnapshot {
        val discoveredInterfaces = mutableListOf<LocalNetworkInterfaceInfo>()
        try {
            val allIfaces = NetworkInterface.getNetworkInterfaces()
            while (allIfaces != null && allIfaces.hasMoreElements()) {
                val iface = allIfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name.lowercase()
                if (excludedPrefixes.any { name.startsWith(it) }) continue

                for (addr in iface.interfaceAddresses) {
                    val inet = addr.address
                    if (inet is Inet4Address && !inet.isLoopbackAddress && inet.isSiteLocalAddress) {
                        val ip = inet.hostAddress ?: continue
                        val broadcast = addr.broadcast?.hostAddress
                        val isAp = isLikelyHotspotInterface(name, ip)
                        discoveredInterfaces += LocalNetworkInterfaceInfo(
                            interfaceName = iface.name,
                            ipAddress = ip,
                            broadcastAddress = broadcast,
                            isHotspotAp = isAp
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore transient interface enumeration glitches
        }

        // Prioritize Hotspot AP interfaces first, then wlan0, then anything else
        val sorted = discoveredInterfaces.sortedWith(
            compareByDescending<LocalNetworkInterfaceInfo> { it.isHotspotAp }
                .thenByDescending { it.interfaceName.startsWith("wlan", ignoreCase = true) }
                .thenBy { it.interfaceName }
        )

        val gatewayIp = findWifiGatewayIp(context)
        val (freqMhz, linkMbps) = readWifiMetrics(context)

        val hasHotspotAp = sorted.any { it.isHotspotAp }
        val linkType = when {
            hasHotspotAp -> NetworkLinkType.HOTSPOT_HOST
            sorted.isNotEmpty() && isLikelyPhoneHotspotSubnet(sorted.first().ipAddress, gatewayIp) ->
                NetworkLinkType.HOTSPOT_OR_WIFI
            sorted.isNotEmpty() -> NetworkLinkType.WIFI_LAN
            else -> NetworkLinkType.OFFLINE
        }

        return LocalNetworkSnapshot(
            linkType = linkType,
            primaryIp = sorted.firstOrNull()?.ipAddress,
            gatewayIp = gatewayIp,
            interfaces = sorted,
            wifiFrequencyMhz = freqMhz,
            linkSpeedMbps = linkMbps
        )
    }

    private fun isLikelyHotspotInterface(ifaceNameLower: String, ip: String): Boolean {
        if (hotspotInterfacePrefixes.any { ifaceNameLower.startsWith(it) }) return true
        // Android tethering often allocates .1 on 192.168.43.1 or 192.168.x.1 on secondary wlan
        if (ip == "192.168.43.1") return true
        return false
    }

    private fun isLikelyPhoneHotspotSubnet(localIp: String, gatewayIp: String?): Boolean {
        if (localIp.startsWith("192.168.43.") || gatewayIp == "192.168.43.1") return true
        // Android 11+ Pixels randomize Hotspot subnet into 192.168.<random>.x with .1 gateway
        return gatewayIp != null && gatewayIp.endsWith(".1") && localIp.startsWith("192.168.")
    }

    @Suppress("DEPRECATION")
    private fun findWifiGatewayIp(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifiNetwork = cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } ?: cm.activeNetwork

            val linkProps = wifiNetwork?.let { cm.getLinkProperties(it) } ?: return null

            // API 30+: dhcpServerAddress directly identifies the Hotspot host or Wi-Fi router
            val dhcpServer = linkProps.dhcpServerAddress
            if (dhcpServer is Inet4Address && dhcpServer.isSiteLocalAddress) {
                return dhcpServer.hostAddress
            }

            // Fallback: find IPv4 default route gateway
            linkProps.routes.firstOrNull { route ->
                route.isDefaultRoute && route.gateway is Inet4Address
            }?.gateway?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readWifiMetrics(context: Context): Pair<Int?, Int?> {
        return try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            val freq = info?.frequency?.takeIf { it > 0 }
            val speed = info?.linkSpeed?.takeIf { it > 0 }
            freq to speed
        } catch (_: Exception) {
            null to null
        }
    }

    /**
     * Acquires Android's WIFI_MODE_FULL_LOW_LATENCY lock to disable Wi-Fi power-saving jitter
     * while streaming 60fps video.
     */
    fun acquireLowLatencyWifiLock(context: Context, tag: String): WifiManager.WifiLock? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wm.createWifiLock(mode, tag).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getDeviceDisplayName(): String {
        val model = Build.MODEL.orEmpty().trim()
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        return when {
            model.isEmpty() -> "Android Pixel"
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.equals("Google", ignoreCase = true) -> model // e.g. "Pixel 8", "Pixel 6 Pro"
            else -> "$manufacturer $model"
        }
    }
}
