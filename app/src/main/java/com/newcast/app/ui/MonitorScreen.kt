package com.newcast.app.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.newcast.app.model.DiscoveredPeer
import com.newcast.app.model.LocalNetworkSnapshot
import com.newcast.app.model.MonitorConnectionState
import com.newcast.app.model.MonitorStats
import com.newcast.app.model.VideoQualityPreset
import com.newcast.app.remote.BluetoothShutterState
import com.newcast.app.ui.theme.MonoBlack
import com.newcast.app.ui.theme.MonoBorder
import com.newcast.app.ui.theme.MonoBorderStrong
import com.newcast.app.ui.theme.MonoInk
import com.newcast.app.ui.theme.MonoSurface
import com.newcast.app.ui.theme.MonoSurfaceRaised
import com.newcast.app.ui.theme.MonoTextMuted
import com.newcast.app.ui.theme.MonoTextPrimary
import com.newcast.app.ui.theme.MonoTextSecondary
import com.newcast.app.ui.theme.MonoWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

object MonitorTouchMapper {
    /**
     * Maps normalized touch coordinates (u, v) on the displayed video rectangle back to
     * normalized coordinates (normX, normY) on the sender's original screen, undoing
     * optional horizontal mirror and clockwise display rotation.
     */
    fun mapViewToSenderNormalized(
        uRaw: Float,
        vRaw: Float,
        rotationDeg: Int,
        isMirrored: Boolean
    ): Pair<Float, Float> {
        val u0 = uRaw.coerceIn(0f, 1f)
        val v0 = vRaw.coerceIn(0f, 1f)
        val u = if (isMirrored) 1f - u0 else u0
        val v = v0
        return when (((rotationDeg % 360) + 360) % 360) {
            90 -> v to (1f - u)
            180 -> (1f - u) to (1f - v)
            270 -> (1f - v) to u
            else -> u to v
        }
    }
}

@Composable
fun MonitorScreen(
    connectionState: MonitorConnectionState,
    stats: MonitorStats,
    network: LocalNetworkSnapshot,
    discoveredPeers: List<DiscoveredPeer>,
    manualIp: String,
    selectedPreset: VideoQualityPreset,
    bluetoothShutterState: BluetoothShutterState,
    preferBluetoothShutter: Boolean,
    latestFeedbackBanner: String?,
    onBack: () -> Unit,
    onUpdateManualIp: (String) -> Unit,
    onConnectPeer: (DiscoveredPeer) -> Unit,
    onConnectManualIp: (String) -> Unit,
    onDisconnect: () -> Unit,
    onAttachSurface: (Surface?) -> Unit,
    onTriggerShutter: () -> Unit,
    onRemoteTap: (Float, Float) -> Unit,
    onRemoteLongPress: (Float, Float) -> Unit,
    onRemoteSwipe: (Float, Float, Float, Float, Long) -> Unit,
    onRemoteNavAction: (String) -> Unit,
    onChangePreset: (VideoQualityPreset) -> Unit,
    onRequestFreshKeyframe: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onEnableBluetoothHid: () -> Unit,
    onConnectBluetoothHidHost: (String) -> Unit,
    onStopBluetoothHid: () -> Unit,
    onSetPreferBluetoothShutter: (Boolean) -> Unit
) {
    val isLiveView = connectionState is MonitorConnectionState.Connected ||
        connectionState is MonitorConnectionState.Reconnecting

    if (isLiveView) {
        MonitorLiveViewfinder(
            connectionState = connectionState,
            stats = stats,
            selectedPreset = selectedPreset,
            bluetoothShutterState = bluetoothShutterState,
            preferBluetoothShutter = preferBluetoothShutter,
            latestFeedbackBanner = latestFeedbackBanner,
            onDisconnect = onDisconnect,
            onAttachSurface = onAttachSurface,
            onTriggerShutter = onTriggerShutter,
            onRemoteTap = onRemoteTap,
            onRemoteLongPress = onRemoteLongPress,
            onRemoteSwipe = onRemoteSwipe,
            onRemoteNavAction = onRemoteNavAction,
            onChangePreset = onChangePreset,
            onRequestFreshKeyframe = onRequestFreshKeyframe,
            onEnableBluetoothHid = onEnableBluetoothHid,
            onConnectBluetoothHidHost = onConnectBluetoothHidHost,
            onStopBluetoothHid = onStopBluetoothHid,
            onSetPreferBluetoothShutter = onSetPreferBluetoothShutter
        )
    } else {
        MonitorDiscoveryLobby(
            connectionState = connectionState,
            network = network,
            discoveredPeers = discoveredPeers,
            manualIp = manualIp,
            onBack = onBack,
            onUpdateManualIp = onUpdateManualIp,
            onConnectPeer = onConnectPeer,
            onConnectManualIp = onConnectManualIp,
            onOpenWifiSettings = onOpenWifiSettings
        )
    }
}

