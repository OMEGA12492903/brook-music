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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.noctalia.music.data.AlbumPageData
import com.noctalia.music.data.ArtistAlbumItem
import com.noctalia.music.data.ArtistPageData
import com.noctalia.music.data.RelatedArtistItem
import com.noctalia.music.data.Track
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.components.TrackCoverImage
import com.noctalia.music.ui.components.TrackRowItem
import com.noctalia.music.ui.components.bouncyClick
import com.noctalia.music.ui.theme.NoctaliaColors

/**
 * Pantalla completa de ficha de Artista (foto hero, Top canciones, Álbumes, Sencillos y Artistas similares)
 * y ficha de Álbum (lista completa numerada, reproducción en orden y descarga offline).
 */
@Composable
fun ArtistAlbumOverlay(
    artistPage: ArtistPageData?,
    loadingArtist: Boolean,
    albumPage: AlbumPageData?,
    loadingAlbum: Boolean,
    currentTrackId: String?,
    favoriteIds: Set<String>,
    onBack: () -> Unit,
    onPlayTrackList: (List<Track>, Int) -> Unit,
    onStartRadio: (Track) -> Unit,
    onAddQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onMoreTrackClick: (Track) -> Unit,
    onOpenAlbum: (ArtistAlbumItem, String) -> Unit,
    onOpenRelatedArtist: (RelatedArtistItem) -> Unit,
    onDownloadTracksOffline: (List<Track>, String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NoctaliaColors.BgDeep, NoctaliaColors.BgMid, NoctaliaColors.BgDeep)
                )
            ),
    ) {
        if (albumPage != null) {
            AlbumDetailView(
                album = albumPage,
                loading = loadingAlbum,
                currentTrackId = currentTrackId,
                favoriteIds = favoriteIds,
                onBack = onBack,
                onPlayTrackList = onPlayTrackList,
                onAddQueue = onAddQueue,
                onToggleFavorite = onToggleFavorite,
                onMoreTrackClick = onMoreTrackClick,
                onDownloadOffline = { onDownloadTracksOffline(albumPage.tracks, albumPage.title) },
            )
        } else if (artistPage != null) {
            ArtistDetailView(
                artist = artistPage,
                loading = loadingArtist,
                currentTrackId = currentTrackId,
                favoriteIds = favoriteIds,
                onBack = onBack,
                onPlayTrackList = onPlayTrackList,
                onStartRadio = onStartRadio,
                onAddQueue = onAddQueue,
                onToggleFavorite = onToggleFavorite,
                onMoreTrackClick = onMoreTrackClick,
                onOpenAlbum = { alb -> onOpenAlbum(alb, artistPage.name) },
                onOpenRelatedArtist = onOpenRelatedArtist,
            )
        }
    }
}

