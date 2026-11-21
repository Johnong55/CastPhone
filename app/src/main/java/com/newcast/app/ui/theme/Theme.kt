package com.newcast.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Strict monochrome OLED palette (Black / White / Neutral Gray)
val MonoBlack = Color(0xFF000000)
val MonoSurface = Color(0xFF0C0C0C)
val MonoSurfaceRaised = Color(0xFF161616)
val MonoBorder = Color(0xFF242424)
val MonoBorderStrong = Color(0xFF424242)

val MonoWhite = Color(0xFFFFFFFF)
val MonoInk = Color(0xFF000000)
val MonoTextPrimary = Color(0xFFF4F4F4)
val MonoTextSecondary = Color(0xFF8C8C8C)
val MonoTextMuted = Color(0xFF595959)

private val MonochromeColorScheme = darkColorScheme(
    primary = MonoWhite,
    onPrimary = MonoInk,
    primaryContainer = MonoSurfaceRaised,
    onPrimaryContainer = MonoWhite,
    secondary = MonoTextPrimary,
    onSecondary = MonoInk,
    secondaryContainer = MonoSurfaceRaised,
    onSecondaryContainer = MonoTextPrimary,
    error = MonoWhite,
    onError = MonoInk,
    errorContainer = MonoSurfaceRaised,
    onErrorContainer = MonoTextPrimary,
    background = MonoBlack,
    onBackground = MonoTextPrimary,
    surface = MonoSurface,
    onSurface = MonoTextPrimary,
    surfaceVariant = MonoSurfaceRaised,
    onSurfaceVariant = MonoTextSecondary,
    outline = MonoBorder,
    outlineVariant = MonoBorder
)

@Composable
fun NewCastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MonochromeColorScheme,
        content = content
    )
}
