package com.newcast.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.newcast.app.model.AppRole
import com.newcast.app.model.MonitorConnectionState
import com.newcast.app.ui.HomeScreen
import com.newcast.app.ui.MonitorScreen
import com.newcast.app.ui.NewCastViewModel
import com.newcast.app.ui.SenderScreen
import com.newcast.app.ui.theme.NewCastTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: NewCastViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NewCastTheme {
                val role by viewModel.role.collectAsState()
                val network by viewModel.networkSnapshot.collectAsState()
                val preset by viewModel.selectedPreset.collectAsState()
                val transport by viewModel.selectedTransport.collectAsState()
                val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsState()
                val senderState by viewModel.senderState.collectAsState()
                val discoveredPeers by viewModel.discoveredPeers.collectAsState()
                val manualIp by viewModel.manualIp.collectAsState()
                val monitorConnState by viewModel.monitorConnectionState.collectAsState()
                val monitorStats by viewModel.monitorStats.collectAsState()
                val btShutterState by viewModel.bluetoothShutterState.collectAsState()
                val preferBtShutter by viewModel.preferBluetoothShutter.collectAsState()

                var feedbackBanner by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    viewModel.toastEvents.collect { message ->
                        feedbackBanner = message
                    }
                }

                LaunchedEffect(feedbackBanner) {
                    if (feedbackBanner != null) {
                        delay(2600L)
                        feedbackBanner = null
                    }
                }

                // Keep screen awake & toggle immersive fullscreen during live Monitor playback
                val isImmersiveMonitor = role == AppRole.MONITOR && (
                    monitorConnState is MonitorConnectionState.Connected ||
                        monitorConnState is MonitorConnectionState.Reconnecting
                    )
                val keepScreenAwake = isImmersiveMonitor || senderState.isCasting

                LaunchedEffect(keepScreenAwake, isImmersiveMonitor) {
                    if (keepScreenAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    setImmersiveSystemBars(isImmersiveMonitor)
                }

                // Screen Capture (MediaProjection) launcher
                val projectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        viewModel.startSenderWithProjection(result.resultCode, data)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Bạn cần đồng ý chia sẻ toàn màn hình để bắt đầu phát",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                // Notification permission launcher (Android 13+) before starting Sender foreground service
                val notifPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ ->
                    launchScreenCaptureConsent(projectionLauncher::launch)
                }

                // Optional Bluetooth permissions launcher (only when user opens Bluetooth HID Shutter)
                val btPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    if (grants.values.all { it }) {
                        viewModel.startBluetoothHid()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Cần quyền Bluetooth để dùng Bluetooth HID Shutter",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                fun requestStartCasting() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        launchScreenCaptureConsent(projectionLauncher::launch)
                    }
                }

                fun requestEnableBluetoothHid() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !viewModel.hasBluetoothPermission()) {
                        btPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_ADVERTISE
                            )
                        )
                    } else {
                        viewModel.startBluetoothHid()
                    }
                }

                // System Back button / edge-swipe navigation stack:
                // Live Monitor Viewfinder -> Monitor Lobby -> Home Screen -> Exit App
                // Sender Screen -> Home Screen -> Exit App
                BackHandler(enabled = role != AppRole.HOME) {
                    when (role) {
                        AppRole.MONITOR -> {
                            val inActiveSession = monitorConnState is MonitorConnectionState.Connected ||
                                monitorConnState is MonitorConnectionState.Reconnecting ||
                                monitorConnState is MonitorConnectionState.Connecting
                            if (inActiveSession) {
                                viewModel.disconnectMonitor()
                            } else {
                                viewModel.goHome()
                            }
                        }
                        AppRole.SENDER -> viewModel.goHome()
                        AppRole.HOME -> Unit
                    }
                }

                when (role) {
                    AppRole.HOME -> HomeScreen(
                        network = network,
                        isSenderCasting = senderState.isCasting,
                        selectedPreset = preset,
                        selectedTransport = transport,
                        onSelectSender = { viewModel.selectRole(AppRole.SENDER) },
                        onSelectMonitor = { viewModel.selectRole(AppRole.MONITOR) },
                        onSelectPreset = viewModel::setPreset,
                        onSelectTransport = viewModel::setTransportMode,
                        onRefreshNetwork = viewModel::refreshSystemStatus,
                        onOpenHotspotSettings = ::openHotspotSettings,
                        onOpenWifiSettings = ::openWifiSettings
                    )

                    AppRole.SENDER -> SenderScreen(
                        senderState = senderState,
                        network = network,
                        selectedPreset = preset,
                        accessibilityEnabled = accessibilityEnabled,
                        onBack = viewModel::goHome,
                        onStartCasting = ::requestStartCasting,
                        onStopCasting = viewModel::stopSender,
                        onLaunchPixelCamera = ::launchPixelCamera,
                        onSelectPreset = viewModel::setPreset,
                        onOpenHotspotSettings = ::openHotspotSettings,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings
                    )

                    AppRole.MONITOR -> MonitorScreen(
                        connectionState = monitorConnState,
                        stats = monitorStats,
                        network = network,
                        discoveredPeers = discoveredPeers,
                        manualIp = manualIp,
                        selectedPreset = preset,
                        bluetoothShutterState = btShutterState,
                        preferBluetoothShutter = preferBtShutter,
                        latestFeedbackBanner = feedbackBanner,
                        onBack = viewModel::goHome,
                        onUpdateManualIp = viewModel::updateManualIp,
                        onConnectPeer = viewModel::connectToPeer,
                        onConnectManualIp = { ip -> viewModel.connectToIp(ip) },
                        onDisconnect = viewModel::disconnectMonitor,
                        onAttachSurface = viewModel::attachMonitorSurface,
                        onRemoteTap = viewModel::sendRemoteTap,
                        onRemoteLongPress = viewModel::sendRemoteLongPress,
                        onRemoteSwipe = { x1, y1, x2, y2, dur -> viewModel.sendRemoteSwipe(x1, y1, x2, y2, dur) },
                        onRemoteNavAction = viewModel::sendRemoteNavAction,
                        onChangePreset = viewModel::setPreset,
                        onRequestFreshKeyframe = viewModel::requestFreshKeyframe,
                        onOpenWifiSettings = ::openWifiSettings,
                        onEnableBluetoothHid = ::requestEnableBluetoothHid,
                        onConnectBluetoothHidHost = viewModel::connectBluetoothHidHost,
                        onStopBluetoothHid = viewModel::stopBluetoothHid,
                        onSetPreferBluetoothShutter = viewModel::setPreferBluetoothShutter
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForegroundActive(true)
    }

    override fun onStop() {
        viewModel.setForegroundActive(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSystemStatus()
    }

    private fun launchScreenCaptureConsent(launch: (Intent) -> Unit) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14/15+: default directly to Entire Screen so switching to Pixel Camera works!
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        launch(intent)
    }

    private fun setImmersiveSystemBars(immersive: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun launchPixelCamera() {
        try {
            val gcamIntent = packageManager.getLaunchIntentForPackage("com.google.android.GoogleCamera")
            if (gcamIntent != null) {
                gcamIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(gcamIntent)
                return
            }
        } catch (_: Exception) {
        }
        try {
            val fallback = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallback)
        } catch (_: Exception) {
            Toast.makeText(this, "Không mở được app Camera — hãy mở thủ công", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openHotspotSettings() {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
