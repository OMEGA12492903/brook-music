package com.noctalia.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.noctalia.music.ui.components.NoctaliaBackground
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.components.NoctaliaMiniEqualizer
import com.noctalia.music.ui.components.NoctaliaPillChip
import com.noctalia.music.ui.components.NoctaliaWaveSeekBar
import com.noctalia.music.ui.components.TrackCoverImage
import com.noctalia.music.ui.components.TrackRowItem
import com.noctalia.music.ui.theme.NoctaliaColors

@Composable
fun MiniPlayerBar(
    player: PlayerUiState,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = player.currentTrack ?: return
    val frac = if (player.durationMs > 0L) (player.positionMs.toFloat() / player.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    NoctaliaGlassCard(
        cornerRadius = 20.dp,
        strong = true,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackCoverImage(url = track.thumb, size = 44.dp, cornerRadius = 11.dp)
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
                        text = when {
                            player.isLoading -> "Cargando audio..."
                            !player.errorMsg.isNullOrBlank() -> player.errorMsg
                            else -> track.artist
                        },
                        color = when {
                            player.isLoading -> NoctaliaColors.CyanGlow
                            !player.errorMsg.isNullOrBlank() -> NoctaliaColors.Magenta
                            else -> NoctaliaColors.TextMuted
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                NoctaliaMiniEqualizer(isPlaying = player.isPlaying)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(NoctaliaColors.Cyan)
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    if (player.isLoading) {
                        CircularProgressIndicator(
                            color = NoctaliaColors.OnCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            imageVector = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = NoctaliaColors.OnCyan,
                            modifier = Modifier.size(22.dp),
                        )
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
                    .background(Color.White.copy(alpha = 0.14f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
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
    isFavorite: Boolean,
    favoriteIds: Set<String>,
    onCollapse: () -> Unit,
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

    NoctaliaBackground(
        modifier = Modifier
            .fillMaxSize()
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
            // Barra superior: Minimizar | BROOK + calidad + timer | Botón Offline
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NoctaliaGlassCard(cornerRadius = 99.dp) {
                    IconButton(onClick = onCollapse, modifier = Modifier.size(40.dp)) {
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

                NoctaliaGlassCard(cornerRadius = 99.dp, highlighted = isCached) {
                    IconButton(
                        onClick = { onToggleOffline(track) },
                        enabled = !isDownloading,
                        modifier = Modifier.size(40.dp),
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                color = NoctaliaColors.CyanGlow,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                imageVector = if (isCached) Icons.Rounded.DownloadDone else Icons.Rounded.DownloadForOffline,
                                contentDescription = "Guardar sin conexión",
                                tint = if (isCached) NoctaliaColors.CyanGlow else NoctaliaColors.TextPrimary,
                            )
                        }
                    }
                }
            }

            // Contenido central según pestaña activa
            when (activeSheet) {
                "lyrics" -> {
                    LyricsPanel(
                        player = player,
                        onSeek = onSeek,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 12.dp),
                    )
                }

                "queue" -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
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
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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
                            .weight(1f)
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
                    // Vista principal del reproductor
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        TrackCoverImage(
                            url = track.thumb,
                            size = 266.dp,
                            cornerRadius = 26.dp,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = NoctaliaColors.TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${track.artist} ▸",
                                color = NoctaliaColors.Cyan,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onArtistClick(track.artist) },
                            )
                        }
                        NoctaliaGlassCard(cornerRadius = 99.dp, highlighted = isFavorite) {
                            IconButton(onClick = { onToggleFavorite(track) }, modifier = Modifier.size(44.dp)) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Favorita",
                                    tint = if (isFavorite) NoctaliaColors.CyanGlow else NoctaliaColors.TextPrimary,
                                )
                            }
                        }
                    }

                    if (player.sponsorSkippedNotice != null) {
                        Text(
                            text = player.sponsorSkippedNotice,
                            color = NoctaliaColors.Magenta,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    if (player.errorMsg != null) {
                        Text(
                            text = player.errorMsg,
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
                        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(44.dp)) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = "Aleatorio",
                                tint = if (player.shuffle) NoctaliaColors.CyanGlow else NoctaliaColors.TextMuted,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        IconButton(onClick = onPrev, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Anterior", tint = NoctaliaColors.TextPrimary, modifier = Modifier.size(34.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(width = 76.dp, height = 56.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(Brush.linearGradient(listOf(NoctaliaColors.CyanGlow, NoctaliaColors.Cyan)))
                                .clickable(onClick = onPlayPause),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (player.isLoading) {
                                CircularProgressIndicator(color = NoctaliaColors.OnCyan, strokeWidth = 2.8.dp, modifier = Modifier.size(24.dp))
                            } else {
                                Icon(
                                    imageVector = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = "Reproducir / Pausar",
                                    tint = NoctaliaColors.OnCyan,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                        }
                        IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Siguiente", tint = NoctaliaColors.TextPrimary, modifier = Modifier.size(34.dp))
                        }
                        IconButton(onClick = onCycleRepeat, modifier = Modifier.size(44.dp)) {
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

            // Barra inferior de 4 pestañas rápidas: Letra | Cola | EQ | Timer
            Spacer(Modifier.height(8.dp))
            NoctaliaGlassCard(cornerRadius = 99.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(5.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf(
                        "lyrics" to "Letra",
                        "queue" to "Cola",
                        "audio" to "EQ",
                        "timer" to "Timer",
                    ).forEach { (key, label) ->
                        val active = activeSheet == key
                        NoctaliaPillChip(
                            label = label,
                            active = active,
                            onClick = { activeSheet = if (active) null else key },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsPanel(
    player: PlayerUiState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
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

    NoctaliaGlassCard(cornerRadius = 22.dp, strong = true, modifier = modifier.fillMaxWidth()) {
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
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(lyrics.synced) { idx, line ->
                        val isActive = idx == activeIdx
                        Text(
                            text = line.text,
                            color = when {
                                isActive -> NoctaliaColors.CyanGlow
                                idx < activeIdx -> NoctaliaColors.TextDim
                                else -> NoctaliaColors.TextPrimary.copy(alpha = 0.82f)
                            },
                            fontSize = if (isActive) 20.sp else 16.sp,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                            lineHeight = 25.sp,
                            modifier = Modifier
                                .fillMaxWidth()
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
                        fontSize = 15.sp,
                        lineHeight = 23.sp,
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
