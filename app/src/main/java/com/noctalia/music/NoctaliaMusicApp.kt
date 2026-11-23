package com.noctalia.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.noctalia.music.data.AppUpdater
import com.noctalia.music.data.AuthStore
import com.noctalia.music.data.GitHubCloudSync
import com.noctalia.music.data.LibraryRepository
import com.noctalia.music.data.LocalAudioRepository
import com.noctalia.music.data.LyricsClient
import com.noctalia.music.data.OfflineCacheManager
import com.noctalia.music.data.RecommendationEngine
import com.noctalia.music.data.SponsorBlockClient
import com.noctalia.music.data.SpotifyClient
import com.noctalia.music.data.YTMusicApi
import com.noctalia.music.player.AudioEffectsController
import com.noctalia.music.player.MusicPlayerController

class NoctaliaMusicApp : Application(), ImageLoaderFactory {

    lateinit var authStore: AuthStore
        private set
    lateinit var api: YTMusicApi
        private set
    lateinit var spotifyClient: SpotifyClient
        private set
    lateinit var library: LibraryRepository
        private set
    lateinit var localAudio: LocalAudioRepository
        private set
    lateinit var recommendations: RecommendationEngine
        private set
    lateinit var updater: AppUpdater
        private set
    lateinit var cloudSync: GitHubCloudSync
        private set
    lateinit var lyricsClient: LyricsClient
        private set
    lateinit var sponsorBlock: SponsorBlockClient
        private set
    lateinit var offlineCache: OfflineCacheManager
        private set
    lateinit var audioEffects: AudioEffectsController
        private set
    lateinit var playerController: MusicPlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        authStore = AuthStore(this)
        api = YTMusicApi(authStore)
        spotifyClient = SpotifyClient(authStore, api)
        library = LibraryRepository(this)
        localAudio = LocalAudioRepository(this)
        recommendations = RecommendationEngine(api, library)
        updater = AppUpdater(this)
        cloudSync = GitHubCloudSync(this, library)
        lyricsClient = LyricsClient()
        sponsorBlock = SponsorBlockClient()
        offlineCache = OfflineCacheManager(this, api)
        audioEffects = AudioEffectsController(this, library)
        playerController = MusicPlayerController(
            this,
            api,
            library,
            localAudio,
            recommendations,
            lyricsClient,
            sponsorBlock,
            offlineCache,
            audioEffects,
        )
    }

    /**
     * Obliga a Coil a descargar todas las carátulas remotas mediante `api.thumbHttpClient`
     * (HTTPS + dominios oficiales de YouTube/Google/Spotify, tope 3 MiB), manteniendo soporte
     * nativo de ContentResolver para carátulas locales (`content://media/external/audio/albumart/...`).
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(api.thumbHttpClient)
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: NoctaliaMusicApp
            private set
    }
}