@Composable
private fun MonitorDiscoveryLobby(
    connectionState: MonitorConnectionState,
    network: LocalNetworkSnapshot,
    discoveredPeers: List<DiscoveredPeer>,
    manualIp: String,
    onBack: () -> Unit,
    onUpdateManualIp: (String) -> Unit,
    onConnectPeer: (DiscoveredPeer) -> Unit,
    onConnectManualIp: (String) -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Minimal header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = MonoWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "MÀN HÌNH PHỤ",
                    fontSize = 14.sp,
                    letterSpacing = 2.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MonoWhite
                )
            }
            MonoHairlineButton(label = "Wi-Fi", onClick = onOpenWifiSettings)
        }

        Spacer(Modifier.height(20.dp))

        // Compact network status line
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusDot(active = network.primaryIp != null)
                Text(
                    text = network.primaryIp ?: "Offline",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MonoTextSecondary
                )
            }
            if (connectionState is MonitorConnectionState.Connecting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = MonoWhite
                    )
                    Text(
                        text = connectionState.targetIp,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MonoWhite
                    )
                }
            }
        }

        if (connectionState is MonitorConnectionState.Error) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = connectionState.message,
                fontSize = 12.sp,
                color = MonoTextSecondary
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(thickness = 1.dp, color = MonoBorder)
        Spacer(Modifier.height(20.dp))

        // Discovered peers list (capped cleanly so it never scrolls)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (discoveredPeers.isEmpty()) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 1.5.dp,
                        color = MonoTextMuted
                    )
                    Text(
                        text = "Đang tìm máy quay...",
                        fontSize = 13.sp,
                        color = MonoTextSecondary
                    )
                }
            } else {
                discoveredPeers.take(3).forEach { peer ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onConnectPeer(peer) },
                        shape = RoundedCornerShape(14.dp),
                        color = MonoSurface,
                        border = BorderStroke(1.dp, MonoBorderStrong)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = peer.deviceName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MonoWhite
                                )
                                Text(
                                    text = peer.ipAddress,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MonoTextSecondary
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MonoWhite
                            ) {
                                Text(
                                    text = "Xem",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MonoInk,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Compact single-row IP connect bar at bottom
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = onUpdateManualIp,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "192.168.43.1",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MonoTextMuted
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MonoWhite
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MonoWhite,
                    unfocusedBorderColor = MonoBorder,
                    cursorColor = MonoWhite,
                    focusedContainerColor = MonoSurface,
                    unfocusedContainerColor = MonoSurface
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { onConnectManualIp(manualIp) }),
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                onClick = { onConnectManualIp(manualIp) },
                modifier = Modifier.height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MonoWhite,
                    contentColor = MonoInk
                )
            ) {
                Text("Nối", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MonitorLiveViewfinder(
    connectionState: MonitorConnectionState,
    stats: MonitorStats,
    selectedPreset: VideoQualityPreset,
    bluetoothShutterState: BluetoothShutterState,
    preferBluetoothShutter: Boolean,
    latestFeedbackBanner: String?,
    onDisconnect: () -> Unit,
    onAttachSurface: (Surface?) -> Unit,
    onTriggerShutter: () -> Unit,
    onRemoteTap: (Float, Float) -> Unit,
    onRemoteLongPress: (Float, Float) -> Unit,
    onRemoteSwipe: (Float, Float, Float, Float, Long) -> Unit,
    onRemoteNavAction: (String) -> Unit,
    onChangePreset: (VideoQualityPreset) -> Unit,
    onRequestFreshKeyframe: () -> Unit,
    onEnableBluetoothHid: () -> Unit,
    onConnectBluetoothHidHost: (String) -> Unit,
    onStopBluetoothHid: () -> Unit,
    onSetPreferBluetoothShutter: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val gestureScope = rememberCoroutineScope()

    var showHud by remember { mutableStateOf(true) }
    var rotationDeg by remember { mutableIntStateOf(0) }
    var isMirrored by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }
    var remoteTouchEnabled by remember { mutableStateOf(true) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var showBluetoothDialog by remember { mutableStateOf(false) }

    var shutterFlash by remember { mutableStateOf(false) }
    var tapIndicator by remember { mutableStateOf<Pair<Offset, Boolean>?>(null) }
    var swipeTrail by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }

    LaunchedEffect(shutterFlash) {
        if (shutterFlash) {
            delay(160L)
            shutterFlash = false
        }
    }

    LaunchedEffect(tapIndicator) {
        if (tapIndicator != null) {
            delay(460L)
            tapIndicator = null
        }
    }

    LaunchedEffect(swipeTrail) {
        if (swipeTrail != null) {
            delay(320L)
            swipeTrail = null
        }
    }

    val videoW = stats.videoWidth.coerceAtLeast(720)
    val videoH = stats.videoHeight.coerceAtLeast(1280)
    val effW = if (rotationDeg % 180 == 0) videoW else videoH
    val effH = if (rotationDeg % 180 == 0) videoH else videoW
    val useDirectSurfaceView = rotationDeg == 0 && !isMirrored

    val connectedInfo = connectionState as? MonitorConnectionState.Connected

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBlack)
    ) {
        // Aspect-fitted hardware video canvas
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val maxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val maxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)

            val videoAspect = effW.toFloat() / effH.toFloat()
            val containerAspect = maxW / maxH

            val (fitW, fitH) = if (videoAspect > containerAspect) {
                maxW to (maxW / videoAspect)
            } else {
                (maxH * videoAspect) to maxH
            }

            val fitWDp = with(density) { fitW.toDp() }
            val fitHDp = with(density) { fitH.toDp() }

            Box(
                modifier = Modifier.size(fitWDp, fitHDp)
            ) {
                // 1) Hardware video rendering layer
                if (useDirectSurfaceView) {
                    DirectLowLatencySurfaceView(
                        modifier = Modifier.fillMaxSize(),
                        onSurfaceReady = onAttachSurface
                    )
                } else {
                    TransformedTextureView(
                        modifier = Modifier.fillMaxSize(),
                        rotationDeg = rotationDeg,
                        isMirrored = isMirrored,
                        onSurfaceReady = onAttachSurface
                    )
                }

                // 2) Zero-delay unified touch overlay ABOVE AndroidView (Tap / Long-Press / Swipe)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(remoteTouchEnabled, rotationDeg, isMirrored, fitW, fitH) {
                            val dragThresholdPx = 26f
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startPos = down.position
                                val downTimeMs = System.currentTimeMillis()
                                var latestPos = startPos
                                var maxMoved = 0f
                                var longPressTriggered = false

                                val longPressJob = if (remoteTouchEnabled) {
                                    gestureScope.launch {
                                        delay(480L)
                                        if (maxMoved <= dragThresholdPx) {
                                            longPressTriggered = true
                                            tapIndicator = startPos to true
                                            val (nx, ny) = MonitorTouchMapper.mapViewToSenderNormalized(
                                                uRaw = startPos.x / fitW,
                                                vRaw = startPos.y / fitH,
                                                rotationDeg = rotationDeg,
                                                isMirrored = isMirrored
                                            )
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onRemoteLongPress(nx, ny)
                                        }
                                    }
                                } else {
                                    null
                                }

                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.firstOrNull()
                                            ?: break

                                        latestPos = change.position
                                        val dist = (latestPos - startPos).getDistance()
                                        if (dist > maxMoved) {
                                            maxMoved = dist
                                        }
                                        if (maxMoved > dragThresholdPx) {
                                            longPressJob?.cancel()
                                        }
                                        change.consume()

                                        if (!change.pressed) {
                                            break
                                        }
                                    }
                                } finally {
                                    longPressJob?.cancel()
                                }

                                if (longPressTriggered) {
                                    return@awaitEachGesture
                                }

                                if (!remoteTouchEnabled) {
                                    if (maxMoved <= dragThresholdPx) {
                                        showHud = !showHud
                                    }
                                    return@awaitEachGesture
                                }

                                val elapsedMs = (System.currentTimeMillis() - downTimeMs).coerceIn(80L, 900L)
                                if (maxMoved > dragThresholdPx) {
                                    swipeTrail = startPos to latestPos
                                    val (sx, sy) = MonitorTouchMapper.mapViewToSenderNormalized(
                                        startPos.x / fitW, startPos.y / fitH, rotationDeg, isMirrored
                                    )
                                    val (ex, ey) = MonitorTouchMapper.mapViewToSenderNormalized(
                                        latestPos.x / fitW, latestPos.y / fitH, rotationDeg, isMirrored
                                    )
                                    onRemoteSwipe(sx, sy, ex, ey, elapsedMs)
                                } else {
                                    tapIndicator = startPos to false
                                    val (nx, ny) = MonitorTouchMapper.mapViewToSenderNormalized(
                                        uRaw = startPos.x / fitW,
                                        vRaw = startPos.y / fitH,
                                        rotationDeg = rotationDeg,
                                        isMirrored = isMirrored
                                    )
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onRemoteTap(nx, ny)
                                }
                            }
                        }
                )

                // 3) Minimal hairline rule-of-thirds grid
                if (showGrid) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val lineColor = Color.White.copy(alpha = 0.28f)
                        val stroke = 0.75.dp.toPx()
                        drawLine(lineColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), stroke)
                        drawLine(lineColor, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), stroke)
                        drawLine(lineColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), stroke)
                        drawLine(lineColor, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), stroke)
                    }
                }

                // 4) Pure white monochrome focus reticle (single ring = tap, double ring = long-press AF/AE lock)
                tapIndicator?.let { (pt, isLong) ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = MonoWhite.copy(alpha = 0.9f),
                            radius = (if (isLong) 32.dp else 24.dp).toPx(),
                            center = pt,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        if (isLong) {
                            drawCircle(
                                color = MonoWhite.copy(alpha = 0.55f),
                                radius = 22.dp.toPx(),
                                center = pt,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                        drawCircle(
                            color = MonoWhite.copy(alpha = 0.8f),
                            radius = 2.5.dp.toPx(),
                            center = pt
                        )
                    }
                }

                // Monochrome swipe trail
                swipeTrail?.let { (s, e) ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawLine(
                            color = MonoWhite.copy(alpha = 0.55f),
                            start = s,
                            end = e,
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Crisp white shutter frame flash
                if (shutterFlash) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(2.dp, MonoWhite.copy(alpha = 0.85f))
                    )
                }
            }
        }

        // Minimal Reconnecting pill
        if (connectionState is MonitorConnectionState.Reconnecting) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = RoundedCornerShape(12.dp),
                color = MonoBlack.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MonoBorderStrong)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.6.dp,
                        color = MonoWhite
                    )
                    Text(
                        text = "Đang kết nối lại (#${connectionState.attempt})...",
                        color = MonoTextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Top HUD bar (pure monochrome translucent black + white hairlines)
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Monochrome telemetry pill
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MonoBlack.copy(alpha = 0.68f),
                        border = BorderStroke(1.dp, MonoWhite.copy(alpha = 0.16f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusDot(active = stats.fps > 0, sizeDp = 6)
                            Text(
                                text = "${stats.fps}fps · ${stats.rttMs}ms · ${String.format(Locale.US, "%.1f", stats.bitrateMbps)}Mb · ${stats.transportLabel}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MonoWhite
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoHudCircleButton(
                            icon = Icons.Rounded.VisibilityOff,
                            contentDescription = "Ẩn HUD",
                            onClick = { showHud = false }
                        )
                        MonoHudCircleButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = "Ngắt kết nối",
                            onClick = onDisconnect
                        )
                    }
                }

                // Horizontally scrollable monochrome tool strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoHudChip(
                        icon = Icons.Rounded.TouchApp,
                        label = if (remoteTouchEnabled) "Chạm" else "Khóa",
                        active = remoteTouchEnabled,
                        onClick = { remoteTouchEnabled = !remoteTouchEnabled }
                    )
                    MonoHudChip(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        label = "Back",
                        active = false,
                        onClick = { onRemoteNavAction("BACK") }
                    )
                    MonoHudChip(
                        icon = Icons.Rounded.Home,
                        label = "Home",
                        active = false,
                        onClick = { onRemoteNavAction("HOME") }
                    )
                    MonoHudChip(
                        icon = Icons.Rounded.Rotate90DegreesCw,
                        label = "${rotationDeg}°",
                        active = rotationDeg != 0,
                        onClick = { rotationDeg = (rotationDeg + 90) % 360 }
                    )
                    MonoHudChip(
                        icon = Icons.Rounded.Flip,
                        label = "Lật",
                        active = isMirrored,
                        onClick = { isMirrored = !isMirrored }
                    )
                    MonoHudChip(
                        icon = Icons.Rounded.GridOn,
                        label = "Lưới",
                        active = showGrid,
                        onClick = { showGrid = !showGrid }
                    )

                    Box {
                        MonoHudChip(
                            icon = Icons.Rounded.HighQuality,
                            label = (connectedInfo?.preset ?: selectedPreset).badge,
                            active = false,
                            onClick = { showQualityMenu = true }
                        )
                        DropdownMenu(
                            expanded = showQualityMenu,
                            onDismissRequest = { showQualityMenu = false },
                            containerColor = MonoSurface
                        ) {
                            VideoQualityPreset.entries.forEach { preset ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = preset.badge,
                                            fontFamily = FontFamily.Monospace,
                                            color = MonoWhite
                                        )
                                    },
                                    onClick = {
                                        showQualityMenu = false
                                        onChangePreset(preset)
                                    }
                                )
                            }
                        }
                    }

                    MonoHudChip(
                        icon = if (bluetoothShutterState.connectedDeviceAddress != null) {
                            Icons.Rounded.BluetoothConnected
                        } else {
                            Icons.Rounded.Bluetooth
                        },
                        label = "BT",
                        active = bluetoothShutterState.connectedDeviceAddress != null,
                        onClick = { showBluetoothDialog = true }
                    )

                    MonoHudChip(
                        icon = Icons.Rounded.Refresh,
                        label = "Sync",
                        active = false,
                        onClick = onRequestFreshKeyframe
                    )
                }
            }
        }

        // Minimal Show-HUD trigger when HUD is hidden
        AnimatedVisibility(
            visible = !showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            MonoHudCircleButton(
                icon = Icons.Rounded.Visibility,
                contentDescription = "Hiện HUD",
                onClick = { showHud = true }
            )
        }

        // Minimalist feedback pill
        AnimatedVisibility(
            visible = !latestFeedbackBanner.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 104.dp, start = 20.dp, end = 20.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MonoBlack.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, MonoWhite.copy(alpha = 0.22f))
            ) {
                Text(
                    text = latestFeedbackBanner.orEmpty(),
                    fontSize = 11.sp,
                    color = MonoWhite,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        // Minimalist Monochrome Camera Shutter Button
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 22.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MonoBlack.copy(alpha = 0.45f))
                    .border(2.5.dp, MonoWhite, CircleShape)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        shutterFlash = true
                        onTriggerShutter()
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (shutterFlash) 48.dp else 56.dp)
                        .clip(CircleShape)
                        .background(if (shutterFlash) MonoWhite.copy(alpha = 0.4f) else MonoWhite)
                )
            }
        }
    }

    if (showBluetoothDialog) {
        BluetoothShutterDialog(
            state = bluetoothShutterState,
            preferBluetooth = preferBluetoothShutter,
            onDismiss = { showBluetoothDialog = false },
            onStartHid = onEnableBluetoothHid,
            onStopHid = onStopBluetoothHid,
            onConnectPeer = onConnectBluetoothHidHost,
            onTogglePrefer = onSetPreferBluetoothShutter
        )
    }
}

