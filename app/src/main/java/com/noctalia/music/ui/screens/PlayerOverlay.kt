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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.noctalia.music.data.SecurityValidators
import com.noctalia.music.data.Track
import com.noctalia.music.player.PlayerUiState
import com.noctalia.music.player.RepeatMode
import com.noctalia.music.ui.components.NoctaliaBackground
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.components.NoctaliaMiniEqualizer
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
                        text = if (player.isLoading) "Cargando stream Opus..." else track.artist,
                        color = if (player.isLoading) NoctaliaColors.CyanGlow else NoctaliaColors.TextMuted,
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
            // Barra de progreso fina cian en el borde inferior
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
    isFavorite: Boolean,
    favoriteIds: Set<String>,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onToggleAutoRadio: (Boolean) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onClearQueue: () -> Unit,
) {
    val track = player.currentTrack ?: return
    var showQueue by remember { mutableStateOf(false) }

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
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Barra superior: Minimizar | Noctalia Music + calidad | Cola
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
                    Text(
                        text = "${player.qualityLabel} · Sin anuncios",
                        color = NoctaliaColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                NoctaliaGlassCard(cornerRadius = 99.dp, highlighted = showQueue) {
                    IconButton(onClick = { showQueue = !showQueue }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "Cola",
                            tint = if (showQueue) NoctaliaColors.CyanGlow else NoctaliaColors.TextPrimary,
                        )
                    }
                }
            }

            if (showQueue) {
                // Vista de Cola + Auto-Radio
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 14.dp),
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
                                Text(
                                    text = "Auto-Radio infinita",
                                    color = NoctaliaColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Añade canciones similares al terminar",
                                    color = NoctaliaColors.TextMuted,
                                    fontSize = 12.sp,
                                )
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
                        Text(
                            text = "A continuación (${player.queue.size})",
                            color = NoctaliaColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
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
                        contentPadding = PaddingValues(bottom = 16.dp),
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
            } else {
                // Carátula grande con borde cian Noctalia
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    TrackCoverImage(
                        url = track.thumb,
                        size = 286.dp,
                        cornerRadius = 28.dp,
                    )
                }

                // Título, artista y corazón de favorita
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = NoctaliaColors.TextPrimary,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = track.artist,
                            color = NoctaliaColors.TextMuted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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

                if (player.errorMsg != null) {
                    Text(
                        text = player.errorMsg,
                        color = NoctaliaColors.Danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Onda sinusoidal cian interactiva de Noctalia
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

                Spacer(Modifier.height(14.dp))

                // Controles principales: 🔀  ⏮  [ ⏸ ]  ⏭  🔁
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
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
                    IconButton(onClick = onPrev, modifier = Modifier.size(50.dp)) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Anterior",
                            tint = NoctaliaColors.TextPrimary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 78.dp, height = 58.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(
                                Brush.linearGradient(listOf(NoctaliaColors.CyanGlow, NoctaliaColors.Cyan))
                            )
                            .clickable(onClick = onPlayPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (player.isLoading) {
                            CircularProgressIndicator(
                                color = NoctaliaColors.OnCyan,
                                strokeWidth = 2.8.dp,
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            Icon(
                                imageVector = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Reproducir / Pausar",
                                tint = NoctaliaColors.OnCyan,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(50.dp)) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Siguiente",
                            tint = NoctaliaColors.TextPrimary,
                            modifier = Modifier.size(34.dp),
                        )
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
    }
}
