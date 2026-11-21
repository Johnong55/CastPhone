package com.newcast.app.network

import com.newcast.app.model.TransportMode
import com.newcast.app.model.VideoCodecType
import com.newcast.app.model.VideoQualityPreset
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

object CastPorts {
    const val DISCOVERY_UDP = 50500
    const val CONTROL_TCP = 50501
    const val VIDEO_UDP = 50502
    const val VIDEO_TCP = 50503

    // DSCP Expedited Forwarding (EF) -> Wi-Fi WMM AC_VO / AC_VI low-latency priority
    const val DSCP_EF_TRAFFIC_CLASS = 0xB8
}

object VideoPacketFlags {
    const val NONE: Byte = 0
    const val CONFIG: Byte = 1
    const val KEYFRAME: Byte = 2
    const val END_OF_FRAME: Byte = 4
}

/**
 * 20-byte binary header for ultra-low-latency UDP video fragments:
 *  [0..1]   magic (Short = 0x4E43 "NC")
 *  [2]      flags (Byte: bit0=CONFIG, bit1=KEYFRAME, bit2=EOF)
 *  [3]      codecId (Byte: 0=H264, 1=H265)
 *  [4..7]   frameId (Int)
 *  [8..9]   fragIndex (Short)
 *  [10..11] fragCount (Short)
 *  [12..19] timestampUs (Long)
 */
object UdpVideoPacketizer {
    const val MAGIC: Short = 0x4E43
    const val HEADER_SIZE = 20
    const val MAX_PAYLOAD_SIZE = 1360
    const val MAX_PACKET_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE

    /**
     * Zero-allocation-friendly packetizer: writes each UDP datagram into [scratchPacket]
     * and invokes [onPacket] with (packetBytes, packetLength).
     */
    fun packetize(
        frameId: Int,
        flags: Byte,
        codecId: Byte,
        timestampUs: Long,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
        scratchPacket: ByteArray = ByteArray(MAX_PACKET_SIZE),
        onPacket: (ByteArray, Int) -> Unit
    ) {
        if (length <= 0) return
        val fragCount = ceil(length.toDouble() / MAX_PAYLOAD_SIZE.toDouble()).toInt()
        require(fragCount in 1..Short.MAX_VALUE) { "Frame too large: $length bytes" }

        val headerBuf = ByteBuffer.wrap(scratchPacket, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        var pos = offset
        var remaining = length

        for (fragIndex in 0 until fragCount) {
            val chunkLen = minOf(remaining, MAX_PAYLOAD_SIZE)
            val isLast = fragIndex == fragCount - 1
            val packetFlags = if (isLast) {
                (flags.toInt() or VideoPacketFlags.END_OF_FRAME.toInt()).toByte()
            } else {
                flags
            }

            headerBuf.clear()
            headerBuf.putShort(MAGIC)
            headerBuf.put(packetFlags)
            headerBuf.put(codecId)
            headerBuf.putInt(frameId)
            headerBuf.putShort(fragIndex.toShort())
            headerBuf.putShort(fragCount.toShort())
            headerBuf.putLong(timestampUs)

            System.arraycopy(data, pos, scratchPacket, HEADER_SIZE, chunkLen)
            onPacket(scratchPacket, HEADER_SIZE + chunkLen)

            pos += chunkLen
            remaining -= chunkLen
        }
    }

    /** Convenience helper returning independent packet byte arrays (ideal for testing). */
    fun fragmentFrame(
        frameId: Int,
        flags: Byte,
        codecId: Byte,
        timestampUs: Long,
        data: ByteArray
    ): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val scratch = ByteArray(MAX_PACKET_SIZE)
        packetize(frameId, flags, codecId, timestampUs, data, 0, data.size, scratch) { buf, len ->
            out.add(buf.copyOf(len))
        }
        return out
    }
}

data class EncodedVideoFrame(
    val frameId: Int,
    val flags: Byte,
    val codecId: Byte,
    val timestampUs: Long,
    val data: ByteArray,
    val hadPacketLossBefore: Boolean = false
) {
    val isConfig: Boolean get() = (flags.toInt() and VideoPacketFlags.CONFIG.toInt()) != 0
    val isKeyframe: Boolean get() = (flags.toInt() and VideoPacketFlags.KEYFRAME.toInt()) != 0
}

