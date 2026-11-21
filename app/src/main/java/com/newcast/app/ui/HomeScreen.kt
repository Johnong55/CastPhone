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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
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
import com.newcast.app.model.NetworkLinkType
import com.newcast.app.model.TransportMode
import com.newcast.app.model.VideoQualityPreset
import com.newcast.app.network.NetworkHelper
import com.newcast.app.ui.theme.MonoBlack
import com.newcast.app.ui.theme.MonoBorder
import com.newcast.app.ui.theme.MonoBorderStrong
import com.newcast.app.ui.theme.MonoInk
import com.newcast.app.ui.theme.MonoSurface
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
    onSelectSender: () -> Unit,
    onSelectMonitor: () -> Unit,
    onSelectPreset: (VideoQualityPreset) -> Unit,
    onSelectTransport: (TransportMode) -> Unit,
    onRefreshNetwork: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    val deviceName = NetworkHelper.getDeviceDisplayName()
    val isOnline = network.linkType != NetworkLinkType.OFFLINE && network.primaryIp != null
    val band = network.wifiFrequencyMhz?.let { freq ->
        when {
            freq >= 5900 -> "6GHz"
            freq >= 4900 -> "5GHz"
            else -> "2.4GHz"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        // Header
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

        Spacer(Modifier.height(22.dp))

        // Compact single-line network status + quick actions
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
                    text = if (isOnline) {
                        listOfNotNull(network.primaryIp, band).joinToString(" · ")
                    } else {
                        "Offline"
                    },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isOnline) MonoTextPrimary else MonoTextSecondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoCompactChip(label = "Hotspot", onClick = onOpenHotspotSettings)
                MonoCompactChip(label = "Wi-Fi", onClick = onOpenWifiSettings)
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(thickness = 1.dp, color = MonoBorder)
        Spacer(Modifier.height(24.dp))

        // Two primary role tiles (no scroll, generous proportions)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MonoRoleTile(
                index = "01",
                title = "Máy Quay",
                meta = if (isSenderCasting) "Đang phát" else "Phát màn hình",
                highlight = isSenderCasting,
                onClick = onSelectSender
            )

            MonoRoleTile(
                index = "02",
                title = "Màn Hình Phụ",
                meta = "Xem & điều khiển",
                highlight = false,
                onClick = onSelectMonitor
            )
        }

        Spacer(Modifier.weight(1f))

        // Clean bottom segmented controls (zero verbose text)
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

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MonoSurface,
                border = BorderStroke(1.dp, MonoBorder)
            ) {
                Row(modifier = Modifier.padding(3.dp)) {
                    TransportMode.entries.forEach { mode ->
                        val selected = mode == selectedTransport
                        val label = if (mode == TransportMode.UDP_LOW_LATENCY) "UDP" else "TCP"
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
                                text = label,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MonoInk else MonoTextSecondary
                            )
                        }
                    }
                }
            }
        }
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
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
private fun MonoCompactChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MonoSurface,
        border = BorderStroke(1.dp, MonoBorder)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MonoTextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun MonoRoleTile(
    index: String,
    title: String,
    meta: String,
    highlight: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MonoSurface,
        border = BorderStroke(1.dp, if (highlight) MonoWhite else MonoBorderStrong)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 22.dp),
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
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MonoWhite
                )
                Text(
                    text = meta,
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
