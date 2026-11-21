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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noctalia.music.data.LibraryPayload
import com.noctalia.music.data.RemotePlaylist
import com.noctalia.music.data.Track
import com.noctalia.music.player.PlayerUiState
import com.noctalia.music.ui.MainUiState
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.components.NoctaliaPillChip
import com.noctalia.music.ui.components.TrackCoverImage
import com.noctalia.music.ui.components.TrackRowItem
import com.noctalia.music.ui.theme.NoctaliaColors

@Composable
fun LibraryScreen(
    ui: MainUiState,
    player: PlayerUiState,
    library: LibraryPayload,
    initialTab: String,
    onPlayTrack: (Track) -> Unit,
    onPlayTrackList: (List<Track>, Int) -> Unit,
    onAddQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
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
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onOpenReleases: () -> Unit,
) {
    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }
    var selectedLocalPlaylist by remember { mutableStateOf<String?>(null) }
    var showNewPlInput by remember { mutableStateOf(false) }
    var newPlName by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }

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
                    onClick = { activeTab = "favs" },
                )
            }
            item {
                NoctaliaPillChip(
                    label = "Playlists",
                    active = activeTab == "playlists",
                    onClick = { activeTab = "playlists" },
                )
            }
            item {
                NoctaliaPillChip(
                    label = if (ui.isLoggedIn) "Cuenta YT ✓" else "Cuenta YT",
                    active = activeTab == "account",
                    onClick = { activeTab = "account" },
                )
            }
            item {
                NoctaliaPillChip(
                    label = "Ajustes",
                    active = activeTab == "sync",
                    onClick = { activeTab = "sync" },
                )
            }
        }

        when (activeTab) {
            "favs" -> {
                if (library.favorites.isNotEmpty()) {
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
                                    text = "Importar playlist por enlace",
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
                                        placeholder = { Text("https://music.youtube.com/playlist?list=...", fontSize = 12.sp, color = NoctaliaColors.TextDim) },
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

                                    if (info?.updateAvailable == true) {
                                        Button(
                                            onClick = onDownloadUpdate,
                                            enabled = !ui.downloadingUpdate,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Magenta,
                                                contentColor = Color(0xFF17051A),
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                        ) {
                                            Text("Descargar e instalar", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
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
