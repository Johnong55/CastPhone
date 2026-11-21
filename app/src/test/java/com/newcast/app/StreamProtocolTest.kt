package com.newcast.app

import com.newcast.app.model.TransportMode
import com.newcast.app.model.VideoCodecType
import com.newcast.app.model.VideoQualityPreset
import com.newcast.app.network.AdaptiveBitrateController
import com.newcast.app.network.ControlMessage
import com.newcast.app.network.TcpVideoFraming
import com.newcast.app.network.UdpFrameReassembler
import com.newcast.app.network.UdpVideoPacketizer
import com.newcast.app.network.VideoPacketFlags
import com.newcast.app.ui.MonitorTouchMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class StreamProtocolTest {

    @Test
    fun udpPacketizeAndReassemble_multiFragmentOutOfOrder() {
        val payload = ByteArray(5000) { idx -> (idx * 31 and 0xFF).toByte() }
        val packets = UdpVideoPacketizer.fragmentFrame(
            frameId = 42,
            flags = VideoPacketFlags.KEYFRAME,
            codecId = VideoCodecType.H264.id,
            timestampUs = 123_456_789L,
            data = payload
        )
        assertTrue("Expected multiple fragments", packets.size == 4)

        val reassembler = UdpFrameReassembler()
        // Deliver out of order: 2, 0, 3, 1
        assertNull(reassembler.onPacket(packets[2], packets[2].size))
        assertNull(reassembler.onPacket(packets[0], packets[0].size))
        assertNull(reassembler.onPacket(packets[3], packets[3].size))
        val frame = reassembler.onPacket(packets[1], packets[1].size)

        assertNotNull(frame)
        assertEquals(42, frame!!.frameId)
        assertTrue(frame.isKeyframe)
        assertFalse(frame.hadPacketLossBefore)
        assertEquals(123_456_789L, frame.timestampUs)
        assertArrayEquals(payload, frame.data)
    }

    @Test
    fun udpReassembler_flagsPacketLossWhenIncompleteFrameSkipped() {
        val reassembler = UdpFrameReassembler()

        // Frame 1 complete
        val f1 = UdpVideoPacketizer.fragmentFrame(1, VideoPacketFlags.KEYFRAME, 0, 1000L, ByteArray(400) { 1 })
        assertNotNull(reassembler.onPacket(f1[0], f1[0].size))

        // Frame 2 drops its 2nd fragment
        val f2 = UdpVideoPacketizer.fragmentFrame(2, VideoPacketFlags.NONE, 0, 2000L, ByteArray(2500) { 2 })
        assertEquals(2, f2.size)
        assertNull(reassembler.onPacket(f2[0], f2[0].size))

        // Frame 3 arrives complete -> frame 2 purged, hadPacketLossBefore = true!
        val f3 = UdpVideoPacketizer.fragmentFrame(3, VideoPacketFlags.NONE, 0, 3000L, ByteArray(300) { 3 })
        val delivered = reassembler.onPacket(f3[0], f3[0].size)
        assertNotNull(delivered)
        assertEquals(3, delivered!!.frameId)
        assertTrue(delivered.hadPacketLossBefore)
        assertTrue(reassembler.consumeAndResetLossPercent() > 0f)
    }

    @Test
    fun udpReassembler_handlesEncoderRestartFrameIdReset() {
        val reassembler = UdpFrameReassembler()
        val f100 = UdpVideoPacketizer.fragmentFrame(100, VideoPacketFlags.KEYFRAME, 0, 1000L, ByteArray(200) { 7 })
        assertNotNull(reassembler.onPacket(f100[0], f100[0].size))

        // Encoder reconfigured and restarts frame counter at 1 with KEYFRAME
        val f1 = UdpVideoPacketizer.fragmentFrame(1, VideoPacketFlags.KEYFRAME, 0, 2000L, ByteArray(200) { 9 })
        val restarted = reassembler.onPacket(f1[0], f1[0].size)
        assertNotNull(restarted)
        assertEquals(1, restarted!!.frameId)
        assertTrue(restarted.isKeyframe)
    }

    @Test
    fun tcpFraming_roundTrip() {
        val payload = ByteArray(3210) { (it % 251).toByte() }
        val baos = ByteArrayOutputStream()
        TcpVideoFraming.writeFrame(
            out = DataOutputStream(baos),
            frameId = 77,
            flags = VideoPacketFlags.KEYFRAME,
            codecId = VideoCodecType.H265.id,
            timestampUs = 987654L,
            data = payload
        )

        val decoded = TcpVideoFraming.readFrame(DataInputStream(ByteArrayInputStream(baos.toByteArray())))
        assertEquals(77, decoded.frameId)
        assertTrue(decoded.isKeyframe)
        assertEquals(VideoCodecType.H265.id, decoded.codecId)
        assertEquals(987654L, decoded.timestampUs)
        assertArrayEquals(payload, decoded.data)
    }

    @Test
    fun controlMessages_roundTripJson() {
        val messages = listOf(
            ControlMessage.Hello("Pixel 6 Pro", 51234, TransportMode.UDP_LOW_LATENCY, VideoQualityPreset.SMOOTH_720P60),
            ControlMessage.Welcome("Pixel 8", 720, 1600, 60, 8_000_000, VideoCodecType.H264, TransportMode.UDP_LOW_LATENCY, 50503, true, VideoQualityPreset.SMOOTH_720P60),
            ControlMessage.VideoConfig(1600, 720, 60, VideoCodecType.H264),
            ControlMessage.RequestIdr,
            ControlMessage.ChangePreset(VideoQualityPreset.CRISP_1080P60),
            ControlMessage.Shutter,
            ControlMessage.Tap(0.42f, 0.78f),
            ControlMessage.LongPress(0.50f, 0.50f, 600L),
            ControlMessage.Swipe(0.2f, 0.5f, 0.8f, 0.5f, 250L),
            ControlMessage.NavAction("BACK"),
            ControlMessage.ShutterAck(true, "Đã bấm chụp"),
            ControlMessage.Ping(12345L),
            ControlMessage.Pong(12345L, true),
            ControlMessage.Feedback(60, 0.25f, 11),
            ControlMessage.Stop
        )

        for (msg in messages) {
            val serialized = msg.toJsonLine()
            val parsed = ControlMessage.parse(serialized)
            assertEquals("Failed round-trip for $msg", msg, parsed)
        }
    }

    @Test
    fun adaptiveBitrate_reducesOnLossAndRecoversOnCleanLink() {
        val abr = AdaptiveBitrateController(8_000_000)

        // Heavy packet loss -> drops bitrate
        val reduced = abr.onNetworkFeedback(lossPct = 5.0f, rttMs = 40)
        assertNotNull(reduced)
        assertTrue(reduced!! < 8_000_000)

        // Three clean intervals -> ramps back up
        assertNull(abr.onNetworkFeedback(lossPct = 0f, rttMs = 10))
        assertNull(abr.onNetworkFeedback(lossPct = 0f, rttMs = 12))
        val recovered = abr.onNetworkFeedback(lossPct = 0f, rttMs = 10)
        assertNotNull(recovered)
        assertTrue(recovered!! > reduced)
    }

    @Test
    fun presetDimensions_pixel6ProAndPixel8AlignedTo16() {
        // Pixel 6 Pro: 1440x3120; Pixel 8: 1080x2400
        val (p6W, p6H) = VideoQualityPreset.SMOOTH_720P60.computeAlignedDimensions(1440, 3120)
        assertEquals(0, p6W % 16)
        assertEquals(0, p6H % 16)
        assertEquals(720, p6W)
        assertEquals(1568, p6H)

        val (p8W, p8H) = VideoQualityPreset.CRISP_1080P60.computeAlignedDimensions(1080, 2400)
        assertEquals(0, p8W % 16)
        assertEquals(0, p8H % 16)
        assertEquals(1088, p8W)
        assertTrue(p8H > p8W)

        // Landscape orientation preserved
        val (landW, landH) = VideoQualityPreset.SMOOTH_720P60.computeAlignedDimensions(2400, 1080)
        assertEquals(0, landW % 16)
        assertEquals(0, landH % 16)
        assertTrue(landW > landH)
    }

    @Test
    fun monitorTouchMapper_undoesRotationAndMirror() {
        // 0 deg unmirrored
        val (x0, y0) = MonitorTouchMapper.mapViewToSenderNormalized(0.25f, 0.75f, 0, false)
        assertEquals(0.25f, x0, 1e-4f)
        assertEquals(0.75f, y0, 1e-4f)

        // 0 deg mirrored
        val (xm, ym) = MonitorTouchMapper.mapViewToSenderNormalized(0.25f, 0.75f, 0, true)
        assertEquals(0.75f, xm, 1e-4f)
        assertEquals(0.75f, ym, 1e-4f)

        // 90 deg clockwise: top-right (u=1, v=0) on display corresponds to sender top-left (normX=0, normY=0)
        val (x90, y90) = MonitorTouchMapper.mapViewToSenderNormalized(1.0f, 0.0f, 90, false)
        assertEquals(0.0f, x90, 1e-4f)
        assertEquals(0.0f, y90, 1e-4f)

        // 270 deg clockwise: bottom-left (u=0, v=1) on display corresponds to sender top-left (0, 0)
        val (x270, y270) = MonitorTouchMapper.mapViewToSenderNormalized(0.0f, 1.0f, 270, false)
        assertEquals(0.0f, x270, 1e-4f)
        assertEquals(0.0f, y270, 1e-4f)
    }
}
