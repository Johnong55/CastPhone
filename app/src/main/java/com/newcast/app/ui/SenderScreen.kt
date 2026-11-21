package com.newcast.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newcast.app.model.LocalNetworkSnapshot
import com.newcast.app.model.SenderRuntimeState
import com.newcast.app.model.VideoQualityPreset
import com.newcast.app.ui.theme.MonoBlack
import com.newcast.app.ui.theme.MonoBorder
import com.newcast.app.ui.theme.MonoBorderStrong
import com.newcast.app.ui.theme.MonoInk
import com.newcast.app.ui.theme.MonoSurface
import com.newcast.app.ui.theme.MonoTextMuted
import com.newcast.app.ui.theme.MonoTextPrimary
import com.newcast.app.ui.theme.MonoTextSecondary
import com.newcast.app.ui.theme.MonoWhite
import java.util.Locale

@Composable
fun SenderScreen(
    senderState: SenderRuntimeState,
    network: LocalNetworkSnapshot,
    selectedPreset: VideoQualityPreset,
    accessibilityEnabled: Boolean,
    onBack: () -> Unit,
    onStartCasting: () -> Unit,
    onStopCasting: () -> Unit,
    onLaunchPixelCamera: () -> Unit,
    onSelectPreset: (VideoQualityPreset) -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
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
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                text = "MÁY QUAY",
                fontSize = 14.sp,
                letterSpacing = 2.5.sp,
                fontWeight = FontWeight.Medium,
                color = MonoWhite
            )
        }

        Spacer(Modifier.height(28.dp))

        // Live status + IP block
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusDot(active = senderState.isCasting, sizeDp = 8)
                val shortStatus = when {
                    !senderState.isCasting -> "Sẵn sàng"
                    senderState.connectedMonitorName != null -> "Đang chiếu · ${senderState.connectedMonitorName}"
                    else -> "Đang chờ máy phụ"
                }
                Text(
                    text = shortStatus,
                    fontSize = 13.sp,
                    color = if (senderState.isCasting) MonoWhite else MonoTextSecondary
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MonoSurface,
                border = BorderStroke(1.dp, if (senderState.isCasting) MonoBorderStrong else MonoBorder)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = network.linkType.label.uppercase(Locale.ROOT),
                        fontSize = 10.sp,
                        letterSpacing = 1.8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MonoTextMuted
                    )
                    Text(
                        text = network.primaryIp ?: "Offline",
                        fontSize = 26.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = if (network.primaryIp != null) MonoWhite else MonoTextMuted
                    )
                }
            }

            if (senderState.isCasting) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MonoSurface,
                    border = BorderStroke(1.dp, MonoBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MonoMetric(
                            label = "RES",
                            value = "${senderState.captureWidth}×${senderState.captureHeight}"
                        )
                        MonoMetric(
                            label = "FPS",
                            value = "${senderState.encodedFps}"
                        )
                        MonoMetric(
                            label = "MBPS",
                            value = String.format(Locale.US, "%.1f", senderState.currentBitrateMbps)
                        )
                    }
                }
            }

            senderState.errorMessage?.let { err ->
                Text(
                    text = err,
                    fontSize = 12.sp,
                    color = MonoTextSecondary
                )
            }

            if (!senderState.isCasting) {
                Button(
                    onClick = onStartCasting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MonoWhite,
                        contentColor = MonoInk
                    )
                ) {
                    Text(
                        text = "Bắt đầu phát",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onLaunchPixelCamera,
                        modifier = Modifier
                            .weight(1.4f)
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MonoWhite,
                            contentColor = MonoInk
                        )
                    ) {
                        Text(
                            text = "Pixel Camera",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    MonoHairlineButton(
                        label = "Dừng",
                        onClick = onStopCasting,
                        modifier = Modifier
                            .weight(0.8f)
                            .height(52.dp)
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Compact bottom controls: Quality + Hotspot / Accessibility shortcuts
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MonoSurface,
                border = BorderStroke(1.dp, MonoBorder)
            ) {
                Row(modifier = Modifier.padding(3.dp)) {
                    VideoQualityPreset.entries.forEach { preset ->
                        val selected = preset == selectedPreset
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) MonoWhite else Color.Transparent)
                                .clickable { onSelectPreset(preset) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = preset.badge,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MonoInk else MonoTextSecondary
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoHairlineButton(
                    label = "Hotspot",
                    onClick = onOpenHotspotSettings,
                    modifier = Modifier.weight(1f)
                )
                MonoHairlineButton(
                    label = if (accessibilityEnabled) "● Trợ năng" else "○ Trợ năng",
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MonoMetric(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MonoTextMuted
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MonoTextPrimary
        )
    }
}
