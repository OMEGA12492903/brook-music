package com.noctalia.music.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.noctalia.music.data.SecurityValidators
import com.noctalia.music.ui.theme.NoctaliaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class PlayerBgStyle(val id: String, val label: String) {
    LIQUID("liquid", "Líquido"),
    VORTEX("vortex", "Difuminado"),
    AURORA("aurora", "Aurora"),
    PARTICLES("particles", "Partículas");

    companion object {
        fun fromId(id: String?): PlayerBgStyle =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: LIQUID
    }
}

data class ExtractedAlbumArt(
    val colors: List<Color>,
    val baseColor: Color,
    val softBitmap: ImageBitmap?,
)

private data class BgParticle(
    val startX: Float,
    val startY: Float,
    val radius: Float,
    val speedY: Float,
    val driftX: Float,
    val phase: Float,
    val colorIdx: Int,
)

private object AlbumColorCache {
    private val cache = ConcurrentHashMap<String, ExtractedAlbumArt>()

    val noctaliaFallback = ExtractedAlbumArt(
        colors = listOf(
            Color(0xFF00E5FF),
            Color(0xFFFF5EF7),
            Color(0xFF2979FF),
            Color(0xFF00B8D4),
            Color(0xFF7C4DFF),
        ),
        baseColor = Color(0xFF050B11),
        softBitmap = null,
    )

