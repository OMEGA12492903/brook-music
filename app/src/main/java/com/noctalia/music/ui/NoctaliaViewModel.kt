package com.noctalia.music.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.noctalia.music.NoctaliaMusicApp
import com.noctalia.music.data.AppUpdateInfo
import com.noctalia.music.data.CloudSyncState
import com.noctalia.music.data.LibraryPayload
import com.noctalia.music.data.OfflineState
import com.noctalia.music.data.RemotePlaylist
import com.noctalia.music.data.SecurityValidators
import com.noctalia.music.data.SpotifyCollection
import com.noctalia.music.data.SpotifyImportProgress
import com.noctalia.music.data.SpotifyRemotePlaylist
import com.noctalia.music.data.Track
import com.noctalia.music.player.AudioFxState
import com.noctalia.music.player.PlayerUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val isLoggedIn: Boolean = false,
    val accountLabel: String = "Cuenta YouTube Music",
    val accountPlaylists: List<RemotePlaylist> = emptyList(),
    val accountHistory: List<Track> = emptyList(),
    val selectedRemotePlaylist: RemotePlaylist? = null,
    val selectedRemoteTracks: List<Track> = emptyList(),
    val loadingAccount: Boolean = false,
    val isSpotifyLoggedIn: Boolean = false,
    val loadingSpotify: Boolean = false,
    val spotifyPlaylists: List<SpotifyRemotePlaylist> = emptyList(),
    val spotifyImport: SpotifyImportProgress = SpotifyImportProgress(),
    val homeTracks: List<Track> = emptyList(),
    val loadingHome: Boolean = false,
    val searchQuery: String = "",
    val searchMode: String = "songs", // "songs" | "all" | "artist"
    val searchSuggestions: List<String> = emptyList(),
    val searchResults: List<Track> = emptyList(),
    val searching: Boolean = false,
    val checkingUpdate: Boolean = false,
    val downloadingUpdate: Boolean = false,
    val downloadProgress: Float = 0f,
    val updateInfo: AppUpdateInfo? = null,
    val statusMessage: String? = null,
)

class NoctaliaViewModel : ViewModel() {

    private val app = NoctaliaMusicApp.instance
    val authStore = app.authStore
    val api = app.api
    val spotify = app.spotifyClient
    val library = app.library
    val updater = app.updater
    val cloudSync = app.cloudSync
    val offlineCache = app.offlineCache
    val audioEffects = app.audioEffects
    val player = app.playerController

    val playerState: StateFlow<PlayerUiState> = player.state
    val libraryState: StateFlow<LibraryPayload> = library.state
    val offlineState: StateFlow<OfflineState> = offlineCache.state
    val audioFxState: StateFlow<AudioFxState> = audioEffects.state
    val cloudSyncState: StateFlow<CloudSyncState> = cloudSync.state

    private val _ui = MutableStateFlow(
        MainUiState(
            isLoggedIn = authStore.isLoggedIn(),
            accountLabel = authStore.getAccountLabel(),
            isSpotifyLoggedIn = authStore.isSpotifyLoggedIn(),
        )
    )
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var spotifyImportJob: Job? = null

    init {
        refreshHome()
        if (authStore.isLoggedIn()) {
            refreshAccountLibrary()
        }
        if (authStore.isSpotifyLoggedIn()) {
            refreshSpotifyAccount(silent = true)
        }
    }

    fun clearStatusMessage() {
        _ui.update { it.copy(statusMessage = null) }
    }

    fun showMessage(msg: String) {
        _ui.update { it.copy(statusMessage = msg) }
    }

    fun refreshHome() {
        viewModelScope.launch {
            _ui.update { it.copy(loadingHome = true) }
            val tracks = api.search("top hits musica 2026", limit = 14)
            _ui.update { it.copy(homeTracks = tracks, loadingHome = false) }
        }
    }

