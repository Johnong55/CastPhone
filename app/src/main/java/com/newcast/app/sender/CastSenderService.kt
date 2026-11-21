package com.newcast.app.sender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.newcast.app.MainActivity
import com.newcast.app.R
import com.newcast.app.model.SenderRuntimeState
import com.newcast.app.model.TransportMode
import com.newcast.app.model.VideoCodecType
import com.newcast.app.model.VideoQualityPreset
import com.newcast.app.network.AdaptiveBitrateController
import com.newcast.app.network.CastPorts
import com.newcast.app.network.ControlMessage
import com.newcast.app.network.HostDiscoveryAdvertiser
import com.newcast.app.network.NetworkHelper
import com.newcast.app.network.TcpVideoFraming
import com.newcast.app.network.UdpVideoPacketizer
import com.newcast.app.remote.RemoteShutterAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

class CastSenderService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null
    private var screenEncoder: ScreenEncoder? = null
    private var discoveryAdvertiser: HostDiscoveryAdvertiser? = null

    private var controlServerSocket: ServerSocket? = null
    private var tcpVideoServerSocket: ServerSocket? = null
    private var udpSendSocket: DatagramSocket? = null

    @Volatile
    private var activeClient: ConnectedMonitorSession? = null

    @Volatile
    private var activePreset: VideoQualityPreset = VideoQualityPreset.SMOOTH_720P60

    @Volatile
    private var activeTransport: TransportMode = TransportMode.UDP_LOW_LATENCY

    @Volatile
    private var activeCodec: VideoCodecType = VideoCodecType.H264

    @Volatile
    private var lastDisplayRotation: Int = -1

    private val abrController = AdaptiveBitrateController(VideoQualityPreset.SMOOTH_720P60.bitrateBps)
    private val outboundQueue = ArrayBlockingQueue<EncodedOutput>(4)

    private val fpsFramesWindow = AtomicInteger(0)
    private val bitrateBytesWindow = AtomicLong(0L)

    private var senderThread: Thread? = null
    private val senderThreadRunning = AtomicBoolean(false)
    private var statsJob: Job? = null

    private data class ConnectedMonitorSession(
        val deviceName: String,
        val clientIp: String,
        val destAddress: InetAddress,
        val udpPort: Int,
        val transport: TransportMode,
        val controlSocket: Socket,
        val controlWriter: BufferedWriter,
        @Volatile var tcpVideoSocket: Socket? = null,
        @Volatile var tcpVideoOut: DataOutputStream? = null
    ) {
        private val writeLock = Any()

        fun sendControl(msg: ControlMessage) {
            synchronized(writeLock) {
                try {
                    controlWriter.write(msg.toJsonLine())
                    controlWriter.newLine()
                    controlWriter.flush()
                } catch (_: Exception) {
                }
            }
        }

        fun closeAll() {
            try { tcpVideoOut?.close() } catch (_: Exception) {}
            try { tcpVideoSocket?.close() } catch (_: Exception) {}
            try { controlSocket.close() } catch (_: Exception) {}
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopCastingAndSelf()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val rotation = getCurrentDisplayRotation()
            if (rotation != lastDisplayRotation && lastDisplayRotation != -1) {
                lastDisplayRotation = rotation
                mainHandler.postDelayed({ handleDisplayOrientationChanged() }, 180L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCastingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_CHANGE_PRESET -> {
                val preset = VideoQualityPreset.fromNameOrDefault(intent.getStringExtra(EXTRA_PRESET))
                applyPreset(preset)
                return START_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val preset = VideoQualityPreset.fromNameOrDefault(intent.getStringExtra(EXTRA_PRESET))
                val transport = TransportMode.entries.firstOrNull {
                    it.name == intent.getStringExtra(EXTRA_TRANSPORT)
                } ?: TransportMode.UDP_LOW_LATENCY
                val codec = VideoCodecType.entries.firstOrNull {
                    it.name == intent.getStringExtra(EXTRA_CODEC)
                } ?: VideoCodecType.H264

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    _runtimeState.value = SenderRuntimeState(
                        isCasting = false,
                        errorMessage = "Không nhận được quyền chia sẻ màn hình."
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                // CRITICAL on Android 14/15: startForeground(MEDIA_PROJECTION) MUST precede getMediaProjection!
                createNotificationChannel()
                val notification = buildNotification("Đang chờ máy màn hình phụ kết nối...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                startCastingPipeline(resultCode, resultData, preset, transport, codec)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startCastingPipeline(
        resultCode: Int,
        resultData: Intent,
        preset: VideoQualityPreset,
        transport: TransportMode,
        codec: VideoCodecType
    ) {
        cleanupPipeline()

        activePreset = preset
        activeTransport = transport
        activeCodec = codec
        abrController.updateTarget(preset.bitrateBps)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            _runtimeState.value = SenderRuntimeState(
                isCasting = false,
                errorMessage = "Không thể khởi chạy MediaProjection: ${e.message}"
            )
            stopCastingAndSelf()
            return
        }

        if (projection == null) {
            _runtimeState.value = SenderRuntimeState(
                isCasting = false,
                errorMessage = "MediaProjection trả về null."
            )
            stopCastingAndSelf()
            return
        }

        mediaProjection = projection
        // CRITICAL on Android 14+: register callback BEFORE createVirtualDisplay
        projection.registerCallback(projectionCallback, mainHandler)

        val (screenW, screenH, dpi) = getScreenMetrics()
        val (capW, capH) = preset.computeAlignedDimensions(screenW, screenH)
        lastDisplayRotation = getCurrentDisplayRotation()

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, mainHandler)

        val encoder = ScreenEncoder(
            mediaProjection = projection,
            densityDpi = dpi,
            onEncodedFrame = { frame -> enqueueOutboundFrame(frame) },
            onEncoderError = { msg ->
                _runtimeState.value = _runtimeState.value.copy(errorMessage = msg)
            }
        )
        screenEncoder = encoder

        try {
            encoder.start(capW, capH, preset.fps, preset.bitrateBps, codec)
            // Power-saving: suspend hardware encoding until a Monitor phone actually connects
            encoder.setSuspended(true)
        } catch (e: Exception) {
            Log.e(TAG, "Encoder failed to start", e)
            _runtimeState.value = SenderRuntimeState(
                isCasting = false,
                errorMessage = "Lỗi khởi tạo bộ mã hóa video: ${e.message}"
            )
            stopCastingAndSelf()
            return
        }

        startUdpAndTcpServers()
        startOutboundSenderLoop()

        discoveryAdvertiser = HostDiscoveryAdvertiser(
            context = this,
            scope = serviceScope,
            isAccessibilityReady = { RemoteShutterAccessibilityService.instance != null }
        ).also { it.start() }

        _runtimeState.value = SenderRuntimeState(
            isCasting = true,
            statusText = "Chế độ chờ tiết kiệm pin — mở Màn Hình Phụ để kết nối",
            preset = preset,
            transportMode = transport,
            codecType = codec,
            captureWidth = capW,
            captureHeight = capH,
            currentBitrateMbps = 0f
        )
    }

    private fun enqueueOutboundFrame(frame: EncodedOutput) {
        fpsFramesWindow.incrementAndGet()
        if (activeClient == null) return

        val isPriorityFrame = (frame.flags.toInt() and 3) != 0 // CONFIG or KEYFRAME
        if (!outboundQueue.offer(frame)) {
            if (isPriorityFrame) {
                outboundQueue.clear()
                outboundQueue.offer(frame)
            } else {
                val head = outboundQueue.peek()
                if (head != null && (head.flags.toInt() and 3) == 0) {
                    outboundQueue.poll()
                    outboundQueue.offer(frame)
                }
            }
        }
    }

    private fun startOutboundSenderLoop() {
        stopOutboundSenderLoop()
        senderThreadRunning.set(true)
        senderThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val udpSock = DatagramSocket().apply {
                sendBufferSize = 1024 * 1024
                try {
                    trafficClass = CastPorts.DSCP_EF_TRAFFIC_CLASS
                } catch (_: Exception) {
                }
            }
            udpSendSocket = udpSock
            val scratchPacket = ByteArray(UdpVideoPacketizer.MAX_PACKET_SIZE)
            // Single reusable DatagramPacket eliminates ~1,000 object allocations/sec
            val reusableDatagram = DatagramPacket(scratchPacket, scratchPacket.size)

            try {
                while (senderThreadRunning.get()) {
                    val frame = try {
                        outboundQueue.take()
                    } catch (_: InterruptedException) {
                        break
                    }

                    val client = activeClient ?: continue
                    bitrateBytesWindow.addAndGet(frame.data.size.toLong())

                    if (client.transport == TransportMode.TCP_RELIABLE) {
                        val tcpOut = client.tcpVideoOut
                        if (tcpOut != null) {
                            try {
                                TcpVideoFraming.writeFrame(
                                    out = tcpOut,
                                    frameId = frame.frameId,
                                    flags = frame.flags,
                                    codecId = frame.codecId,
                                    timestampUs = frame.timestampUs,
                                    data = frame.data
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "TCP video write failed: ${e.message}")
                            }
                        }
                    } else {
                        try {
                            reusableDatagram.address = client.destAddress
                            reusableDatagram.port = client.udpPort
                            val isLargeBurst = frame.data.size > UdpVideoPacketizer.MAX_PAYLOAD_SIZE * 8
                            var packetCounter = 0

                            UdpVideoPacketizer.packetize(
                                frameId = frame.frameId,
                                flags = frame.flags,
                                codecId = frame.codecId,
                                timestampUs = frame.timestampUs,
                                data = frame.data,
                                scratchPacket = scratchPacket
                            ) { _, pktLen ->
                                reusableDatagram.length = pktLen
                                udpSock.send(reusableDatagram)
                                // Gentle micro-pacing on big keyframes prevents Wi-Fi TX ring drops
                                if (isLargeBurst && (++packetCounter and 3) == 0) {
                                    LockSupport.parkNanos(100_000L) // 0.1ms
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "UDP send failed: ${e.message}")
                        }
                    }
                }
            } finally {
                try { udpSock.close() } catch (_: Exception) {}
                udpSendSocket = null
            }
        }, "NewCastVideoSender").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopOutboundSenderLoop() {
        senderThreadRunning.set(false)
        senderThread?.interrupt()
        try { senderThread?.join(150L) } catch (_: Exception) {}
        senderThread = null
    }

    private fun startUdpAndTcpServers() {
        // TCP Control Server (port 50501)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(CastPorts.CONTROL_TCP))
                }
                controlServerSocket = server

                while (isActive && !server.isClosed) {
                    val clientSocket = server.accept()
                    handleControlClient(clientSocket)
                }
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "Control server stopped: ${e.message}")
            }
        }

        // Optional TCP Video Server (port 50503) for TCP_RELIABLE fallback
        serviceScope.launch(Dispatchers.IO) {
            try {
                val videoServer = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(CastPorts.VIDEO_TCP))
                }
                tcpVideoServerSocket = videoServer

                while (isActive && !videoServer.isClosed) {
                    val vSock = videoServer.accept().apply {
                        tcpNoDelay = true
                        sendBufferSize = 1024 * 1024
                        try {
                            trafficClass = CastPorts.DSCP_EF_TRAFFIC_CLASS
                        } catch (_: Exception) {
                        }
                    }
                    val current = activeClient
                    if (current != null && current.transport == TransportMode.TCP_RELIABLE) {
                        current.tcpVideoSocket = vSock
                        current.tcpVideoOut = DataOutputStream(vSock.getOutputStream())
                        screenEncoder?.requestSyncFrame()
                    } else {
                        try { vSock.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "TCP video server stopped: ${e.message}")
            }
        }
    }

    private fun handleControlClient(socket: Socket) {
        serviceScope.launch(Dispatchers.IO) {
            var session: ConnectedMonitorSession? = null
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 0
                val clientIp = socket.inetAddress.hostAddress ?: return@launch
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                val firstLine = reader.readLine() ?: return@launch
                val hello = ControlMessage.parse(firstLine) as? ControlMessage.Hello ?: return@launch

                // Disconnect any previous monitor cleanly
                activeClient?.closeAll()
                outboundQueue.clear()

                // Apply monitor's requested preset if different
                if (hello.preset != activePreset) {
                    applyPreset(hello.preset)
                }
                activeTransport = hello.transport

                val destAddr = InetAddress.getByName(clientIp)
                val newSession = ConnectedMonitorSession(
                    deviceName = hello.deviceName,
                    clientIp = clientIp,
                    destAddress = destAddr,
                    udpPort = hello.udpPort,
                    transport = hello.transport,
                    controlSocket = socket,
                    controlWriter = writer
                )
                session = newSession
                activeClient = newSession

                // Active streaming mode: acquire low-latency locks, pause discovery broadcast, resume encoder
                acquireActivePowerLocks()
                discoveryAdvertiser?.setBeaconsPaused(true)
                screenEncoder?.setSuspended(false)
                startStatsTicker()

                val enc = screenEncoder
                val accReady = RemoteShutterAccessibilityService.instance != null
                newSession.sendControl(
                    ControlMessage.Welcome(
                        deviceName = NetworkHelper.getDeviceDisplayName(),
                        width = enc?.currentWidth ?: 720,
                        height = enc?.currentHeight ?: 1600,
                        fps = enc?.currentFps ?: activePreset.fps,
                        bitrateBps = enc?.currentBitrateBps ?: activePreset.bitrateBps,
                        codec = enc?.currentCodecType ?: activeCodec,
                        transport = hello.transport,
                        tcpVideoPort = CastPorts.VIDEO_TCP,
                        accessibilityReady = accReady,
                        preset = activePreset
                    )
                )

                _runtimeState.value = _runtimeState.value.copy(
                    statusText = "Đang trình chiếu tới ${hello.deviceName}",
                    transportMode = hello.transport,
                    connectedMonitorName = hello.deviceName,
                    connectedMonitorIp = clientIp
                )
                updateNotification("Đang chiếu tới ${hello.deviceName} (${activePreset.badge})")

                // Immediately push fresh IDR keyframes so the monitor displays video in < 50ms
                screenEncoder?.requestSyncFrame()
                mainHandler.postDelayed({ screenEncoder?.requestSyncFrame() }, 250L)

                // Control receive loop
                while (isActive && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    val msg = ControlMessage.parse(line) ?: continue
                    processControlMessage(msg, newSession)
                }
            } catch (e: Exception) {
                Log.i(TAG, "Monitor control session ended: ${e.message}")
            } finally {
                if (session != null && activeClient === session) {
                    activeClient = null
                    // Power-saving idle mode: suspend encoder & release high-power Wi-Fi/CPU locks
                    screenEncoder?.setSuspended(true)
                    statsJob?.cancel()
                    statsJob = null
                    releaseActivePowerLocks()
                    discoveryAdvertiser?.setBeaconsPaused(false)
                    _runtimeState.value = _runtimeState.value.copy(
                        statusText = "Chế độ chờ tiết kiệm pin — chờ máy phụ kết nối lại",
                        encodedFps = 0,
                        currentBitrateMbps = 0f,
                        connectedMonitorName = null,
                        connectedMonitorIp = null
                    )
                    updateNotification("Chế độ chờ tiết kiệm pin...")
                }
                session?.closeAll()
            }
        }
    }

    private fun processControlMessage(msg: ControlMessage, session: ConnectedMonitorSession) {
        when (msg) {
            ControlMessage.RequestIdr -> {
                screenEncoder?.requestSyncFrame()
            }
            is ControlMessage.ChangePreset -> {
                applyPreset(msg.preset)
            }
            is ControlMessage.Ping -> {
                val accReady = RemoteShutterAccessibilityService.instance != null
                session.sendControl(ControlMessage.Pong(msg.clientTimeMs, accReady))
            }
            is ControlMessage.Feedback -> {
                val adjustedBitrate = abrController.onNetworkFeedback(msg.lossPct, msg.rttMs)
                if (adjustedBitrate != null) {
                    screenEncoder?.updateBitrate(adjustedBitrate)
                }
            }
            ControlMessage.Shutter -> {
                mainHandler.post {
                    val acc = RemoteShutterAccessibilityService.instance
                    if (acc == null) {
                        session.sendControl(
                            ControlMessage.ShutterAck(
                                success = false,
                                message = "Chưa bật NewCast Remote Shutter trong Trợ năng trên máy quay (hoặc dùng Bluetooth HID)"
                            )
                        )
                    } else {
                        val (ok, detail) = acc.triggerCameraShutter()
                        session.sendControl(ControlMessage.ShutterAck(ok, detail))
                    }
                }
            }
            is ControlMessage.Tap -> {
                mainHandler.post {
                    val acc = RemoteShutterAccessibilityService.instance
                    if (acc != null) {
                        acc.dispatchNormalizedTap(msg.normX, msg.normY)
                    } else {
                        session.sendControl(
                            ControlMessage.ShutterAck(
                                success = false,
                                message = "Chưa bật Trợ năng (NewCast Remote Shutter) trên Máy Quay"
                            )
                        )
                    }
                }
            }
            is ControlMessage.LongPress -> {
                mainHandler.post {
                    val acc = RemoteShutterAccessibilityService.instance
                    if (acc != null) {
                        acc.dispatchNormalizedLongPress(msg.normX, msg.normY, msg.durationMs)
                    } else {
                        session.sendControl(
                            ControlMessage.ShutterAck(
                                success = false,
                                message = "Chưa bật Trợ năng (NewCast Remote Shutter) trên Máy Quay"
                            )
                        )
                    }
                }
            }
            is ControlMessage.Swipe -> {
                mainHandler.post {
                    val acc = RemoteShutterAccessibilityService.instance
                    if (acc != null) {
                        acc.dispatchNormalizedSwipe(msg.x1, msg.y1, msg.x2, msg.y2, msg.durationMs)
                    } else {
                        session.sendControl(
                            ControlMessage.ShutterAck(
                                success = false,
                                message = "Chưa bật Trợ năng (NewCast Remote Shutter) trên Máy Quay"
                            )
                        )
                    }
                }
            }
            is ControlMessage.NavAction -> {
                mainHandler.post {
                    val acc = RemoteShutterAccessibilityService.instance
                    if (acc != null) {
                        acc.performRemoteNav(msg.action)
                    } else {
                        session.sendControl(
                            ControlMessage.ShutterAck(
                                success = false,
                                message = "Chưa bật Trợ năng (NewCast Remote Shutter) trên Máy Quay"
                            )
                        )
                    }
                }
            }
            ControlMessage.Stop -> {
                session.closeAll()
            }
            else -> Unit
        }
    }

    private fun applyPreset(preset: VideoQualityPreset) {
        val enc = screenEncoder ?: return
        activePreset = preset
        abrController.updateTarget(preset.bitrateBps)

        val (screenW, screenH, _) = getScreenMetrics()
        val (capW, capH) = preset.computeAlignedDimensions(screenW, screenH)
        enc.reconfigure(capW, capH, preset.fps, preset.bitrateBps, activeCodec)
        if (activeClient == null) {
            enc.setSuspended(true)
        }

        activeClient?.sendControl(
            ControlMessage.VideoConfig(capW, capH, preset.fps, activeCodec)
        )
        _runtimeState.value = _runtimeState.value.copy(
            preset = preset,
            captureWidth = capW,
            captureHeight = capH,
            currentBitrateMbps = if (activeClient != null) preset.bitrateBps / 1_000_000f else 0f
        )
        if (activeClient != null) {
            mainHandler.postDelayed({ screenEncoder?.requestSyncFrame() }, 120L)
        }
    }

    private fun handleDisplayOrientationChanged() {
        val enc = screenEncoder ?: return
        val (screenW, screenH, _) = getScreenMetrics()
        val (capW, capH) = activePreset.computeAlignedDimensions(screenW, screenH)
        if (capW == enc.currentWidth && capH == enc.currentHeight) return

        enc.reconfigure(capW, capH, activePreset.fps, abrController.currentBitrateBps, activeCodec)
        if (activeClient == null) {
            enc.setSuspended(true)
        }
        activeClient?.sendControl(
            ControlMessage.VideoConfig(capW, capH, activePreset.fps, activeCodec)
        )
        _runtimeState.value = _runtimeState.value.copy(
            captureWidth = capW,
            captureHeight = capH
        )
        if (activeClient != null) {
            mainHandler.postDelayed({ screenEncoder?.requestSyncFrame() }, 120L)
        }
    }

    private fun startStatsTicker() {
        statsJob?.cancel()
        statsJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000L)
                val fps = fpsFramesWindow.getAndSet(0)
                val bytes = bitrateBytesWindow.getAndSet(0L)
                val mbps = (bytes * 8f) / 1_000_000f
                _runtimeState.value = _runtimeState.value.copy(
                    encodedFps = fps,
                    currentBitrateMbps = if (activeClient != null) mbps else abrController.currentBitrateBps / 1_000_000f
                )
            }
        }
    }

    private fun getScreenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), dpi)
        } else {
            val dm = resources.displayMetrics
            Triple(dm.widthPixels, dm.heightPixels, dpi)
        }
    }

    private fun getCurrentDisplayRotation(): Int {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun stopCastingAndSelf() {
        cleanupPipeline()
        _runtimeState.value = SenderRuntimeState(
            isCasting = false,
            statusText = "Đã dừng phát màn hình"
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupPipeline() {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }

        statsJob?.cancel()
        statsJob = null
        stopOutboundSenderLoop()

        discoveryAdvertiser?.stop()
        discoveryAdvertiser = null

        activeClient?.sendControl(ControlMessage.Stop)
        activeClient?.closeAll()
        activeClient = null

        try { controlServerSocket?.close() } catch (_: Exception) {}
        controlServerSocket = null
        try { tcpVideoServerSocket?.close() } catch (_: Exception) {}
        tcpVideoServerSocket = null
        try { udpSendSocket?.close() } catch (_: Exception) {}
        udpSendSocket = null

        outboundQueue.clear()

        screenEncoder?.release()
        screenEncoder = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        releaseActivePowerLocks()
    }

    private fun acquireActivePowerLocks() {
        if (wifiLock == null) {
            wifiLock = NetworkHelper.acquireLowLatencyWifiLock(this, "NewCast:SenderWifiLock")
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NewCast:SenderWakeLock").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseActivePowerLocks() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        cleanupPipeline()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NewCast Screen Casting",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hiển thị trạng thái phát màn hình NewCast"
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CastSenderService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("NewCast • Đang phát màn hình")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Dừng phát", stopIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        try {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID, buildNotification(contentText))
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "NewCastSenderService"
        private const val CHANNEL_ID = "newcast_sender_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.newcast.app.ACTION_START_CAST"
        const val ACTION_STOP = "com.newcast.app.ACTION_STOP_CAST"
        const val ACTION_CHANGE_PRESET = "com.newcast.app.ACTION_CHANGE_PRESET"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_CODEC = "codec"

        private val _runtimeState = MutableStateFlow(SenderRuntimeState())
        val runtimeState: StateFlow<SenderRuntimeState> = _runtimeState.asStateFlow()

        fun startCasting(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            preset: VideoQualityPreset,
            transport: TransportMode,
            codec: VideoCodecType
        ) {
            val intent = Intent(context, CastSenderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_PRESET, preset.name)
                putExtra(EXTRA_TRANSPORT, transport.name)
                putExtra(EXTRA_CODEC, codec.name)
            }
            context.startForegroundService(intent)
        }

        fun stopCasting(context: Context) {
            val intent = Intent(context, CastSenderService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun changePreset(context: Context, preset: VideoQualityPreset) {
            val intent = Intent(context, CastSenderService::class.java).apply {
                action = ACTION_CHANGE_PRESET
                putExtra(EXTRA_PRESET, preset.name)
            }
            context.startService(intent)
        }
    }
}
