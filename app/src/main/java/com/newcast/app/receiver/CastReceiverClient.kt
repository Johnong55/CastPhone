package com.newcast.app.receiver

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.newcast.app.model.MonitorConnectionState
import com.newcast.app.model.MonitorStats
import com.newcast.app.model.TransportMode
import com.newcast.app.model.VideoCodecType
import com.newcast.app.model.VideoQualityPreset
import com.newcast.app.network.CastPorts
import com.newcast.app.network.ControlMessage
import com.newcast.app.network.NetworkHelper
import com.newcast.app.network.TcpVideoFraming
import com.newcast.app.network.UdpFrameReassembler
import com.newcast.app.network.UdpVideoPacketizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CastReceiverClient(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _connectionState = MutableStateFlow<MonitorConnectionState>(MonitorConnectionState.Idle)
    val connectionState: StateFlow<MonitorConnectionState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow(MonitorStats())
    val stats: StateFlow<MonitorStats> = _stats.asStateFlow()

    private val _remoteFeedbackEvents = MutableSharedFlow<Pair<Boolean, String>>(extraBufferCapacity = 8)
    val remoteFeedbackEvents: SharedFlow<Pair<Boolean, String>> = _remoteFeedbackEvents.asSharedFlow()

    private val userDisconnected = AtomicBoolean(false)
    private val lastIdrRequestMs = AtomicLong(0L)
    private val bytesReceivedWindow = AtomicLong(0L)
    private val lastRttMs = AtomicInteger(0)

    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionJob: Job? = null

    @Volatile
    private var controlSocket: Socket? = null

    @Volatile
    private var controlWriter: BufferedWriter? = null
    private val controlWriteLock = Any()

    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var tcpVideoSocket: Socket? = null

    private val videoDecoder = VideoDecoder(
        onVideoSizeChanged = { w, h ->
            _stats.value = _stats.value.copy(videoWidth = w, videoHeight = h)
        },
        onNeedKeyframe = { requestIdrThrottled() }
    )

    fun attachSurface(surface: Surface?) {
        videoDecoder.attachSurface(surface)
    }

    fun connect(
        hostIp: String,
        controlPort: Int = CastPorts.CONTROL_TCP,
        transportMode: TransportMode = TransportMode.UDP_LOW_LATENCY,
        preset: VideoQualityPreset = VideoQualityPreset.SMOOTH_720P60
    ) {
        disconnectInternal(userRequested = false)
        userDisconnected.set(false)

        wifiLock = NetworkHelper.acquireLowLatencyWifiLock(context, "NewCast:MonitorWifiLock")

        sessionJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && !userDisconnected.get()) {
                attempt++
                if (attempt == 1) {
                    _connectionState.value = MonitorConnectionState.Connecting(hostIp)
                } else {
                    _connectionState.value = MonitorConnectionState.Reconnecting(
                        targetIp = hostIp,
                        attempt = attempt - 1,
                        reason = "Đang kết nối lại..."
                    )
                }

                val endedCleanly = runSingleSession(hostIp, controlPort, transportMode, preset)
                if (endedCleanly || userDisconnected.get()) {
                    break
                }
                if (attempt >= MAX_RECONNECT_ATTEMPTS) {
                    _connectionState.value = MonitorConnectionState.Error(
                        "Không thể kết nối tới $hostIp sau $MAX_RECONNECT_ATTEMPTS lần thử. Hãy kiểm tra Hotspot/Wi-Fi và đảm bảo máy quay đang bấm Phát màn hình."
                    )
                    break
                }
                delay(1200L)
            }
            releaseWifiLock()
        }
    }

    private suspend fun runSingleSession(
        hostIp: String,
        controlPort: Int,
        transportMode: TransportMode,
        preset: VideoQualityPreset
    ): Boolean {
        val reassembler = UdpFrameReassembler(maxInFlightFrames = 4)
        var videoThread: Thread? = null
        var pingJob: Job? = null
        var statsJob: Job? = null

        try {
            // Prepare UDP listening socket on an ephemeral port (avoids local port collisions)
            val uSock = DatagramSocket(0).apply {
                receiveBufferSize = 2 * 1024 * 1024
                soTimeout = 2000
            }
            udpSocket = uSock
            val localUdpPort = uSock.localPort

            val cSock = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(hostIp, controlPort), 4000)
                soTimeout = 0
            }
            controlSocket = cSock

            val reader = BufferedReader(InputStreamReader(cSock.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(cSock.getOutputStream(), Charsets.UTF_8))
            controlWriter = writer

            sendControl(
                ControlMessage.Hello(
                    deviceName = NetworkHelper.getDeviceDisplayName(),
                    udpPort = localUdpPort,
                    transport = transportMode,
                    preset = preset
                )
            )

            val welcomeLine = reader.readLine()
                ?: throw IllegalStateException("Máy quay đóng kết nối sớm")
            val welcome = ControlMessage.parse(welcomeLine) as? ControlMessage.Welcome
                ?: throw IllegalStateException("Phản hồi không hợp lệ từ máy quay")

            _connectionState.value = MonitorConnectionState.Connected(
                hostName = welcome.deviceName,
                hostIp = hostIp,
                accessibilityReady = welcome.accessibilityReady,
                preset = welcome.preset
            )
            _stats.value = _stats.value.copy(
                videoWidth = welcome.width,
                videoHeight = welcome.height,
                transportLabel = if (welcome.transport == TransportMode.UDP_LOW_LATENCY) "UDP" else "TCP"
            )

            videoDecoder.configure(welcome.width, welcome.height, welcome.codec)

            // Start video receiver on a dedicated URGENT_DISPLAY thread for zero scheduler jitter
            videoThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                if (welcome.transport == TransportMode.TCP_RELIABLE) {
                    runTcpVideoLoop(hostIp, welcome.tcpVideoPort)
                } else {
                    runUdpVideoLoop(uSock, reassembler)
                }
            }, "NewCastVideoReceiver").apply {
                isDaemon = true
                start()
            }

            // Periodic Ping (RTT measurement every 1.5s to minimize control wakeups)
            pingJob = scope.launch(Dispatchers.IO) {
                while (isActive && !cSock.isClosed) {
                    sendControl(ControlMessage.Ping(SystemClock.elapsedRealtime()))
                    delay(1500L)
                }
            }

            // Periodic stats HUD + ABR network feedback (every 500ms)
            statsJob = scope.launch(Dispatchers.IO) {
                var tick = 0
                while (isActive && !cSock.isClosed) {
                    delay(500L)
                    tick++
                    val lossPct = reassembler.consumeAndResetLossPercent()
                    val rtt = lastRttMs.get()
                    if (tick % 2 == 0) {
                        val renderedFps = videoDecoder.consumeRenderedFramesCount()
                        val bytes = bytesReceivedWindow.getAndSet(0L)
                        val mbps = (bytes * 8f) / 1_000_000f
                        _stats.value = _stats.value.copy(
                            fps = renderedFps,
                            rttMs = rtt,
                            bitrateMbps = mbps,
                            packetLossPct = lossPct
                        )
                        sendControl(ControlMessage.Feedback(renderedFps, lossPct, rtt))
                    } else if (lossPct > 1.0f) {
                        sendControl(ControlMessage.Feedback(_stats.value.fps, lossPct, rtt))
                    }
                }
            }

            // Control messages receive loop
            while (scope.isActive && !cSock.isClosed && !userDisconnected.get()) {
                val line = reader.readLine() ?: break
                val msg = ControlMessage.parse(line) ?: continue
                when (msg) {
                    is ControlMessage.VideoConfig -> {
                        reassembler.reset()
                        videoDecoder.configure(msg.width, msg.height, msg.codec)
                    }
                    is ControlMessage.Pong -> {
                        val rtt = (SystemClock.elapsedRealtime() - msg.clientTimeMs)
                            .coerceIn(0L, 9999L).toInt()
                        lastRttMs.set(rtt)
                        val cur = _connectionState.value
                        if (cur is MonitorConnectionState.Connected && cur.accessibilityReady != msg.accessibilityReady) {
                            _connectionState.value = cur.copy(accessibilityReady = msg.accessibilityReady)
                        }
                    }
                    is ControlMessage.ShutterAck -> {
                        _remoteFeedbackEvents.tryEmit(msg.success to msg.message)
                    }
                    ControlMessage.Stop -> {
                        _connectionState.value = MonitorConnectionState.Idle
                        return true
                    }
                    else -> Unit
                }
            }
            return userDisconnected.get()
        } catch (e: Exception) {
            Log.w(TAG, "Receiver session disconnected: ${e.message}")
            return userDisconnected.get()
        } finally {
            pingJob?.cancel()
            statsJob?.cancel()
            closeSessionSockets()
            videoThread?.interrupt()
            try { videoThread?.join(150L) } catch (_: Exception) {}
            videoDecoder.stop()
        }
    }

    private fun runUdpVideoLoop(sock: DatagramSocket, reassembler: UdpFrameReassembler) {
        val packetBuf = ByteArray(UdpVideoPacketizer.MAX_PACKET_SIZE + 64)
        val datagram = DatagramPacket(packetBuf, packetBuf.size)

        while (!sock.isClosed && !userDisconnected.get()) {
            try {
                sock.receive(datagram)
                bytesReceivedWindow.addAndGet(datagram.length.toLong())
                val frame = reassembler.onPacket(datagram.data, datagram.length) ?: continue
                if (frame.hadPacketLossBefore) {
                    requestIdrThrottled()
                }
                videoDecoder.submitFrame(frame)
            } catch (_: SocketTimeoutException) {
                // Keep checking loop condition
            } catch (e: Exception) {
                if (!sock.isClosed) {
                    Log.w(TAG, "UDP receive error: ${e.message}")
                }
                break
            }
        }
    }

    private fun runTcpVideoLoop(hostIp: String, tcpVideoPort: Int) {
        try {
            val sock = Socket().apply {
                tcpNoDelay = true
                receiveBufferSize = 2 * 1024 * 1024
                connect(InetSocketAddress(hostIp, tcpVideoPort), 4000)
            }
            tcpVideoSocket = sock
            val input = DataInputStream(sock.getInputStream())
            while (!sock.isClosed && !userDisconnected.get()) {
                val frame = TcpVideoFraming.readFrame(input)
                bytesReceivedWindow.addAndGet(frame.data.size.toLong())
                videoDecoder.submitFrame(frame)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TCP video loop ended: ${e.message}")
        }
    }

    fun sendShutter() {
        scope.launch(Dispatchers.IO) {
            sendControl(ControlMessage.Shutter)
        }
    }

    fun sendTap(normX: Float, normY: Float) {
        scope.launch(Dispatchers.IO) {
            sendControl(ControlMessage.Tap(normX, normY))
        }
    }

    fun sendLongPress(normX: Float, normY: Float, durationMs: Long = 550L) {
        scope.launch(Dispatchers.IO) {
            sendControl(ControlMessage.LongPress(normX, normY, durationMs))
        }
    }

    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 220L) {
        scope.launch(Dispatchers.IO) {
            sendControl(ControlMessage.Swipe(x1, y1, x2, y2, durationMs))
        }
    }

    fun sendNavAction(action: String) {
        scope.launch(Dispatchers.IO) {
            sendControl(ControlMessage.NavAction(action))
        }
    }

    fun changePreset(preset: VideoQualityPreset) {
        val cur = _connectionState.value
        if (cur is MonitorConnectionState.Connected) {
            _connectionState.value = cur.copy(preset = preset)
        }
        scope.launch(Dispatchers.IO) {
            sendControl(ControlMessage.ChangePreset(preset))
        }
    }

    fun requestIdrThrottled() {
        val now = SystemClock.elapsedRealtime()
        val prev = lastIdrRequestMs.get()
        if (now - prev >= 250L && lastIdrRequestMs.compareAndSet(prev, now)) {
            scope.launch(Dispatchers.IO) {
                sendControl(ControlMessage.RequestIdr)
            }
        }
    }

    fun disconnect() {
        disconnectInternal(userRequested = true)
    }

    fun release() {
        disconnectInternal(userRequested = true)
        videoDecoder.release()
    }

    private fun disconnectInternal(userRequested: Boolean) {
        if (userRequested) {
            userDisconnected.set(true)
            scope.launch(Dispatchers.IO) {
                sendControl(ControlMessage.Stop)
                closeSessionSockets()
            }
        } else {
            closeSessionSockets()
        }
        sessionJob?.cancel()
        sessionJob = null
        videoDecoder.stop()
        releaseWifiLock()
        if (userRequested) {
            _connectionState.value = MonitorConnectionState.Idle
            _stats.value = MonitorStats()
        }
    }

    private fun sendControl(msg: ControlMessage) {
        synchronized(controlWriteLock) {
            try {
                controlWriter?.apply {
                    write(msg.toJsonLine())
                    newLine()
                    flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun closeSessionSockets() {
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        try { tcpVideoSocket?.close() } catch (_: Exception) {}
        tcpVideoSocket = null
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        controlWriter = null
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    companion object {
        private const val TAG = "NewCastReceiver"
        private const val MAX_RECONNECT_ATTEMPTS = 15
    }
}
