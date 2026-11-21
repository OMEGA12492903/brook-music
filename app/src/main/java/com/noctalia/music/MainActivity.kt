package com.noctalia.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noctalia.music.ui.NoctaliaViewModel
import com.noctalia.music.ui.components.NoctaliaBackground
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.screens.FullPlayerOverlay
import com.noctalia.music.ui.screens.HomeScreen
import com.noctalia.music.ui.screens.LibraryScreen
import com.noctalia.music.ui.screens.MiniPlayerBar
import com.noctalia.music.ui.screens.SearchScreen
import com.noctalia.music.ui.screens.YouTubeLoginDialog
import com.noctalia.music.ui.theme.NoctaliaColors
import com.noctalia.music.ui.theme.NoctaliaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            NoctaliaTheme {
                NoctaliaRootScreen()
            }
        }
    }
}

@Composable
private fun NoctaliaRootScreen(vm: NoctaliaViewModel = viewModel()) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val library by vm.libraryState.collectAsStateWithLifecycle()
    val offline by vm.offlineState.collectAsStateWithLifecycle()
    val audioFx by vm.audioFxState.collectAsStateWithLifecycle()

    var currentNav by remember { mutableStateOf("home") }
    var libraryInitialTab by remember { mutableStateOf("favs") }
    var fullPlayerOpen by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Pedir permiso de notificaciones multimedia en Android 13+ para mostrar controles en bloqueo
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(ui.statusMessage) {
        val msg = ui.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearStatusMessage()
    }

    // Exportar / Importar music_library.json compatible con Noctalia PC
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(vm.library.exportJson().toByteArray(Charsets.UTF_8))
                }
                vm.showMessage("Biblioteca exportada (music_library.json).")
            } catch (_: Exception) {
                vm.showMessage("No se pudo exportar el archivo.")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val maxImportBytes = 2 * 1024 * 1024 // Límite 2 MiB anti-OOM
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = stream.read(chunk)
                        if (n <= 0) break
                        total += n
                        if (total > maxImportBytes) throw IllegalArgumentException("Archivo demasiado grande")
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
                if (bytes != null) vm.importLibraryJson(bytes.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                vm.showMessage("No se pudo leer el archivo JSON (máx. 2 MB).")
            }
        }
    }

    BackHandler(enabled = fullPlayerOpen || ui.selectedRemotePlaylist != null || currentNav != "home") {
        when {
            fullPlayerOpen -> fullPlayerOpen = false
            ui.selectedRemotePlaylist != null -> vm.closeRemotePlaylist()
            currentNav != "home" -> currentNav = "home"
        }
    }

    val favIds = remember(library.favorites) { library.favorites.map { it.id }.toSet() }

    NoctaliaBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            when (currentNav) {
                "home" -> HomeScreen(
                    ui = ui,
                    player = player,
                    library = library,
                    onGenreClick = { q ->
                        currentNav = "search"
                        vm.onSearchQueryChanged(q, immediate = true)
                    },
                    onPlayTrack = { vm.player.playTrack(it) },
                    onAddQueue = {
                        vm.player.addToQueue(it)
                        vm.showMessage("Añadida «${it.title}» a la cola")
                    },
                    onToggleFavorite = { vm.library.toggleFavorite(it) },
                    onOpenLogin = {
                        if (ui.isLoggedIn) {
                            libraryInitialTab = "account"
                            currentNav = "library"
                        } else {
                            showLoginDialog = true
                        }
                    },
                    onOpenRemotePlaylist = { pl ->
                        libraryInitialTab = "account"
                        currentNav = "library"
                        vm.openRemotePlaylist(pl)
                    },
                )

                "search" -> SearchScreen(
                    ui = ui,
                    player = player,
                    library = library,
                    onQueryChange = { q, imm -> vm.onSearchQueryChanged(q, immediate = imm) },
                    onModeChange = { vm.setSearchMode(it) },
                    onPlayTrack = { vm.player.playTrack(it) },
                    onAddQueue = {
                        vm.player.addToQueue(it)
                        vm.showMessage("Añadida «${it.title}» a la cola")
                    },
                    onToggleFavorite = { vm.library.toggleFavorite(it) },
                )

                "library" -> LibraryScreen(
                    ui = ui,
                    player = player,
                    library = library,
                    offline = offline,
                    activeTab = libraryInitialTab,
                    onTabChange = { libraryInitialTab = it },
                    onPlayTrack = { vm.player.playTrack(it) },
                    onPlayTrackList = { list, idx -> vm.player.playTrackList(list, idx) },
                    onAddQueue = {
                        vm.player.addToQueue(it)
                        vm.showMessage("Añadida «${it.title}» a la cola")
                    },
                    onToggleFavorite = { vm.library.toggleFavorite(it) },
                    onCacheTracksOffline = { list, lbl -> vm.cacheTrackListOffline(list, lbl) },
                    onClearOfflineCache = { vm.clearOfflineCache() },
                    onCreatePlaylist = { vm.library.createPlaylist(it) },
                    onDeletePlaylist = { vm.library.deletePlaylist(it) },
                    onSaveCurrentToPlaylist = { pl ->
                        player.currentTrack?.let {
                            vm.library.addTrackToPlaylist(pl, it)
                            vm.showMessage("Guardada en «$pl»")
                        }
                    },
                    onRemoveFromPlaylist = { pl, vid -> vm.library.removeTrackFromPlaylist(pl, vid) },
                    onOpenLogin = { showLoginDialog = true },
                    onLogout = { vm.logoutYouTubeAccount() },
                    onRefreshAccount = { vm.refreshAccountLibrary() },
                    onOpenRemotePlaylist = { vm.openRemotePlaylist(it) },
                    onCloseRemotePlaylist = { vm.closeRemotePlaylist() },
                    onImportRemotePlaylist = { pl, tr -> vm.importRemotePlaylistToLocal(pl, tr) },
                    onImportPlaylistUrl = { vm.importPlaylistFromUrl(it) },
                    onExportJsonClick = { exportLauncher.launch("music_library.json") },
                    onImportJsonClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onCheckUpdate = { vm.checkAppUpdate() },
                    onDownloadUpdate = { vm.downloadAndInstallUpdate() },
                    onOpenReleases = { vm.openReleasesPage() },
                )
            }

            // Snackbar flotante
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp),
            )

            // Mini reproductor + Dock inferior flotante estilo Noctalia
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (player.currentTrack != null) {
                        MiniPlayerBar(
                            player = player,
                            onExpand = { fullPlayerOpen = true },
                            onPlayPause = { vm.player.togglePlayPause() },
                        )
                    }

                    NoctaliaGlassCard(cornerRadius = 99.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            NavDockItem(
                                label = "Inicio",
                                icon = Icons.Rounded.Home,
                                active = currentNav == "home",
                                onClick = { currentNav = "home" },
                                modifier = Modifier.weight(1f),
                            )
                            NavDockItem(
                                label = "Buscar",
                                icon = Icons.Rounded.Search,
                                active = currentNav == "search",
                                onClick = { currentNav = "search" },
                                modifier = Modifier.weight(1f),
                            )
                            NavDockItem(
                                label = "Listas",
                                icon = Icons.Rounded.Favorite,
                                active = currentNav == "library",
                                onClick = { currentNav = "library" },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // Reproductor a pantalla completa
        if (fullPlayerOpen && player.currentTrack != null) {
            FullPlayerOverlay(
                player = player,
                audioFx = audioFx,
                offline = offline,
                isFavorite = player.currentTrack?.id in favIds,
                favoriteIds = favIds,
                onCollapse = { fullPlayerOpen = false },
                onArtistClick = { artist ->
                    fullPlayerOpen = false
                    currentNav = "search"
                    vm.onSearchQueryChanged(artist.substringBefore(",").trim(), immediate = true, mode = "artist")
                },
                onPlayPause = { vm.player.togglePlayPause() },
                onNext = { vm.player.skipNext() },
                onPrev = { vm.player.skipPrevious() },
                onSeek = { vm.player.seekTo(it) },
                onToggleShuffle = { vm.player.toggleShuffle() },
                onCycleRepeat = { vm.player.cycleRepeat() },
                onToggleFavorite = { vm.library.toggleFavorite(it) },
                onToggleOffline = { vm.toggleOfflineTrack(it) },
                onToggleAutoRadio = { vm.player.setAutoRadio(it) },
                onPlayQueueIndex = { vm.player.playFromQueue(it) },
                onRemoveQueueIndex = { vm.player.removeFromQueue(it) },
                onClearQueue = { vm.player.clearQueue() },
                onSelectEqPreset = { vm.audioEffects.setPreset(it) },
                onBassBoostChange = { vm.audioEffects.setBassBoost(it) },
                onToggleNormalize = { vm.audioEffects.setNormalizeVolume(it) },
                onToggleSponsorBlock = { vm.audioEffects.setSponsorBlock(it) },
                onSetSleepMinutes = { vm.player.setSleepTimerMinutes(it) },
                onSetSleepEndOfTrack = { vm.player.setSleepAtEndOfTrack() },
                onCancelSleep = { vm.player.cancelSleepTimer() },
            )
        }

        // Diálogo seguro de inicio de sesión de YouTube Music
        if (showLoginDialog) {
            YouTubeLoginDialog(
                onDismiss = { showLoginDialog = false },
                onLoginSuccess = { cookie ->
                    showLoginDialog = false
                    vm.onYouTubeLoginSuccess(cookie)
                },
            )
        }
    }
}

@Composable
private fun NavDockItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(99.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (active) NoctaliaColors.Cyan else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) NoctaliaColors.OnCyan else NoctaliaColors.TextMuted,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (active) NoctaliaColors.OnCyan else NoctaliaColors.TextMuted,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
        )
    }
}
