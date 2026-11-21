package com.newcast.app.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

data class BluetoothShutterState(
    val isRegistered: Boolean = false,
    val connectedDeviceName: String? = null,
    val connectedDeviceAddress: String? = null,
    val bondedDevices: List<BondedBluetoothPeer> = emptyList(),
    val statusText: String = "Chưa bật Bluetooth HID Shutter"
)

data class BondedBluetoothPeer(
    val name: String,
    val address: String
)

/**
 * Optional hardware-key Bluetooth HID Remote Shutter (Consumer Control Volume Up).
 * Works directly with stock Pixel Camera without needing Accessibility Service on the Camera phone.
 */
class BluetoothShutterController(private val context: Context) {

    private val _state = MutableStateFlow(BluetoothShutterState())
    val state: StateFlow<BluetoothShutterState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val btExecutor = Executors.newSingleThreadExecutor()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedDevices() {
        if (!hasBluetoothPermission()) return
        val adapter = bluetoothAdapter ?: return
        val bonded = try {
            adapter.bondedDevices?.map { dev ->
                BondedBluetoothPeer(
                    name = dev.name ?: dev.address,
                    address = dev.address
                )
            }?.sortedBy { it.name.lowercase() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        _state.value = _state.value.copy(bondedDevices = bonded)
    }

    @SuppressLint("MissingPermission")
    fun startHidProfile() {
        if (!hasBluetoothPermission()) {
            _state.value = _state.value.copy(statusText = "Cần cấp quyền Bluetooth để dùng HID Shutter")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(statusText = "Hãy bật Bluetooth trước")
            return
        }
        refreshBondedDevices()

        if (hidDevice != null && _state.value.isRegistered) return

        adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        _state.value = _state.value.copy(statusText = "Đang khởi tạo Bluetooth HID Shutter...")
    }

    @SuppressLint("MissingPermission")
    fun connectToHost(address: String) {
        if (!hasBluetoothPermission()) return
        val adapter = bluetoothAdapter ?: return
        val hid = hidDevice ?: run {
            startHidProfile()
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        } ?: return

        _state.value = _state.value.copy(statusText = "Đang kết nối HID tới ${device.name ?: address}...")
        hid.connect(device)
    }

    @SuppressLint("MissingPermission")
    fun triggerVolumeUpShutter(): Boolean {
        if (!hasBluetoothPermission()) return false
        val hid = hidDevice ?: return false
        val host = connectedHost ?: return false

        val pressed = hid.sendReport(host, REPORT_ID_CONSUMER, byteArrayOf(0x01))
        if (pressed) {
            mainHandler.postDelayed({
                try {
                    hid.sendReport(host, REPORT_ID_CONSUMER, byteArrayOf(0x00))
                } catch (_: Exception) {
                }
            }, 65L)
        }
        return pressed
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val hid = hidDevice
        val adapter = bluetoothAdapter
        try {
            if (hid != null && hasBluetoothPermission()) {
                connectedHost?.let { hid.disconnect(it) }
                hid.unregisterApp()
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            }
        } catch (_: Exception) {
        }
        hidDevice = null
        connectedHost = null
        _state.value = BluetoothShutterState(
            bondedDevices = _state.value.bondedDevices,
            statusText = "Đã tắt Bluetooth HID Shutter"
        )
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hid = proxy as BluetoothHidDevice
            hidDevice = hid

            val sdp = BluetoothHidDeviceAppSdpSettings(
                "NewCast Shutter",
                "Wireless Camera Remote Shutter",
                "NewCast",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HID_CONSUMER_DESCRIPTOR
            )
            try {
                hid.registerApp(sdp, null, null, btExecutor, hidCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed registering HID app", e)
                _state.value = _state.value.copy(statusText = "Lỗi đăng ký HID: ${e.message}")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                connectedHost = null
                _state.value = _state.value.copy(
                    isRegistered = false,
                    connectedDeviceName = null,
                    connectedDeviceAddress = null,
                    statusText = "Bluetooth HID đã ngắt"
                )
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            _state.value = _state.value.copy(
                isRegistered = registered,
                statusText = if (registered) {
                    "Bluetooth HID sẵn sàng — chọn máy Pixel đã ghép đôi"
                } else {
                    "Chưa đăng ký được Bluetooth HID"
                }
            )
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    _state.value = _state.value.copy(
                        connectedDeviceName = device?.name ?: device?.address,
                        connectedDeviceAddress = device?.address,
                        statusText = "HID Shutter đã kết nối: ${device?.name ?: device?.address}"
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost?.address == device?.address) {
                        connectedHost = null
                    }
                    _state.value = _state.value.copy(
                        connectedDeviceName = null,
                        connectedDeviceAddress = null,
                        statusText = "Bluetooth HID đã ngắt kết nối"
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "NewCastBtHid"
        private const val REPORT_ID_CONSUMER = 1

        // Standard USB HID 1.11 Consumer Control descriptor (Volume Up = bit 0, Volume Down = bit 1)
        private val HID_CONSUMER_DESCRIPTOR = byteArrayOf(
            0x05, 0x0C,
            0x09, 0x01,
            0xA1.toByte(), 0x01,
            0x85.toByte(), REPORT_ID_CONSUMER.toByte(),
            0x15, 0x00,
            0x25, 0x01,
            0x75, 0x01,
            0x95.toByte(), 0x02,
            0x09, 0xE9.toByte(),
            0x09, 0xEA.toByte(),
            0x81.toByte(), 0x02,
            0x95.toByte(), 0x06,
            0x81.toByte(), 0x03,
            0xC0.toByte()
        )
    }
}