/**
 * Reassembles fragmented UDP packets into complete encoded frames with minimal jitter
 * and immediate drop-on-stall semantics so latency never accumulates.
 */
class UdpFrameReassembler(
    private val maxInFlightFrames: Int = 4
) {
    private class FrameSlot(
        val frameId: Int,
        val flags: Byte,
        val codecId: Byte,
        val timestampUs: Long,
        val fragCount: Int
    ) {
        val parts = arrayOfNulls<ByteArray>(fragCount)
        var receivedCount = 0
        var totalBytes = 0
    }

    private val slots = LinkedHashMap<Int, FrameSlot>()
    private var lastDeliveredFrameId = -1

    var packetsReceived: Long = 0L
        private set
    var framesDelivered: Long = 0L
        private set
    var framesDropped: Long = 0L
        private set

    fun reset() {
        slots.clear()
        lastDeliveredFrameId = -1
    }

    fun consumeAndResetLossPercent(): Float {
        val delivered = framesDelivered
        val dropped = framesDropped
        framesDelivered = 0L
        framesDropped = 0L
        val total = delivered + dropped
        if (total <= 0L) return 0f
        return (dropped.toFloat() * 100f) / total.toFloat()
    }

    fun onPacket(buffer: ByteArray, length: Int): EncodedVideoFrame? {
        if (length <= UdpVideoPacketizer.HEADER_SIZE) return null
        val header = ByteBuffer.wrap(buffer, 0, UdpVideoPacketizer.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val magic = header.short
        if (magic != UdpVideoPacketizer.MAGIC) return null

        val flags = header.get()
        val codecId = header.get()
        val frameId = header.int
        val fragIndex = header.short.toInt() and 0xFFFF
        val fragCount = header.short.toInt() and 0xFFFF
        val timestampUs = header.long

        if (fragCount <= 0 || fragIndex >= fragCount) return null
        packetsReceived++

        val isKeyframeOrConfig =
            (flags.toInt() and (VideoPacketFlags.KEYFRAME.toInt() or VideoPacketFlags.CONFIG.toInt())) != 0

        // Handle encoder restart / frameId reset gracefully
        if (lastDeliveredFrameId >= 0 && frameId < lastDeliveredFrameId - 10 && isKeyframeOrConfig) {
            slots.clear()
            lastDeliveredFrameId = -1
        }

        if (frameId <= lastDeliveredFrameId) {
            return null
        }

        val payloadLen = length - UdpVideoPacketizer.HEADER_SIZE
        val payload = ByteArray(payloadLen)
        System.arraycopy(buffer, UdpVideoPacketizer.HEADER_SIZE, payload, 0, payloadLen)

        // Single-fragment fast path (avoids HashMap allocation for small frames / SPS-PPS)
        if (fragCount == 1) {
            val missed = purgeOlderIncompleteFrames(frameId)
            val gapMissed = lastDeliveredFrameId >= 0 && frameId > lastDeliveredFrameId + 1
            if (gapMissed && !missed) {
                framesDropped += (frameId - lastDeliveredFrameId - 1)
            }
            lastDeliveredFrameId = frameId
            framesDelivered++
            return EncodedVideoFrame(
                frameId = frameId,
                flags = flags,
                codecId = codecId,
                timestampUs = timestampUs,
                data = payload,
                hadPacketLossBefore = (missed || gapMissed) && !isKeyframeOrConfig
            )
        }

        val slot = slots.getOrPut(frameId) {
            FrameSlot(
                frameId = frameId,
                flags = (flags.toInt() and VideoPacketFlags.END_OF_FRAME.toInt().inv()).toByte(),
                codecId = codecId,
                timestampUs = timestampUs,
                fragCount = fragCount
            )
        }

        if (slot.fragCount != fragCount) return null
        if (slot.parts[fragIndex] == null) {
            slot.parts[fragIndex] = payload
            slot.receivedCount++
            slot.totalBytes += payloadLen
        }

        if (slot.receivedCount == slot.fragCount) {
            slots.remove(frameId)
            val assembled = ByteArray(slot.totalBytes)
            var outPos = 0
            for (part in slot.parts) {
                val bytes = part ?: return null
                System.arraycopy(bytes, 0, assembled, outPos, bytes.size)
                outPos += bytes.size
            }

            val purgedOld = purgeOlderIncompleteFrames(frameId)
            val gapMissed = lastDeliveredFrameId >= 0 && frameId > lastDeliveredFrameId + 1
            if (gapMissed && !purgedOld) {
                framesDropped += (frameId - lastDeliveredFrameId - 1)
            }
            lastDeliveredFrameId = frameId
            framesDelivered++

            return EncodedVideoFrame(
                frameId = frameId,
                flags = slot.flags,
                codecId = slot.codecId,
                timestampUs = slot.timestampUs,
                data = assembled,
                hadPacketLossBefore = (purgedOld || gapMissed) && !isKeyframeOrConfig
            )
        }

        // Prevent memory / latency buildup if old frames lost packets
        while (slots.size > maxInFlightFrames) {
            val oldestKey = slots.keys.minOrNull() ?: break
            slots.remove(oldestKey)
            framesDropped++
        }

        return null
    }

    private fun purgeOlderIncompleteFrames(newerFrameId: Int): Boolean {
        var droppedAny = false
        val iter = slots.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key < newerFrameId) {
                iter.remove()
                framesDropped++
                droppedAny = true
            }
        }
        return droppedAny
    }
}

