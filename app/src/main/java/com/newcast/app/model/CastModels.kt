package com.newcast.app.model

import kotlin.math.roundToInt

enum class AppRole {
    HOME,
    SENDER,
    MONITOR
}

enum class TransportMode(val label: String, val subtitle: String) {
    UDP_LOW_LATENCY(
        label = "UDP Độ trễ thấp",
        subtitle = "Mượt nhất (~35–70ms), ưu tiên cho Hotspot & Wi-Fi 5GHz"
    ),
    TCP_RELIABLE(
        label = "TCP Ổn định",
        subtitle = "Không vỡ hình khi nhiễu sóng, độ trễ nhỉnh hơn nhẹ"
    )
}

enum class VideoCodecType(val id: Byte, val mimeType: String, val label: String) {
    H264(0, "video/avc", "H.264 (Nhanh nhất)"),
    H265(1, "video/hevc", "H.265 (Tiết kiệm băng thông)");

    companion object {
        fun fromId(id: Byte): VideoCodecType = entries.firstOrNull { it.id == id } ?: H264
    }
}

enum class VideoQualityPreset(
    val shortSidePx: Int,
    val fps: Int,
    val bitrateBps: Int,
    val label: String,
    val badge: String,
    val description: String
) {
    SMOOTH_720P60(
        shortSidePx = 720,
        fps = 60,
        bitrateBps = 6_500_000,
        label = "720p 60fps • Mượt nhất",
        badge = "720p60",
        description = "Khuyên dùng: siêu mượt 60fps, mát máy & tiết kiệm pin khi quay 4K60"
    ),
    CRISP_1080P60(
        shortSidePx = 1080,
        fps = 60,
        bitrateBps = 12_000_000,
        label = "1080p 60fps • Sắc nét",
        badge = "1080p60",
        description = "Cực nét để kiểm tra lấy nét tay, yêu cầu Hotspot 5GHz gần"
    ),
    COOL_720P30(
        shortSidePx = 720,
        fps = 30,
        bitrateBps = 4_000_000,
        label = "720p 30fps • Tiết kiệm pin",
        badge = "720p30",
        description = "Mát máy & tối ưu pin tối đa cho buổi quay dài ngoài trời"
    );

    /**
     * Computes hardware-encoder-safe capture dimensions (multiples of 16)
     * preserving the exact aspect ratio of the device screen.
     */
    fun computeAlignedDimensions(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val safeW = screenWidth.coerceAtLeast(320)
        val safeH = screenHeight.coerceAtLeast(320)
        val isPortrait = safeH >= safeW
        val rawShort = if (isPortrait) safeW else safeH
        val rawLong = if (isPortrait) safeH else safeW

        val targetShort = minOf(shortSidePx, rawShort)
        val scale = targetShort.toFloat() / rawShort.toFloat()
        val targetLong = (rawLong * scale).roundToInt()

        val alignedShort = alignTo16(targetShort)
        val alignedLong = alignTo16(targetLong)

        return if (isPortrait) {
            alignedShort to alignedLong
        } else {
            alignedLong to alignedShort
        }
    }

    companion object {
        fun alignTo16(value: Int): Int {
            val clamped = value.coerceAtLeast(160)
            return ((clamped + 8) / 16) * 16
        }

        fun fromNameOrDefault(name: String?): VideoQualityPreset =
            entries.firstOrNull { it.name == name } ?: SMOOTH_720P60
    }
}

enum class NetworkLinkType(val label: String) {
    HOTSPOT_HOST("Đang phát Hotspot"),
    HOTSPOT_OR_WIFI("Hotspot / Wi-Fi"),
    WIFI_LAN("Mạng Wi-Fi"),
    OFFLINE("Chưa bật Hotspot / Wi-Fi")
}

data class LocalNetworkInterfaceInfo(
    val interfaceName: String,
    val ipAddress: String,
    val broadcastAddress: String?,
    val isHotspotAp: Boolean
)

data class LocalNetworkSnapshot(
    val linkType: NetworkLinkType,
    val primaryIp: String?,
    val gatewayIp: String?,
    val interfaces: List<LocalNetworkInterfaceInfo>,
    val wifiFrequencyMhz: Int? = null,
    val linkSpeedMbps: Int? = null
) {
    val is5GhzOrHigher: Boolean
        get() = (wifiFrequencyMhz ?: 0) >= 4900

    val allIps: List<String>
        get() = interfaces.map { it.ipAddress }.distinct()
}

data class DiscoveredPeer(
    val deviceName: String,
    val ipAddress: String,
    val controlPort: Int,
    val linkMode: String,
    val accessibilityReady: Boolean,
    val lastSeenMs: Long
)

data class SenderRuntimeState(
    val isCasting: Boolean = false,
    val statusText: String = "Sẵn sàng phát màn hình",
    val preset: VideoQualityPreset = VideoQualityPreset.SMOOTH_720P60,
    val transportMode: TransportMode = TransportMode.UDP_LOW_LATENCY,
    val codecType: VideoCodecType = VideoCodecType.H264,
    val captureWidth: Int = 0,
    val captureHeight: Int = 0,
    val encodedFps: Int = 0,
    val currentBitrateMbps: Float = 0f,
    val connectedMonitorName: String? = null,
    val connectedMonitorIp: String? = null,
    val errorMessage: String? = null
)

data class MonitorStats(
    val fps: Int = 0,
    val rttMs: Int = 0,
    val bitrateMbps: Float = 0f,
    val packetLossPct: Float = 0f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val transportLabel: String = "UDP"
)

sealed class MonitorConnectionState {
    data object Idle : MonitorConnectionState()
    data class Connecting(val targetIp: String) : MonitorConnectionState()
    data class Connected(
        val hostName: String,
        val hostIp: String,
        val accessibilityReady: Boolean,
        val preset: VideoQualityPreset
    ) : MonitorConnectionState()
    data class Reconnecting(val targetIp: String, val attempt: Int, val reason: String) : MonitorConnectionState()
    data class Error(val message: String) : MonitorConnectionState()
}
