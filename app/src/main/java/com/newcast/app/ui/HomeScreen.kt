package com.newcast.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.newcast.app.model.NetworkLinkType
import com.newcast.app.model.TransportMode
import com.newcast.app.model.VideoQualityPreset
import com.newcast.app.network.NetworkHelper
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

@Composable
fun HomeScreen(
    network: LocalNetworkSnapshot,
    isSenderCasting: Boolean,
    selectedPreset: VideoQualityPreset,
    selectedTransport: TransportMode,
    accessibilityEnabled: Boolean,
    onSelectSender: () -> Unit,
    onSelectMonitor: () -> Unit,
    onSelectPreset: (VideoQualityPreset) -> Unit,
    onSelectTransport: (TransportMode) -> Unit,
    onRefreshNetwork: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val deviceName = NetworkHelper.getDeviceDisplayName()
    val isOnline = network.linkType != NetworkLinkType.OFFLINE && network.primaryIp != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Minimalist Wordmark Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "NEWCAST",
                    fontSize = 20.sp,
                    letterSpacing = 3.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MonoWhite
                )
                Text(
                    text = deviceName,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MonoTextSecondary
                )
            }
            IconButton(
                onClick = onRefreshNetwork,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Làm mới",
                    tint = MonoTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Minimal Network Strip
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusDot(active = isOnline)
                    Text(
                        text = network.linkType.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isOnline) MonoTextPrimary else MonoTextSecondary
                    )
                }

                if (isOnline) {
                    val band = network.wifiFrequencyMhz?.let { freq ->
                        when {
                            freq >= 5900 -> "6GHz"
                            freq >= 4900 -> "5GHz"
                            else -> "2.4GHz"
                        }
                    }
                    Text(
                        text = listOfNotNull(network.primaryIp, band).joinToString(" · "),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MonoTextSecondary
                    )
                }
            }

            if (network.wifiFrequencyMhz != null && !network.is5GhzOrHigher) {
                Text(
                    text = "Đang ở 2.4GHz — nên bật Hotspot 5GHz để mượt 60fps.",
                    fontSize = 11.sp,
                    color = MonoTextMuted
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoHairlineButton(
                    label = "Hotspot",
                    onClick = onOpenHotspotSettings,
                    modifier = Modifier.weight(1f)
                )
                MonoHairlineButton(
                    label = "Wi-Fi",
                    onClick = onOpenWifiSettings,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = MonoBorder)

        // Primary Role Selection (2 crisp monochrome tiles)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "CHẾ ĐỘ",
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
                color = MonoTextMuted
            )

            MonoRoleTile(
                index = "01",
                title = if (isSenderCasting) "Máy Quay · Đang phát" else "Máy Quay",
                subtitle = if (isSenderCasting) {
                    "Đang phát nền — chạm để mở lại bảng điều khiển"
                } else {
                    "Phát toàn màn hình cho Pixel Camera gốc"
                },
                onClick = onSelectSender
            )

            MonoRoleTile(
                index = "02",
                title = "Màn Hình Phụ",
                subtitle = "Xem 60fps, chạm lấy nét & bấm chụp từ xa",
                onClick = onSelectMonitor
            )
        }

        HorizontalDivider(thickness = 1.dp, color = MonoBorder)

        // Stream Configuration (segmented minimalist controls)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "CHẤT LƯỢNG & KẾT NỐI",
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
                color = MonoTextMuted
            )

            // 3-way Segmented Quality Selector
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

            Text(
                text = selectedPreset.description,
                fontSize = 12.sp,
                color = MonoTextSecondary
            )

            // 2-way Segmented Transport Selector
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MonoSurface,
                border = BorderStroke(1.dp, MonoBorder)
            ) {
                Row(modifier = Modifier.padding(3.dp)) {
                    TransportMode.entries.forEach { mode ->
                        val selected = mode == selectedTransport
                        val shortLabel = if (mode == TransportMode.UDP_LOW_LATENCY) {
                            "UDP · Mượt nhất"
                        } else {
                            "TCP · Ổn định"
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) MonoWhite else Color.Transparent)
                                .clickable { onSelectTransport(mode) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = shortLabel,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MonoInk else MonoTextSecondary
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = MonoBorder)

        // Minimal Remote Shutter / Accessibility Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusDot(active = accessibilityEnabled)
                    Text(
                        text = "Điều khiển chạm & chụp từ xa",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MonoTextPrimary
                    )
                }
                Text(
                    text = if (accessibilityEnabled) {
                        "Trợ năng đã bật trên máy này"
                    } else {
                        "Cần bật trên Máy Quay để chạm & chụp qua Wi-Fi"
                    },
                    fontSize = 11.sp,
                    color = MonoTextSecondary
                )
            }
            if (!accessibilityEnabled) {
                Spacer(Modifier.width(12.dp))
                MonoHairlineButton(
                    label = "Bật",
                    onClick = onOpenAccessibilitySettings
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun StatusDot(
    active: Boolean,
    sizeDp: Int = 7
) {
    if (active) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(MonoWhite)
        )
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .border(1.2.dp, MonoTextMuted, CircleShape)
        )
    }
}

@Composable
internal fun MonoHairlineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MonoSurface,
        border = BorderStroke(1.dp, MonoBorder)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MonoTextPrimary
            )
        }
    }
}

@Composable
private fun MonoRoleTile(
    index: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MonoSurface,
        border = BorderStroke(1.dp, MonoBorderStrong)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = index,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MonoTextMuted
            )
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MonoWhite
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MonoTextSecondary
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MonoTextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
