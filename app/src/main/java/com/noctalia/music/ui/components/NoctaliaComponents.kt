package com.noctalia.music.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.noctalia.music.data.SecurityValidators
import com.noctalia.music.data.Track
import com.noctalia.music.ui.theme.NoctaliaColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun NoctaliaBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(NoctaliaColors.BgDeep, NoctaliaColors.BgMid, NoctaliaColors.BgDeep)
                )
            )
    ) {
        // Halos ambientales cian / magenta / azul idénticos al estilo Noctalia
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NoctaliaColors.Pink.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.16f, size.height * 0.08f),
                    radius = size.width * 0.72f,
                ),
                center = Offset(size.width * 0.16f, size.height * 0.08f),
                radius = size.width * 0.72f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NoctaliaColors.Cyan.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.88f, size.height * 0.16f),
                    radius = size.width * 0.68f,
                ),
                center = Offset(size.width * 0.88f, size.height * 0.16f),
                radius = size.width * 0.68f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NoctaliaColors.Blue.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.92f),
                    radius = size.width * 0.75f,
                ),
                center = Offset(size.width * 0.5f, size.height * 0.92f),
                radius = size.width * 0.75f,
            )
        }
        content()
    }
}

@Composable
fun NoctaliaGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    strong: Boolean = false,
    highlighted: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val bg = when {
        highlighted -> NoctaliaColors.Cyan.copy(alpha = 0.16f)
        strong -> NoctaliaColors.GlassStrong
        else -> NoctaliaColors.Glass
    }
    val borderColor = when {
        highlighted -> NoctaliaColors.CyanGlow.copy(alpha = 0.65f)
        strong -> NoctaliaColors.BorderCyanStrong
        else -> NoctaliaColors.BorderCyan
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(bg)
            .border(BorderStroke(if (strong || highlighted) 1.3.dp else 1.dp, borderColor), RoundedCornerShape(cornerRadius)),
        content = content,
    )
}

@Composable
fun NoctaliaPillChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(99.dp)
    Surface(
        modifier = modifier.clip(shape).clickable(onClick = onClick),
        shape = shape,
        color = if (active) NoctaliaColors.Cyan else NoctaliaColors.Glass,
        border = BorderStroke(
            width = 1.dp,
            color = if (active) NoctaliaColors.CyanGlow else NoctaliaColors.BorderCyan,
        ),
    ) {
        Text(
            text = label,
            color = if (active) NoctaliaColors.OnCyan else NoctaliaColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
fun TrackCoverImage(
    url: String,
    size: Dp,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(NoctaliaColors.Cyan.copy(alpha = 0.30f), Color(0xFF0E1720))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = NoctaliaColors.CyanGlow,
                            modifier = Modifier.size(size * 0.45f),
                        )
                    }
                },
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = NoctaliaColors.CyanGlow,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

@Composable
fun TrackRowItem(
    track: Track,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onAddQueue: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    indexBadge: String? = null,
) {
    NoctaliaGlassCard(
        cornerRadius = 16.dp,
        highlighted = isCurrent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (indexBadge != null) {
                Text(
                    text = indexBadge,
                    color = NoctaliaColors.Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(22.dp),
                )
            }
            TrackCoverImage(url = track.thumb, size = 48.dp, cornerRadius = 11.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = if (isCurrent) NoctaliaColors.CyanGlow else NoctaliaColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val dur = track.durationText.ifBlank {
                    if (track.duration > 0) SecurityValidators.fmtDuration(track.duration) else ""
                }
                val sub = if (dur.isNotEmpty()) "${track.artist} · $dur" else track.artist
                Text(
                    text = sub,
                    color = NoctaliaColors.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onAddQueue != null) {
                IconButton(onClick = onAddQueue, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Añadir a la cola",
                        tint = NoctaliaColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (onToggleFavorite != null) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Favorita",
                        tint = if (isFavorite) NoctaliaColors.CyanGlow else NoctaliaColors.TextDim,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Quitar",
                        tint = NoctaliaColors.Danger,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Barra temporal interactiva con la onda sinusoidal animada característica de Noctalia.
 */
@Composable
fun NoctaliaWaveSeekBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "wave")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val liveFraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val displayFraction = if (dragging) dragFraction else liveFraction

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0L) {
                        val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        onSeek((frac * durationMs).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        if (durationMs > 0L) {
                            onSeek((dragFraction * durationMs).toLong())
                        }
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                )
            }
    ) {
        val midY = size.height / 2f
        val pad = 10f
        val trackW = (size.width - pad * 2).coerceAtLeast(1f)
        val thumbX = pad + trackW * displayFraction

        // Parte restante (línea recta suave)
        if (thumbX < size.width - pad) {
            drawLine(
                color = NoctaliaColors.TextPrimary.copy(alpha = 0.22f),
                start = Offset(thumbX, midY),
                end = Offset(size.width - pad, midY),
                strokeWidth = 6f,
                cap = StrokeCap.Round,
            )
        }

        // Parte reproducida (onda sinusoidal cian)
        val amp = if (isPlaying) 9.5f else 2.2f
        val path = Path()
        var x = pad
        var first = true
        while (x <= thumbX) {
            val y = midY + sin(x * 0.065f - (if (isPlaying) phase else 0f)) * amp
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            x += 4f
        }
        drawPath(
            path = path,
            color = NoctaliaColors.Cyan,
            style = Stroke(width = 7f, cap = StrokeCap.Round),
        )

        // Punto indicador blanco brillante
        drawCircle(
            color = NoctaliaColors.CyanGlow.copy(alpha = 0.35f),
            radius = 16f,
            center = Offset(thumbX, midY),
        )
        drawCircle(
            color = Color.White,
            radius = 10f,
            center = Offset(thumbX, midY),
        )
    }
}

@Composable
fun NoctaliaMiniEqualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "eq")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "eq_phase",
    )

    Canvas(modifier = modifier.size(width = 24.dp, height = 20.dp)) {
        val barCount = 4
        val barW = size.width / (barCount * 1.85f)
        val gap = (size.width - barCount * barW) / (barCount - 1)
        for (i in 0 until barCount) {
            val frac = if (isPlaying) {
                0.24f + 0.72f * abs(sin(phase + i * 1.15f))
            } else {
                0.24f
            }
            val barH = size.height * frac
            val x = i * (barW + gap)
            val y = (size.height - barH) / 2f
            drawRoundRect(
                color = NoctaliaColors.CyanGlow,
                topLeft = Offset(x, y),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}
