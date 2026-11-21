package com.noctalia.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Efecto táctil de muelle suave al pulsar tarjetas, botones y chips.
 */
fun Modifier.bouncyClick(
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "bouncy_scale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

@Composable
fun NoctaliaBackground(
    modifier: Modifier = Modifier,
    animateHalos: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val drift by if (animateHalos) {
        val infinite = rememberInfiniteTransition(label = "bg_halos")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 16000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "halo_drift",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(NoctaliaColors.BgDeep, NoctaliaColors.BgMid, NoctaliaColors.BgDeep)
                )
            )
    ) {
        // Halos ambientales cian / magenta / azul con respiración muy sutil estilo Noctalia
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dx1 = sin(drift) * size.width * 0.045f
            val dy1 = cos(drift) * size.height * 0.025f
            val c1 = Offset(size.width * 0.16f + dx1, size.height * 0.08f + dy1)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NoctaliaColors.Pink.copy(alpha = 0.22f), Color.Transparent),
                    center = c1,
                    radius = size.width * 0.72f,
                ),
                center = c1,
                radius = size.width * 0.72f,
            )

            val dx2 = cos(drift * 0.85f) * size.width * 0.045f
            val dy2 = sin(drift * 0.85f) * size.height * 0.03f
            val c2 = Offset(size.width * 0.88f + dx2, size.height * 0.16f + dy2)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NoctaliaColors.Cyan.copy(alpha = 0.20f), Color.Transparent),
                    center = c2,
                    radius = size.width * 0.68f,
                ),
                center = c2,
                radius = size.width * 0.68f,
            )

            val c3 = Offset(size.width * 0.5f - dx1 * 0.7f, size.height * 0.92f - dy2 * 0.6f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NoctaliaColors.Blue.copy(alpha = 0.16f), Color.Transparent),
                    center = c3,
                    radius = size.width * 0.75f,
                ),
                center = c3,
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
    val bg by animateColorAsState(
        targetValue = when {
            highlighted -> NoctaliaColors.Cyan.copy(alpha = 0.16f)
            strong -> NoctaliaColors.GlassStrong
            else -> NoctaliaColors.Glass
        },
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "glass_bg",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            highlighted -> NoctaliaColors.CyanGlow.copy(alpha = 0.65f)
            strong -> NoctaliaColors.BorderCyanStrong
            else -> NoctaliaColors.BorderCyan
        },
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "glass_border",
    )
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
    val chipBg by animateColorAsState(
        targetValue = if (active) NoctaliaColors.Cyan else NoctaliaColors.Glass,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "chip_bg",
    )
    val chipBorder by animateColorAsState(
        targetValue = if (active) NoctaliaColors.CyanGlow else NoctaliaColors.BorderCyan,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "chip_border",
    )
    val chipText by animateColorAsState(
        targetValue = if (active) NoctaliaColors.OnCyan else NoctaliaColors.TextPrimary,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "chip_text",
    )
    Surface(
        modifier = modifier
            .clip(shape)
            .bouncyClick(pressedScale = 0.93f, onClick = onClick),
        shape = shape,
        color = chipBg,
        border = BorderStroke(width = 1.dp, color = chipBorder),
    ) {
        Text(
            text = label,
            color = chipText,
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
    onMoreClick: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    indexBadge: String? = null,
) {
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent) NoctaliaColors.CyanGlow else NoctaliaColors.TextPrimary,
        animationSpec = tween(220),
        label = "row_title_color",
    )
    var favPulse by remember { mutableStateOf(false) }
    val favScale by animateFloatAsState(
        targetValue = if (favPulse) 1.32f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 550f),
        finishedListener = { favPulse = false },
        label = "row_fav_scale",
    )

    // Gesto de deslizar hacia la derecha para añadir a la cola (estilo Spotify)
    var dragRaw by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val swipeOffset by animateFloatAsState(
        targetValue = if (isDragging) dragRaw else 0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
        label = "row_swipe_x",
    )

    val swipeModifier = if (onAddQueue != null) {
        Modifier.pointerInput(track.id) {
            detectHorizontalDragGestures(
                onDragStart = {
                    isDragging = true
                    dragRaw = 0f
                },
                onHorizontalDrag = { _, dragAmount ->
                    dragRaw = (dragRaw + dragAmount).coerceIn(0f, 190f)
                },
                onDragEnd = {
                    val triggered = dragRaw > 110f
                    isDragging = false
                    dragRaw = 0f
                    if (triggered) onAddQueue()
                },
                onDragCancel = {
                    isDragging = false
                    dragRaw = 0f
                },
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
    ) {
        if (onAddQueue != null && swipeOffset > 2f) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                NoctaliaColors.Cyan.copy(alpha = 0.45f),
                                NoctaliaColors.Cyan.copy(alpha = 0.08f),
                            )
                        )
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = NoctaliaColors.CyanGlow,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (swipeOffset > 110f) "Soltar para encolar" else "Añadir a la cola",
                    color = NoctaliaColors.CyanGlow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        NoctaliaGlassCard(
            cornerRadius = 16.dp,
            highlighted = isCurrent,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = swipeOffset }
                .then(swipeModifier)
                .bouncyClick(pressedScale = 0.975f, onClick = onPlay),
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
                Box(contentAlignment = Alignment.Center) {
                    TrackCoverImage(url = track.thumb, size = 48.dp, cornerRadius = 11.dp)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isCurrent,
                        enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.7f),
                        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.7f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color.Black.copy(alpha = 0.52f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            NoctaliaMiniEqualizer(isPlaying = true)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = titleColor,
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
                if (onToggleFavorite != null) {
                    IconButton(
                        onClick = {
                            favPulse = true
                            onToggleFavorite()
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Favorita",
                            tint = if (isFavorite) NoctaliaColors.CyanGlow else NoctaliaColors.TextDim,
                            modifier = Modifier
                                .size(19.dp)
                                .graphicsLayer {
                                    scaleX = favScale
                                    scaleY = favScale
                                },
                        )
                    }
                }
                if (onMoreClick != null) {
                    IconButton(onClick = onMoreClick, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Más opciones",
                            tint = NoctaliaColors.TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else if (onAddQueue != null) {
                    IconButton(onClick = onAddQueue, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Añadir a la cola",
                            tint = NoctaliaColors.TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (onMoveUp != null) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = "Subir en cola",
                            tint = NoctaliaColors.TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (onMoveDown != null) {
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Bajar en cola",
                            tint = NoctaliaColors.TextPrimary,
                            modifier = Modifier.size(20.dp),
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
}

/**
 * Toast flotante estilo cristal ahumado Noctalia (sustituye al Snackbar gris genérico).
 */
@Composable
fun NoctaliaGlassToast(
    message: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = !message.isNullOrBlank(),
        enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = 420f)) { -it } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(160)),
        modifier = modifier,
    ) {
        NoctaliaGlassCard(
            cornerRadius = 99.dp,
            strong = true,
            highlighted = true,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(NoctaliaColors.Cyan.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = NoctaliaColors.CyanGlow,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = message.orEmpty(),
                    color = NoctaliaColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Placeholders animados tipo Skeleton Shimmer para carga de listas de canciones.
 */
@Composable
fun TrackListShimmer(
    count: Int = 6,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "shimmer_tr")
    val shift by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_shift",
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.05f),
            NoctaliaColors.CyanGlow.copy(alpha = 0.16f),
            Color.White.copy(alpha = 0.05f),
        ),
        start = Offset(shift * 420f, 0f),
        end = Offset(shift * 420f + 260f, 120f),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(count) { idx ->
            NoctaliaGlassCard(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(shimmerBrush),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (idx % 2 == 0) 0.68f else 0.54f)
                                .height(13.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(shimmerBrush),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (idx % 2 == 0) 0.42f else 0.36f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(shimmerBrush),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Menú deslizable (Bottom Sheet) de acciones rápidas para cualquier canción.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsBottomSheet(
    track: Track,
    isFavorite: Boolean,
    isOfflineCached: Boolean,
    isOfflineDownloading: Boolean,
    playlistNames: List<String>,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddQueue: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onToggleOffline: () -> Unit,
    onStartRadio: () -> Unit,
    onOpenArtist: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showPlaylists by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NoctaliaColors.GlassStrong.copy(alpha = 0.96f),
        contentColor = NoctaliaColors.TextPrimary,
        scrimColor = Color(0xAA02060B),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Cabecera de la canción
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackCoverImage(url = track.thumb, size = 50.dp, cornerRadius = 12.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = NoctaliaColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        color = NoctaliaColors.Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = NoctaliaColors.BorderCyan.copy(alpha = 0.45f))
            Spacer(Modifier.height(4.dp))

            SheetActionItem(
                icon = Icons.Rounded.SkipNext,
                tint = NoctaliaColors.CyanGlow,
                label = "Reproducir a continuación",
                onClick = {
                    onPlayNext()
                    onDismiss()
                },
            )
            SheetActionItem(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                tint = NoctaliaColors.Cyan,
                label = "Añadir a la cola",
                onClick = {
                    onAddQueue()
                    onDismiss()
                },
            )
            SheetActionItem(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                tint = NoctaliaColors.Magenta,
                label = if (showPlaylists) "Elige una playlist:" else "Añadir a una playlist...",
                onClick = { showPlaylists = !showPlaylists },
            )

            AnimatedVisibility(visible = showPlaylists) {
                if (playlistNames.isEmpty()) {
                    Text(
                        text = "Crea una playlist primero en la pestaña Listas.",
                        color = NoctaliaColors.TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 46.dp, bottom = 8.dp),
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 42.dp, top = 4.dp, bottom = 8.dp),
                    ) {
                        items(playlistNames) { plName ->
                            NoctaliaPillChip(
                                label = plName,
                                active = false,
                                onClick = {
                                    onAddToPlaylist(plName)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }

            SheetActionItem(
                icon = if (isOfflineCached) Icons.Rounded.DownloadDone else Icons.Rounded.DownloadForOffline,
                tint = if (isOfflineCached) NoctaliaColors.CyanGlow else NoctaliaColors.TextPrimary,
                label = when {
                    isOfflineDownloading -> "Descargando sin conexión..."
                    isOfflineCached -> "Quitar de descargas sin conexión"
                    else -> "Descargar para modo sin conexión"
                },
                onClick = {
                    if (!isOfflineDownloading) {
                        onToggleOffline()
                        onDismiss()
                    }
                },
            )
            SheetActionItem(
                icon = Icons.Rounded.Radio,
                tint = Color(0xFFB388FF),
                label = "Iniciar Radio similar",
                onClick = {
                    onStartRadio()
                    onDismiss()
                },
            )
            SheetActionItem(
                icon = Icons.Rounded.Person,
                tint = NoctaliaColors.TextPrimary,
                label = "Ver canciones de ${track.artist.substringBefore(",").trim()}",
                onClick = {
                    onOpenArtist()
                    onDismiss()
                },
            )
            SheetActionItem(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                tint = if (isFavorite) NoctaliaColors.CyanGlow else NoctaliaColors.TextMuted,
                label = if (isFavorite) "Quitar de Favoritas" else "Añadir a Favoritas",
                onClick = {
                    onToggleFavorite()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun SheetActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .bouncyClick(pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(
            text = label,
            color = NoctaliaColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val smoothFraction by animateFloatAsState(
        targetValue = liveFraction,
        animationSpec = tween(durationMillis = 260, easing = LinearEasing),
        label = "seek_smooth",
    )
    val displayFraction = if (dragging) dragFraction else smoothFraction

    val animatedAmp by animateFloatAsState(
        targetValue = if (isPlaying) 9.5f else 1.8f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 260f),
        label = "wave_amp",
    )
    val thumbGlowRadius by animateFloatAsState(
        targetValue = if (dragging) 22f else 16f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "thumb_glow",
    )

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

        // Parte reproducida (onda sinusoidal cian con transición suave de amplitud)
        val amp = animatedAmp
        val path = Path()
        var x = pad
        var first = true
        while (x <= thumbX) {
            val y = midY + sin(x * 0.065f - phase) * amp
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

        // Punto indicador blanco brillante (crece suavemente al arrastrar)
        drawCircle(
            color = NoctaliaColors.CyanGlow.copy(alpha = 0.35f),
            radius = thumbGlowRadius,
            center = Offset(thumbX, midY),
        )
        drawCircle(
            color = Color.White,
            radius = if (dragging) 12f else 10f,
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
