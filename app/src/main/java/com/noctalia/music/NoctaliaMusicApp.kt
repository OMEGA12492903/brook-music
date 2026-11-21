package com.noctalia.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.noctalia.music.data.AppUpdater
import com.noctalia.music.data.AuthStore
import com.noctalia.music.data.LibraryRepository
import com.noctalia.music.data.YTMusicApi
import com.noctalia.music.player.MusicPlayerController

class NoctaliaMusicApp : Application(), ImageLoaderFactory {

    lateinit var authStore: AuthStore
        private set
    lateinit var api: YTMusicApi
        private set
    lateinit var library: LibraryRepository
        private set
    lateinit var updater: AppUpdater
        private set
    lateinit var playerController: MusicPlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        authStore = AuthStore(this)
        api = YTMusicApi(authStore)
        library = LibraryRepository(this)
        updater = AppUpdater(this)
        playerController = MusicPlayerController(this, api, library)
    }

    /**
     * Obliga a Coil a descargar todas las carátulas mediante `api.thumbHttpClient`,
     * que valida HTTPS + dominios oficiales de YouTube/Google en cada salto/redirección y tope 3 MiB.
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