    fun onSearchQueryChanged(query: String, immediate: Boolean = false, mode: String? = null) {
        val nextMode = mode ?: _ui.value.searchMode
        _ui.update { it.copy(searchQuery = query, searchMode = nextMode) }
        searchJob?.cancel()
        suggestJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _ui.update { it.copy(searchResults = emptyList(), searchSuggestions = emptyList(), searching = false) }
            return
        }
        if (immediate) {
            _ui.update { it.copy(searchSuggestions = emptyList()) }
            library.recordSearchQuery(trimmed)
        } else if (trimmed.length >= 2) {
            suggestJob = viewModelScope.launch {
                delay(170L)
                val sugs = api.getSearchSuggestions(trimmed)
                if (_ui.value.searchQuery.trim() == trimmed) {
                    _ui.update { it.copy(searchSuggestions = sugs) }
                }
            }
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(420L)
            _ui.update { it.copy(searching = true) }
            val items = api.search(trimmed, limit = 20, mode = nextMode)
            _ui.update { it.copy(searchResults = items, searching = false) }
        }
    }

    fun clearSearchSuggestions() {
        _ui.update { it.copy(searchSuggestions = emptyList()) }
    }

    /**
     * Procesa enlaces compartidos desde YouTube, YouTube Music, Spotify, WhatsApp o Navegador (`Compartir -> Brook`).
     */
    fun handleSharedYoutubeText(rawText: String?) {
        val spotifyUrl = SecurityValidators.extractSpotifyUrlFromSharedText(rawText)
        if (spotifyUrl != null) {
            importSpotifyUrl(spotifyUrl, playAfterImport = true)
            return
        }

        val videoId = SecurityValidators.extractVideoIdFromSharedText(rawText)
        val playlistId = SecurityValidators.extractPlaylistIdFromSharedText(rawText)
        when {
            videoId != null -> {
                showMessage("Abriendo canción de YouTube en Brook...")
                viewModelScope.launch {
                    val resolvedTrack = api.fetchTrackByVideoId(videoId) ?: Track(
                        id = videoId,
                        title = "Canción compartida",
                        artist = "YouTube Music",
                        thumb = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                    )
                    player.playTrack(resolvedTrack, pushHistory = true)
                    showMessage("Reproduciendo «${resolvedTrack.title}»")
                }
            }
            playlistId != null -> {
                showMessage("Cargando playlist compartida...")
                viewModelScope.launch {
                    val (plTitle, tracks) = api.fetchPlaylistWithTitle(playlistId, useAuth = authStore.isLoggedIn())
                    if (tracks.isEmpty()) {
                        showMessage("No se pudo abrir la playlist (asegúrate de que sea Pública u Oculta).")
                    } else {
                        player.playTrackList(tracks, 0)
                        showMessage("Reproduciendo «$plTitle» (${tracks.size} canciones)")
                    }
                }
            }
            !rawText.isNullOrBlank() -> {
                showMessage("Enlace no reconocido como YouTube ni Spotify.")
            }
        }
    }

    fun setSearchMode(mode: String) {
        onSearchQueryChanged(_ui.value.searchQuery, immediate = true, mode = mode)
    }

    fun toggleOfflineTrack(track: Track) {
        viewModelScope.launch {
            if (offlineCache.isCached(track.id)) {
                offlineCache.removeTrack(track.id)
                showMessage("Quitada «${track.title}» de caché sin conexión.")
            } else {
                showMessage("Guardando «${track.title}» sin conexión...")
                val ok = offlineCache.cacheTrack(track)
                showMessage(
                    if (ok) "«${track.title}» lista para escuchar sin conexión."
                    else "No se pudo guardar «${track.title}» sin conexión."
                )
            }
        }
    }

    fun cacheTrackListOffline(tracks: List<Track>, label: String) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val pending = tracks.filterNot { offlineCache.isCached(it.id) }.take(30)
            if (pending.isEmpty()) {
                showMessage("Todas las canciones de $label ya están guardadas sin conexión.")
                return@launch
            }
            showMessage("Descargando ${pending.size} canciones de $label para modo sin conexión...")
            var okCount = 0
            for (t in pending) {
                if (offlineCache.cacheTrack(t)) okCount++
            }
            showMessage("Listo: $okCount canciones guardadas sin conexión.")
        }
    }

    fun clearOfflineCache() {
        offlineCache.clearAll()
        showMessage("Caché sin conexión vaciada.")
    }

    fun onYouTubeLoginSuccess(cookie: String) {
        authStore.saveCookie(cookie)
        _ui.update {
            it.copy(
                isLoggedIn = authStore.isLoggedIn(),
                accountLabel = authStore.getAccountLabel(),
                statusMessage = "Cuenta de YouTube Music conectada. Cargando tu biblioteca...",
            )
        }
        refreshAccountLibrary()
    }

    fun logoutYouTubeAccount() {
        authStore.clearSession()
        _ui.update {
            it.copy(
                isLoggedIn = false,
                accountPlaylists = emptyList(),
                accountHistory = emptyList(),
                selectedRemotePlaylist = null,
                selectedRemoteTracks = emptyList(),
                statusMessage = "Sesión de YouTube Music cerrada.",
            )
        }
    }

    fun refreshAccountLibrary() {
        if (!authStore.isLoggedIn()) return
        viewModelScope.launch {
            _ui.update { it.copy(loadingAccount = true) }
            val playlists = api.fetchAccountPlaylists()
            val history = api.fetchAccountHistory()
            _ui.update {
                it.copy(
                    accountPlaylists = playlists,
                    accountHistory = history,
                    loadingAccount = false,
                )
            }
        }
    }

    fun openRemotePlaylist(pl: RemotePlaylist) {
        viewModelScope.launch {
            _ui.update { it.copy(selectedRemotePlaylist = pl, selectedRemoteTracks = emptyList(), loadingAccount = true) }
            val tracks = api.fetchPlaylistTracks(pl.browseId, useAuth = true)
            _ui.update { it.copy(selectedRemoteTracks = tracks, loadingAccount = false) }
        }
    }

    fun closeRemotePlaylist() {
        _ui.update { it.copy(selectedRemotePlaylist = null, selectedRemoteTracks = emptyList()) }
    }

    fun importRemotePlaylistToLocal(pl: RemotePlaylist, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val count = if (pl.browseId == "VLLM") {
            library.mergeFavorites(tracks)
        } else {
            library.savePlaylistWithTracks(pl.title, tracks)
        }
        showMessage("Importadas $count canciones de «${pl.title}» a tu biblioteca local.")
    }

    fun importPlaylistFromUrl(urlInput: String) {
        val spotifyUrl = SecurityValidators.extractSpotifyUrlFromSharedText(urlInput)
        if (spotifyUrl != null) {
            importSpotifyUrl(spotifyUrl, playAfterImport = false)
            return
        }
        val listId = SecurityValidators.extractPlaylistIdFromUrl(urlInput)
        if (listId == null) {
            showMessage("Enlace de playlist de YouTube o Spotify no válido.")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(loadingAccount = true) }
            val (plTitle, tracks) = api.fetchPlaylistWithTitle(listId, useAuth = authStore.isLoggedIn())
            _ui.update { it.copy(loadingAccount = false) }
            if (tracks.isEmpty()) {
                showMessage("No se encontraron canciones (asegúrate de que la playlist sea Pública u Oculta).")
            } else {
                val count = library.savePlaylistWithTracks(plTitle, tracks)
                showMessage("Importadas $count canciones a «$plTitle».")
            }
        }
    }

    // ─── SPOTIFY (CUENTA, ENLACES PÚBLICOS/COMPARTIDOS Y CSV EXPORTIFY) ───
    fun onSpotifyLoginSuccess(bearerToken: String, clientToken: String?) {
        if (!authStore.saveSpotifySession(bearerToken, clientToken)) {
            showMessage("No se pudo guardar la sesión de Spotify.")
            return
        }
        _ui.update {
            it.copy(
                isSpotifyLoggedIn = true,
                statusMessage = "Cuenta Spotify conectada. Cargando tus playlists...",
            )
        }
        refreshSpotifyAccount(silent = false)
    }

    fun logoutSpotifyAccount() {
        spotifyImportJob?.cancel()
        authStore.clearSpotifySession()
        _ui.update {
            it.copy(
                isSpotifyLoggedIn = false,
                loadingSpotify = false,
                spotifyPlaylists = emptyList(),
                spotifyImport = SpotifyImportProgress(),
                statusMessage = "Sesión de Spotify cerrada.",
            )
        }
    }

    fun refreshSpotifyAccount(silent: Boolean = false) {
        if (!authStore.isSpotifyLoggedIn()) return
        viewModelScope.launch {
            _ui.update { it.copy(loadingSpotify = true) }
            val res = spotify.fetchAccountPlaylists()
            res.onSuccess { list ->
                _ui.update { it.copy(spotifyPlaylists = list, loadingSpotify = false) }
            }.onFailure { err ->
                _ui.update { it.copy(loadingSpotify = false) }
                if (!silent) {
                    showMessage(err.message ?: "No se pudieron cargar tus listas de Spotify.")
                }
            }
        }
    }

    fun cancelSpotifyImport() {
        spotifyImportJob?.cancel()
        spotifyImportJob = null
        _ui.update { it.copy(spotifyImport = SpotifyImportProgress()) }
        showMessage("Importación de Spotify cancelada.")
    }

    fun importSpotifyUrl(rawInput: String, playAfterImport: Boolean = false) {
        if (rawInput.isBlank()) return
        spotifyImportJob?.cancel()
        spotifyImportJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    spotifyImport = SpotifyImportProgress(title = "Leyendo Spotify...", matched = 0, total = 0, active = true),
                )
            }
            val res = spotify.fetchFromUrl(rawInput)
            res.onFailure { err ->
                _ui.update { it.copy(spotifyImport = SpotifyImportProgress()) }
                showMessage(err.message ?: "No se pudo leer el enlace de Spotify.")
            }.onSuccess { collection ->
                matchAndSaveSpotifyCollection(collection, playAfterImport = playAfterImport)
            }
        }
    }

    fun importSpotifyRemotePlaylist(pl: SpotifyRemotePlaylist) {
        spotifyImportJob?.cancel()
        spotifyImportJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    spotifyImport = SpotifyImportProgress(title = pl.title, matched = 0, total = 0, active = true),
                )
            }
            val res = spotify.fetchRemotePlaylistCollection(pl)
            res.onFailure { err ->
                _ui.update { it.copy(spotifyImport = SpotifyImportProgress()) }
                showMessage(err.message ?: "No se pudo cargar «${pl.title}» desde Spotify.")
            }.onSuccess { collection ->
                matchAndSaveSpotifyCollection(collection, playAfterImport = false)
            }
        }
    }

    fun importSpotifyCsv(rawCsv: String, fileName: String) {
        spotifyImportJob?.cancel()
        spotifyImportJob = viewModelScope.launch {
            val res = spotify.parseCsv(rawCsv, fileName)
            res.onFailure { err ->
                showMessage(err.message ?: "El archivo CSV no es válido.")
            }.onSuccess { collection ->
                matchAndSaveSpotifyCollection(collection, playAfterImport = false)
            }
        }
    }

    private suspend fun matchAndSaveSpotifyCollection(
        collection: SpotifyCollection,
        playAfterImport: Boolean,
    ) {
        val total = collection.tracks.size
        _ui.update {
            it.copy(
                spotifyImport = SpotifyImportProgress(
                    title = collection.title,
                    matched = 0,
                    total = total,
                    active = true,
                )
            )
        }
        try {
            val matchedTracks = spotify.matchSeedsToYouTube(collection.tracks) { done, tot ->
                _ui.update {
                    it.copy(
                        spotifyImport = SpotifyImportProgress(
                            title = collection.title,
                            matched = done,
                            total = tot,
                            active = true,
                        )
                    )
                }
            }
            _ui.update { it.copy(spotifyImport = SpotifyImportProgress()) }

            if (matchedTracks.isEmpty()) {
                showMessage("No se encontraron coincidencias de audio para «${collection.title}».")
                return
            }

            if (collection.kind == "track") {
                val track = matchedTracks.first()
                player.playTrack(track, pushHistory = true)
                showMessage("Reproduciendo desde Spotify: «${track.title}»")
                return
            }

            val savedCount = if (collection.kind == "liked") {
                library.mergeFavorites(matchedTracks)
            } else {
                library.savePlaylistWithTracks(collection.title, matchedTracks)
            }

            if (playAfterImport) {
                player.playTrackList(matchedTracks, 0)
            }

            val destLabel = if (collection.kind == "liked") "Favoritas" else "«${collection.title}»"
            showMessage("Spotify listo: $savedCount/$total canciones importadas a $destLabel.")
        } finally {
            _ui.update {
                if (it.spotifyImport.active) it.copy(spotifyImport = SpotifyImportProgress()) else it
            }
        }
    }

    fun importLibraryJson(raw: String) {
        val ok = library.importJson(raw)
        showMessage(
            if (ok) "Biblioteca sincronizada desde Noctalia PC."
            else "El archivo JSON no es válido."
        )
    }

    fun checkAppUpdate() {
        if (_ui.value.checkingUpdate || _ui.value.downloadingUpdate) return
        viewModelScope.launch {
            _ui.update { it.copy(checkingUpdate = true) }
            val res = updater.checkForUpdate()
            res.onSuccess { info ->
                _ui.update {
                    it.copy(
                        checkingUpdate = false,
                        updateInfo = info,
                        statusMessage = if (info.updateAvailable) {
                            "Nueva versión disponible: v${info.latestVersionName}"
                        } else {
                            "Brook ya está actualizado (v${info.currentVersionName})."
                        },
                    )
                }
            }.onFailure { err ->
                _ui.update {
                    it.copy(
                        checkingUpdate = false,
                        statusMessage = err.message ?: "No se pudo comprobar actualizaciones.",
                    )
                }
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val info = _ui.value.updateInfo ?: return
        if (_ui.value.downloadingUpdate) return
        viewModelScope.launch {
            _ui.update { it.copy(downloadingUpdate = true, downloadProgress = 0f) }
            val res = updater.downloadApk(info.downloadUrl) { prog ->
                _ui.update { it.copy(downloadProgress = prog) }
            }
            res.onSuccess { file ->
                _ui.update { it.copy(downloadingUpdate = false, downloadProgress = 1f) }
                val launched = updater.launchApkInstaller(file)
                if (!launched) {
                    showMessage("Activa «Instalar apps desconocidas» para Brook y vuelve a pulsar Instalar.")
                }
            }.onFailure { err ->
                _ui.update {
                    it.copy(
                        downloadingUpdate = false,
                        statusMessage = err.message ?: "Error al descargar la actualización.",
                    )
                }
            }
        }
    }

    fun openReleasesPage() {
        updater.openReleasesInBrowser()
    }

    fun saveCloudBackupToken(rawToken: String) {
        if (!cloudSync.saveToken(rawToken)) {
            showMessage("Token no válido. Pega tu Fine-Grained Token de GitHub.")
            return
        }
        showMessage("Token cifrado guardado. Sincronizando con brook-backup...")
        syncCloudBackupNow()
    }

    fun syncCloudBackupNow() {
        viewModelScope.launch {
            val res = cloudSync.syncNow(silent = false)
            res.onSuccess { msg -> showMessage(msg) }
                .onFailure { err -> showMessage(err.message ?: "Error al sincronizar con GitHub.") }
        }
    }

    fun restoreCloudBackup() {
        viewModelScope.launch {
            val res = cloudSync.restoreFromCloud()
            res.onSuccess { msg -> showMessage(msg) }
                .onFailure { err -> showMessage(err.message ?: "Error al restaurar desde GitHub.") }
        }
    }

    fun disconnectCloudBackup() {
        cloudSync.clearToken()
        showMessage("Copia en la nube desconectada y token borrado.")
    }
}