/**
 * Simple length-prefixed binary framing for optional TCP Reliable Video Mode.
 */
object TcpVideoFraming {
    private const val MAGIC = 0x4E435450 // "NCTP"

    fun writeFrame(
        out: DataOutputStream,
        frameId: Int,
        flags: Byte,
        codecId: Byte,
        timestampUs: Long,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size
    ) {
        out.writeInt(MAGIC)
        out.writeInt(frameId)
        out.writeByte(flags.toInt())
        out.writeByte(codecId.toInt())
        out.writeLong(timestampUs)
        out.writeInt(length)
        out.write(data, offset, length)
        out.flush()
    }

    fun readFrame(input: DataInputStream): EncodedVideoFrame {
        val magic = input.readInt()
        if (magic != MAGIC) {
            throw IllegalStateException("Invalid TCP stream magic: $magic")
        }
        val frameId = input.readInt()
        val flags = input.readByte()
        val codecId = input.readByte()
        val timestampUs = input.readLong()
        val length = input.readInt()
        require(length in 1..(16 * 1024 * 1024)) { "Invalid frame length: $length" }
        val data = ByteArray(length)
        input.readFully(data)
        return EncodedVideoFrame(
            frameId = frameId,
            flags = flags,
            codecId = codecId,
            timestampUs = timestampUs,
            data = data,
            hadPacketLossBefore = false
        )
    }
}

/**
 * Smooth adaptive bitrate controller so Wi-Fi/Hotspot hiccups lower bitrate temporarily
 * instead of stuttering frames or building latency.
 */
class AdaptiveBitrateController(
    targetBitrateBps: Int
) {
    var targetBitrateBps: Int = targetBitrateBps
        private set
    var currentBitrateBps: Int = targetBitrateBps
        private set

    private var cleanIntervals = 0
    private val minBitrateBps: Int
        get() = (targetBitrateBps * 0.35f).roundToInt().coerceAtLeast(1_500_000)

    fun updateTarget(newTargetBps: Int) {
        targetBitrateBps = newTargetBps
        currentBitrateBps = newTargetBps
        cleanIntervals = 0
    }

    /**
     * Returns a new bitrate (bps) if the encoder should adjust, or null if unchanged.
     */
    fun onNetworkFeedback(lossPct: Float, rttMs: Int): Int? {
        val previous = currentBitrateBps
        val candidate = when {
            lossPct >= 4.0f || rttMs >= 80 -> {
                cleanIntervals = 0
                (currentBitrateBps * 0.75f).roundToInt().coerceAtLeast(minBitrateBps)
            }
            lossPct >= 1.5f || rttMs >= 45 -> {
                cleanIntervals = 0
                (currentBitrateBps * 0.88f).roundToInt().coerceAtLeast(minBitrateBps)
            }
            lossPct < 0.5f && rttMs < 30 -> {
                cleanIntervals++
                if (cleanIntervals >= 3 && currentBitrateBps < targetBitrateBps) {
                    cleanIntervals = 0
                    (currentBitrateBps * 1.12f).roundToInt().coerceAtMost(targetBitrateBps)
                } else {
                    currentBitrateBps
                }
            }
            else -> currentBitrateBps
        }

        val deltaRatio = kotlin.math.abs(candidate - previous).toFloat() / previous.coerceAtLeast(1).toFloat()
        return if (deltaRatio >= 0.05f || (candidate == targetBitrateBps && previous != targetBitrateBps)) {
            currentBitrateBps = candidate
            candidate
        } else {
            null
        }
    }
}

