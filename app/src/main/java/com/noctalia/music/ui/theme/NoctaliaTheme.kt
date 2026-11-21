package com.noctalia.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.noctalia.music.ui.components.AlbumColorCache
import com.noctalia.music.ui.components.ExtractedAlbumArt

data class BrookPalette(
    val bgDeep: Color,
    val bgMid: Color,
    val glass: Color,
    val glassStrong: Color,
    val cyan: Color,
    val cyanGlow: Color,
    val onCyan: Color,
    val magenta: Color,
    val onMagenta: Color,
    val pink: Color,
    val blue: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textDim: Color,
    val borderCyan: Color,
    val borderCyanStrong: Color,
)

val DefaultNoctaliaPalette = BrookPalette(
    bgDeep = Color(0xFF070B10),
    bgMid = Color(0xFF0B131C),
    glass = Color(0xC8101A22),
    glassStrong = Color(0xEE0C151D),
    cyan = Color(0xFF00D7E4),
    cyanGlow = Color(0xFF00F0FF),
    onCyan = Color(0xFF041318),
    magenta = Color(0xFFFF61FE),
    onMagenta = Color(0xFF17051A),
    pink = Color(0xFFBE2C9C),
    blue = Color(0xFF3584E4),
    textPrimary = Color(0xFFF0F9FF),
    textMuted = Color(0xB3F0F9FF),
    textDim = Color(0x88F0F9FF),
    borderCyan = Color(0x5500F0FF),
    borderCyanStrong = Color(0xB800F0FF),
)

/**
 * Tokens de color observables de Brook.
 * Cuando «Tema dinámico en toda la app» está activo, todos estos colores se animan
 * suavemente al cambiar de canción manteniendo contraste WCAG legible en todos los botones.
 */
object NoctaliaColors {
    var BgDeep by mutableStateOf(DefaultNoctaliaPalette.bgDeep)
        private set
    var BgMid by mutableStateOf(DefaultNoctaliaPalette.bgMid)
        private set
    var Glass by mutableStateOf(DefaultNoctaliaPalette.glass)
        private set
    var GlassStrong by mutableStateOf(DefaultNoctaliaPalette.glassStrong)
        private set
    var Cyan by mutableStateOf(DefaultNoctaliaPalette.cyan)
        private set
    var CyanGlow by mutableStateOf(DefaultNoctaliaPalette.cyanGlow)
        private set
    var OnCyan by mutableStateOf(DefaultNoctaliaPalette.onCyan)
        private set
    var Magenta by mutableStateOf(DefaultNoctaliaPalette.magenta)
        private set
    var OnMagenta by mutableStateOf(DefaultNoctaliaPalette.onMagenta)
        private set
    var Pink by mutableStateOf(DefaultNoctaliaPalette.pink)
        private set
    var Blue by mutableStateOf(DefaultNoctaliaPalette.blue)
        private set
    var TextPrimary by mutableStateOf(DefaultNoctaliaPalette.textPrimary)
        private set
    var TextMuted by mutableStateOf(DefaultNoctaliaPalette.textMuted)
        private set
    var TextDim by mutableStateOf(DefaultNoctaliaPalette.textDim)
        private set
    var BorderCyan by mutableStateOf(DefaultNoctaliaPalette.borderCyan)
        private set
    var BorderCyanStrong by mutableStateOf(DefaultNoctaliaPalette.borderCyanStrong)
        private set
    val Danger = Color(0xFFFB7185)

    internal fun apply(p: BrookPalette) {
        BgDeep = p.bgDeep
        BgMid = p.bgMid
        Glass = p.glass
        GlassStrong = p.glassStrong
        Cyan = p.cyan
        CyanGlow = p.cyanGlow
        OnCyan = p.onCyan
        Magenta = p.magenta
        OnMagenta = p.onMagenta
        Pink = p.pink
        Blue = p.blue
        TextPrimary = p.textPrimary
        TextMuted = p.textMuted
        TextDim = p.textDim
        BorderCyan = p.borderCyan
        BorderCyanStrong = p.borderCyanStrong
    }
}

