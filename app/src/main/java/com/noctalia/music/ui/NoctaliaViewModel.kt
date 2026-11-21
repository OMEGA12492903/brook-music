package com.noctalia.music.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.noctalia.music.NoctaliaMusicApp
import com.noctalia.music.data.AppUpdateInfo
import com.noctalia.music.data.LibraryPayload
import com.noctalia.music.data.RemotePlaylist
import com.noctalia.music.data.SecurityValidators
import com.noctalia.music.data.Track
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
    val homeTracks: List<Track> = emptyList(),
    val loadingHome: Boolean = false,
    val searchQuery: String = "",
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
    val library = app.library
    val updater = app.updater
    val player = app.playerController

    val playerState: StateFlow<PlayerUiState> = player.state
    val libraryState: StateFlow<LibraryPayload> = library.state

    private val _ui = MutableStateFlow(
        MainUiState(
            isLoggedIn = authStore.isLoggedIn(),
            accountLabel = authStore.getAccountLabel(),
        )
    )
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private var searchJob: Job? = null

    init {
        refreshHome()
        if (authStore.isLoggedIn()) {
            refreshAccountLibrary()
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

    fun onSearchQueryChanged(query: String, immediate: Boolean = false) {
        _ui.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _ui.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(420L)
            _ui.update { it.copy(searching = true) }
            val items = api.search(trimmed, limit = 20)
            _ui.update { it.copy(searchResults = items, searching = false) }
        }
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
        val listId = SecurityValidators.extractPlaylistIdFromUrl(urlInput)
        if (listId == null) {
            showMessage("Enlace de playlist no válido.")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(loadingAccount = true) }
            val tracks = api.fetchPlaylistTracks(listId, useAuth = authStore.isLoggedIn())
            _ui.update { it.copy(loadingAccount = false) }
            if (tracks.isEmpty()) {
                showMessage("No se encontraron canciones (asegúrate de que la playlist sea Pública u Oculta).")
            } else {
                val count = library.savePlaylistWithTracks("Playlist YT", tracks)
                showMessage("Importadas $count canciones a «Playlist YT».")
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
}
