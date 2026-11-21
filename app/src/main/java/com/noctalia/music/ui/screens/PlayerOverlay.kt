package com.noctalia.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noctalia.music.data.OfflineState
import com.noctalia.music.data.SecurityValidators
import com.noctalia.music.data.Track
import com.noctalia.music.player.AudioFxState
import com.noctalia.music.player.EqPreset
import com.noctalia.music.player.PlayerUiState
import com.noctalia.music.player.RepeatMode
import com.noctalia.music.ui.components.AlbumColorCache
import com.noctalia.music.ui.components.BrookAnimatedPlayerBackground
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.components.NoctaliaMiniEqualizer
import com.noctalia.music.ui.components.NoctaliaPillChip
import com.noctalia.music.ui.components.NoctaliaWaveSeekBar
import com.noctalia.music.ui.components.PlayerBgStyle
import com.noctalia.music.ui.components.TrackCoverImage
import com.noctalia.music.ui.components.TrackRowItem
import com.noctalia.music.ui.components.bouncyClick
import com.noctalia.music.ui.theme.NoctaliaColors
import kotlin.math.abs

@Composable
fun MiniPlayerBar(
    player: PlayerUiState,
    useCoverColors: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = player.currentTrack ?: return
    val context = LocalContext.current
    var artData by remember { mutableStateOf(AlbumColorCache.noctaliaFallback) }
    LaunchedEffect(track.thumb) {
        artData = AlbumColorCache.extract(context, track.thumb)
    }

    val dominantRaw = if (useCoverColors) {
        artData.colors.firstOrNull() ?: NoctaliaColors.Cyan
    } else {
        NoctaliaColors.Cyan
    }
    val tintBgStart by animateColorAsState(
        targetValue = Color(
            red = dominantRaw.red * 0.42f + 0.04f,
            green = dominantRaw.green * 0.42f + 0.06f,
            blue = dominantRaw.blue * 0.42f + 0.08f,
            alpha = 0.90f,
        ),
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "mini_tint_start",
    )
    val tintBgEnd by animateColorAsState(
        targetValue = Color(
            red = dominantRaw.red * 0.14f + 0.03f,
            green = dominantRaw.green * 0.14f + 0.05f,
            blue = dominantRaw.blue * 0.16f + 0.08f,
            alpha = 0.94f,
        ),
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "mini_tint_end",
    )
    val tintBorder by animateColorAsState(
        targetValue = dominantRaw.copy(alpha = if (player.isPlaying) 0.56f else 0.34f),
        animationSpec = tween(420),
        label = "mini_tint_border",
    )

    val rawFrac = if (player.durationMs > 0L) (player.positionMs.toFloat() / player.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val animFrac by animateFloatAsState(
        targetValue = rawFrac,
        animationSpec = tween(durationMillis = 260, easing = LinearEasing),
        label = "mini_progress",
    )

    // Gesto unificado: deslizar ←/→ cambia de canción, deslizar ↑ abre el reproductor
    var dragXRaw by remember { mutableFloatStateOf(0f) }
    var dragYAcc by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val animDragX by animateFloatAsState(
        targetValue = if (isDragging) dragXRaw else 0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
        label = "mini_drag_x",
    )
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = animDragX * 0.45f }
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(tintBgStart, tintBgEnd)))
            .border(BorderStroke(1.2.dp, tintBorder), shape)
            .pointerInput(track.id) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        dragXRaw = 0f
                        dragYAcc = 0f
                    },
                    onDrag = { _, dragAmount ->
                        dragXRaw = (dragXRaw + dragAmount.x).coerceIn(-160f, 160f)
                        dragYAcc += dragAmount.y
                    },
                    onDragEnd = {
                        val dx = dragXRaw
                        val dy = dragYAcc
                        isDragging = false
                        dragXRaw = 0f
                        dragYAcc = 0f
                        when {
                            dy < -50f && abs(dy) > abs(dx) -> onExpand()
                            dx < -80f && abs(dx) > abs(dy) -> onNext()
                            dx > 80f && abs(dx) > abs(dy) -> onPrev()
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        dragXRaw = 0f
                        dragYAcc = 0f
                    },
                )
            }
            .bouncyClick(pressedScale = 0.98f, onClick = onExpand),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = track,
                    transitionSpec = {
                        (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 3 } +
                            fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(200)) { -it / 3 } + fadeOut(tween(180)))
                    },
                    modifier = Modifier.weight(1f),
                    label = "mini_track_swap",
                ) { activeTrack ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackCoverImage(url = activeTrack.thumb, size = 44.dp, cornerRadius = 11.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeTrack.title,
                                color = NoctaliaColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    player.isLoading -> "Cargando audio..."
                                    !player.errorMsg.isNullOrBlank() -> player.errorMsg
                                    else -> activeTrack.artist
                                },
                                color = when {
                                    player.isLoading -> NoctaliaColors.CyanGlow
                                    !player.errorMsg.isNullOrBlank() -> NoctaliaColors.Magenta
                                    else -> NoctaliaColors.TextPrimary.copy(alpha = 0.78f)
                                },
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                NoctaliaMiniEqualizer(isPlaying = player.isPlaying)
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .bouncyClick(pressedScale = 0.84f, onClick = onNext),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Siguiente",
                        tint = NoctaliaColors.TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(NoctaliaColors.Cyan)
                        .bouncyClick(pressedScale = 0.86f, onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = if (player.isLoading) "load" else if (player.isPlaying) "pause" else "play",
                        transitionSpec = {
                            (scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.65f) + fadeIn(tween(150))) togetherWith
                                (scaleOut(tween(120), targetScale = 0.65f) + fadeOut(tween(120)))
                        },
                        label = "mini_play_icon",
                    ) { mode ->
                        if (mode == "load") {
                            CircularProgressIndicator(
                                color = NoctaliaColors.OnCyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                imageVector = if (mode == "pause") Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = NoctaliaColors.OnCyan,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 2.dp)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animFrac)
                        .height(2.5.dp)
                        .background(NoctaliaColors.CyanGlow),
                )
            }
        }
    }
}

