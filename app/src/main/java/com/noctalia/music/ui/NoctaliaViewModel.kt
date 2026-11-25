package com.noctalia.music.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.noctalia.music.NoctaliaMusicApp
import com.noctalia.music.data.AlbumPageData
import com.noctalia.music.data.AppUpdateInfo
import com.noctalia.music.data.ArtistAlbumItem
import com.noctalia.music.data.ArtistMixCard
import com.noctalia.music.data.ArtistPageData
import com.noctalia.music.data.CloudSyncState
import com.noctalia.music.data.HomeRecommendations
import com.noctalia.music.data.LibraryPayload
import com.noctalia.music.data.LocalAudioState
import com.noctalia.music.data.OfflineState
import com.noctalia.music.data.RemotePlaylist
import com.noctalia.music.data.SecurityValidators
import com.noctalia.music.data.ShareCardRenderer
import com.noctalia.music.data.SpotifyCollection
import com.noctalia.music.data.SpotifyImportProgress
import com.noctalia.music.data.SpotifyRemotePlaylist
import com.noctalia.music.data.Track
import com.noctalia.music.player.AudioFxState
import com.noctalia.music.player.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val spotifyError: String? = null,
    val spotifyImport: SpotifyImportProgress = SpotifyImportProgress(),
    val homeTracks: List<Track> = emptyList(),
    val homeRecommendations: HomeRecommendations = HomeRecommendations(),
    val loadingHome: Boolean = false,
    val searchQuery: String = "",
    val searchMode: String = "songs", // "songs" | "artist" | "lyrics" | "all"
    val searchSuggestions: List<String> = emptyList(),
    val searchResults: List<Track> = emptyList(),
    val searching: Boolean = false,
    val activeArtistPage: ArtistPageData? = null,
    val loadingArtistPage: Boolean = false,
    val activeAlbumPage: AlbumPageData? = null,
    val loadingAlbumPage: Boolean = false,
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
    val localAudio = app.localAudio
    val recommendations = app.recommendations
    val updater = app.updater
    val cloudSync = app.cloudSync
    val lyricsClient = app.lyricsClient
    val offlineCache = app.offlineCache
    val audioEffects = app.audioEffects
    val player = app.playerController

    val playerState: StateFlow<PlayerUiState> = player.state
    val libraryState: StateFlow<LibraryPayload> = library.state
    val localAudioState: StateFlow<LocalAudioState> = localAudio.state
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
    private var homeRefreshJob: Job? = null
    private var artistPageJob: Job? = null
    private var albumPageJob: Job? = null
    private var autoCacheFavJob: Job? = null
    private val artistHistoryStack = ArrayDeque<ArtistPageData>()

    init {
        refreshHome(forceRefresh = false)
        if (authStore.isLoggedIn()) {
            refreshAccountLibrary()
        }
        if (authStore.isSpotifyLoggedIn()) {
            refreshSpotifyAccount(silent = true)
        }
        if (localAudio.checkPermission()) {
            refreshLocalAudio()
        }
    }

    fun clearStatusMessage() {
        _ui.update { it.copy(statusMessage = null) }
    }

    fun showMessage(msg: String) {
        _ui.update { it.copy(statusMessage = msg) }
    }

    fun refreshHome(forceRefresh: Boolean = false) {
        if (!forceRefresh && (homeRefreshJob?.isActive == true || recommendations.hasFreshHomeCache())) {
            return
        }
        homeRefreshJob?.cancel()
        homeRefreshJob = viewModelScope.launch {
            _ui.update { it.copy(loadingHome = true) }
            val recs = recommendations.buildHomeRecommendations(forceRefresh = forceRefresh)
            _ui.update {
                it.copy(
                    homeRecommendations = recs,
                    homeTracks = recs.forYouTracks,
                    loadingHome = false,
                )
            }
        }
    }

    fun playArtistMix(card: ArtistMixCard) {
        showMessage("Creando Mix de ${card.artistName}...")
        viewModelScope.launch {
            val tracks = recommendations.buildArtistMix(card)
            if (tracks.isEmpty()) {
                showMessage("No se pudo crear el mix ahora mismo.")
            } else {
                player.playTrackList(tracks, 0)
                showMessage("Mix de ${card.artistName} (${tracks.size} canciones)")
            }
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
            val items = if (nextMode == "lyrics") {
                searchByLyricsPhrase(trimmed)
            } else {
                api.search(trimmed, limit = 20, mode = nextMode)
            }
            _ui.update { it.copy(searchResults = items, searching = false) }
        }
    }

    private suspend fun searchByLyricsPhrase(phrase: String): List<Track> = coroutineScope {
        val hits = lyricsClient.searchTracksByLyricPhrase(phrase, limit = 7)
        if (hits.isNotEmpty()) {
            val resolved = hits.map { hit ->
                async(Dispatchers.IO) {
                    api.search("${hit.trackName} ${hit.artistName}", limit = 2, mode = "songs").firstOrNull()
                }
            }.awaitAll().filterNotNull().distinctBy { it.id }
            if (resolved.isNotEmpty()) return@coroutineScope resolved
        }
        api.search("$phrase letra", limit = 16, mode = "songs")
    }

    // ─── PÁGINA REAL DE ARTISTA Y ÁLBUM ───
    fun openArtistPage(nameOrBrowseId: String, fallbackName: String = "") {
        val clean = nameOrBrowseId.trim()
        if (clean.isEmpty()) return
        _ui.value.activeArtistPage?.let { prev ->
            if (prev.browseId != clean && !prev.name.equals(clean, ignoreCase = true)) {
                artistHistoryStack.addLast(prev)
                while (artistHistoryStack.size > 8) artistHistoryStack.removeFirst()
            }
        }
        artistPageJob?.cancel()
        _ui.update {
            it.copy(
                activeAlbumPage = null,
                loadingAlbumPage = false,
                loadingArtistPage = true,
                activeArtistPage = ArtistPageData(
                    browseId = if (clean.startsWith("UC")) clean else "",
                    name = fallbackName.ifBlank { if (clean.startsWith("UC")) "Cargando artista..." else clean },
                ),
            )
        }
        artistPageJob = viewModelScope.launch {
            val data = api.fetchArtistPage(clean, fallbackName = fallbackName)
            if (data != null) {
                _ui.update { it.copy(activeArtistPage = data, loadingArtistPage = false) }
            } else {
                _ui.update { it.copy(activeArtistPage = null, loadingArtistPage = false) }
                showMessage("No se pudo abrir la ficha del artista.")
            }
        }
    }

    fun openAlbumPage(album: ArtistAlbumItem, fallbackArtist: String = "") {
        albumPageJob?.cancel()
        _ui.update {
            it.copy(
                loadingAlbumPage = true,
                activeAlbumPage = AlbumPageData(
                    browseId = album.browseId,
                    title = album.title,
                    artist = fallbackArtist,
                    year = album.year,
                    thumb = album.thumb,
                ),
            )
        }
        albumPageJob = viewModelScope.launch {
            val data = api.fetchAlbumPage(
                browseId = album.browseId,
                fallbackTitle = album.title,
                fallbackArtist = fallbackArtist,
                fallbackThumb = album.thumb,
            )
            if (data != null && data.tracks.isNotEmpty()) {
                _ui.update { it.copy(activeAlbumPage = data, loadingAlbumPage = false) }
            } else {
                _ui.update { it.copy(activeAlbumPage = null, loadingAlbumPage = false) }
                showMessage("No se pudieron cargar las canciones de este álbum.")
            }
        }
    }

    fun closeAlbumPage() {
        albumPageJob?.cancel()
        _ui.update { it.copy(activeAlbumPage = null, loadingAlbumPage = false) }
    }

    fun closeArtistPage() {
        artistPageJob?.cancel()
        if (_ui.value.activeAlbumPage != null) {
            closeAlbumPage()
            return
        }
        if (artistHistoryStack.isNotEmpty()) {
            val prev = artistHistoryStack.removeLast()
            _ui.update { it.copy(activeArtistPage = prev, loadingArtistPage = false) }
        } else {
            _ui.update { it.copy(activeArtistPage = null, loadingArtistPage = false) }
        }
    }

    // ─── ARCHIVOS LOCALES DEL TELÉFONO ───
    fun refreshLocalAudio() {
        viewModelScope.launch {
            localAudio.refresh()
        }
    }

    // ─── AUTO-CACHÉ INTELIGENTE EN WI-FI ───
    fun setAutoCacheWifi(enabled: Boolean) {
        library.updateAutoCacheWifi(enabled)
        showMessage(if (enabled) "Auto-caché en Wi-Fi activado." else "Auto-caché en Wi-Fi desactivado.")
    }

    fun setAutoCacheMaxMb(maxMb: Int) {
        library.updateAutoCacheMaxMb(maxMb)
        val favIds = library.state.value.favorites.map { it.id }.toSet()
        viewModelScope.launch(Dispatchers.IO) {
            offlineCache.evictAutoCacheIfNeeded(maxMb.toLong() * 1024L * 1024L, favIds)
        }
        showMessage("Límite de caché ajustado a ${if (maxMb >= 1024) "${maxMb / 1024} GB" else "$maxMb MB"}.")
    }

    fun setAutoCacheFavorites(enabled: Boolean) {
        library.updateAutoCacheFavorites(enabled)
        if (enabled) {
            syncFavoritesOfflineIfWifi()
            showMessage("Favoritas se guardarán automáticamente en Wi-Fi.")
        } else {
            autoCacheFavJob?.cancel()
            showMessage("Auto-descarga de Favoritas desactivada.")
        }
    }

    fun toggleFavoriteTrack(track: Track): Boolean {
        val added = library.toggleFavorite(track)
        if (added && library.state.value.settings.autoCacheFavorites && SecurityValidators.isValidVideoId(track.id)) {
            val maxMb = library.state.value.settings.autoCacheMaxMb
            val favIds = library.state.value.favorites.map { it.id }.toSet()
            viewModelScope.launch {
                offlineCache.autoCacheIfWifi(track, maxMb = maxMb, protectedIds = favIds, pin = true)
            }
        }
        return added
    }

    private fun syncFavoritesOfflineIfWifi() {
        if (!offlineCache.isOnUnmeteredWifi()) return
        autoCacheFavJob?.cancel()
        autoCacheFavJob = viewModelScope.launch {
            val settings = library.state.value.settings
            val favs = library.state.value.favorites
                .filter { SecurityValidators.isValidVideoId(it.id) && !offlineCache.isCached(it.id) }
                .take(25)
            val favIds = library.state.value.favorites.map { it.id }.toSet()
            for (t in favs) {
                if (!library.state.value.settings.autoCacheFavorites) break
                if (!offlineCache.isOnUnmeteredWifi()) break
                offlineCache.autoCacheIfWifi(t, maxMb = settings.autoCacheMaxMb, protectedIds = favIds, pin = true)
            }
        }
    }

    // ─── COMPARTIR TARJETA DE LETRA Y BROOK WRAPPED ───
    fun shareLyricsCard(track: Track, lines: List<String>) {
        if (lines.isEmpty()) return
        showMessage("Generando tarjeta de letra...")
        viewModelScope.launch {
            val ok = ShareCardRenderer.shareLyricsCard(app, track, lines)
            if (!ok) showMessage("No se pudo generar la tarjeta de letra.")
        }
    }

    fun shareWrappedCard() {
        val stats = library.state.value.stats.values
            .sortedByDescending { it.listenedSec * 2 + it.playCount * 30L }
        if (stats.isEmpty()) {
            showMessage("Escucha algunas canciones primero para generar tu Wrapped.")
            return
        }
        val totalSec = stats.sumOf { it.listenedSec }
        val totalMin = if (totalSec > 0L) (totalSec / 60L).coerceAtLeast(1L) else 0L
        val totalPlays = stats.sumOf { it.playCount }
        val topArtist = stats
            .groupBy { it.track.artist.substringBefore(",").trim().ifBlank { "Artista" } }
            .map { (name, list) ->
                val sec = list.sumOf { it.listenedSec }
                val plays = list.sumOf { it.playCount }
                Triple(name, plays, sec * 2 + plays * 30L)
            }
            .maxByOrNull { it.third }
            ?.let { it.first to it.second }
            ?: ("Tu artista" to 0)

        showMessage("Generando tu tarjeta Brook Wrapped...")
        viewModelScope.launch {
            val ok = ShareCardRenderer.shareWrappedCard(
                context = app,
                totalMinutes = totalMin,
                totalPlays = totalPlays,
                topArtistName = topArtist.first,
                topArtistPlays = topArtist.second,
                topSongs = stats.take(5),
            )
            if (!ok) showMessage("No se pudo compartir la tarjeta Wrapped.")
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
        if (SecurityValidators.isLocalTrackId(track.id)) {
            showMessage("«${track.title}» ya está en el almacenamiento local de tu móvil.")
            return
        }
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
            val remoteTracks = tracks.filter { SecurityValidators.isValidVideoId(it.id) }
            if (remoteTracks.isEmpty()) {
                showMessage("Las canciones de $label ya están en tu almacenamiento local.")
                return@launch
            }
            val pending = remoteTracks.filterNot { offlineCache.isCached(it.id) }.take(30)
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
        viewModelScope.launch(Dispatchers.IO) {
            offlineCache.clearAll()
        }
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
        recommendations.setAccountHistorySeeds(emptyList())
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
            recommendations.setAccountHistorySeeds(history)
            _ui.update {
                it.copy(
                    accountPlaylists = playlists,
                    accountHistory = history,
                    loadingAccount = false,
                )
            }
            if (history.isNotEmpty() && !_ui.value.homeRecommendations.personalized) {
                refreshHome(forceRefresh = true)
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
                spotifyError = null,
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
                spotifyError = null,
                spotifyImport = SpotifyImportProgress(),
                statusMessage = "Sesión de Spotify cerrada.",
            )
        }
    }

    fun refreshSpotifyAccount(silent: Boolean = false) {
        if (!authStore.isSpotifyLoggedIn()) return
        viewModelScope.launch {
            _ui.update { it.copy(loadingSpotify = true, spotifyError = null) }
            val res = spotify.fetchAccountPlaylists()
            res.onSuccess { list ->
                _ui.update { it.copy(spotifyPlaylists = list, loadingSpotify = false, spotifyError = null) }
                if (!silent) {
                    val plCount = (list.size - 1).coerceAtLeast(0)
                    showMessage(
                        if (plCount > 0) "Spotify listo: $plCount playlists y Me gusta cargados."
                        else "Spotify conectado · Canciones que te gustan disponible."
                    )
                }
            }.onFailure { err ->
                val msg = err.message ?: "No se pudieron cargar tus listas de Spotify."
                _ui.update { it.copy(loadingSpotify = false, spotifyError = msg) }
                if (!silent) {
                    showMessage(msg)
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