@Composable
private fun ArtistDetailView(
    artist: ArtistPageData,
    loading: Boolean,
    currentTrackId: String?,
    favoriteIds: Set<String>,
    onBack: () -> Unit,
    onPlayTrackList: (List<Track>, Int) -> Unit,
    onStartRadio: (Track) -> Unit,
    onAddQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onMoreTrackClick: (Track) -> Unit,
    onOpenAlbum: (ArtistAlbumItem) -> Unit,
    onOpenRelatedArtist: (RelatedArtistItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 172.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero banner del artista
        item(key = "artist_hero", contentType = "hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp),
            ) {
                if (artist.bannerThumb.isNotBlank()) {
                    AsyncImage(
                        model = artist.bannerThumb,
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0x5504080E),
                                    Color(0x8804080E),
                                    NoctaliaColors.BgDeep,
                                )
                            )
                        )
                )
                NoctaliaGlassCard(
                    cornerRadius = 99.dp,
                    strong = true,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp)
                        .bouncyClick(pressedScale = 0.88f, onClick = onBack),
                ) {
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Volver",
                            tint = NoctaliaColors.TextPrimary,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "ARTISTA",
                        color = NoctaliaColors.CyanGlow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.4.sp,
                    )
                    Text(
                        text = artist.name,
                        color = NoctaliaColors.TextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (artist.subscribers.isNotBlank()) {
                        Text(
                            text = artist.subscribers,
                            color = NoctaliaColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // Botones de acción (Reproducir todo + Radio del artista)
        if (artist.topSongs.isNotEmpty()) {
            item(key = "artist_actions", contentType = "actions") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { onPlayTrackList(artist.topSongs, 0) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NoctaliaColors.Cyan,
                            contentColor = NoctaliaColors.OnCyan,
                        ),
                        shape = RoundedCornerShape(99.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reproducir Top", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                    Button(
                        onClick = { onStartRadio(artist.topSongs.first()) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NoctaliaColors.GlassStrong,
                            contentColor = NoctaliaColors.CyanGlow,
                        ),
                        shape = RoundedCornerShape(99.dp),
                    ) {
                        Icon(Icons.Rounded.Radio, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Radio artista", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        if (loading) {
            item(key = "artist_loading", contentType = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = NoctaliaColors.CyanGlow, strokeWidth = 2.5.dp)
                }
            }
        }

        // Top Canciones
        if (artist.topSongs.isNotEmpty()) {
            item(key = "hdr_top_songs", contentType = "section_header") {
                Text(
                    text = "Canciones populares",
                    color = NoctaliaColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            itemsIndexed(
                items = artist.topSongs.take(12),
                key = { _, t -> "art_top_${t.id}" },
                contentType = { _, _ -> "track_row" },
            ) { idx, track ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TrackRowItem(
                        track = track,
                        isCurrent = currentTrackId == track.id,
                        isFavorite = track.id in favoriteIds,
                        onPlay = { onPlayTrackList(artist.topSongs, idx) },
                        onAddQueue = { onAddQueue(track) },
                        onToggleFavorite = { onToggleFavorite(track) },
                        onMoreClick = { onMoreTrackClick(track) },
                        indexBadge = "${idx + 1}",
                    )
                }
            }
        }

        // Álbumes
        if (artist.albums.isNotEmpty()) {
            item(key = "sec_albums", contentType = "album_carousel") {
                AlbumCarouselSection(
                    title = "Álbumes",
                    items = artist.albums,
                    onAlbumClick = onOpenAlbum,
                )
            }
        }

        // Sencillos y EPs
        if (artist.singles.isNotEmpty()) {
            item(key = "sec_singles", contentType = "album_carousel") {
                AlbumCarouselSection(
                    title = "Sencillos y EPs",
                    items = artist.singles,
                    onAlbumClick = onOpenAlbum,
                )
            }
        }

        // Artistas similares
        if (artist.relatedArtists.isNotEmpty()) {
            item(key = "sec_related", contentType = "related_carousel") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Artistas similares",
                        color = NoctaliaColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = artist.relatedArtists,
                            key = { "rel_${it.browseId}" },
                            contentType = { "related_artist" },
                        ) { rel ->
                            Column(
                                modifier = Modifier
                                    .width(104.dp)
                                    .bouncyClick(pressedScale = 0.94f) { onOpenRelatedArtist(rel) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                TrackCoverImage(
                                    url = rel.thumb,
                                    size = 92.dp,
                                    cornerRadius = 99.dp,
                                )
                                Text(
                                    text = rel.name,
                                    color = NoctaliaColors.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (rel.subtitle.isNotBlank()) {
                                    Text(
                                        text = rel.subtitle,
                                        color = NoctaliaColors.TextMuted,
                                        fontSize = 10.sp,
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

        if (artist.description.isNotBlank()) {
            item(key = "artist_bio", contentType = "bio") {
                NoctaliaGlassCard(
                    cornerRadius = 18.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Acerca de ${artist.name}",
                            color = NoctaliaColors.CyanGlow,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = artist.description,
                            color = NoctaliaColors.TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCarouselSection(
    title: String,
    items: List<ArtistAlbumItem>,
    onAlbumClick: (ArtistAlbumItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = NoctaliaColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = items,
                key = { "alb_${it.browseId}" },
                contentType = { "album_card" },
            ) { alb ->
                NoctaliaGlassCard(
                    cornerRadius = 16.dp,
                    modifier = Modifier
                        .width(142.dp)
                        .bouncyClick(pressedScale = 0.95f) { onAlbumClick(alb) },
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TrackCoverImage(url = alb.thumb, size = 122.dp, cornerRadius = 12.dp)
                        Text(
                            text = alb.title,
                            color = NoctaliaColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (alb.year.isNotBlank()) {
                            Text(
                                text = alb.year,
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

@Composable
private fun AlbumDetailView(
    album: AlbumPageData,
    loading: Boolean,
    currentTrackId: String?,
    favoriteIds: Set<String>,
    onBack: () -> Unit,
    onPlayTrackList: (List<Track>, Int) -> Unit,
    onAddQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onMoreTrackClick: (Track) -> Unit,
    onDownloadOffline: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "alb_top_bar", contentType = "top_bar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NoctaliaColors.GlassStrong)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver", tint = NoctaliaColors.TextPrimary)
                }
                Text(
                    text = "Álbum",
                    color = NoctaliaColors.CyanGlow,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        item(key = "alb_header", contentType = "album_header") {
            NoctaliaGlassCard(cornerRadius = 22.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TrackCoverImage(url = album.thumb, size = 180.dp, cornerRadius = 20.dp)
                    Text(
                        text = album.title,
                        color = NoctaliaColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(album.artist, album.year).filter { it.isNotBlank() }.joinToString(" · "),
                        color = NoctaliaColors.CyanGlow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (album.tracks.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { onPlayTrackList(album.tracks, 0) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NoctaliaColors.Cyan,
                                    contentColor = NoctaliaColors.OnCyan,
                                ),
                                shape = RoundedCornerShape(99.dp),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reproducir (${album.tracks.size})", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            }
                            Button(
                                onClick = onDownloadOffline,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NoctaliaColors.Glass,
                                    contentColor = NoctaliaColors.TextPrimary,
                                ),
                                shape = RoundedCornerShape(99.dp),
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Offline", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        if (loading) {
            item(key = "alb_loading", contentType = "loading") {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NoctaliaColors.CyanGlow, strokeWidth = 2.5.dp)
                }
            }
        }

        itemsIndexed(
            items = album.tracks,
            key = { idx, t -> "alb_tr_${idx}_${t.id}" },
            contentType = { _, _ -> "track_row" },
        ) { idx, track ->
            TrackRowItem(
                track = track,
                isCurrent = currentTrackId == track.id,
                isFavorite = track.id in favoriteIds,
                onPlay = { onPlayTrackList(album.tracks, idx) },
                onAddQueue = { onAddQueue(track) },
                onToggleFavorite = { onToggleFavorite(track) },
                onMoreClick = { onMoreTrackClick(track) },
                indexBadge = "${idx + 1}",
            )
        }

        item(key = "alb_spacer", contentType = "spacer") {
            Spacer(Modifier.height(172.dp))
        }
    }
}
