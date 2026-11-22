package com.noctalia.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noctalia.music.data.Track
import com.noctalia.music.ui.NoctaliaViewModel
import com.noctalia.music.ui.components.NoctaliaBackground
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.components.NoctaliaGlassToast
import com.noctalia.music.ui.components.PlayerBgStyle
import com.noctalia.music.ui.components.TrackActionsBottomSheet
import com.noctalia.music.ui.components.bouncyClick
import kotlinx.coroutines.delay
import com.noctalia.music.ui.screens.FullPlayerOverlay
import com.noctalia.music.ui.screens.HomeScreen
import com.noctalia.music.ui.screens.LibraryScreen
import com.noctalia.music.ui.screens.MiniPlayerBar
import com.noctalia.music.ui.screens.SearchScreen
import com.noctalia.music.ui.screens.YouTubeLoginDialog
import com.noctalia.music.ui.theme.NoctaliaColors
import com.noctalia.music.ui.theme.NoctaliaTheme
import com.noctalia.music.ui.theme.SyncDynamicBrookTheme

class MainActivity : ComponentActivity() {
    private val _pendingSharedText = MutableStateFlow<String?>(null)
    private val pendingSharedText = _pendingSharedText.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (savedInstanceState == null) {
            extractIncomingSharedText(intent)?.let { _pendingSharedText.value = it }
        }
        setContent {
            NoctaliaTheme {
                NoctaliaRootScreen(
                    pendingSharedFlow = pendingSharedText,
                    onConsumeShared = { _pendingSharedText.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIncomingSharedText(intent)?.let { _pendingSharedText.value = it }
    }

    private fun extractIncomingSharedText(intent: Intent?): String? {
        if (intent == null) return null
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> if (intent.type?.startsWith("text/") == true) {
                intent.getStringExtra(Intent.EXTRA_TEXT)
            } else {
                null
            }
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        return raw?.trim()?.take(2048)?.takeIf { it.isNotEmpty() }
    }
}

@Composable
private fun NoctaliaRootScreen(
    pendingSharedFlow: kotlinx.coroutines.flow.StateFlow<String?>,
    onConsumeShared: () -> Unit,
    vm: NoctaliaViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val library by vm.libraryState.collectAsStateWithLifecycle()
    val offline by vm.offlineState.collectAsStateWithLifecycle()
    val audioFx by vm.audioFxState.collectAsStateWithLifecycle()
    val cloudSync by vm.cloudSyncState.collectAsStateWithLifecycle()
    val pendingShared by pendingSharedFlow.collectAsStateWithLifecycle()

    LaunchedEffect(pendingShared) {
        val shared = pendingShared ?: return@LaunchedEffect
        onConsumeShared()
        vm.handleSharedYoutubeText(shared)
    }

    var currentNav by remember { mutableStateOf("home") }
    var libraryInitialTab by remember { mutableStateOf("favs") }
    var fullPlayerOpen by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var actionSheetTrack by remember { mutableStateOf<Track?>(null) }

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
        if (ui.statusMessage != null) {
            delay(2400L)
            vm.clearStatusMessage()
        }
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

    val navOrder = mapOf("home" to 0, "search" to 1, "library" to 2)

    // Sincroniza dinámicamente todos los colores de Brook con la portada de la canción que suena
    SyncDynamicBrookTheme(
        coverUrl = player.currentTrack?.thumb,
        enabled = library.settings.dynamicAppTheme,
    )

    NoctaliaBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            AnimatedContent(
                targetState = currentNav,
                transitionSpec = {
                    val forward = (navOrder[targetState] ?: 0) >= (navOrder[initialState] ?: 0)
                    val enter = slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) {
                        if (forward) it / 8 else -it / 8
                    } + fadeIn(tween(220))
                    val exit = slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) {
                        if (forward) -it / 10 else it / 10
                    } + fadeOut(tween(170))
                    enter togetherWith exit
                },
                label = "main_nav_transition",
            ) { navTab ->
            when (navTab) {
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
                    onMoreTrackClick = { actionSheetTrack = it },
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
                    onRetryHome = { vm.refreshHome() },
                )

                "search" -> SearchScreen(
                    ui = ui,
                    player = player,
                    library = library,
                    onQueryChange = { q, imm -> vm.onSearchQueryChanged(q, immediate = imm) },
                    onModeChange = { vm.setSearchMode(it) },
                    onPlayTrack = {
                        vm.clearSearchSuggestions()
                        vm.player.playTrack(it)
                    },
                    onAddQueue = {
                        vm.player.addToQueue(it)
                        vm.showMessage("Añadida «${it.title}» a la cola")
                    },
                    onToggleFavorite = { vm.library.toggleFavorite(it) },
                    onMoreTrackClick = { actionSheetTrack = it },
                    onClearSearchHistory = { vm.library.clearSearchHistory() },
                )

                "library" -> LibraryScreen(
                    ui = ui,
                    player = player,
                    library = library,
                    offline = offline,
                    cloudSync = cloudSync,
                    activeTab = libraryInitialTab,
                    onTabChange = { libraryInitialTab = it },
                    onPlayTrack = { vm.player.playTrack(it) },
                    onPlayTrackList = { list, idx -> vm.player.playTrackList(list, idx) },
                    onAddQueue = {
                        vm.player.addToQueue(it)
                        vm.showMessage("Añadida «${it.title}» a la cola")
                    },
                    onToggleFavorite = { vm.library.toggleFavorite(it) },
                    onMoreTrackClick = { actionSheetTrack = it },
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
                    onSelectBgStyle = { vm.library.updatePlayerBgStyle(it.id) },
                    onToggleBgUseCoverColors = { vm.library.updatePlayerBgUseCoverColors(it) },
                    onToggleDynamicAppTheme = { vm.library.updateDynamicAppTheme(it) },
                    onOpenArtistFromStats = { artist ->
                        currentNav = "search"
                        vm.onSearchQueryChanged(artist, immediate = true, mode = "artist")
                    },
                    onClearStats = {
                        vm.library.clearStats()
                        vm.showMessage("Estadísticas reiniciadas.")
                    },
                    onCheckUpdate = { vm.checkAppUpdate() },
                    onDownloadUpdate = { vm.downloadAndInstallUpdate() },
                    onOpenReleases = { vm.openReleasesPage() },
                    onSaveCloudToken = { vm.saveCloudBackupToken(it) },
                    onSyncCloudNow = { vm.syncCloudBackupNow() },
                    onRestoreCloud = { vm.restoreCloudBackup() },
                    onToggleCloudAutoSync = { vm.cloudSync.setAutoSyncEnabled(it) },
                    onDisconnectCloud = { vm.disconnectCloudBackup() },
                    onOpenCreateTokenPage = { vm.cloudSync.openTokenCreationPage() },
                )
            }
            }