@Composable
fun FullPlayerOverlay(
    player: PlayerUiState,
    audioFx: AudioFxState,
    offline: OfflineState,
    bgStyle: PlayerBgStyle,
    bgUseCoverColors: Boolean,
    dynamicAppTheme: Boolean,
    isFavorite: Boolean,
    favoriteIds: Set<String>,
    onCollapse: () -> Unit,
    onSelectBgStyle: (PlayerBgStyle) -> Unit,
    onToggleBgUseCoverColors: (Boolean) -> Unit,
    onToggleDynamicAppTheme: (Boolean) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onToggleOffline: (Track) -> Unit,
    onToggleAutoRadio: (Boolean) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onSelectEqPreset: (EqPreset) -> Unit,
    onBassBoostChange: (Int) -> Unit,
    onToggleNormalize: (Boolean) -> Unit,
    onToggleSponsorBlock: (Boolean) -> Unit,
    onSetSleepMinutes: (Int) -> Unit,
    onSetSleepEndOfTrack: () -> Unit,
    onCancelSleep: () -> Unit,
) {
    val track = player.currentTrack ?: return
    // null = reproductor principal | "lyrics" | "queue" | "audio" | "timer"
    var activeSheet by remember { mutableStateOf<String?>(null) }

    val isCached = track.id in offline.cachedIds
    val isDownloading = track.id in offline.downloadingIds

    // Gesto interactivo de deslizar hacia abajo para bajar/minimizar el reproductor
    var dragDownRaw by remember { mutableFloatStateOf(0f) }
    var isDraggingDown by remember { mutableStateOf(false) }
    val dragDownOffset by animateFloatAsState(
        targetValue = if (isDraggingDown) dragDownRaw else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "player_drag_down",
    )
    val dragFrac = (dragDownOffset / 900f).coerceIn(0f, 1f)

    BrookAnimatedPlayerBackground(
        coverUrl = track.thumb,
        isPlaying = player.isPlaying,
        style = bgStyle,
        useCoverColors = bgUseCoverColors,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dragDownOffset
                val sc = 1f - dragFrac * 0.08f
                scaleX = sc
                scaleY = sc
                alpha = 1f - dragFrac * 0.35f
            }
            .clip(RoundedCornerShape(topStart = (dragFrac * 34f).dp, topEnd = (dragFrac * 34f).dp))
            .clickable(enabled = true, onClick = {}),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Indicador superior deslizable + Barra superior: Minimizar | BROOK + calidad | Offline
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                isDraggingDown = true
                                dragDownRaw = 0f
                            },
                            onVerticalDrag = { _, dragAmount ->
                                dragDownRaw = (dragDownRaw + dragAmount).coerceAtLeast(0f)
                            },
                            onDragEnd = {
                                val shouldDismiss = dragDownRaw > 150f
                                isDraggingDown = false
                                dragDownRaw = 0f
                                if (shouldDismiss) onCollapse()
                            },
                            onDragCancel = {
                                isDraggingDown = false
                                dragDownRaw = 0f
                            },
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.White.copy(alpha = 0.24f)),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NoctaliaGlassCard(
                        cornerRadius = 99.dp,
                        modifier = Modifier.bouncyClick(pressedScale = 0.88f, onClick = onCollapse),
                    ) {
                        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Minimizar",
                                tint = NoctaliaColors.TextPrimary,
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "BROOK",
                            color = NoctaliaColors.Cyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.6.sp,
                        )
                        val timerTxt = when {
                            player.sleepEndOfTrack -> " · ⏱ Fin pista"
                            player.sleepRemainingSec != null -> " · ⏱ ${SecurityValidators.fmtDuration(player.sleepRemainingSec)}"
                            else -> ""
                        }
                        Text(
                            text = "${player.qualityLabel}$timerTxt",
                            color = if (timerTxt.isNotEmpty()) NoctaliaColors.Magenta else NoctaliaColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    NoctaliaGlassCard(
                        cornerRadius = 99.dp,
                        highlighted = isCached,
                        modifier = Modifier.bouncyClick(
                            pressedScale = 0.88f,
                            enabled = !isDownloading,
                            onClick = { onToggleOffline(track) },
                        ),
                    ) {
                        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            AnimatedContent(
                                targetState = when {
                                    isDownloading -> "dl"
                                    isCached -> "done"
                                    else -> "idle"
                                },
                                transitionSpec = {
                                    (scaleIn(spring(dampingRatio = 0.5f, stiffness = 450f), initialScale = 0.55f) + fadeIn(tween(180))) togetherWith
                                        (scaleOut(tween(140), targetScale = 0.6f) + fadeOut(tween(140)))
                                },
                                label = "offline_btn_anim",
                            ) { dlState ->
                                when (dlState) {
                                    "dl" -> CircularProgressIndicator(
                                        color = NoctaliaColors.CyanGlow,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    "done" -> Icon(
                                        imageVector = Icons.Rounded.DownloadDone,
                                        contentDescription = "Guardada sin conexión",
                                        tint = NoctaliaColors.CyanGlow,
                                    )
                                    else -> Icon(
                                        imageVector = Icons.Rounded.DownloadForOffline,
                                        contentDescription = "Guardar sin conexión",
                                        tint = NoctaliaColors.TextPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Contenido central con transición fluida entre Reproductor / Letra / Cola / EQ / Timer
            AnimatedContent(
                targetState = activeSheet,
                transitionSpec = {
                    val enteringMain = targetState == null
                    val enterAnim = if (enteringMain) {
                        fadeIn(tween(240)) + scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.96f)
                    } else {
                        slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 6 } + fadeIn(tween(220))
                    }
                    val exitAnim = if (enteringMain) {
                        slideOutVertically(tween(200)) { it / 6 } + fadeOut(tween(180))
                    } else {
                        fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.96f)
                    }
                    enterAnim togetherWith exitAnim
                },
                modifier = Modifier.weight(1f),
                label = "player_sheet_switch",
            ) { sheet ->
            when (sheet) {
                "lyrics" -> {
                    LyricsPanel(
                        player = player,
                        onSeek = onSeek,
                        onPlayPause = onPlayPause,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 8.dp),
                    )
                }

                "queue" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NoctaliaGlassCard(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text("Auto-Radio infinita", color = NoctaliaColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("Añade canciones similares al terminar", color = NoctaliaColors.TextMuted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = player.autoRadio,
                                    onCheckedChange = onToggleAutoRadio,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = NoctaliaColors.OnCyan,
                                        checkedTrackColor = NoctaliaColors.Cyan,
                                    ),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("A continuación (${player.queue.size})", color = NoctaliaColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            if (player.queue.isNotEmpty()) {
                                Text(
                                    text = "Limpiar",
                                    color = NoctaliaColors.Danger,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable(onClick = onClearQueue),
                                )
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(player.queue, key = { idx, t -> "q_${idx}_${t.id}" }) { idx, qTrack ->
                                TrackRowItem(
                                    track = qTrack,
                                    isCurrent = false,
                                    isFavorite = qTrack.id in favoriteIds,
                                    onPlay = { onPlayQueueIndex(idx) },
                                    onRemove = { onRemoveQueueIndex(idx) },
                                    indexBadge = "${idx + 1}",
                                )
                            }
                        }
                    }
                }

                "audio" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Fondo animado", color = NoctaliaColors.CyanGlow, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(PlayerBgStyle.entries) { st ->
                                        NoctaliaPillChip(
                                            label = st.label,
                                            active = bgStyle == st,
                                            onClick = { onSelectBgStyle(st) },
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Colores de la portada", color = NoctaliaColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("Adapta el fondo animado a cada canción", color = NoctaliaColors.TextMuted, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = bgUseCoverColors,
                                        onCheckedChange = onToggleBgUseCoverColors,
                                        colors = SwitchDefaults.colors(checkedThumbColor = NoctaliaColors.OnCyan, checkedTrackColor = NoctaliaColors.Cyan),
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Tema dinámico en toda la app", color = NoctaliaColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("Cambia botones, dock, bordes y onda según la canción", color = NoctaliaColors.TextMuted, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = dynamicAppTheme,
                                        onCheckedChange = onToggleDynamicAppTheme,
                                        colors = SwitchDefaults.colors(checkedThumbColor = NoctaliaColors.OnCyan, checkedTrackColor = NoctaliaColors.Cyan),
                                    )
                                }
                            }
                        }

                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Ecualizador (Presets)", color = NoctaliaColors.CyanGlow, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(EqPreset.entries) { preset ->
                                        NoctaliaPillChip(
                                            label = preset.label,
                                            active = audioFx.preset == preset,
                                            onClick = { onSelectEqPreset(preset) },
                                        )
                                    }
                                }

                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Bass Boost", color = NoctaliaColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("${audioFx.bassBoostStrength / 10}%", color = NoctaliaColors.CyanGlow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = audioFx.bassBoostStrength.toFloat(),
                                    onValueChange = { onBassBoostChange(it.toInt()) },
                                    valueRange = 0f..1000f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = NoctaliaColors.Cyan,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                                    ),
                                )
                            }
                        }

                        NoctaliaGlassCard(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Normalizar volumen", color = NoctaliaColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("Mantiene un volumen uniforme entre canciones", color = NoctaliaColors.TextMuted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = audioFx.normalizeVolume,
                                    onCheckedChange = onToggleNormalize,
                                    colors = SwitchDefaults.colors(checkedThumbColor = NoctaliaColors.OnCyan, checkedTrackColor = NoctaliaColors.Cyan),
                                )
                            }
                        }

                        NoctaliaGlassCard(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("SponsorBlock (Saltar intros)", color = NoctaliaColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("Salta partes no musicales y silencios largos", color = NoctaliaColors.TextMuted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = audioFx.sponsorBlockEnabled,
                                    onCheckedChange = onToggleSponsorBlock,
                                    colors = SwitchDefaults.colors(checkedThumbColor = NoctaliaColors.OnCyan, checkedTrackColor = NoctaliaColors.Cyan),
                                )
                            }
                        }
                    }
                }

                "timer" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Temporizador de apagado", color = NoctaliaColors.CyanGlow, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                                Text(
                                    text = when {
                                        player.sleepEndOfTrack -> "Se detendrá al terminar esta canción."
                                        player.sleepRemainingSec != null -> "Faltan ${SecurityValidators.fmtDuration(player.sleepRemainingSec)} (con bajada suave de volumen)."
                                        else -> "Elige cuándo quieres que Brook pause la música automáticamente."
                                    },
                                    color = NoctaliaColors.TextMuted,
                                    fontSize = 13.sp,
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(15, 30, 45, 60).forEach { mins ->
                                        NoctaliaPillChip(
                                            label = "${mins}m",
                                            active = false,
                                            onClick = { onSetSleepMinutes(mins) },
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    NoctaliaPillChip(
                                        label = "Al terminar pista",
                                        active = player.sleepEndOfTrack,
                                        onClick = onSetSleepEndOfTrack,
                                    )
                                    if (player.sleepRemainingSec != null || player.sleepEndOfTrack) {
                                        NoctaliaPillChip(
                                            label = "Desactivar",
                                            active = false,
                                            onClick = onCancelSleep,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    // Vista principal del reproductor: carátula fija y nítida sobre el fondo animado vivo
                    var coverDragX by remember { mutableFloatStateOf(0f) }
                    var coverDragging by remember { mutableStateOf(false) }
                    val animCoverDragX by animateFloatAsState(
                        targetValue = if (coverDragging) coverDragX else 0f,
                        animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
                        label = "cover_drag_x",
                    )

                    var favPop by remember { mutableStateOf(false) }
                    val favScale by animateFloatAsState(
                        targetValue = if (favPop) 1.36f else 1f,
                        animationSpec = spring(dampingRatio = 0.40f, stiffness = 540f),
                        finishedListener = { favPop = false },
                        label = "player_fav_pop",
                    )
                    val playCorner by animateDpAsState(
                        targetValue = if (player.isPlaying) 20.dp else 99.dp,
                        animationSpec = spring(dampingRatio = 0.65f, stiffness = 380f),
                        label = "play_pill_corner",
                    )

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Carátula tal cual, fija y nítida (deslizar lateral para Siguiente/Anterior)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .pointerInput(track.id) {
                                    detectHorizontalDragGestures(
                                        onDragStart = {
                                            coverDragging = true
                                            coverDragX = 0f
                                        },
                                        onHorizontalDrag = { _, dragAmount ->
                                            coverDragX = (coverDragX + dragAmount).coerceIn(-240f, 240f)
                                        },
                                        onDragEnd = {
                                            val dx = coverDragX
                                            coverDragging = false
                                            coverDragX = 0f
                                            when {
                                                dx < -95f -> onNext()
                                                dx > 95f -> onPrev()
                                            }
                                        },
                                        onDragCancel = {
                                            coverDragging = false
                                            coverDragX = 0f
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedContent(
                                targetState = track.thumb,
                                transitionSpec = {
                                    (slideInHorizontally(spring(dampingRatio = 0.80f, stiffness = 380f)) { it / 2 } +
                                        fadeIn(tween(240))) togetherWith
                                        (slideOutHorizontally(tween(200)) { -it / 2 } +
                                            fadeOut(tween(180)))
                                },
                                label = "big_cover_swap",
                            ) { coverUrl ->
                                TrackCoverImage(
                                    url = coverUrl,
                                    size = 266.dp,
                                    cornerRadius = 26.dp,
                                    modifier = Modifier.graphicsLayer {
                                        translationX = animCoverDragX
                                        rotationZ = (animCoverDragX / 40f).coerceIn(-5f, 5f)
                                    },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AnimatedContent(
                                targetState = track,
                                transitionSpec = {
                                    (slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(240))) togetherWith
                                        (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(160)))
                                },
                                modifier = Modifier.weight(1f),
                                label = "title_swap",
                            ) { activeTrack ->
                                Column {
                                    Text(
                                        text = activeTrack.title,
                                        color = NoctaliaColors.TextPrimary,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "${activeTrack.artist} ▸",
                                        color = NoctaliaColors.Cyan,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.clickable { onArtistClick(activeTrack.artist) },
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            NoctaliaGlassCard(
                                cornerRadius = 99.dp,
                                highlighted = isFavorite,
                                modifier = Modifier.bouncyClick(pressedScale = 0.86f) {
                                    favPop = true
                                    onToggleFavorite(track)
                                },
                            ) {
                                Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        contentDescription = "Favorita",
                                        tint = if (isFavorite) NoctaliaColors.CyanGlow else NoctaliaColors.TextPrimary,
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = favScale
                                            scaleY = favScale
                                        },
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = player.sponsorSkippedNotice != null,
                            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                            exit = shrinkVertically(tween(200)) + fadeOut(tween(180)),
                        ) {
                            Text(
                                text = player.sponsorSkippedNotice.orEmpty(),
                                color = NoctaliaColors.Magenta,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        AnimatedVisibility(
                            visible = player.errorMsg != null,
                            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                            exit = shrinkVertically(tween(200)) + fadeOut(tween(180)),
                        ) {
                            Text(
                                text = player.errorMsg.orEmpty(),
                                color = NoctaliaColors.Danger,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        NoctaliaWaveSeekBar(
                            positionMs = player.positionMs,
                            durationMs = player.durationMs,
                            isPlaying = player.isPlaying,
                            onSeek = onSeek,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = SecurityValidators.fmtDuration((player.positionMs / 1000L).toInt()),
                                color = NoctaliaColors.TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            val remSec = ((player.durationMs - player.positionMs).coerceAtLeast(0L) / 1000L).toInt()
                            Text(
                                text = "-${SecurityValidators.fmtDuration(remSec)}",
                                color = NoctaliaColors.TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .bouncyClick(pressedScale = 0.85f, onClick = onToggleShuffle),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.Shuffle,
                                    contentDescription = "Aleatorio",
                                    tint = if (player.shuffle) NoctaliaColors.CyanGlow else NoctaliaColors.TextMuted,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .bouncyClick(pressedScale = 0.84f, onClick = onPrev),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Anterior", tint = NoctaliaColors.TextPrimary, modifier = Modifier.size(34.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(width = 78.dp, height = 56.dp)
                                    .clip(RoundedCornerShape(playCorner))
                                    .background(Brush.linearGradient(listOf(NoctaliaColors.CyanGlow, NoctaliaColors.Cyan)))
                                    .bouncyClick(pressedScale = 0.90f, onClick = onPlayPause),
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedContent(
                                    targetState = if (player.isLoading) "load" else if (player.isPlaying) "pause" else "play",
                                    transitionSpec = {
                                        (scaleIn(spring(dampingRatio = 0.55f, stiffness = 480f), initialScale = 0.6f) + fadeIn(tween(150))) togetherWith
                                            (scaleOut(tween(120), targetScale = 0.6f) + fadeOut(tween(120)))
                                    },
                                    label = "big_play_morph",
                                ) { mode ->
                                    if (mode == "load") {
                                        CircularProgressIndicator(color = NoctaliaColors.OnCyan, strokeWidth = 2.8.dp, modifier = Modifier.size(24.dp))
                                    } else {
                                        Icon(
                                            imageVector = if (mode == "pause") Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = "Reproducir / Pausar",
                                            tint = NoctaliaColors.OnCyan,
                                            modifier = Modifier.size(34.dp),
                                        )
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .bouncyClick(pressedScale = 0.84f, onClick = onNext),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = "Siguiente", tint = NoctaliaColors.TextPrimary, modifier = Modifier.size(34.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .bouncyClick(pressedScale = 0.85f, onClick = onCycleRepeat),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (player.repeatMode == RepeatMode.TRACK) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                    contentDescription = "Repetir",
                                    tint = if (player.repeatMode != RepeatMode.NONE) NoctaliaColors.CyanGlow else NoctaliaColors.TextMuted,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
            }

            // Barra inferior de 4 pestañas con iconos + badge de cola/timer
            Spacer(Modifier.height(8.dp))
            val timerActive = player.sleepRemainingSec != null || player.sleepEndOfTrack
            val queueLabel = if (player.queue.isNotEmpty()) "Cola · ${player.queue.size}" else "Cola"
            NoctaliaGlassCard(cornerRadius = 22.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PlayerBottomTabItem(
                        icon = Icons.Rounded.MusicNote,
                        label = "Letra",
                        active = activeSheet == "lyrics",
                        accentDot = false,
                        onClick = { activeSheet = if (activeSheet == "lyrics") null else "lyrics" },
                        modifier = Modifier.weight(1f),
                    )
                    PlayerBottomTabItem(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        label = queueLabel,
                        active = activeSheet == "queue",
                        accentDot = false,
                        onClick = { activeSheet = if (activeSheet == "queue") null else "queue" },
                        modifier = Modifier.weight(1f),
                    )
                    PlayerBottomTabItem(
                        icon = Icons.Rounded.GraphicEq,
                        label = "EQ",
                        active = activeSheet == "audio",
                        accentDot = false,
                        onClick = { activeSheet = if (activeSheet == "audio") null else "audio" },
                        modifier = Modifier.weight(1f),
                    )
                    PlayerBottomTabItem(
                        icon = Icons.Rounded.Bedtime,
                        label = "Timer",
                        active = activeSheet == "timer",
                        accentDot = timerActive,
                        onClick = { activeSheet = if (activeSheet == "timer") null else "timer" },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerBottomTabItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    accentDot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (active) NoctaliaColors.Cyan.copy(alpha = 0.24f) else Color.Transparent,
        animationSpec = tween(200),
        label = "ptab_bg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            active -> NoctaliaColors.CyanGlow
            accentDot -> NoctaliaColors.Magenta
            else -> NoctaliaColors.TextPrimary.copy(alpha = 0.78f)
        },
        animationSpec = tween(200),
        label = "ptab_tint",
    )
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .bouncyClick(pressedScale = 0.92f, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LyricsPanel(
    player: PlayerUiState,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = player.currentTrack
    val lyrics = player.lyrics
    val listState = rememberLazyListState()

    val activeIdx = remember(lyrics.synced, player.positionMs) {
        if (lyrics.synced.isEmpty()) -1
        else lyrics.synced.indexOfLast { it.timeMs <= player.positionMs + 250L }
    }

    LaunchedEffect(activeIdx) {
        if (activeIdx >= 0) {
            listState.animateScrollToItem((activeIdx - 2).coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Cabecera compacta estilo Apple Music mientras lees la letra
        if (track != null) {
            NoctaliaGlassCard(cornerRadius = 18.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackCoverImage(url = track.thumb, size = 40.dp, cornerRadius = 10.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = NoctaliaColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${track.artist} · Letra",
                            color = NoctaliaColors.CyanGlow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(NoctaliaColors.Cyan)
                            .bouncyClick(pressedScale = 0.86f, onClick = onPlayPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = NoctaliaColors.OnCyan,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        // Contenedor translúcido para que el fondo animado brille detrás de las letras
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0x38050B11))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp)),
        ) {
            when {
                player.loadingLyrics -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NoctaliaColors.CyanGlow, strokeWidth = 2.5.dp)
                    }
                }

                lyrics.synced.isNotEmpty() -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 26.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        itemsIndexed(lyrics.synced) { idx, line ->
                            val isActive = idx == activeIdx
                            val lineAlpha by animateFloatAsState(
                                targetValue = when {
                                    isActive -> 1f
                                    idx < activeIdx -> 0.34f
                                    else -> 0.56f
                                },
                                animationSpec = tween(280, easing = FastOutSlowInEasing),
                                label = "lyric_alpha_$idx",
                            )
                            val lineScale by animateFloatAsState(
                                targetValue = if (isActive) 1.05f else 0.96f,
                                animationSpec = spring(dampingRatio = 0.72f, stiffness = 340f),
                                label = "lyric_scale_$idx",
                            )
                            Text(
                                text = line.text,
                                color = Color.White,
                                fontSize = if (isActive) 22.sp else 18.sp,
                                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                                lineHeight = 28.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        alpha = lineAlpha
                                        scaleX = lineScale
                                        scaleY = lineScale
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    }
                                    .clickable { onSeek(line.timeMs) },
                            )
                        }
                    }
                }

                lyrics.plain.isNotBlank() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                    ) {
                        Text(
                            text = lyrics.plain,
                            color = NoctaliaColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 25.sp,
                        )
                    }
                }

                else -> {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No se encontró letra sincronizada para esta canción.",
                            color = NoctaliaColors.TextMuted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
