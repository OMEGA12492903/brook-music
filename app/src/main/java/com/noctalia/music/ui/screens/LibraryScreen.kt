package com.noctalia.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import com.noctalia.music.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noctalia.music.data.CloudSyncState
import com.noctalia.music.data.GitHubCloudSync
import com.noctalia.music.data.LibraryPayload
import com.noctalia.music.data.LocalAudioState
import com.noctalia.music.data.OfflineState
import com.noctalia.music.data.RemotePlaylist
import com.noctalia.music.data.SpotifyRemotePlaylist
import com.noctalia.music.data.Track
import com.noctalia.music.player.PlayerUiState
import com.noctalia.music.ui.MainUiState
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.components.NoctaliaPillChip
import com.noctalia.music.ui.components.PlayerBgStyle
import com.noctalia.music.ui.components.TrackCoverImage
import com.noctalia.music.ui.components.TrackRowItem
import com.noctalia.music.ui.theme.NoctaliaColors

@Composable
fun LibraryScreen(
    ui: MainUiState,
    player: PlayerUiState,
    library: LibraryPayload,
    localAudio: LocalAudioState,
    offline: OfflineState,
    cloudSync: CloudSyncState,
    activeTab: String,
    onTabChange: (String) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayTrackList: (List<Track>, Int) -> Unit,
    onAddQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onMoreTrackClick: (Track) -> Unit,
    onCacheTracksOffline: (List<Track>, String) -> Unit,
    onClearOfflineCache: () -> Unit,
    onToggleAutoCacheWifi: (Boolean) -> Unit,
    onSelectAutoCacheMaxMb: (Int) -> Unit,
    onToggleAutoCacheFavorites: (Boolean) -> Unit,
    onRequestLocalAudioPermission: () -> Unit,
    onRefreshLocalAudio: () -> Unit,
    onOpenWrapped: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onSaveCurrentToPlaylist: (String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
    onRefreshAccount: () -> Unit,
    onOpenRemotePlaylist: (RemotePlaylist) -> Unit,
    onCloseRemotePlaylist: () -> Unit,
    onImportRemotePlaylist: (RemotePlaylist, List<Track>) -> Unit,
    onImportPlaylistUrl: (String) -> Unit,
    onExportJsonClick: () -> Unit,
    onImportJsonClick: () -> Unit,
    onSelectBgStyle: (PlayerBgStyle) -> Unit,
    onToggleBgUseCoverColors: (Boolean) -> Unit,
    onToggleDynamicAppTheme: (Boolean) -> Unit,
    onOpenArtistFromStats: (String) -> Unit,
    onClearStats: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onOpenReleases: () -> Unit,
    onSaveCloudToken: (String) -> Unit,
    onSyncCloudNow: () -> Unit,
    onRestoreCloud: () -> Unit,
    onToggleCloudAutoSync: (Boolean) -> Unit,
    onDisconnectCloud: () -> Unit,
    onOpenCreateTokenPage: () -> Unit,
    onOpenSpotifyLogin: () -> Unit,
    onLogoutSpotify: () -> Unit,
    onRefreshSpotify: () -> Unit,
    onImportSpotifyUrl: (String) -> Unit,
    onImportSpotifyRemotePlaylist: (SpotifyRemotePlaylist) -> Unit,
    onImportSpotifyCsvClick: () -> Unit,
    onCancelSpotifyImport: () -> Unit,
) {
    var selectedLocalPlaylist by remember { mutableStateOf<String?>(null) }
    var showNewPlInput by remember { mutableStateOf(false) }
    var newPlName by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var spotifyUrlInput by remember { mutableStateOf("") }
    var cloudTokenInput by remember { mutableStateOf("") }
    var localFilterQuery by remember { mutableStateOf("") }

    val favIds = remember(library.favorites) { library.favorites.map { it.id }.toSet() }

    // Si hay una playlist remota de YouTube Music abierta, mostrar su detalle directamente
    val remotePl = ui.selectedRemotePlaylist
    if (remotePl != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCloseRemotePlaylist) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver", tint = NoctaliaColors.TextPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = remotePl.title,
                        color = NoctaliaColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${ui.selectedRemoteTracks.size} canciones · Tu cuenta YouTube Music",
                        color = NoctaliaColors.Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (ui.selectedRemoteTracks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPlayTrackList(ui.selectedRemoteTracks, 0) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NoctaliaColors.Cyan,
                            contentColor = NoctaliaColors.OnCyan,
                        ),
                        shape = RoundedCornerShape(99.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reproducir todo", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                    NoctaliaPillChip(
                        label = "Guardar en local",
                        active = false,
                        onClick = { onImportRemotePlaylist(remotePl, ui.selectedRemoteTracks) },
                    )
                }
            }

            if (ui.loadingAccount && ui.selectedRemoteTracks.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = NoctaliaColors.CyanGlow, strokeWidth = 2.5.dp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 164.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ui.selectedRemoteTracks, key = { "rem_${it.id}" }) { track ->
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
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tu Biblioteca",
                color = NoctaliaColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            if (activeTab == "playlists") {
                NoctaliaPillChip(
                    label = "+ Nueva",
                    active = showNewPlInput,
                    onClick = { showNewPlInput = !showNewPlInput },
                )
            }
        }

        // Pestañas segmentadas estilo Noctalia
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                NoctaliaPillChip(
                    label = "Favoritas (${library.favorites.size})",
                    active = activeTab == "favs",
                    onClick = { onTabChange("favs") },
                )
            }
            item {
                NoctaliaPillChip(
                    label = "Playlists",
                    active = activeTab == "playlists",
                    onClick = { onTabChange("playlists") },
                )
            }
            item {
                NoctaliaPillChip(
                    label = if (localAudio.tracks.isNotEmpty()) "Locales (${localAudio.tracks.size})" else "Locales",
                    active = activeTab == "local",
                    onClick = { onTabChange("local") },
                )
            }
            item {
                NoctaliaPillChip(
                    label = "Stats",
                    active = activeTab == "stats",
                    onClick = { onTabChange("stats") },
                )
            }
            item {
                NoctaliaPillChip(
                    label = if (ui.isLoggedIn) "Cuenta YT ✓" else "Cuenta YT",
                    active = activeTab == "account",
                    onClick = { onTabChange("account") },
                )
            }
            item {
                NoctaliaPillChip(
                    label = if (ui.isSpotifyLoggedIn) "Spotify ✓" else "Spotify",
                    active = activeTab == "spotify",
                    onClick = { onTabChange("spotify") },
                )
            }
            item {
                NoctaliaPillChip(
                    label = "Ajustes",
                    active = activeTab == "sync",
                    onClick = { onTabChange("sync") },
                )
            }
        }

        if (ui.spotifyImport.active) {
            NoctaliaGlassCard(cornerRadius = 16.dp, highlighted = true, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (ui.spotifyImport.total > 0) {
                                "Importando «${ui.spotifyImport.title}» (${ui.spotifyImport.matched}/${ui.spotifyImport.total})"
                            } else {
                                "Leyendo «${ui.spotifyImport.title}» desde Spotify..."
                            },
                            color = NoctaliaColors.CyanGlow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Cancelar",
                            color = NoctaliaColors.Danger,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onCancelSpotifyImport),
                        )
                    }
                    if (ui.spotifyImport.total > 0) {
                        val prog = (ui.spotifyImport.matched.toFloat() / ui.spotifyImport.total.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { prog },
                            modifier = Modifier.fillMaxWidth(),
                            color = NoctaliaColors.CyanGlow,
                            trackColor = Color.White.copy(alpha = 0.14f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = NoctaliaColors.CyanGlow,
                            trackColor = Color.White.copy(alpha = 0.14f),
                        )
                    }
                }
            }
        }

        val libTabOrder = mapOf("favs" to 0, "playlists" to 1, "local" to 2, "stats" to 3, "account" to 4, "spotify" to 5, "sync" to 6)
        AnimatedContent(
            targetState = activeTab,
            transitionSpec = {
                val forward = (libTabOrder[targetState] ?: 0) >= (libTabOrder[initialState] ?: 0)
                val enter = slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) {
                    if (forward) it / 8 else -it / 8
                } + fadeIn(tween(200))
                val exit = slideOutHorizontally(tween(180)) {
                    if (forward) -it / 10 else it / 10
                } + fadeOut(tween(150))
                enter togetherWith exit
            },
            modifier = Modifier.weight(1f),
            label = "library_tab_switch",
        ) { currentTab ->
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        when (currentTab) {
            "favs" -> {
                if (library.favorites.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { onPlayTrackList(library.favorites, 0) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NoctaliaColors.Cyan,
                                contentColor = NoctaliaColors.OnCyan,
                            ),
                            shape = RoundedCornerShape(99.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reproducir Favoritas", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        NoctaliaPillChip(
                            label = "Modo sin conexión",
                            active = false,
                            onClick = { onCacheTracksOffline(library.favorites, "Favoritas") },
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 164.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (library.favorites.isEmpty()) {
                        item {
                            Text(
                                text = "Aún no tienes canciones favoritas. Toca el corazón en cualquier canción o importa tus Me gusta desde «Cuenta YT».",
                                color = NoctaliaColors.TextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 20.dp),
                            )
                        }
                    } else {
                        items(library.favorites, key = { "fav_${it.id}" }) { track ->
                            TrackRowItem(
                                track = track,
                                isCurrent = player.currentTrack?.id == track.id,
                                isFavorite = true,
                                onPlay = { onPlayTrack(track) },
                                onAddQueue = { onAddQueue(track) },
                                onToggleFavorite = { onToggleFavorite(track) },
                                onMoreClick = { onMoreTrackClick(track) },
                            )
                        }
                    }
                }
            }

            "playlists" -> {
                if (showNewPlInput) {
                    NoctaliaGlassCard(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = newPlName,
                                onValueChange = { newPlName = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("Nombre de la lista...", fontSize = 13.sp, color = NoctaliaColors.TextDim) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NoctaliaColors.CyanGlow,
                                    unfocusedBorderColor = NoctaliaColors.BorderCyan,
                                    focusedTextColor = NoctaliaColors.TextPrimary,
                                    unfocusedTextColor = NoctaliaColors.TextPrimary,
                                ),
                            )
                            Button(
                                onClick = {
                                    if (newPlName.isNotBlank()) {
                                        onCreatePlaylist(newPlName)
                                        selectedLocalPlaylist = newPlName.trim()
                                        newPlName = ""
                                        showNewPlInput = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NoctaliaColors.Cyan,
                                    contentColor = NoctaliaColors.OnCyan,
                                ),
                            ) {
                                Text("Crear", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                val plNames = library.playlists.keys.toList()
                val activePl = selectedLocalPlaylist?.takeIf { library.playlists.containsKey(it) } ?: plNames.firstOrNull()

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(plNames) { name ->
                        NoctaliaPillChip(
                            label = "$name (${library.playlists[name]?.size ?: 0})",
                            active = name == activePl,
                            onClick = { selectedLocalPlaylist = name },
                        )
                    }
                }

                if (activePl != null) {
                    val tracks = library.playlists[activePl].orEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tracks.isNotEmpty()) {
                            NoctaliaPillChip(
                                label = "▶ Reproducir",
                                active = true,
                                onClick = { onPlayTrackList(tracks, 0) },
                            )
                        }
                        if (player.currentTrack != null) {
                            NoctaliaPillChip(
                                label = "+ Guardar actual",
                                active = false,
                                onClick = { onSaveCurrentToPlaylist(activePl) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onDeletePlaylist(activePl) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Borrar lista", tint = NoctaliaColors.Danger)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 164.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (tracks.isEmpty()) {
                            item {
                                Text(
                                    text = "Lista vacía. Reproduce una canción y pulsa «+ Guardar actual».",
                                    color = NoctaliaColors.TextMuted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 18.dp),
                                )
                            }
                        } else {
                            items(tracks, key = { "pl_${activePl}_${it.id}" }) { track ->
                                TrackRowItem(
                                    track = track,
                                    isCurrent = player.currentTrack?.id == track.id,
                                    isFavorite = track.id in favIds,
                                    onPlay = { onPlayTrack(track) },
                                    onAddQueue = { onAddQueue(track) },
                                    onRemove = { onRemoveFromPlaylist(activePl, track.id) },
                                )
                            }
                        }
                    }
                }
            }

            "local" -> {
                if (!localAudio.hasPermission) {
                    NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Música local del móvil",
                                color = NoctaliaColors.CyanGlow,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Text(
                                text = "Reproduce tus archivos MP3, FLAC, M4A, OGG o WAV almacenados en tu teléfono mezclados en las mismas playlists, cola, EQ y Android Auto de Brook.",
                                color = NoctaliaColors.TextMuted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                            Button(
                                onClick = onRequestLocalAudioPermission,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NoctaliaColors.Cyan,
                                    contentColor = NoctaliaColors.OnCyan,
                                ),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("Conceder permiso y escanear", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                } else {
                    val filteredLocal = remember(localAudio.tracks, localFilterQuery) {
                        val q = localFilterQuery.trim()
                        if (q.isEmpty()) localAudio.tracks
                        else localAudio.tracks.filter {
                            it.title.contains(q, ignoreCase = true) ||
                                it.artist.contains(q, ignoreCase = true) ||
                                it.album.contains(q, ignoreCase = true)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { if (filteredLocal.isNotEmpty()) onPlayTrackList(filteredLocal, 0) },
                            enabled = filteredLocal.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NoctaliaColors.Cyan,
                                contentColor = NoctaliaColors.OnCyan,
                            ),
                            shape = RoundedCornerShape(99.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reproducir (${filteredLocal.size})", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        Button(
                            onClick = onRefreshLocalAudio,
                            enabled = !localAudio.scanning,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NoctaliaColors.Glass,
                                contentColor = NoctaliaColors.TextPrimary,
                            ),
                            shape = RoundedCornerShape(99.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (localAudio.scanning) "Escaneando..." else "Actualizar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (localAudio.tracks.size > 6) {
                        OutlinedTextField(
                            value = localFilterQuery,
                            onValueChange = { localFilterQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Filtrar archivos locales...", fontSize = 12.sp, color = NoctaliaColors.TextDim) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NoctaliaColors.CyanGlow,
                                unfocusedBorderColor = NoctaliaColors.BorderCyan,
                                focusedTextColor = NoctaliaColors.TextPrimary,
                                unfocusedTextColor = NoctaliaColors.TextPrimary,
                            ),
                        )
                    }

                    if (localAudio.scanning && localAudio.tracks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = NoctaliaColors.CyanGlow, strokeWidth = 2.5.dp)
                        }
                    } else if (filteredLocal.isEmpty()) {
                        Text(
                            text = if (localAudio.tracks.isEmpty()) {
                                "No se encontraron archivos de música (MP3/FLAC/M4A) en el dispositivo."
                            } else {
                                "Sin coincidencias para «$localFilterQuery»."
                            },
                            color = NoctaliaColors.TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 164.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(
                                items = filteredLocal,
                                key = { _, t -> "loc_${t.id}" },
                                contentType = { _, _ -> "local_track" },
                            ) { idx, track ->
                                TrackRowItem(
                                    track = track,
                                    isCurrent = player.currentTrack?.id == track.id,
                                    isFavorite = track.id in favIds,
                                    onPlay = { onPlayTrackList(filteredLocal, idx) },
                                    onAddQueue = { onAddQueue(track) },
                                    onToggleFavorite = { onToggleFavorite(track) },
                                    onMoreClick = { onMoreTrackClick(track) },
                                )
                            }
                        }
                    }
                }
            }

            "stats" -> {
                val allStats = remember(library.stats) {
                    library.stats.values.sortedByDescending { it.listenedSec * 2 + it.playCount * 35L }
                }
                val totalSec = remember(allStats) { allStats.sumOf { it.listenedSec } }
                val totalPlays = remember(allStats) { allStats.sumOf { it.playCount } }
                val topArtists = remember(allStats) {
                    allStats
                        .groupBy { it.track.artist.substringBefore(",").trim().ifEmpty { "Artista" } }
                        .map { (artist, list) ->
                            Triple(artist, list.sumOf { it.listenedSec }, list.sumOf { it.playCount })
                        }
                        .sortedByDescending { it.second * 2 + it.third * 35L }
                        .take(8)
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 164.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Brook Stats",
                                        color = NoctaliaColors.CyanGlow,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                    Row(
                                        modifier = Modifier
                                            .clickable { onTabChange("sync") }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (cloudSync.configured && cloudSync.lastError == null) {
                                                Icons.Rounded.CloudDone
                                            } else {
                                                Icons.Rounded.CloudSync
                                            },
                                            contentDescription = null,
                                            tint = when {
                                                cloudSync.lastError != null -> NoctaliaColors.Danger
                                                cloudSync.configured -> NoctaliaColors.CyanGlow
                                                else -> NoctaliaColors.TextMuted
                                            },
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = when {
                                                !cloudSync.configured -> "Activar copia GitHub →"
                                                cloudSync.syncing -> "Sincronizando..."
                                                cloudSync.lastError != null -> "Error copia · Revisar"
                                                cloudSync.lastSyncTimeText.isNotBlank() -> "GitHub · ${cloudSync.lastSyncTimeText}"
                                                else -> "GitHub conectado"
                                            },
                                            color = when {
                                                cloudSync.lastError != null -> NoctaliaColors.Danger
                                                cloudSync.configured -> NoctaliaColors.Cyan
                                                else -> NoctaliaColors.TextMuted
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                val mins = totalSec / 60L
                                val timeLabel = if (mins >= 60L) "${mins / 60L}h ${mins % 60L}m" else "${mins} min"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column {
                                        Text(timeLabel, color = NoctaliaColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                                        Text("Tiempo escuchado", color = NoctaliaColors.TextMuted, fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("$totalPlays", color = NoctaliaColors.CyanGlow, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                                        Text("Reproducciones", color = NoctaliaColors.TextMuted, fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("${topArtists.size}", color = NoctaliaColors.Magenta, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                                        Text("Top artistas", color = NoctaliaColors.TextMuted, fontSize = 11.sp)
                                    }
                                }
                                if (allStats.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Button(
                                            onClick = onOpenWrapped,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Magenta,
                                                contentColor = NoctaliaColors.OnMagenta,
                                            ),
                                            shape = RoundedCornerShape(99.dp),
                                        ) {
                                            Text("Ver Wrapped", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                        }
                                        Button(
                                            onClick = { onPlayTrackList(allStats.take(30).map { it.track }, 0) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Cyan,
                                                contentColor = NoctaliaColors.OnCyan,
                                            ),
                                            shape = RoundedCornerShape(99.dp),
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(2.dp))
                                            Text("Top", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                        }
                                        Text(
                                            text = "Reiniciar",
                                            color = NoctaliaColors.Danger,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clickable(onClick = onClearStats)
                                                .padding(horizontal = 6.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (topArtists.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Tus artistas más escuchados", color = NoctaliaColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    itemsIndexed(topArtists, key = { _, item -> "art_${item.first}" }) { idx, (artistName, sec, plays) ->
                                        val m = (sec / 60L).coerceAtLeast(1L)
                                        NoctaliaGlassCard(
                                            cornerRadius = 16.dp,
                                            highlighted = idx == 0,
                                            modifier = Modifier.clickable { onOpenArtistFromStats(artistName) },
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                                Text(
                                                    text = "#${idx + 1} $artistName",
                                                    color = if (idx == 0) NoctaliaColors.CyanGlow else NoctaliaColors.TextPrimary,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = "$plays rep. · ${m}m",
                                                    color = NoctaliaColors.TextMuted,
                                                    fontSize = 11.sp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (allStats.isEmpty()) {
                        item {
                            Text(
                                text = "Escucha algunas canciones para ver aquí tu Top personal y estadísticas privadas.",
                                color = NoctaliaColors.TextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                    } else {
                        item {
                            Text("Tus canciones más repetidas", color = NoctaliaColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        val topSongs = allStats.take(25)
                        itemsIndexed(topSongs, key = { _, st -> "st_${st.track.id}" }) { idx, st ->
                            TrackRowItem(
                                track = st.track,
                                isCurrent = player.currentTrack?.id == st.track.id,
                                isFavorite = st.track.id in favIds,
                                onPlay = { onPlayTrack(st.track) },
                                onAddQueue = { onAddQueue(st.track) },
                                onToggleFavorite = { onToggleFavorite(st.track) },
                                onMoreClick = { onMoreTrackClick(st.track) },
                                indexBadge = "#${idx + 1}",
                            )
                        }
                    }
                }
            }

            "account" -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 164.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(
                                        Icons.Rounded.AccountCircle,
                                        contentDescription = null,
                                        tint = NoctaliaColors.CyanGlow,
                                        modifier = Modifier.size(30.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (ui.isLoggedIn) "Cuenta YouTube Music conectada" else "Conectar tu cuenta YouTube Music",
                                            color = NoctaliaColors.TextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                        Text(
                                            text = if (ui.isLoggedIn) {
                                                "Sesión cifrada localmente · Audio Opus limpio sin anuncios"
                                            } else {
                                                "Carga tus Me gusta, playlists privadas e historial al instante."
                                            },
                                            color = NoctaliaColors.TextMuted,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }

                                if (ui.isLoggedIn) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = onRefreshAccount,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Cyan,
                                                contentColor = NoctaliaColors.OnCyan,
                                            ),
                                            shape = RoundedCornerShape(99.dp),
                                        ) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Actualizar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        Button(
                                            onClick = onLogout,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Danger.copy(alpha = 0.22f),
                                                contentColor = NoctaliaColors.Danger,
                                            ),
                                            shape = RoundedCornerShape(99.dp),
                                        ) {
                                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Cerrar sesión", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = onOpenLogin,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NoctaliaColors.Cyan,
                                            contentColor = NoctaliaColors.OnCyan,
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text("Iniciar sesión en YouTube Music", fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                        }
                    }

                    if (ui.isLoggedIn) {
                        if (ui.loadingAccount && ui.accountPlaylists.isEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator(color = NoctaliaColors.CyanGlow, strokeWidth = 2.5.dp)
                                }
                            }
                        } else {
                            items(ui.accountPlaylists) { pl ->
                                NoctaliaGlassCard(
                                    cornerRadius = 16.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenRemotePlaylist(pl) },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TrackCoverImage(url = pl.thumb, size = 50.dp, cornerRadius = 12.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = pl.title,
                                                color = NoctaliaColors.TextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = pl.subtitle,
                                                color = NoctaliaColors.TextMuted,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Icon(
                                            Icons.Rounded.Download,
                                            contentDescription = "Abrir",
                                            tint = NoctaliaColors.CyanGlow,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // También permitir pegar un enlace de playlist pública/oculta sin cuenta
                    item {
                        NoctaliaGlassCard(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Importar playlist por enlace (YouTube o Spotify)",
                                    color = NoctaliaColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = urlInput,
                                        onValueChange = { urlInput = it },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        placeholder = { Text("Enlace de YouTube Music o Spotify...", fontSize = 12.sp, color = NoctaliaColors.TextDim) },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = NoctaliaColors.CyanGlow,
                                            unfocusedBorderColor = NoctaliaColors.BorderCyan,
                                            focusedTextColor = NoctaliaColors.TextPrimary,
                                            unfocusedTextColor = NoctaliaColors.TextPrimary,
                                        ),
                                    )
                                    Button(
                                        onClick = {
                                            onImportPlaylistUrl(urlInput)
                                            urlInput = ""
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NoctaliaColors.Cyan,
                                            contentColor = NoctaliaColors.OnCyan,
                                        ),
                                    ) {
                                        Text("Importar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "spotify" -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 164.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 1. Importar cualquier enlace de Spotify sin necesidad de iniciar sesión
                    item {
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "Importar desde Spotify (sin cuenta)",
                                    color = NoctaliaColors.CyanGlow,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                Text(
                                    text = "Pega cualquier enlace de playlist, álbum, canción o artista de Spotify (o pulsa Compartir → Brook desde la app de Spotify). Brook empareja cada canción con audio Opus limpio sin anuncios.",
                                    color = NoctaliaColors.TextMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = spotifyUrlInput,
                                        onValueChange = { spotifyUrlInput = it },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        placeholder = { Text("https://open.spotify.com/playlist/...", fontSize = 12.sp, color = NoctaliaColors.TextDim) },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = NoctaliaColors.CyanGlow,
                                            unfocusedBorderColor = NoctaliaColors.BorderCyan,
                                            focusedTextColor = NoctaliaColors.TextPrimary,
                                            unfocusedTextColor = NoctaliaColors.TextPrimary,
                                        ),
                                    )
                                    Button(
                                        onClick = {
                                            val raw = spotifyUrlInput
                                            spotifyUrlInput = ""
                                            onImportSpotifyUrl(raw)
                                        },
                                        enabled = spotifyUrlInput.isNotBlank() && !ui.spotifyImport.active,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NoctaliaColors.Cyan,
                                            contentColor = NoctaliaColors.OnCyan,
                                        ),
                                    ) {
                                        Text("Importar", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // 2. Conectar cuenta Spotify (para listas privadas, >100 canciones y Me gusta)
                    item {
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(
                                        Icons.Rounded.AccountCircle,
                                        contentDescription = null,
                                        tint = NoctaliaColors.CyanGlow,
                                        modifier = Modifier.size(30.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (ui.isSpotifyLoggedIn) "Cuenta Spotify conectada" else "Conectar tu cuenta Spotify",
                                            color = NoctaliaColors.TextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                        Text(
                                            text = if (ui.isSpotifyLoggedIn) {
                                                "Toca cualquier playlist abajo para importarla junto a tu biblioteca"
                                            } else {
                                                "Importa tus listas privadas, playlists de +100 canciones y «Canciones que te gustan»."
                                            },
                                            color = NoctaliaColors.TextMuted,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }

                                if (ui.isSpotifyLoggedIn) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = onRefreshSpotify,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Cyan,
                                                contentColor = NoctaliaColors.OnCyan,
                                            ),
                                            shape = RoundedCornerShape(99.dp),
                                        ) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Actualizar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        Button(
                                            onClick = onOpenSpotifyLogin,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Glass,
                                                contentColor = NoctaliaColors.TextPrimary,
                                            ),
                                            shape = RoundedCornerShape(99.dp),
                                        ) {
                                            Text("Reconectar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        Button(
                                            onClick = onLogoutSpotify,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Danger.copy(alpha = 0.22f),
                                                contentColor = NoctaliaColors.Danger,
                                            ),
                                            shape = RoundedCornerShape(99.dp),
                                        ) {
                                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = onOpenSpotifyLogin,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NoctaliaColors.Cyan,
                                            contentColor = NoctaliaColors.OnCyan,
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text("Iniciar sesión en Spotify", fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                        }
                    }

                    if (ui.isSpotifyLoggedIn) {
                        if (ui.loadingSpotify && ui.spotifyPlaylists.isEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator(color = NoctaliaColors.CyanGlow, strokeWidth = 2.5.dp)
                                }
                            }
                        } else {
                            items(ui.spotifyPlaylists, key = { "sp_${it.id}" }) { pl ->
                                NoctaliaGlassCard(
                                    cornerRadius = 16.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !ui.spotifyImport.active) {
                                            onImportSpotifyRemotePlaylist(pl)
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TrackCoverImage(url = pl.thumb, size = 50.dp, cornerRadius = 12.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = pl.title,
                                                color = NoctaliaColors.TextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = pl.subtitle,
                                                color = NoctaliaColors.TextMuted,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Icon(
                                            Icons.Rounded.Download,
                                            contentDescription = "Importar",
                                            tint = NoctaliaColors.CyanGlow,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Importar archivo CSV (Exportify / TuneMyMusic) sin cuenta
                    item {
                        NoctaliaGlassCard(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Importar archivo CSV de Spotify (Exportify)",
                                    color = NoctaliaColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Ideal si tienes playlists enormes o prefieres importar tus listas desde un archivo .csv de Exportify / TuneMyMusic.",
                                    color = NoctaliaColors.TextMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                )
                                Button(
                                    onClick = onImportSpotifyCsvClick,
                                    enabled = !ui.spotifyImport.active,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NoctaliaColors.Glass,
                                        contentColor = NoctaliaColors.TextPrimary,
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text("Seleccionar archivo .csv", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            "sync" -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 164.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Tarjeta de Actualización automática desde GitHub Releases (como en OpenMacro)
                    item {
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            text = "Actualizar Brook",
                                            color = NoctaliaColors.CyanGlow,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                        Text(
                                            text = "Versión instalada: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                                            color = NoctaliaColors.TextMuted,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        tint = NoctaliaColors.CyanGlow,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }

                                val info = ui.updateInfo
                                if (info != null) {
                                    val sizeMb = info.apkSizeBytes?.let { " · %.1f MB".format(it / (1024.0 * 1024.0)) } ?: ""
                                    Text(
                                        text = if (info.updateAvailable) {
                                            "Disponible: v${info.latestVersionName}$sizeMb"
                                        } else {
                                            "Ya tienes la última versión (v${info.latestVersionName})."
                                        },
                                        color = if (info.updateAvailable) NoctaliaColors.Magenta else NoctaliaColors.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }

                                if (ui.downloadingUpdate) {
                                    LinearProgressIndicator(
                                        progress = { ui.downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = NoctaliaColors.CyanGlow,
                                        trackColor = Color.White.copy(alpha = 0.14f),
                                    )
                                    Text(
                                        text = "Descargando APK... ${(ui.downloadProgress * 100).toInt()}%",
                                        color = NoctaliaColors.Cyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = onCheckUpdate,
                                        enabled = !ui.checkingUpdate && !ui.downloadingUpdate,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NoctaliaColors.Cyan,
                                            contentColor = NoctaliaColors.OnCyan,
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text(
                                            text = if (ui.checkingUpdate) "Buscando..." else "Buscar actualización",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 12.sp,
                                        )
                                    }

                                    if (info != null) {
                                        Button(
                                            onClick = onDownloadUpdate,
                                            enabled = !ui.downloadingUpdate,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (info.updateAvailable) NoctaliaColors.Magenta else NoctaliaColors.Glass,
                                                contentColor = if (info.updateAvailable) NoctaliaColors.OnMagenta else NoctaliaColors.TextPrimary,
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                        ) {
                                            Text(
                                                text = if (info.updateAvailable) "Descargar e instalar" else "Reinstalar APK",
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 12.sp,
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = "Abrir GitHub Releases en el navegador",
                                    color = NoctaliaColors.TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable(onClick = onOpenReleases),
                                )
                            }
                        }
                    }

                    // Tarjeta de Copia en la Nube Privada (GitHub · brook-backup)
                    item {
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Copia en la nube (GitHub)",
                                            color = NoctaliaColors.CyanGlow,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                        Text(
                                            text = "Repo privado: ${GitHubCloudSync.BACKUP_REPO_SLUG}",
                                            color = NoctaliaColors.TextMuted,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    Icon(
                                        imageVector = if (cloudSync.configured) Icons.Rounded.CloudDone else Icons.Rounded.CloudSync,
                                        contentDescription = null,
                                        tint = if (cloudSync.lastError != null) NoctaliaColors.Danger else NoctaliaColors.CyanGlow,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }

                                if (!cloudSync.configured) {
                                    Text(
                                        text = "Guarda automáticamente tus Brook Stats, Favoritas y Playlists en tiempo real en tu repo privado. Tu token se cifra localmente en Android Keystore.",
                                        color = NoctaliaColors.TextMuted,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                    )
                                    OutlinedTextField(
                                        value = cloudTokenInput,
                                        onValueChange = { cloudTokenInput = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        placeholder = {
                                            Text(
                                                text = "Pega tu Fine-Grained Token (github_pat_...)",
                                                fontSize = 12.sp,
                                                color = NoctaliaColors.TextDim,
                                            )
                                        },
                                        trailingIcon = if (cloudTokenInput.isNotEmpty()) {
                                            {
                                                IconButton(onClick = { cloudTokenInput = "" }) {
                                                    Icon(
                                                        Icons.Rounded.Close,
                                                        contentDescription = "Limpiar",
                                                        tint = NoctaliaColors.TextMuted,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = NoctaliaColors.CyanGlow,
                                            unfocusedBorderColor = NoctaliaColors.BorderCyan,
                                            focusedTextColor = NoctaliaColors.TextPrimary,
                                            unfocusedTextColor = NoctaliaColors.TextPrimary,
                                        ),
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Button(
                                            onClick = {
                                                val tokenToSave = cloudTokenInput
                                                cloudTokenInput = ""
                                                onSaveCloudToken(tokenToSave)
                                            },
                                            enabled = cloudTokenInput.isNotBlank(),
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Cyan,
                                                contentColor = NoctaliaColors.OnCyan,
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                        ) {
                                            Text("Conectar y guardar", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                        }
                                        Button(
                                            onClick = onOpenCreateTokenPage,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Glass,
                                                contentColor = NoctaliaColors.TextPrimary,
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                        ) {
                                            Text("Crear token", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                } else {
                                    if (cloudSync.syncing) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = NoctaliaColors.CyanGlow,
                                                strokeWidth = 2.dp,
                                            )
                                            Text(
                                                text = "Sincronizando con brook-backup...",
                                                color = NoctaliaColors.CyanGlow,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = if (cloudSync.lastSyncTimeText.isNotBlank()) {
                                                "Conectado · Última copia: ${cloudSync.lastSyncTimeText}"
                                            } else {
                                                "Conectado · Listo para sincronizar"
                                            },
                                            color = NoctaliaColors.TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }

                                    if (cloudSync.lastError != null) {
                                        Text(
                                            text = cloudSync.lastError,
                                            color = NoctaliaColors.Danger,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = "Copia automática en tiempo real",
                                                color = NoctaliaColors.TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                text = "Guarda Stats, Favoritas y Playlists automáticamente",
                                                color = NoctaliaColors.TextMuted,
                                                fontSize = 11.sp,
                                            )
                                        }
                                        Switch(
                                            checked = cloudSync.autoSyncEnabled,
                                            onCheckedChange = onToggleCloudAutoSync,
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = NoctaliaColors.OnCyan,
                                                checkedTrackColor = NoctaliaColors.Cyan,
                                            ),
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Button(
                                            onClick = onSyncCloudNow,
                                            enabled = !cloudSync.syncing,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Cyan,
                                                contentColor = NoctaliaColors.OnCyan,
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                        ) {
                                            Text(
                                                text = if (cloudSync.syncing) "Guardando..." else "Sincronizar ahora",
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 12.sp,
                                            )
                                        }
                                        Button(
                                            onClick = onRestoreCloud,
                                            enabled = !cloudSync.syncing,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Glass,
                                                contentColor = NoctaliaColors.TextPrimary,
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                        ) {
                                            Text("Restaurar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }

                                    Text(
                                        text = "Desconectar y borrar token",
                                        color = NoctaliaColors.Danger,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable(onClick = onDisconnectCloud)
                                            .padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Tarjeta de Fondo Animado del Reproductor
                    item {
                        val currentBgStyle = PlayerBgStyle.fromId(library.settings.bgStyle)
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "Fondo animado del reproductor",
                                    color = NoctaliaColors.CyanGlow,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                Text(
                                    text = "Mantiene la portada fija y nítida mientras el fondo fluye al ritmo de la música.",
                                    color = NoctaliaColors.TextMuted,
                                    fontSize = 12.sp,
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(PlayerBgStyle.entries) { st ->
                                        NoctaliaPillChip(
                                            label = st.label,
                                            active = currentBgStyle == st,
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
                                        Text(
                                            text = "Usar colores de la portada",
                                            color = NoctaliaColors.TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = "Desactívalo para usar siempre tonos Noctalia (Cian/Magenta)",
                                            color = NoctaliaColors.TextMuted,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    Switch(
                                        checked = library.settings.bgUseCoverColors,
                                        onCheckedChange = onToggleBgUseCoverColors,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NoctaliaColors.OnCyan,
                                            checkedTrackColor = NoctaliaColors.Cyan,
                                        ),
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = "Tema dinámico en toda la app",
                                            color = NoctaliaColors.TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = "Adapta botones, dock, bordes y onda a la portada actual",
                                            color = NoctaliaColors.TextMuted,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    Switch(
                                        checked = library.settings.dynamicAppTheme,
                                        onCheckedChange = onToggleDynamicAppTheme,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NoctaliaColors.OnCyan,
                                            checkedTrackColor = NoctaliaColors.Cyan,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    // Tarjeta de Caché Sin Conexión + Auto-caché inteligente en Wi-Fi
                    item {
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "Modo sin conexión y Auto-caché Wi-Fi",
                                    color = NoctaliaColors.CyanGlow,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                val mb = offline.totalBytes / (1024.0 * 1024.0)
                                val maxMb = library.settings.autoCacheMaxMb
                                val capLabel = if (maxMb >= 1024) "${maxMb / 1024} GB" else "$maxMb MB"
                                Text(
                                    text = "${offline.cachedIds.size} canciones guardadas · %.1f MB / $capLabel".format(mb),
                                    color = NoctaliaColors.TextMuted,
                                    fontSize = 13.sp,
                                )
                                val usedFrac = (mb / maxMb.coerceAtLeast(100)).toFloat().coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { usedFrac },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = NoctaliaColors.CyanGlow,
                                    trackColor = Color.White.copy(alpha = 0.14f),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Auto-caché en Wi-Fi", color = NoctaliaColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("Guarda automáticamente lo que escuchas en Wi-Fi", color = NoctaliaColors.TextMuted, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = library.settings.autoCacheWifi,
                                        onCheckedChange = onToggleAutoCacheWifi,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NoctaliaColors.OnCyan,
                                            checkedTrackColor = NoctaliaColors.Cyan,
                                        ),
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Auto-descargar Favoritas en Wi-Fi", color = NoctaliaColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("Mantiene tus Favoritas listas sin cobertura", color = NoctaliaColors.TextMuted, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = library.settings.autoCacheFavorites,
                                        onCheckedChange = onToggleAutoCacheFavorites,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NoctaliaColors.OnCyan,
                                            checkedTrackColor = NoctaliaColors.Cyan,
                                        ),
                                    )
                                }
                                Text("Límite de auto-caché", color = NoctaliaColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(
                                        items = listOf(250 to "250 MB", 500 to "500 MB", 1024 to "1 GB", 2048 to "2 GB"),
                                        key = { it.first },
                                        contentType = { "cache_cap" },
                                    ) { (capMb, lbl) ->
                                        NoctaliaPillChip(
                                            label = lbl,
                                            active = library.settings.autoCacheMaxMb == capMb,
                                            onClick = { onSelectAutoCacheMaxMb(capMb) },
                                        )
                                    }
                                }
                                if (offline.cachedIds.isNotEmpty()) {
                                    Button(
                                        onClick = onClearOfflineCache,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NoctaliaColors.Danger.copy(alpha = 0.22f),
                                            contentColor = NoctaliaColors.Danger,
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text("Vaciar caché sin conexión", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Tarjeta Sync PC Noctalia
                    item {
                        NoctaliaGlassCard(cornerRadius = 18.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "Sincronizar con Noctalia PC (CachyOS)",
                                    color = NoctaliaColors.CyanGlow,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                Text(
                                    text = "Usa exactamente el mismo archivo ~/.local/state/noctalia/music_library.json que tu barra de Noctalia en Linux. Exporta o importa tus Favoritas y Playlists sin depender de servidores.",
                                    color = NoctaliaColors.TextMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Button(
                                        onClick = onImportJsonClick,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NoctaliaColors.Cyan,
                                            contentColor = NoctaliaColors.OnCyan,
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text("Importar JSON", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                    }
                                    Button(
                                        onClick = onExportJsonClick,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NoctaliaColors.Glass,
                                            contentColor = NoctaliaColors.TextPrimary,
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text("Exportar JSON", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        }
    }
}
