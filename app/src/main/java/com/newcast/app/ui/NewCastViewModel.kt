package com.newcast.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newcast.app.model.AppRole
import com.newcast.app.model.DiscoveredPeer
import com.newcast.app.model.LocalNetworkSnapshot
import com.newcast.app.model.MonitorConnectionState
import com.newcast.app.model.MonitorStats
import com.newcast.app.model.SenderRuntimeState
import com.newcast.app.model.TransportMode
import com.newcast.app.model.VideoCodecType
import com.newcast.app.model.VideoQualityPreset
import com.newcast.app.network.CastPorts
import com.newcast.app.network.MonitorDiscoveryScanner
import com.newcast.app.network.NetworkHelper
import com.newcast.app.receiver.CastReceiverClient
import com.newcast.app.remote.BluetoothShutterController
import com.newcast.app.remote.BluetoothShutterState
import com.newcast.app.remote.RemoteShutterAccessibilityService
import com.newcast.app.sender.CastSenderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NewCastViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val prefs = appContext.getSharedPreferences("newcast_prefs", Context.MODE_PRIVATE)

    private val _role = MutableStateFlow(AppRole.HOME)
    val role: StateFlow<AppRole> = _role.asStateFlow()

    private val _selectedPreset = MutableStateFlow(
        VideoQualityPreset.fromNameOrDefault(prefs.getString(KEY_PRESET, null))
    )
    val selectedPreset: StateFlow<VideoQualityPreset> = _selectedPreset.asStateFlow()

    private val _selectedTransport = MutableStateFlow(
        TransportMode.entries.firstOrNull { it.name == prefs.getString(KEY_TRANSPORT, null) }
            ?: TransportMode.UDP_LOW_LATENCY
    )
    val selectedTransport: StateFlow<TransportMode> = _selectedTransport.asStateFlow()

    private val _selectedCodec = MutableStateFlow(
        VideoCodecType.entries.firstOrNull { it.name == prefs.getString(KEY_CODEC, null) }
            ?: VideoCodecType.H264
    )
    val selectedCodec: StateFlow<VideoCodecType> = _selectedCodec.asStateFlow()

    private val _manualIp = MutableStateFlow(prefs.getString(KEY_LAST_IP, "") ?: "")
    val manualIp: StateFlow<String> = _manualIp.asStateFlow()

    private val _networkSnapshot = MutableStateFlow(NetworkHelper.inspectLocalNetwork(appContext))
    val networkSnapshot: StateFlow<LocalNetworkSnapshot> = _networkSnapshot.asStateFlow()

    private val _accessibilityEnabled = MutableStateFlow(
        RemoteShutterAccessibilityService.isEnabledInSettings(appContext)
    )
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    private val _preferBluetoothShutter = MutableStateFlow(false)
    val preferBluetoothShutter: StateFlow<Boolean> = _preferBluetoothShutter.asStateFlow()

    val senderState: StateFlow<SenderRuntimeState> = CastSenderService.runtimeState

    private val discoveryScanner = MonitorDiscoveryScanner(appContext, viewModelScope)
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = discoveryScanner.peers

    private val receiverClient = CastReceiverClient(appContext, viewModelScope)
    val monitorConnectionState: StateFlow<MonitorConnectionState> = receiverClient.connectionState
    val monitorStats: StateFlow<MonitorStats> = receiverClient.stats

    private val bluetoothShutter = BluetoothShutterController(appContext)
    val bluetoothShutterState: StateFlow<BluetoothShutterState> = bluetoothShutter.state

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 12)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    @Volatile
    private var isForegroundActive = true

    init {
        // Power-efficient status refresh: paused in background (e.g. inside Pixel Camera)
        // and paused during fullscreen Monitor playback so zero battery is wasted polling system_server.
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val isLiveMonitor = _role.value == AppRole.MONITOR &&
                    monitorConnectionState.value is MonitorConnectionState.Connected
                if (isForegroundActive && !isLiveMonitor) {
                    refreshSystemStatus()
                }
                delay(5000L)
            }
        }

        // Forward remote shutter acknowledgements to UI toast pill
        viewModelScope.launch {
            receiverClient.remoteFeedbackEvents.collect { (_, msg) ->
                if (msg.isNotBlank()) {
                    _toastEvents.tryEmit(msg)
                }
            }
        }

        // If Sender service is already casting (e.g. returning from Pixel Camera), jump to SENDER view
        if (CastSenderService.runtimeState.value.isCasting) {
            _role.value = AppRole.SENDER
        }
    }

    fun setForegroundActive(active: Boolean) {
        isForegroundActive = active
        if (active) {
            viewModelScope.launch(Dispatchers.IO) {
                refreshSystemStatus()
            }
        }
    }

    fun refreshSystemStatus() {
        val snap = NetworkHelper.inspectLocalNetwork(appContext)
        _networkSnapshot.value = snap
        _accessibilityEnabled.value = RemoteShutterAccessibilityService.isEnabledInSettings(appContext)

        // Pre-fill manual IP with Hotspot gateway if empty (1-tap ready!)
        if (_manualIp.value.isBlank() && !snap.gatewayIp.isNullOrBlank()) {
            _manualIp.value = snap.gatewayIp
        }
    }

    fun selectRole(newRole: AppRole) {
        _role.value = newRole
        if (newRole == AppRole.MONITOR) {
            discoveryScanner.start()
            bluetoothShutter.refreshBondedDevices()
        } else {
            discoveryScanner.stop()
            if (newRole != AppRole.MONITOR) {
                receiverClient.disconnect()
            }
        }
    }

    fun goHome() {
        discoveryScanner.stop()
        receiverClient.disconnect()
        _role.value = AppRole.HOME
    }

    fun setPreset(preset: VideoQualityPreset) {
        _selectedPreset.value = preset
        prefs.edit().putString(KEY_PRESET, preset.name).apply()
        if (senderState.value.isCasting) {
            CastSenderService.changePreset(appContext, preset)
        }
        if (monitorConnectionState.value is MonitorConnectionState.Connected) {
            receiverClient.changePreset(preset)
        }
    }

    fun setTransportMode(mode: TransportMode) {
        _selectedTransport.value = mode
        prefs.edit().putString(KEY_TRANSPORT, mode.name).apply()
    }

    fun setCodecType(codec: VideoCodecType) {
        _selectedCodec.value = codec
        prefs.edit().putString(KEY_CODEC, codec.name).apply()
    }

    fun updateManualIp(ip: String) {
        _manualIp.value = ip.trim()
    }

    fun startSenderWithProjection(resultCode: Int, resultData: Intent) {
        CastSenderService.startCasting(
            context = appContext,
            resultCode = resultCode,
            resultData = resultData,
            preset = _selectedPreset.value,
            transport = _selectedTransport.value,
            codec = _selectedCodec.value
        )
    }

    fun stopSender() {
        CastSenderService.stopCasting(appContext)
    }

    fun connectToPeer(peer: DiscoveredPeer) {
        connectToIp(peer.ipAddress, peer.controlPort)
    }

    fun connectToIp(ip: String, port: Int = CastPorts.CONTROL_TCP) {
        val cleaned = ip.trim()
        if (cleaned.isBlank()) {
            _toastEvents.tryEmit("Hãy nhập địa chỉ IP của máy quay")
            return
        }
        _manualIp.value = cleaned
        prefs.edit().putString(KEY_LAST_IP, cleaned).apply()
        discoveryScanner.stop()
        receiverClient.connect(
            hostIp = cleaned,
            controlPort = port,
            transportMode = _selectedTransport.value,
            preset = _selectedPreset.value
        )
    }

    fun disconnectMonitor() {
        receiverClient.disconnect()
        if (_role.value == AppRole.MONITOR) {
            discoveryScanner.start()
        }
    }

    fun attachMonitorSurface(surface: Surface?) {
        receiverClient.attachSurface(surface)
    }

    fun triggerRemoteShutter() {
        val btState = bluetoothShutter.state.value
        val connState = monitorConnectionState.value as? MonitorConnectionState.Connected

        val useBt = _preferBluetoothShutter.value && btState.connectedDeviceAddress != null
        val fallbackToBt = connState != null && !connState.accessibilityReady && btState.connectedDeviceAddress != null

        if (useBt || fallbackToBt) {
            val ok = bluetoothShutter.triggerVolumeUpShutter()
            _toastEvents.tryEmit(
                if (ok) "Đã gửi phím chụp Bluetooth HID (Volume Up)"
                else "Bluetooth HID chưa sẵn sàng"
            )
        } else {
            receiverClient.sendShutter()
        }
    }

    fun sendRemoteTap(normX: Float, normY: Float) {
        receiverClient.sendTap(normX, normY)
    }

    fun sendRemoteLongPress(normX: Float, normY: Float) {
        receiverClient.sendLongPress(normX, normY)
    }

    fun sendRemoteSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 220L) {
        receiverClient.sendSwipe(x1, y1, x2, y2, durationMs)
    }

    fun sendRemoteNavAction(action: String) {
        receiverClient.sendNavAction(action)
    }

    fun requestFreshKeyframe() {
        receiverClient.requestIdrThrottled()
    }

    fun setPreferBluetoothShutter(prefer: Boolean) {
        _preferBluetoothShutter.value = prefer
    }

    fun hasBluetoothPermission(): Boolean = bluetoothShutter.hasBluetoothPermission()

    fun startBluetoothHid() {
        bluetoothShutter.startHidProfile()
    }

    fun connectBluetoothHidHost(address: String) {
        bluetoothShutter.connectToHost(address)
    }

    fun stopBluetoothHid() {
        bluetoothShutter.stop()
    }

    override fun onCleared() {
        discoveryScanner.stop()
        receiverClient.release()
        bluetoothShutter.stop()
        super.onCleared()
    }

    companion object {
        private const val KEY_PRESET = "preset"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_CODEC = "codec"
        private const val KEY_LAST_IP = "last_host_ip"
    }
}