@Composable
private fun DirectLowLatencySurfaceView(
    modifier: Modifier,
    onSurfaceReady: (Surface?) -> Unit
) {
    DisposableEffect(Unit) {
        onDispose { onSurfaceReady(null) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceReady(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        onSurfaceReady(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceReady(null)
                    }
                })
            }
        }
    )
}

@Composable
private fun TransformedTextureView(
    modifier: Modifier,
    rotationDeg: Int,
    isMirrored: Boolean,
    onSurfaceReady: (Surface?) -> Unit
) {
    val activeSurfaceRef = remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            onSurfaceReady(null)
            activeSurfaceRef.value?.release()
            activeSurfaceRef.value = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        applyTextureTransform(this@apply, width, height, rotationDeg, isMirrored)
                        val surface = Surface(surfaceTexture)
                        activeSurfaceRef.value?.release()
                        activeSurfaceRef.value = surface
                        onSurfaceReady(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        applyTextureTransform(this@apply, width, height, rotationDeg, isMirrored)
                    }

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        onSurfaceReady(null)
                        activeSurfaceRef.value?.release()
                        activeSurfaceRef.value = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                }
            }
        },
        update = { tv ->
            if (tv.width > 0 && tv.height > 0) {
                applyTextureTransform(tv, tv.width, tv.height, rotationDeg, isMirrored)
            }
        }
    )
}