private fun tuneHsv(c: Color, minSat: Float, maxSat: Float, minVal: Float, maxVal: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = hsv[1].coerceIn(minSat, maxSat)
    hsv[2] = hsv[2].coerceIn(minVal, maxVal)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun deriveDynamicPalette(art: ExtractedAlbumArt): BrookPalette {
    val cols = art.colors
    if (cols.isEmpty()) return DefaultNoctaliaPalette

    val primary = tuneHsv(cols[0], minSat = 0.56f, maxSat = 0.94f, minVal = 0.78f, maxVal = 0.96f)
    val glow = tuneHsv(cols[0], minSat = 0.44f, maxSat = 0.86f, minVal = 0.90f, maxVal = 1.0f)
    val secondary = tuneHsv(cols.getOrElse(1) { cols[0] }, minSat = 0.52f, maxSat = 0.92f, minVal = 0.74f, maxVal = 0.96f)
    val haloPink = tuneHsv(cols.getOrElse(2) { secondary }, minSat = 0.48f, maxSat = 0.88f, minVal = 0.64f, maxVal = 0.90f)
    val haloBlue = tuneHsv(cols.getOrElse(3) { primary }, minSat = 0.48f, maxSat = 0.88f, minVal = 0.64f, maxVal = 0.90f)

    // Contraste WCAG automático para texto/iconos encima de botones principales y secundarios
    val relLuminance = 0.2126f * primary.red + 0.7152f * primary.green + 0.0722f * primary.blue
    val onPrimary = if (relLuminance > 0.40f) Color(0xFF060D12) else Color(0xFFFFFFFF)
    val secLuminance = 0.2126f * secondary.red + 0.7152f * secondary.green + 0.0722f * secondary.blue
    val onSecondary = if (secLuminance > 0.40f) Color(0xFF0E0512) else Color(0xFFFFFFFF)

    val bgDeep = Color(
        red = (0.025f + primary.red * 0.085f).coerceIn(0.02f, 0.12f),
        green = (0.025f + primary.green * 0.080f).coerceIn(0.02f, 0.12f),
        blue = (0.032f + primary.blue * 0.090f).coerceIn(0.03f, 0.14f),
        alpha = 1f,
    )
    val bgMid = Color(
        red = (0.040f + primary.red * 0.125f).coerceIn(0.03f, 0.16f),
        green = (0.042f + primary.green * 0.115f).coerceIn(0.03f, 0.16f),
        blue = (0.052f + primary.blue * 0.130f).coerceIn(0.04f, 0.18f),
        alpha = 1f,
    )
    val glass = Color(
        red = (0.055f + primary.red * 0.12f).coerceIn(0.05f, 0.19f),
        green = (0.060f + primary.green * 0.11f).coerceIn(0.05f, 0.19f),
        blue = (0.075f + primary.blue * 0.12f).coerceIn(0.06f, 0.21f),
        alpha = 0.79f,
    )
    val glassStrong = Color(
        red = (0.042f + primary.red * 0.095f).coerceIn(0.04f, 0.15f),
        green = (0.048f + primary.green * 0.090f).coerceIn(0.04f, 0.15f),
        blue = (0.062f + primary.blue * 0.100f).coerceIn(0.05f, 0.17f),
        alpha = 0.93f,
    )

    return BrookPalette(
        bgDeep = bgDeep,
        bgMid = bgMid,
        glass = glass,
        glassStrong = glassStrong,
        cyan = primary,
        cyanGlow = glow,
        onCyan = onPrimary,
        magenta = secondary,
        onMagenta = onSecondary,
        pink = haloPink,
        blue = haloBlue,
        textPrimary = Color(0xFFF4F9FF),
        textMuted = Color(0xB8F4F9FF),
        textDim = Color(0x8AF4F9FF),
        borderCyan = glow.copy(alpha = 0.36f),
        borderCyanStrong = glow.copy(alpha = 0.72f),
    )
}

/**
 * Sincroniza de forma animada el tema global de toda la app con la portada de la canción actual.
 */
@Composable
fun SyncDynamicBrookTheme(
    coverUrl: String?,
    enabled: Boolean,
) {
    val context = LocalContext.current
    var target by remember { mutableStateOf(DefaultNoctaliaPalette) }

    LaunchedEffect(coverUrl, enabled) {
        if (!enabled || coverUrl.isNullOrBlank()) {
            target = DefaultNoctaliaPalette
        } else {
            val extracted = AlbumColorCache.extract(context, coverUrl)
            target = if (extracted === AlbumColorCache.noctaliaFallback) {
                DefaultNoctaliaPalette
            } else {
                deriveDynamicPalette(extracted)
            }
        }
    }

    val spec = tween<Color>(durationMillis = 650, easing = FastOutSlowInEasing)
    val animBgDeep by animateColorAsState(target.bgDeep, spec, label = "t_bgDeep")
    val animBgMid by animateColorAsState(target.bgMid, spec, label = "t_bgMid")
    val animGlass by animateColorAsState(target.glass, spec, label = "t_glass")
    val animGlassStrong by animateColorAsState(target.glassStrong, spec, label = "t_glassStrong")
    val animCyan by animateColorAsState(target.cyan, spec, label = "t_cyan")
    val animCyanGlow by animateColorAsState(target.cyanGlow, spec, label = "t_cyanGlow")
    val animOnCyan by animateColorAsState(target.onCyan, spec, label = "t_onCyan")
    val animMagenta by animateColorAsState(target.magenta, spec, label = "t_magenta")
    val animOnMagenta by animateColorAsState(target.onMagenta, spec, label = "t_onMagenta")
    val animPink by animateColorAsState(target.pink, spec, label = "t_pink")
    val animBlue by animateColorAsState(target.blue, spec, label = "t_blue")
    val animBorder by animateColorAsState(target.borderCyan, spec, label = "t_border")
    val animBorderStrong by animateColorAsState(target.borderCyanStrong, spec, label = "t_borderStrong")

    SideEffect {
        NoctaliaColors.apply(
            BrookPalette(
                bgDeep = animBgDeep,
                bgMid = animBgMid,
                glass = animGlass,
                glassStrong = animGlassStrong,
                cyan = animCyan,
                cyanGlow = animCyanGlow,
                onCyan = animOnCyan,
                magenta = animMagenta,
                onMagenta = animOnMagenta,
                pink = animPink,
                blue = animBlue,
                textPrimary = target.textPrimary,
                textMuted = target.textMuted,
                textDim = target.textDim,
                borderCyan = animBorder,
                borderCyanStrong = animBorderStrong,
            )
        )
    }
}

@Composable
fun NoctaliaTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = NoctaliaColors.Cyan,
        onPrimary = NoctaliaColors.OnCyan,
        secondary = NoctaliaColors.Magenta,
        onSecondary = NoctaliaColors.OnMagenta,
        tertiary = NoctaliaColors.Blue,
        background = NoctaliaColors.BgDeep,
        onBackground = NoctaliaColors.TextPrimary,
        surface = NoctaliaColors.Glass,
        onSurface = NoctaliaColors.TextPrimary,
        surfaceVariant = NoctaliaColors.BgMid,
        onSurfaceVariant = NoctaliaColors.TextMuted,
        outline = NoctaliaColors.BorderCyan,
    )
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