            // Mini reproductor (entra animado al poner una canción) + Dock inferior flotante estilo Noctalia
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(
                        visible = player.currentTrack != null,
                        enter = slideInVertically(
                            animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f),
                            initialOffsetY = { it },
                        ) + fadeIn(tween(220)) + scaleIn(tween(240), initialScale = 0.94f),
                        exit = slideOutVertically(
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            targetOffsetY = { it },
                        ) + fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.94f),
                    ) {
                        MiniPlayerBar(
                            player = player,
                            useCoverColors = library.settings.bgUseCoverColors || library.settings.dynamicAppTheme,
                            onExpand = { fullPlayerOpen = true },
                            onPlayPause = { vm.player.togglePlayPause() },
                            onNext = { vm.player.skipNext() },
                            onPrev = { vm.player.skipPrevious() },
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

        // Reproductor a pantalla completa (sube y baja con físicas suaves estilo Noctalia)
        AnimatedVisibility(
            visible = fullPlayerOpen && player.currentTrack != null,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.84f, stiffness = 340f),
                initialOffsetY = { it },
            ) + fadeIn(tween(220)),
            exit = slideOutVertically(
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                targetOffsetY = { it },
            ) + fadeOut(tween(220)),
        ) {
            FullPlayerOverlay(
                player = player,
                audioFx = audioFx,
                offline = offline,
                bgStyle = PlayerBgStyle.fromId(library.settings.bgStyle),
                bgUseCoverColors = library.settings.bgUseCoverColors,
                dynamicAppTheme = library.settings.dynamicAppTheme,
                isFavorite = player.currentTrack?.id in favIds,
                favoriteIds = favIds,
                onCollapse = { fullPlayerOpen = false },
                onSelectBgStyle = { vm.library.updatePlayerBgStyle(it.id) },
                onToggleBgUseCoverColors = { vm.library.updatePlayerBgUseCoverColors(it) },
                onToggleDynamicAppTheme = { vm.library.updateDynamicAppTheme(it) },
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
                onMoveQueueItem = { from, to -> vm.player.moveQueueItem(from, to) },
                onClearQueue = { vm.player.clearQueue() },
                onSelectEqPreset = { vm.audioEffects.setPreset(it) },
                onBassBoostChange = { vm.audioEffects.setBassBoost(it) },
                onToggleNormalize = { vm.audioEffects.setNormalizeVolume(it) },
                onToggleSponsorBlock = { vm.audioEffects.setSponsorBlock(it) },
                onToggleSkipSilence = { vm.player.setSkipSilence(it) },
                onToggleSmoothFade = { vm.player.setSmoothFade(it) },
                onSetSpeedPitch = { spd, ptc -> vm.player.setPlaybackSpeedPitch(spd, ptc) },
                onSetSleepMinutes = { vm.player.setSleepTimerMinutes(it) },
                onSetSleepEndOfTrack = { vm.player.setSleepAtEndOfTrack() },
                onCancelSleep = { vm.player.cancelSleepTimer() },
            )
        }

        // Toast flotante de cristal Noctalia (siempre visible incluso sobre el reproductor grande)
        NoctaliaGlassToast(
            message = ui.statusMessage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 18.dp, end = 18.dp),
        )

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

        // Menú deslizable de acciones rápidas sobre cualquier canción
        val sheetTrack = actionSheetTrack
        if (sheetTrack != null) {
            TrackActionsBottomSheet(
                track = sheetTrack,
                isFavorite = sheetTrack.id in favIds,
                isOfflineCached = sheetTrack.id in offline.cachedIds,
                isOfflineDownloading = sheetTrack.id in offline.downloadingIds,
                playlistNames = library.playlists.keys.toList(),
                onDismiss = { actionSheetTrack = null },
                onPlayNext = {
                    vm.player.addPlayNext(sheetTrack)
                    vm.showMessage("Sonará a continuación: «${sheetTrack.title}»")
                },
                onAddQueue = {
                    vm.player.addToQueue(sheetTrack)
                    vm.showMessage("Añadida «${sheetTrack.title}» a la cola")
                },
                onAddToPlaylist = { plName ->
                    vm.library.addTrackToPlaylist(plName, sheetTrack)
                    vm.showMessage("Guardada en «$plName»")
                },
                onToggleOffline = { vm.toggleOfflineTrack(sheetTrack) },
                onStartRadio = {
                    vm.player.startRadioFromTrack(sheetTrack)
                    vm.showMessage("Radio iniciada desde «${sheetTrack.title}»")
                },
                onOpenArtist = {
                    currentNav = "search"
                    vm.onSearchQueryChanged(
                        sheetTrack.artist.substringBefore(",").trim(),
                        immediate = true,
                        mode = "artist",
                    )
                },
                onToggleFavorite = {
                    val added = vm.library.toggleFavorite(sheetTrack)
                    vm.showMessage(if (added) "Añadida a Favoritas" else "Quitada de Favoritas")
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
    val bgColor by animateColorAsState(
        targetValue = if (active) NoctaliaColors.Cyan else Color.Transparent,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "dock_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (active) NoctaliaColors.OnCyan else NoctaliaColors.TextMuted,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "dock_fg",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (active) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 480f),
        label = "dock_icon_scale",
    )

    Row(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .bouncyClick(pressedScale = 0.93f, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier
                .size(19.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
        )
    }
}