private fun applyTextureTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    rotationDeg: Int,
    isMirrored: Boolean
) {
    val cx = viewWidth / 2f
    val cy = viewHeight / 2f
    val matrix = Matrix()
    val normRot = ((rotationDeg % 360) + 360) % 360
    if (normRot != 0) {
        matrix.postRotate(normRot.toFloat(), cx, cy)
        if (normRot == 90 || normRot == 270) {
            matrix.postScale(
                viewWidth.toFloat() / viewHeight.toFloat(),
                viewHeight.toFloat() / viewWidth.toFloat(),
                cx,
                cy
            )
        }
    }
    if (isMirrored) {
        matrix.postScale(-1f, 1f, cx, cy)
    }
    textureView.setTransform(matrix)
}

@Composable
private fun MonoHudCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MonoBlack.copy(alpha = 0.68f),
        border = BorderStroke(1.dp, MonoWhite.copy(alpha = 0.2f)),
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MonoWhite,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun MonoHudChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (active) MonoWhite else MonoBlack.copy(alpha = 0.68f),
        border = BorderStroke(
            1.dp,
            if (active) MonoWhite else MonoWhite.copy(alpha = 0.18f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) MonoInk else MonoWhite,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MonoInk else MonoWhite
            )
        }
    }
}

@Composable
private fun BluetoothShutterDialog(
    state: BluetoothShutterState,
    preferBluetooth: Boolean,
    onDismiss: () -> Unit,
    onStartHid: () -> Unit,
    onStopHid: () -> Unit,
    onConnectPeer: (String) -> Unit,
    onTogglePrefer: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MonoSurface,
        title = {
            Text(
                text = "Bluetooth HID Shutter",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MonoWhite
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Giả lập nút chụp Volume-Up chuẩn Bluetooth cho Pixel Camera gốc mà không cần bật Trợ năng.",
                    fontSize = 12.sp,
                    color = MonoTextSecondary
                )
                Text(
                    text = state.statusText,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MonoTextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Ưu tiên Bluetooth HID",
                        fontSize = 13.sp,
                        color = MonoTextPrimary
                    )
                    Switch(
                        checked = preferBluetooth,
                        onCheckedChange = onTogglePrefer,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MonoInk,
                            checkedTrackColor = MonoWhite,
                            uncheckedThumbColor = MonoTextSecondary,
                            uncheckedTrackColor = MonoSurfaceRaised,
                            uncheckedBorderColor = MonoBorderStrong
                        )
                    )
                }

                if (!state.isRegistered) {
                    Button(
                        onClick = onStartHid,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MonoWhite,
                            contentColor = MonoInk
                        )
                    ) {
                        Text("Kích hoạt Bluetooth HID", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    if (state.bondedDevices.isEmpty()) {
                        Text(
                            text = "Hãy ghép đôi 2 máy Pixel trong Cài đặt Bluetooth trước.",
                            fontSize = 12.sp,
                            color = MonoTextSecondary
                        )
                    } else {
                        Text(
                            text = "THIẾT BỊ ĐÃ GHÉP ĐÔI",
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MonoTextMuted
                        )
                        state.bondedDevices.take(5).forEach { dev ->
                            val isConnected = dev.address == state.connectedDeviceAddress
                            MonoHairlineButton(
                                label = if (isConnected) "● ${dev.name}" else dev.name,
                                onClick = { onConnectPeer(dev.address) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    TextButton(onClick = onStopHid) {
                        Text("Tắt Bluetooth HID", color = MonoTextSecondary, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng", color = MonoWhite)
            }
        }
    )
}
