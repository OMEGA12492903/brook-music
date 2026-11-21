package com.noctalia.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object NoctaliaColors {
    val BgDeep = Color(0xFF070B10)
    val BgMid = Color(0xFF0B131C)
    val Glass = Color(0xC8101A22)
    val GlassStrong = Color(0xEE0C151D)
    val Cyan = Color(0xFF00D7E4)
    val CyanGlow = Color(0xFF00F0FF)
    val OnCyan = Color(0xFF041318)
    val Magenta = Color(0xFFFF61FE)
    val Pink = Color(0xFFBE2C9C)
    val Blue = Color(0xFF3584E4)
    val TextPrimary = Color(0xFFF0F9FF)
    val TextMuted = Color(0xB3F0F9FF)
    val TextDim = Color(0x88F0F9FF)
    val BorderCyan = Color(0x5500F0FF)
    val BorderCyanStrong = Color(0xB800F0FF)
    val Danger = Color(0xFFFB7185)
}

private val NoctaliaDarkScheme = darkColorScheme(
    primary = NoctaliaColors.Cyan,
    onPrimary = NoctaliaColors.OnCyan,
    secondary = NoctaliaColors.Magenta,
    onSecondary = Color(0xFF17051A),
    tertiary = NoctaliaColors.Blue,
    background = NoctaliaColors.BgDeep,
    onBackground = NoctaliaColors.TextPrimary,
    surface = NoctaliaColors.Glass,
    onSurface = NoctaliaColors.TextPrimary,
    surfaceVariant = Color(0xFF14212C),
    onSurfaceVariant = NoctaliaColors.TextMuted,
    outline = NoctaliaColors.BorderCyan,
)

@Composable
fun NoctaliaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NoctaliaDarkScheme,
        content = content,
    )
}
