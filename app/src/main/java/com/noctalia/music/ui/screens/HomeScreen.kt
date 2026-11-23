package com.noctalia.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noctalia.music.data.ArtistMixCard
import com.noctalia.music.data.LibraryPayload
import com.noctalia.music.data.RemotePlaylist
import com.noctalia.music.data.Track
import com.noctalia.music.player.PlayerUiState
import com.noctalia.music.ui.MainUiState
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.components.NoctaliaPillChip
import com.noctalia.music.ui.components.TrackCoverImage
import com.noctalia.music.ui.components.TrackListShimmer
import com.noctalia.music.ui.components.TrackRowItem
import com.noctalia.music.ui.components.bouncyClick
import com.noctalia.music.ui.theme.NoctaliaColors
import java.util.Calendar

private val GENRE_SHORTCUTS = listOf(
    "Urbano" to "reggaeton urbano hits",
    "Phonk" to "phonk drift bass",
    "Lo-Fi" to "lofi chill beats",
    "Rock" to "rock classics hits",
    "Electrónica" to "electronic dance hits",
)

@Composable
fun HomeScreen(
    ui: MainUiState,
    player: PlayerUiState,
    library: LibraryPayload,
    onGenreClick: (String) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayTrackList: (List<Track>, Int) -> Unit,
    onPlayArtistMix: (ArtistMixCard) -> Unit,
    onAddQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onMoreTrackClick: (Track) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenRemotePlaylist: (RemotePlaylist) -> Unit,
    onRetryHome: () -> Unit,
) {
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 6..12 -> "Buenos días"
            in 13..20 -> "Buenas tardes"
            else -> "Buenas noches"
        }
    }

    val quickTracks = remember(library.recent, ui.accountHistory, ui.homeTracks) {
        when {
            library.recent.isNotEmpty() -> library.recent.take(4)
            ui.accountHistory.isNotEmpty() -> ui.accountHistory.take(4)
            else -> ui.homeTracks.take(4)
        }
    }

    val favIds = remember(library.favorites) { library.favorites.map { it.id }.toSet() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 164.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Cabecera estilo Noctalia + estado cuenta YT Music + badge calidad
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "BROOK",
                        color = NoctaliaColors.Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.6.sp,
                    )
                    Text(
                        text = greeting,
                        color = NoctaliaColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }

                NoctaliaGlassCard(
                    cornerRadius = 99.dp,
                    modifier = Modifier.bouncyClick(pressedScale = 0.94f) { onOpenLogin() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (ui.isLoggedIn) Icons.Rounded.CheckCircle else Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            tint = if (ui.isLoggedIn) NoctaliaColors.CyanGlow else NoctaliaColors.Magenta,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (ui.isLoggedIn) "YT Conectado" else "Conectar YT",
                            color = NoctaliaColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // Chips rápidos de géneros
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(GENRE_SHORTCUTS) { (label, query) ->
                    NoctaliaPillChip(
                        label = label,
                        active = false,
                        onClick = { onGenreClick(query) },
                    )
                }
            }
        }

        // Grid rápido 2x2 estilo Spotify ("Vuelve a escuchar")
        if (quickTracks.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (library.recent.isNotEmpty()) "Vuelve a escuchar" else "Acceso rápido",
                        color = NoctaliaColors.TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    quickTracks.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            pair.forEach { track ->
                                NoctaliaGlassCard(
                                    cornerRadius = 14.dp,
                                    highlighted = player.currentTrack?.id == track.id,
                                    modifier = Modifier
                                        .weight(1f)
                                        .bouncyClick(pressedScale = 0.96f) { onPlayTrack(track) },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TrackCoverImage(url = track.thumb, size = 44.dp, cornerRadius = 10.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = track.title,
                                            color = NoctaliaColors.TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Carrusel «Hecho para ti» (Tu Mix Diario, Descubrimiento y Mixes de tus artistas)
        val recs = ui.homeRecommendations
        if (recs.personalized && (recs.forYouTracks.isNotEmpty() || recs.artistMixes.isNotEmpty())) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = NoctaliaColors.CyanGlow,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Hecho para ti",
                                color = NoctaliaColors.TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        Text(
                            text = "Aleatorio inteligente activo",
                            color = NoctaliaColors.Cyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (recs.forYouTracks.isNotEmpty()) {
                            item(key = "mix_daily") {
                                NoctaliaGlassCard(
                                    cornerRadius = 18.dp,
                                    highlighted = true,
                                    modifier = Modifier
                                        .width(142.dp)
                                        .bouncyClick(pressedScale = 0.96f) {
                                            onPlayTrackList(recs.forYouTracks, 0)
                                        },
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        TrackCoverImage(
                                            url = recs.forYouTracks.first().thumb,
                                            size = 126.dp,
                                            cornerRadius = 12.dp,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "Tu Mix Diario",
                                            color = NoctaliaColors.CyanGlow,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${recs.forYouTracks.size} temas a tu estilo",
                                            color = NoctaliaColors.TextMuted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        if (recs.discoverTracks.isNotEmpty()) {
                            item(key = "mix_discover") {
                                NoctaliaGlassCard(
                                    cornerRadius = 18.dp,
                                    modifier = Modifier
                                        .width(142.dp)
                                        .bouncyClick(pressedScale = 0.96f) {
                                            onPlayTrackList(recs.discoverTracks, 0)
                                        },
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        TrackCoverImage(
                                            url = recs.discoverTracks.first().thumb,
                                            size = 126.dp,
                                            cornerRadius = 12.dp,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "Descubrimiento",
                                            color = NoctaliaColors.Magenta,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${recs.discoverTracks.size} canciones nuevas",
                                            color = NoctaliaColors.TextMuted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        items(recs.artistMixes, key = { "artmix_${it.artistName}" }) { card ->
                            NoctaliaGlassCard(
                                cornerRadius = 18.dp,
                                modifier = Modifier
                                    .width(142.dp)
                                    .bouncyClick(pressedScale = 0.96f) { onPlayArtistMix(card) },
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    TrackCoverImage(url = card.coverUrl, size = 126.dp, cornerRadius = 12.dp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Mix de ${card.artistName}",
                                        color = NoctaliaColors.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = card.subtitle,
                                        color = NoctaliaColors.TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Carrusel «Porque escuchas a [Artista]»
        if (recs.becauseTitle.isNotEmpty() && recs.becauseTracks.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = recs.becauseTitle,
                        color = NoctaliaColors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recs.becauseTracks, key = { "bec_${it.id}" }) { track ->
                            NoctaliaGlassCard(
                                cornerRadius = 16.dp,
                                highlighted = player.currentTrack?.id == track.id,
                                modifier = Modifier
                                    .width(132.dp)
                                    .bouncyClick(pressedScale = 0.96f) { onPlayTrack(track) },
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    TrackCoverImage(url = track.thumb, size = 116.dp, cornerRadius = 11.dp)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = track.title,
                                        color = NoctaliaColors.TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = track.artist,
                                        color = NoctaliaColors.TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Carrusel de playlists de tu cuenta de YouTube Music (si iniciaste sesión)
        if (ui.isLoggedIn && ui.accountPlaylists.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Tu YouTube Music",
                            color = NoctaliaColors.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = "Sin anuncios",
                            color = NoctaliaColors.Magenta,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ui.accountPlaylists) { pl ->
                            NoctaliaGlassCard(
                                cornerRadius = 18.dp,
                                modifier = Modifier
                                    .width(136.dp)
                                    .clickable { onOpenRemotePlaylist(pl) },
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    TrackCoverImage(url = pl.thumb, size = 120.dp, cornerRadius = 12.dp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = pl.title,
                                        color = NoctaliaColors.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = pl.subtitle,
                                        color = NoctaliaColors.TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Lista principal: Recomendados personalizados para ti (o tendencias si aún no hay historial)
        val feedTracks = if (recs.forYouTracks.isNotEmpty()) {
            recs.forYouTracks
        } else if (ui.accountHistory.isNotEmpty()) {
            ui.accountHistory
        } else {
            ui.homeTracks
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (recs.personalized) "Recomendados para ti" else "Tendencias · Opus sin anuncios",
                    color = NoctaliaColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (feedTracks.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .clickable { onPlayTrackList(feedTracks, 0) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "Reproducir todo",
                                tint = NoctaliaColors.CyanGlow,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Todo",
                                color = NoctaliaColors.CyanGlow,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .clickable(enabled = !ui.loadingHome, onClick = onRetryHome)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Actualizar recomendaciones",
                            tint = NoctaliaColors.TextMuted,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = if (ui.loadingHome) "..." else "Renovar",
                            color = NoctaliaColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        if (ui.loadingHome && feedTracks.isEmpty()) {
            item {
                TrackListShimmer(count = 6)
            }
        } else if (feedTracks.isEmpty()) {
            item {
                NoctaliaGlassCard(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Sin conexión o sin resultados ahora mismo.",
                            color = NoctaliaColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Puedes escuchar tus canciones guardadas en Listas o reintentar cuando tengas red.",
                            color = NoctaliaColors.TextMuted,
                            fontSize = 12.sp,
                        )
                        NoctaliaPillChip(
                            label = "Reintentar",
                            active = true,
                            onClick = onRetryHome,
                        )
                    }
                }
            }
        } else {
            items(feedTracks, key = { "home_${it.id}" }) { track ->
                TrackRowItem(
                    track = track,
                    isCurrent = player.currentTrack?.id == track.id,
                    isFavorite = track.id in favIds,
                    onPlay = { onPlayTrack(track) },
                    onAddQueue = { onAddQueue(track) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    onMoreClick = { onMoreTrackClick(track) },
                )
            }
        }
    }
}