/**
 * JSON-lines control channel messages over TCP port 50501.
 */
sealed class ControlMessage {
    data class Hello(
        val deviceName: String,
        val udpPort: Int,
        val transport: TransportMode,
        val preset: VideoQualityPreset
    ) : ControlMessage()

    data class Welcome(
        val deviceName: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateBps: Int,
        val codec: VideoCodecType,
        val transport: TransportMode,
        val tcpVideoPort: Int,
        val accessibilityReady: Boolean,
        val preset: VideoQualityPreset
    ) : ControlMessage()

    data class VideoConfig(
        val width: Int,
        val height: Int,
        val fps: Int,
        val codec: VideoCodecType
    ) : ControlMessage()

    data object RequestIdr : ControlMessage()
    data class ChangePreset(val preset: VideoQualityPreset) : ControlMessage()
    data object Shutter : ControlMessage()
    data class Tap(val normX: Float, val normY: Float) : ControlMessage()
    data class LongPress(
        val normX: Float,
        val normY: Float,
        val durationMs: Long = 550L
    ) : ControlMessage()
    data class Swipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long = 220L
    ) : ControlMessage()
    data class NavAction(val action: String) : ControlMessage()

    data class ShutterAck(val success: Boolean, val message: String) : ControlMessage()
    data class Ping(val clientTimeMs: Long) : ControlMessage()
    data class Pong(val clientTimeMs: Long, val accessibilityReady: Boolean) : ControlMessage()
    data class Feedback(val fps: Int, val lossPct: Float, val rttMs: Int) : ControlMessage()
    data object Stop : ControlMessage()

    fun toJsonLine(): String {
        val obj = JSONObject()
        when (this) {
            is Hello -> {
                obj.put("t", "HELLO")
                obj.put("name", deviceName)
                obj.put("udpPort", udpPort)
                obj.put("transport", transport.name)
                obj.put("preset", preset.name)
            }
            is Welcome -> {
                obj.put("t", "WELCOME")
                obj.put("name", deviceName)
                obj.put("w", width)
                obj.put("h", height)
                obj.put("fps", fps)
                obj.put("bitrate", bitrateBps)
                obj.put("codec", codec.name)
                obj.put("transport", transport.name)
                obj.put("tcpVideoPort", tcpVideoPort)
                obj.put("acc", accessibilityReady)
                obj.put("preset", preset.name)
            }
            is VideoConfig -> {
                obj.put("t", "VIDEO_CONFIG")
                obj.put("w", width)
                obj.put("h", height)
                obj.put("fps", fps)
                obj.put("codec", codec.name)
            }
            RequestIdr -> obj.put("t", "REQUEST_IDR")
            is ChangePreset -> {
                obj.put("t", "CHANGE_PRESET")
                obj.put("preset", preset.name)
            }
            Shutter -> obj.put("t", "SHUTTER")
            is Tap -> {
                obj.put("t", "TAP")
                obj.put("x", normX.toDouble())
                obj.put("y", normY.toDouble())
            }
            is LongPress -> {
                obj.put("t", "LONG_PRESS")
                obj.put("x", normX.toDouble())
                obj.put("y", normY.toDouble())
                obj.put("dur", durationMs)
            }
            is Swipe -> {
                obj.put("t", "SWIPE")
                obj.put("x1", x1.toDouble())
                obj.put("y1", y1.toDouble())
                obj.put("x2", x2.toDouble())
                obj.put("y2", y2.toDouble())
                obj.put("dur", durationMs)
            }
            is NavAction -> {
                obj.put("t", "NAV")
                obj.put("a", action)
            }
            is ShutterAck -> {
                obj.put("t", "SHUTTER_ACK")
                obj.put("ok", success)
                obj.put("msg", message)
            }
            is Ping -> {
                obj.put("t", "PING")
                obj.put("ts", clientTimeMs)
            }
            is Pong -> {
                obj.put("t", "PONG")
                obj.put("ts", clientTimeMs)
                obj.put("acc", accessibilityReady)
            }
            is Feedback -> {
                obj.put("t", "FEEDBACK")
                obj.put("fps", fps)
                obj.put("loss", lossPct.toDouble())
                obj.put("rtt", rttMs)
            }
            Stop -> obj.put("t", "STOP")
        }
        return obj.toString()
    }

    companion object {
        fun parse(jsonLine: String): ControlMessage? {
            return try {
                val obj = JSONObject(jsonLine)
                when (obj.optString("t")) {
                    "HELLO" -> Hello(
                        deviceName = obj.optString("name", "Monitor"),
                        udpPort = obj.optInt("udpPort", CastPorts.VIDEO_UDP),
                        transport = TransportMode.entries.firstOrNull {
                            it.name == obj.optString("transport")
                        } ?: TransportMode.UDP_LOW_LATENCY,
                        preset = VideoQualityPreset.fromNameOrDefault(obj.optString("preset"))
                    )
                    "WELCOME" -> Welcome(
                        deviceName = obj.optString("name", "Pixel"),
                        width = obj.optInt("w", 720),
                        height = obj.optInt("h", 1600),
                        fps = obj.optInt("fps", 60),
                        bitrateBps = obj.optInt("bitrate", 8_000_000),
                        codec = VideoCodecType.entries.firstOrNull {
                            it.name == obj.optString("codec")
                        } ?: VideoCodecType.H264,
                        transport = TransportMode.entries.firstOrNull {
                            it.name == obj.optString("transport")
                        } ?: TransportMode.UDP_LOW_LATENCY,
                        tcpVideoPort = obj.optInt("tcpVideoPort", CastPorts.VIDEO_TCP),
                        accessibilityReady = obj.optBoolean("acc", false),
                        preset = VideoQualityPreset.fromNameOrDefault(obj.optString("preset"))
                    )
                    "VIDEO_CONFIG" -> VideoConfig(
                        width = obj.optInt("w", 720),
                        height = obj.optInt("h", 1600),
                        fps = obj.optInt("fps", 60),
                        codec = VideoCodecType.entries.firstOrNull {
                            it.name == obj.optString("codec")
                        } ?: VideoCodecType.H264
                    )
                    "REQUEST_IDR" -> RequestIdr
                    "CHANGE_PRESET" -> ChangePreset(
                        preset = VideoQualityPreset.fromNameOrDefault(obj.optString("preset"))
                    )
                    "SHUTTER" -> Shutter
                    "TAP" -> Tap(
                        normX = obj.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        normY = obj.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                    )
                    "LONG_PRESS" -> LongPress(
                        normX = obj.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        normY = obj.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                        durationMs = obj.optLong("dur", 550L).coerceIn(300L, 2000L)
                    )
                    "SWIPE" -> Swipe(
                        x1 = obj.optDouble("x1", 0.5).toFloat().coerceIn(0f, 1f),
                        y1 = obj.optDouble("y1", 0.5).toFloat().coerceIn(0f, 1f),
                        x2 = obj.optDouble("x2", 0.5).toFloat().coerceIn(0f, 1f),
                        y2 = obj.optDouble("y2", 0.5).toFloat().coerceIn(0f, 1f),
                        durationMs = obj.optLong("dur", 220L).coerceIn(50L, 1200L)
                    )
                    "NAV" -> NavAction(
                        action = obj.optString("a", "BACK")
                    )
                    "SHUTTER_ACK" -> ShutterAck(
                        success = obj.optBoolean("ok", false),
                        message = obj.optString("msg", "")
                    )
                    "PING" -> Ping(clientTimeMs = obj.optLong("ts", 0L))
                    "PONG" -> Pong(
                        clientTimeMs = obj.optLong("ts", 0L),
                        accessibilityReady = obj.optBoolean("acc", false)
                    )
                    "FEEDBACK" -> Feedback(
                        fps = obj.optInt("fps", 0),
                        lossPct = obj.optDouble("loss", 0.0).toFloat(),
                        rttMs = obj.optInt("rtt", 0)
                    )
                    "STOP" -> Stop
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