    suspend fun extract(context: Context, rawUrl: String): ExtractedAlbumArt {
        val url = rawUrl.trim()
        if (url.isEmpty() || !SecurityValidators.isSafeThumbUrl(url)) {
            return noctaliaFallback
        }
        cache[url]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val req = ImageRequest.Builder(context)
                    .data(url)
                    .size(64, 64)
                    .allowHardware(false)
                    .build()
                val res = context.imageLoader.execute(req)
                val drawable = (res as? SuccessResult)?.drawable
                val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return@withContext noctaliaFallback
                val sampled = sampleBitmap(bmp)
                if (cache.size > 32) cache.clear()
                cache[url] = sampled
                sampled
            } catch (_: Throwable) {
                noctaliaFallback
            }
        }
    }

    private fun sampleBitmap(src: Bitmap): ExtractedAlbumArt {
        val scaled = Bitmap.createScaledBitmap(src, 40, 40, true)
        // Mini-bitmap 16x16 reescalado con FilterQuality.High produce un desenfoque fluido GPU en API 26-35
        val softRaw = Bitmap.createScaledBitmap(src, 16, 16, true)
        val softImg = try {
            softRaw.asImageBitmap()
        } catch (_: Throwable) {
            null
        }

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        // Cuantización rápida por 12 sectores de matiz (HSV) para obtener 5 colores ricos y distintos
        val bucketWeight = FloatArray(12)
        val bucketR = FloatArray(12)
        val bucketG = FloatArray(12)
        val bucketB = FloatArray(12)
        val hsv = FloatArray(3)

        var avgR = 0f
        var avgG = 0f
        var avgB = 0f
        var avgCount = 0f

        for (px in pixels) {
            val r = android.graphics.Color.red(px)
            val g = android.graphics.Color.green(px)
            val b = android.graphics.Color.blue(px)
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val hue = hsv[0]
            val sat = hsv[1]
            val v = hsv[2]

            avgR += r; avgG += g; avgB += b; avgCount += 1f

            // Priorizar colores vivos y evitar grises apagados o negro/blanco puros
            if (v < 0.14f || (sat < 0.14f && v > 0.88f)) continue
            val weight = 0.25f + sat * 1.85f + (1f - kotlin.math.abs(v - 0.62f)) * 0.7f
            val idx = ((hue / 30f).toInt()).coerceIn(0, 11)
            bucketWeight[idx] += weight
            bucketR[idx] += r * weight
            bucketG[idx] += g * weight
            bucketB[idx] += b * weight
        }

        val ranked = (0 until 12)
            .filter { bucketWeight[it] > 0.5f }
            .sortedByDescending { bucketWeight[it] }

        val picked = mutableListOf<Color>()
        val usedBuckets = mutableListOf<Int>()

        for (idx in ranked) {
            if (picked.size >= 5) break
            // Evitar elegir dos tonos casi pegados si todavía hay otros distintos
            val tooClose = usedBuckets.any { u ->
                val d = kotlin.math.abs(u - idx)
                kotlin.math.min(d, 12 - d) <= 1
            }
            if (tooClose && picked.size < 3 && ranked.size >= 4) continue
            val wSum = bucketWeight[idx]
            val c = boostVibrancy(
                (bucketR[idx] / wSum).roundToInt(),
                (bucketG[idx] / wSum).roundToInt(),
                (bucketB[idx] / wSum).roundToInt(),
            )
            picked.add(c)
            usedBuckets.add(idx)
        }

        // Completar hasta 5 colores si la portada es monocromática
        if (picked.isEmpty()) {
            val r = (avgR / avgCount.coerceAtLeast(1f)).roundToInt()
            val g = (avgG / avgCount.coerceAtLeast(1f)).roundToInt()
            val b = (avgB / avgCount.coerceAtLeast(1f)).roundToInt()
            picked.add(boostVibrancy(r, g, b))
        }
        val seedCount = picked.size
        while (picked.size < 5) {
            val srcCol = picked[picked.size % seedCount]
            picked.add(shiftHue(srcCol, 28f * (picked.size - seedCount + 1)))
        }

        // Color base profundo teñido ligeramente con el color dominante de la portada
        val dom = picked.first()
        val base = Color(
            red = (dom.red * 0.10f).coerceIn(0.02f, 0.14f),
            green = (dom.green * 0.10f).coerceIn(0.02f, 0.14f),
            blue = (dom.blue * 0.12f).coerceIn(0.03f, 0.16f),
            alpha = 1f,
        )

        if (scaled != src) scaled.recycle()
        return ExtractedAlbumArt(colors = picked, baseColor = base, softBitmap = softImg)
    }

    private fun boostVibrancy(r: Int, g: Int, b: Int): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), hsv)
        hsv[1] = (hsv[1] * 1.22f).coerceIn(0.38f, 0.96f)
        hsv[2] = hsv[2].coerceIn(0.48f, 0.95f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    private fun shiftHue(c: Color, deltaDeg: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        hsv[0] = (hsv[0] + deltaDeg + 360f) % 360f
        hsv[1] = (hsv[1] * 0.92f).coerceIn(0.35f, 0.92f)
        hsv[2] = (hsv[2] * 0.88f).coerceIn(0.42f, 0.90f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}

/**
 * Fondo animado fluido a 60/120fps para el reproductor de Brook.
 * Mantiene la portada fija y nítida encima mientras el fondo se mueve suavemente
 * usando los colores reales de la carátula (o la paleta Noctalia).
 */
@Composable
fun BrookAnimatedPlayerBackground(
    coverUrl: String,
    isPlaying: Boolean,
    style: PlayerBgStyle,
    useCoverColors: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    var artData by remember { mutableStateOf(AlbumColorCache.noctaliaFallback) }

    LaunchedEffect(coverUrl) {
        artData = AlbumColorCache.extract(context, coverUrl)
    }

    val activePalette = if (useCoverColors) artData.colors else AlbumColorCache.noctaliaFallback.colors
    val targetBase = if (useCoverColors) artData.baseColor else AlbumColorCache.noctaliaFallback.baseColor

    val colorSpec = tween<Color>(durationMillis = 750, easing = FastOutSlowInEasing)
    val c0 by animateColorAsState(activePalette.getOrElse(0) { NoctaliaColors.Cyan }, colorSpec, label = "bg_c0")
    val c1 by animateColorAsState(activePalette.getOrElse(1) { NoctaliaColors.Magenta }, colorSpec, label = "bg_c1")
    val c2 by animateColorAsState(activePalette.getOrElse(2) { NoctaliaColors.Blue }, colorSpec, label = "bg_c2")
    val c3 by animateColorAsState(activePalette.getOrElse(3) { NoctaliaColors.CyanGlow }, colorSpec, label = "bg_c3")
    val c4 by animateColorAsState(activePalette.getOrElse(4) { NoctaliaColors.Pink }, colorSpec, label = "bg_c4")
    val baseColor by animateColorAsState(targetBase, colorSpec, label = "bg_base")

    // Reloj continuo con aceleración/desaceleración suave entre Play (1.0x) y Pausa (0.16x)
    var animTime by remember { mutableFloatStateOf(0f) }
    var playingState by remember { mutableStateOf(isPlaying) }
    playingState = isPlaying

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        var speedFactor = if (playingState) 1f else 0.18f
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    val dt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    val targetSpeed = if (playingState) 1.05f else 0.16f
                    speedFactor += (targetSpeed - speedFactor) * (dt * 3.8f).coerceAtMost(1f)
                    animTime += dt * speedFactor
                }
                lastNanos = frameNanos
            }
        }
    }

    val particles = remember {
        val rng = Random(42)
        List(36) {
            BgParticle(
                startX = rng.nextFloat(),
                startY = rng.nextFloat(),
                radius = 4f + rng.nextFloat() * 9f,
                speedY = 0.032f + rng.nextFloat() * 0.055f,
                driftX = (rng.nextFloat() - 0.5f) * 0.06f,
                phase = rng.nextFloat() * (2f * PI.toFloat()),
                colorIdx = rng.nextInt(5),
            )
        }
    }

    val palList = listOf(c0, c1, c2, c3, c4)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseColor),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val t = animTime

            drawRect(color = baseColor)

            when (style) {
                PlayerBgStyle.LIQUID, PlayerBgStyle.PARTICLES -> {
                    val blobs = arrayOf(
                        Triple(
                            Offset(w * (0.5f + 0.38f * sin(t * 0.55f)), h * (0.23f + 0.19f * cos(t * 0.42f))),
                            w * 0.86f,
                            c0.copy(alpha = 0.52f),
                        ),
                        Triple(
                            Offset(w * (0.5f + 0.42f * cos(t * 0.48f + 1.3f)), h * (0.50f + 0.25f * sin(t * 0.37f + 0.7f))),
                            w * 0.90f,
                            c1.copy(alpha = 0.46f),
                        ),
                        Triple(
                            Offset(w * (0.5f + 0.36f * sin(t * 0.62f + 2.6f)), h * (0.78f + 0.20f * cos(t * 0.51f + 1.9f))),
                            w * 0.84f,
                            c2.copy(alpha = 0.46f),
                        ),
                        Triple(
                            Offset(w * (0.5f + 0.44f * cos(t * 0.35f + 4.1f)), h * (0.35f + 0.28f * sin(t * 0.58f + 3.2f))),
                            w * 0.78f,
                            c3.copy(alpha = 0.40f),
                        ),
                        Triple(
                            Offset(w * (0.5f + 0.30f * sin(t * 0.72f + 5.0f)), h * (0.64f + 0.22f * cos(t * 0.45f + 4.4f))),
                            w * 0.68f,
                            c4.copy(alpha = 0.30f),
                        ),
                    )
                    for ((center, rad, col) in blobs) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(col, col.copy(alpha = col.alpha * 0.48f), Color.Transparent),
                                center = center,
                                radius = rad,
                            ),
                            radius = rad,
                            center = center,
                            blendMode = BlendMode.Screen,
                        )
                    }

                    if (style == PlayerBgStyle.PARTICLES) {
                        for (p in particles) {
                            val py = ((p.startY - t * p.speedY) % 1f + 1f) % 1f
                            val px = ((p.startX + sin(t * 0.9f + p.phase) * 0.045f + t * p.driftX) % 1f + 1f) % 1f
                            val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 2.1f + p.phase))
                            val pos = Offset(px * w, py * h)
                            val col = palList[p.colorIdx % palList.size]
                            val glowRad = p.radius * 3.1f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.58f * twinkle),
                                        col.copy(alpha = 0.42f * twinkle),
                                        Color.Transparent,
                                    ),
                                    center = pos,
                                    radius = glowRad,
                                ),
                                radius = glowRad,
                                center = pos,
                                blendMode = BlendMode.Screen,
                            )
                        }
                    }
                }

                PlayerBgStyle.VORTEX -> {
                    val img = artData.softBitmap
                    if (img != null) {
                        val side1 = (kotlin.math.max(w, h) * (1.28f + 0.08f * sin(t * 0.5f))).roundToInt()
                        val ox1 = (w * 0.5f + sin(t * 0.45f) * w * 0.12f).roundToInt()
                        val oy1 = (h * 0.44f + cos(t * 0.38f) * h * 0.08f).roundToInt()
                        withTransform({
                            rotate(degrees = (t * 11f) % 360f, pivot = Offset(ox1.toFloat(), oy1.toFloat()))
                        }) {
                            drawImage(
                                image = img,
                                dstOffset = IntOffset(ox1 - side1 / 2, oy1 - side1 / 2),
                                dstSize = IntSize(side1, side1),
                                alpha = 0.72f,
                                filterQuality = FilterQuality.High,
                            )
                        }

                        val side2 = (kotlin.math.max(w, h) * (1.16f + 0.09f * cos(t * 0.6f))).roundToInt()
                        val ox2 = (w * 0.5f + cos(t * 0.52f) * w * 0.14f).roundToInt()
                        val oy2 = (h * 0.60f + sin(t * 0.41f) * h * 0.08f).roundToInt()
                        withTransform({
                            rotate(degrees = (-t * 14f) % 360f, pivot = Offset(ox2.toFloat(), oy2.toFloat()))
                        }) {
                            drawImage(
                                image = img,
                                dstOffset = IntOffset(ox2 - side2 / 2, oy2 - side2 / 2),
                                dstSize = IntSize(side2, side2),
                                alpha = 0.42f,
                                filterQuality = FilterQuality.High,
                                blendMode = BlendMode.Screen,
                            )
                        }
                    }

                    // Baño suave de color para enriquecer o aplicar paleta Noctalia
                    val washA = if (useCoverColors) 0.24f else 0.55f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(c0.copy(alpha = washA), Color.Transparent),
                            center = Offset(w * (0.3f + 0.25f * sin(t * 0.5f)), h * 0.28f),
                            radius = w * 0.9f,
                        ),
                        radius = w * 0.9f,
                        center = Offset(w * (0.3f + 0.25f * sin(t * 0.5f)), h * 0.28f),
                        blendMode = BlendMode.Screen,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(c1.copy(alpha = washA), Color.Transparent),
                            center = Offset(w * (0.7f + 0.25f * cos(t * 0.45f)), h * 0.72f),
                            radius = w * 0.9f,
                        ),
                        radius = w * 0.9f,
                        center = Offset(w * (0.7f + 0.25f * cos(t * 0.45f)), h * 0.72f),
                        blendMode = BlendMode.Screen,
                    )
                }

                PlayerBgStyle.AURORA -> {
                    val steps = 28
                    val stepW = (w + 60f) / steps
                    for (k in 0 until 4) {
                        val baseY = h * (0.17f + k * 0.21f)
                        val amp = h * (0.065f + k * 0.015f)
                        val halfThick = h * (0.11f + k * 0.018f)
                        val path = Path()
                        for (i in 0..steps) {
                            val x = -30f + i * stepW
                            val y = baseY +
                                sin(x * 0.0055f + t * (0.75f + k * 0.14f) + k * 1.4f) * amp +
                                cos(x * 0.0032f - t * 0.48f + k) * (amp * 0.45f)
                            if (i == 0) path.moveTo(x, y - halfThick) else path.lineTo(x, y - halfThick)
                        }
                        for (i in steps downTo 0) {
                            val x = -30f + i * stepW
                            val y = baseY +
                                sin(x * 0.0055f + t * (0.75f + k * 0.14f) + k * 1.4f) * amp +
                                cos(x * 0.0032f - t * 0.48f + k) * (amp * 0.45f)
                            path.lineTo(x, y + halfThick)
                        }
                        path.close()
                        val colA = palList[k % palList.size]
                        val colB = palList[(k + 1) % palList.size]
                        drawPath(
                            path = path,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    colA.copy(alpha = 0.42f),
                                    colB.copy(alpha = 0.28f),
                                    Color.Transparent,
                                ),
                                startY = baseY - halfThick * 1.4f,
                                endY = baseY + halfThick * 1.4f,
                            ),
                            blendMode = BlendMode.Screen,
                        )
                    }
                }
            }

            // Velo sutil de contraste para que textos blancos y botones cian se lean perfectos siempre
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0x6604090E),
                        0.38f to Color(0x2204090E),
                        0.76f to Color(0x8804090E),
                        1.0f to Color(0xBA04090E),
                    )
                )
            )
        }

        content()
    }
}
