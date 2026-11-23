package com.noctalia.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

data class SpotifyTrackSeed(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
)

data class SpotifyCollection(
    val kind: String, // "playlist" | "album" | "track" | "artist" | "liked" | "csv"
    val id: String,
    val title: String,
    val coverUrl: String = "",
    val tracks: List<SpotifyTrackSeed> = emptyList(),
)

data class SpotifyRemotePlaylist(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumb: String,
    val isLikedSongs: Boolean = false,
)

data class SpotifyImportProgress(
    val title: String = "",
    val matched: Int = 0,
    val total: Int = 0,
    val active: Boolean = false,
)

/**
 * Cliente endurecido de importación y sincronización de Spotify -> Brook:
 * - Enlaces públicos/compartidos (playlist, álbum, canción, artista y enlaces cortos `spotify.link`):
 *   0 login, 0 API keys, mediante `open.spotify.com/embed/...` (`__NEXT_DATA__`).
 * - Cuenta Spotify conectada (opcional): obtiene tus listas privadas, playlists de >100 canciones
 *   y «Canciones que te gustan» con token cifrado en Android Keystore.
 * - Importador CSV (Exportify / TuneMyMusic) 100% local sin límites.
 * - Emparejador inteligente en paralelo (Semaphore 4) contra YouTube Music con puntuación por
 *   título, artista, duración y filtro anti-covers/karaoke/remixes no pedidos.
 */
class SpotifyClient(
    private val authStore: AuthStore,
    private val ytApi: YTMusicApi,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Cliente público aislado (0 cookies, redirecciones controladas manualmente, solo dominios Spotify)
    private val publicClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            val u = req.url
            if (u.scheme != "https" || u.host.lowercase() !in ALLOWED_PUBLIC_HOSTS) {
                throw IOException("Blocked non-Spotify host: ${u.host}")
            }
            val clean = req.newBuilder()
                .removeHeader("Cookie")
                .removeHeader("Authorization")
                .build()
            chain.proceed(clean)
        })
        .build()

    // Cliente autenticado aislado (SIN redirecciones, SOLO api.spotify.com y api-partner.spotify.com)
    private val authClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            val u = req.url
            if (u.scheme != "https" || u.host.lowercase() !in ALLOWED_AUTH_HOSTS) {
                throw IOException("Blocked non-Spotify API host: ${u.host}")
            }
            val clean = req.newBuilder()
                .removeHeader("Cookie")
                .build()
            chain.proceed(clean)
        })
        .build()

    // Caché en memoria de hashes Persisted Query de Spotify Pathfinder (actualizable en vivo desde web-player.*.js)
    private val pathfinderHashes = ConcurrentHashMap<String, String>(DEFAULT_PATHFINDER_HASHES)
    @Volatile
    private var hashesRefreshedAtMs: Long = 0L

    private fun readBounded(resp: Response, maxBytes: Long = MAX_BODY_BYTES): String? {
        val src = resp.body?.source() ?: return null
        src.request(maxBytes + 1)
        if (src.buffer.size > maxBytes) return null
        return src.buffer.readUtf8()
    }

    /**
     * Resuelve enlaces cortos (`spotify.link`, `spoti.fi`, `spotify.app.link`) de forma segura
     * (máx. 4 saltos HTTPS dentro de hosts oficiales de Spotify) y extrae `(tipo, id)`.
     */
    suspend fun resolveEntityFromInput(rawInput: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val extracted = SecurityValidators.extractSpotifyUrlFromSharedText(rawInput) ?: rawInput.trim().take(2048)
        parseDirectEntity(extracted)?.let { return@withContext it }

        var currentUrl = extracted
        repeat(4) {
            val uri = try {
                URI(currentUrl)
            } catch (_: Exception) {
                return@withContext null
            }
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return@withContext null
            if (!uri.scheme.equals("https", ignoreCase = true) || host !in ALLOWED_PUBLIC_HOSTS) {
                return@withContext null
            }
            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", DESKTOP_UA)
                .get()
                .build()
            val nextLoc = try {
                publicClient.newCall(req).execute().use { resp ->
                    if (resp.code in 300..399) {
                        resp.header("Location")?.trim()
                    } else if (resp.isSuccessful) {
                        // Algunos enlaces spotify.link redirigen mediante canonical/og:url o enlace en HTML
                        val body = readBounded(resp, maxBytes = 256L * 1024L).orEmpty()
                        val targetMatch = CANONICAL_ENTITY_RE.find(body) ?: EMBED_ENTITY_IN_TEXT_RE.find(body)
                        if (targetMatch != null) {
                            return@withContext targetMatch.groupValues[1].lowercase() to targetMatch.groupValues[2]
                        }
                        null
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val resolvedNext = try {
                uri.resolve(nextLoc).toString()
            } catch (_: Exception) {
                nextLoc
            }
            parseDirectEntity(resolvedNext)?.let { return@withContext it }
            currentUrl = resolvedNext
        }
        null
    }

    private fun parseDirectEntity(input: String): Pair<String, String>? {
        val m = DIRECT_ENTITY_RE.find(input) ?: return null
        return m.groupValues[1].lowercase() to m.groupValues[2]
    }

    /**
     * Obtiene una playlist, álbum, canción o top de artista de Spotify a partir de su enlace.
     * Si el usuario conectó su cuenta Spotify y es una playlist, intenta primero la API completa
     * (soporta privadas y >100 canciones) y si falla recurre al Embed público (0 login).
     */
    suspend fun fetchFromUrl(rawInput: String): Result<SpotifyCollection> = withContext(Dispatchers.IO) {
        val (kind, id) = resolveEntityFromInput(rawInput)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Enlace de Spotify no válido. Pega una playlist, álbum, canción o artista.")
            )

        var expiredSessionErr: Throwable? = null
        if (kind == "playlist" && authStore.isSpotifyLoggedIn()) {
            val pfRes = fetchPlaylistViaPathfinder(id)
            if (pfRes.isSuccess && pfRes.getOrThrow().tracks.isNotEmpty()) {
                return@withContext pfRes
            }
            if (pfRes.exceptionOrNull() is SecurityException) {
                expiredSessionErr = pfRes.exceptionOrNull()
            }
            val authRes = fetchPlaylistViaApi(id)
            if (authRes.isSuccess && authRes.getOrThrow().tracks.isNotEmpty()) {
                return@withContext authRes
            }
            if (authRes.exceptionOrNull() is SecurityException) {
                expiredSessionErr = authRes.exceptionOrNull()
            }
        }
        val embedRes = fetchPublicEmbed(kind, id)
        if (embedRes.isFailure && expiredSessionErr != null) {
            Result.failure(expiredSessionErr)
        } else {
            embedRes
        }
    }

    private fun fetchPublicEmbed(kind: String, id: String): Result<SpotifyCollection> {
        if (kind !in ALLOWED_KINDS || !SPOTIFY_ID_RE.matches(id)) {
            return Result.failure(IllegalArgumentException("Identificador de Spotify inválido."))
        }
        val req = Request.Builder()
            .url("https://open.spotify.com/embed/$kind/$id")
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .get()
            .build()

        return try {
            publicClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(
                        IOException(
                            if (resp.code == 404) {
                                "No se encontró esa lista de Spotify (si es privada, conecta tu cuenta Spotify)."
                            } else {
                                "Spotify devolvió HTTP ${resp.code}."
                            }
                        )
                    )
                }
                val html = readBounded(resp)
                    ?: return Result.failure(IOException("Respuesta de Spotify demasiado grande."))
                val m = NEXT_DATA_RE.find(html)
                    ?: return Result.failure(IOException("No se pudieron leer los datos de Spotify."))

                val root = json.parseToJsonElement(m.groupValues[1]).jsonObject
                val entity = root.spObj("props")
                    ?.spObj("pageProps")
                    ?.spObj("state")
                    ?.spObj("data")
                    ?.spObj("entity")
                    ?: return Result.failure(IOException("Spotify no devolvió canciones para ese enlace."))

                val name = cleanText(entity.spStr("name") ?: entity.spStr("title")).ifBlank { "Spotify" }
                val cover = extractCoverUrl(entity)

                val seeds = mutableListOf<SpotifyTrackSeed>()
                if (kind == "track") {
                    val artists = entity.spArr("artists")
                        ?.mapNotNull { cleanText(it.asSpObj()?.spStr("name")).takeIf { s -> s.isNotEmpty() } }
                        ?.joinToString(", ")
                        .orEmpty()
                        .ifBlank { cleanText(entity.spStr("subtitle")) }
                    val durMs = entity.spLong("duration") ?: 0L
                    seeds.add(SpotifyTrackSeed(title = name, artist = artists, durationMs = durMs))
                } else {
                    val trackList = entity.spArr("trackList").orEmpty()
                    for (item in trackList.take(MAX_IMPORT_TRACKS)) {
                        val o = item.asSpObj() ?: continue
                        val tTitle = cleanText(o.spStr("title") ?: o.spStr("name"))
                        if (tTitle.isEmpty()) continue
                        val tArtist = cleanText(o.spStr("subtitle"))
                        val durMs = o.spLong("duration") ?: 0L
                        seeds.add(
                            SpotifyTrackSeed(
                                title = tTitle,
                                artist = tArtist,
                                album = if (kind == "album") name else "",
                                durationMs = durMs,
                            )
                        )
                    }
                }

                if (seeds.isEmpty()) {
                    Result.failure(IOException("La lista de Spotify está vacía o es privada."))
                } else {
                    Result.success(
                        SpotifyCollection(
                            kind = kind,
                            id = id,
                            title = name.take(36),
                            coverUrl = cover,
                            tracks = seeds,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(IOException("Error al leer Spotify (${e.message?.take(60) ?: "red"})."))
        }
    }

    // ─── API AUTENTICADA DE CUENTA SPOTIFY (PATHFINDER GRAPHQL WEB PLAYER + RESPALDO REST) ───

    private fun refreshPathfinderHashesFromWebPlayer(): Boolean {
        val now = System.currentTimeMillis()
        if (now - hashesRefreshedAtMs < 60_000L) return false
        hashesRefreshedAtMs = now
        return try {
            val htmlReq = Request.Builder()
                .url("https://open.spotify.com/")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .get()
                .build()
            val html = publicClient.newCall(htmlReq).execute().use { resp ->
                if (!resp.isSuccessful) return false
                readBounded(resp, maxBytes = 2L * 1024L * 1024L)
            } ?: return false

            val bundleUrl = WEB_PLAYER_JS_RE.find(html)?.groupValues?.getOrNull(1) ?: return false
            val jsReq = Request.Builder()
                .url(bundleUrl)
                .header("User-Agent", DESKTOP_UA)
                .get()
                .build()
            val js = publicClient.newCall(jsReq).execute().use { resp ->
                if (!resp.isSuccessful) return false
                readBounded(resp, maxBytes = 14L * 1024L * 1024L)
            } ?: return false

            var updated = 0
            for (m in PATHFINDER_HASH_RE.findAll(js)) {
                val op = m.groupValues[1]
                val hash = m.groupValues[2]
                if (op in TRACKED_PATHFINDER_OPS) {
                    pathfinderHashes[op] = hash
                    updated++
                }
            }
            updated > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun pathfinderQuery(operationName: String, variables: JsonObject): Result<JsonObject> {
        val bearer = authStore.getSpotifyBearerToken()
            ?: return Result.failure(SecurityException("Conecta primero tu cuenta Spotify."))
        val clientToken = authStore.getSpotifyClientToken()

        fun executeOnce(endpointUrl: String, hash: String): Result<JsonObject> {
            val payload = buildJsonObject {
                put("variables", variables)
                put("operationName", operationName)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", hash)
                    }
                }
            }.toString()

            val reqBuilder = Request.Builder()
                .url(endpointUrl)
                .header("Authorization", "Bearer $bearer")
                .header("app-platform", "WebPlayer")
                .header("User-Agent", DESKTOP_UA)
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            if (!clientToken.isNullOrBlank()) {
                reqBuilder.header("client-token", clientToken)
            }

            return try {
                authClient.newCall(reqBuilder.build()).execute().use { resp ->
                    when (resp.code) {
                        in 200..299 -> {
                            val txt = readBounded(resp)
                                ?: return Result.failure(IOException("Respuesta demasiado grande."))
                            Result.success(json.parseToJsonElement(txt).jsonObject)
                        }
                        401 -> Result.failure(
                            SecurityException("Sesión de Spotify caducada. Pulsa «Reconectar».")
                        )
                        403 -> Result.failure(
                            SecurityException("Spotify denegó el acceso (403). Pulsa «Reconectar».")
                        )
                        429 -> Result.failure(IOException("Spotify pidió esperar unos segundos (límite de peticiones)."))
                        else -> Result.failure(IOException("Spotify Pathfinder devolvió HTTP ${resp.code}."))
                    }
                }
            } catch (e: Exception) {
                Result.failure(IOException("No se pudo conectar con Spotify (${e.message?.take(45) ?: "red"})."))
            }
        }

        val initialHash = pathfinderHashes[operationName]
            ?: return Result.failure(IOException("Operación Pathfinder desconocida: $operationName"))

        var res = executeOnce(PATHFINDER_V2_URL, initialHash)
        if (res.isFailure && res.exceptionOrNull() !is SecurityException) {
            val v1Res = executeOnce(PATHFINDER_V1_URL, initialHash)
            if (v1Res.isSuccess) res = v1Res
        }

        val root = res.getOrNull()
        val errorsText = root?.spArr("errors")?.toString().orEmpty()
        if (root != null && errorsText.contains("PersistedQueryNotFound", ignoreCase = true)) {
            if (refreshPathfinderHashesFromWebPlayer()) {
                val freshHash = pathfinderHashes[operationName]
                if (!freshHash.isNullOrBlank() && freshHash != initialHash) {
                    res = executeOnce(PATHFINDER_V2_URL, freshHash)
                }
            }
        }

        val finalRoot = res.getOrNull()
        if (finalRoot != null && finalRoot.spObj("data") == null && finalRoot.spArr("errors") != null) {
            val msg = finalRoot.spArr("errors")?.firstOrNull()?.asSpObj()?.spStr("message") ?: "Error GraphQL"
            return Result.failure(IOException("Spotify: $msg"))
        }
        return res
    }

    private fun extractPathfinderImageUrl(imagesObj: JsonObject?): String {
        if (imagesObj == null) return ""
        val firstItem = imagesObj.spArr("items")?.firstOrNull()?.asSpObj()
        val fromItemSources = firstItem?.spArr("sources")?.firstOrNull()?.asSpObj()?.spStr("url")
        if (SecurityValidators.isSafeThumbUrl(fromItemSources)) return fromItemSources!!
        val fromDirectSources = imagesObj.spArr("sources")?.firstOrNull()?.asSpObj()?.spStr("url")
        if (SecurityValidators.isSafeThumbUrl(fromDirectSources)) return fromDirectSources!!
        val fromItemUrl = firstItem?.spStr("url")
        if (SecurityValidators.isSafeThumbUrl(fromItemUrl)) return fromItemUrl!!
        return ""
    }

    private fun parsePathfinderTrackData(tData: JsonObject?): SpotifyTrackSeed? {
        if (tData == null) return null
        val typeName = tData.spStr("__typename")
        if (typeName != null && typeName != "Track") return null
        val title = cleanText(tData.spStr("name"))
        if (title.isEmpty()) return null
        val artists = tData.spObj("artists")?.spArr("items")
            ?.mapNotNull { el ->
                val o = el.asSpObj() ?: return@mapNotNull null
                cleanText(o.spObj("profile")?.spStr("name") ?: o.spStr("name"))
                    .takeIf { it.isNotEmpty() }
            }
            ?.joinToString(", ")
            .orEmpty()
        val album = cleanText(tData.spObj("albumOfTrack")?.spStr("name"))
        val durMs = tData.spObj("trackDuration")?.spLong("totalMilliseconds")
            ?: tData.spObj("duration")?.spLong("totalMilliseconds")
            ?: 0L
        return SpotifyTrackSeed(title = title, artist = artists, album = album, durationMs = durMs)
    }

    private fun fetchAccountPlaylistsViaPathfinder(): Result<List<SpotifyRemotePlaylist>> {
        var likedCount: Int? = null
        var likedThumb = ""
        val playlists = mutableListOf<SpotifyRemotePlaylist>()
        val seen = HashSet<String>()
        var offset = 0
        var pages = 0

        var usePlaylistFilter = true
        while (pages < 2 && playlists.size < 150) {
            fun buildLibVars(filterPlaylists: Boolean) = buildJsonObject {
                putJsonArray("filters") { if (filterPlaylists) add("Playlists") }
                put("order", JsonNull)
                put("textFilter", "")
                putJsonArray("features") { add("LIKED_SONGS") }
                put("limit", 100)
                put("offset", offset)
                put("flatten", true)
                putJsonArray("expandedFolders") {}
                put("folderUri", JsonNull)
                put("includeFoldersWhenFlattening", false)
            }
            var res = pathfinderQuery("libraryV3", buildLibVars(usePlaylistFilter))
            if (res.isSuccess && usePlaylistFilter) {
                val tn = res.getOrThrow().spObj("data")?.spObj("me")?.spObj("libraryV3")?.spStr("__typename")
                if (tn == "LibraryInvalidFilterIdError") {
                    usePlaylistFilter = false
                    res = pathfinderQuery("libraryV3", buildLibVars(false))
                }
            }
            if (res.isFailure) {
                if (pages == 0) return Result.failure(res.exceptionOrNull()!!)
                break
            }
            val lib = res.getOrThrow().spObj("data")?.spObj("me")?.spObj("libraryV3")
                ?: return Result.failure(IOException("Spotify no devolvió tu biblioteca."))
            if (lib.spStr("__typename") != "LibraryPage" && lib.spArr("items") == null) {
                return Result.failure(IOException("Spotify devolvió un formato inesperado en biblioteca."))
            }
            val items = lib.spArr("items").orEmpty()
            for (el in items) {
                val entry = el.asSpObj() ?: continue
                val wrapper = entry.spObj("item") ?: continue
                val wrapperType = wrapper.spStr("__typename").orEmpty()
                val dataObj = wrapper.spObj("data") ?: continue
                when (wrapperType) {
                    "LibraryPseudoPlaylistResponseWrapper" -> {
                        if (dataObj.spStr("__typename") == "PseudoPlaylist") {
                            likedCount = dataObj.spInt("count")
                            likedThumb = extractPathfinderImageUrl(dataObj.spObj("image"))
                        }
                    }
                    "PlaylistResponseWrapper" -> {
                        if (dataObj.spStr("__typename") != "Playlist") continue
                        val rawUri = wrapper.spStr("_uri") ?: dataObj.spStr("uri").orEmpty()
                        val id = rawUri.substringAfterLast(":").takeIf { SPOTIFY_ID_RE.matches(it) } ?: continue
                        if (!seen.add(id)) continue
                        val title = cleanText(dataObj.spStr("name")).ifBlank { "Playlist Spotify" }.take(36)
                        val owner = cleanText(
                            dataObj.spObj("ownerV2")?.spObj("data")?.spStr("name")
                                ?: dataObj.spObj("ownerV2")?.spObj("data")?.spStr("username")
                        )
                        val total = dataObj.spObj("content")?.spInt("totalCount")
                        val subtitle = buildString {
                            if (total != null && total >= 0) append("$total canciones")
                            if (owner.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append(owner)
                            }
                            if (isEmpty()) append("Playlist de Spotify")
                        }
                        val thumb = extractPathfinderImageUrl(dataObj.spObj("images"))
                        playlists.add(SpotifyRemotePlaylist(id = id, title = title, subtitle = subtitle, thumb = thumb))
                    }
                }
            }
            val totalCount = lib.spInt("totalCount") ?: items.size
            offset += items.size
            pages++
            if (items.isEmpty() || offset >= totalCount) break
        }

        val likedSubtitle = if (likedCount != null && likedCount > 0) {
            "$likedCount canciones · Se guardan en Favoritas"
        } else {
            "Tus Me gusta de Spotify · Se guardan en Favoritas"
        }
        val out = mutableListOf(
            SpotifyRemotePlaylist(
                id = "LIKED_SONGS",
                title = "Canciones que te gustan",
                subtitle = likedSubtitle,
                thumb = likedThumb,
                isLikedSongs = true,
            )
        )
        out.addAll(playlists)
        return Result.success(out)
    }

    private fun fetchLikedSongsViaPathfinder(): Result<SpotifyCollection> {
        val seeds = mutableListOf<SpotifyTrackSeed>()
        var offset = 0
        var pages = 0

        while (pages < 6 && seeds.size < MAX_IMPORT_TRACKS) {
            val vars = buildJsonObject {
                put("offset", offset)
                put("limit", 50)
            }
            val res = pathfinderQuery("fetchLibraryTracks", vars)
            if (res.isFailure) {
                if (pages == 0) return Result.failure(res.exceptionOrNull()!!)
                break
            }
            val tracksObj = res.getOrThrow().spObj("data")?.spObj("me")?.spObj("library")?.spObj("tracks")
                ?: return Result.failure(IOException("No se pudieron leer tus canciones favoritas de Spotify."))
            val items = tracksObj.spArr("items").orEmpty()
            for (el in items) {
                val tData = el.asSpObj()?.spObj("track")?.spObj("data")
                parsePathfinderTrackData(tData)?.let { seeds.add(it) }
                if (seeds.size >= MAX_IMPORT_TRACKS) break
            }
            val total = tracksObj.spInt("totalCount") ?: items.size
            offset += items.size
            pages++
            if (items.isEmpty() || offset >= total) break
        }

        return if (seeds.isEmpty()) {
            Result.failure(IOException("No se encontraron canciones en «Me gusta» de Spotify."))
        } else {
            Result.success(
                SpotifyCollection(
                    kind = "liked",
                    id = "LIKED_SONGS",
                    title = "Favoritas Spotify",
                    tracks = seeds,
                )
            )
        }
    }

    private fun fetchPlaylistViaPathfinder(playlistId: String, fallbackTitle: String = "Spotify"): Result<SpotifyCollection> {
        if (!SPOTIFY_ID_RE.matches(playlistId)) {
            return Result.failure(IllegalArgumentException("ID de playlist inválido."))
        }
        var title = fallbackTitle
        var cover = ""
        val seeds = mutableListOf<SpotifyTrackSeed>()
        var offset = 0
        var pages = 0

        while (pages < 4 && seeds.size < MAX_IMPORT_TRACKS) {
            val vars = buildJsonObject {
                put("uri", "spotify:playlist:$playlistId")
                put("offset", offset)
                put("limit", 100)
                put("enableWatchFeedEntrypoint", false)
                put("includeEpisodeContentRatingsV2", false)
            }
            val res = pathfinderQuery("fetchPlaylist", vars)
            if (res.isFailure) {
                if (pages == 0) return Result.failure(res.exceptionOrNull()!!)
                break
            }
            val pl = res.getOrThrow().spObj("data")?.spObj("playlistV2")
            if (pl == null || pl.spStr("__typename") != "Playlist") {
                if (pages == 0) return Result.failure(IOException("No se pudo abrir la playlist en Spotify."))
                break
            }
            if (pages == 0) {
                cleanText(pl.spStr("name")).takeIf { it.isNotEmpty() }?.let { title = it }
                cover = extractPathfinderImageUrl(pl.spObj("images"))
            }
            val content = pl.spObj("content") ?: break
            val items = content.spArr("items").orEmpty()
            for (el in items) {
                val itemV2 = el.asSpObj()?.spObj("itemV2") ?: continue
                if (itemV2.spStr("__typename") != "TrackResponseWrapper") continue
                parsePathfinderTrackData(itemV2.spObj("data"))?.let { seeds.add(it) }
                if (seeds.size >= MAX_IMPORT_TRACKS) break
            }
            val total = content.spInt("totalCount") ?: items.size
            offset += items.size
            pages++
            if (items.isEmpty() || offset >= total) break
        }

        return if (seeds.isEmpty()) {
            Result.failure(IOException("La playlist de Spotify está vacía."))
        } else {
            Result.success(
                SpotifyCollection(
                    kind = "playlist",
                    id = playlistId,
                    title = title.take(36),
                    coverUrl = cover,
                    tracks = seeds,
                )
            )
        }
    }

    private fun authGetJson(url: String): Result<JsonObject> {
        val bearer = authStore.getSpotifyBearerToken()
            ?: return Result.failure(SecurityException("Conecta primero tu cuenta Spotify."))
        val clientToken = authStore.getSpotifyClientToken()

        fun executeRequest(includeWebPlayerHeaders: Boolean): Result<JsonObject> {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $bearer")
                .header("User-Agent", DESKTOP_UA)
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .header("Accept", "application/json")
                .get()
            if (includeWebPlayerHeaders) {
                reqBuilder.header("app-platform", "WebPlayer")
                if (!clientToken.isNullOrBlank()) {
                    reqBuilder.header("client-token", clientToken)
                }
            }
            return try {
                authClient.newCall(reqBuilder.build()).execute().use { resp ->
                    when (resp.code) {
                        in 200..299 -> {
                            val txt = readBounded(resp)
                                ?: return Result.failure(IOException("Respuesta demasiado grande."))
                            Result.success(json.parseToJsonElement(txt).jsonObject)
                        }
                        401 -> Result.failure(
                            SecurityException("Sesión de Spotify caducada. Pulsa «Reconectar».")
                        )
                        403 -> Result.failure(
                            SecurityException("Spotify denegó el acceso (403). Pulsa «Reconectar» o importa la lista por enlace.")
                        )
                        429 -> Result.failure(IOException("Spotify pidió esperar unos segundos (límite de peticiones)."))
                        else -> Result.failure(IOException("Spotify devolvió HTTP ${resp.code}."))
                    }
                }
            } catch (e: Exception) {
                Result.failure(IOException("No se pudo conectar con Spotify (${e.message?.take(45) ?: "red"})."))
            }
        }

        val first = executeRequest(includeWebPlayerHeaders = true)
        if (first.isSuccess) return first
        // Si falló con 401/403 enviando `client-token` / `app-platform`, reintentar una vez solo con Bearer
        if (first.exceptionOrNull() is SecurityException) {
            val retry = executeRequest(includeWebPlayerHeaders = false)
            if (retry.isSuccess) return retry
        }
        return first
    }

    suspend fun fetchAccountPlaylists(): Result<List<SpotifyRemotePlaylist>> = withContext(Dispatchers.IO) {
        if (!authStore.isSpotifyLoggedIn()) {
            return@withContext Result.failure(SecurityException("No has conectado tu cuenta Spotify."))
        }
        // 1. Usar Pathfinder GraphQL (`libraryV3`), la API real del Web Player de Spotify en 2026
        val pfRes = fetchAccountPlaylistsViaPathfinder()
        if (pfRes.isSuccess) return@withContext pfRes

        // 2. Respaldo REST `/v1/me/playlists` por si el token también tiene permisos Web API
        val out = mutableListOf(
            SpotifyRemotePlaylist(
                id = "LIKED_SONGS",
                title = "Canciones que te gustan",
                subtitle = "Tus Me gusta de Spotify · Se guardan en Favoritas",
                thumb = "",
                isLikedSongs = true,
            )
        )
        val seen = HashSet<String>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        var pages = 0

        while (nextUrl != null && pages < 3 && out.size < 100) {
            val res = authGetJson(nextUrl)
            if (res.isFailure) {
                if (pages == 0) return@withContext Result.failure(pfRes.exceptionOrNull() ?: res.exceptionOrNull()!!)
                break
            }
            val root = res.getOrThrow()
            val items = root.spArr("items").orEmpty()
            for (el in items) {
                val o = el.asSpObj() ?: continue
                val id = o.spStr("id")?.takeIf { SPOTIFY_ID_RE.matches(it) } ?: continue
                if (!seen.add(id)) continue
                val title = cleanText(o.spStr("name")).ifBlank { "Playlist Spotify" }.take(36)
                val total = o.spObj("tracks")?.spInt("total") ?: o.spObj("items")?.spInt("total")
                val owner = cleanText(o.spObj("owner")?.spStr("display_name"))
                val subtitle = buildString {
                    if (total != null && total >= 0) append("$total canciones")
                    if (owner.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(owner)
                    }
                    if (isEmpty()) append("Playlist de Spotify")
                }
                val thumb = o.spArr("images")
                    ?.firstOrNull()?.asSpObj()?.spStr("url")
                    ?.takeIf { SecurityValidators.isSafeThumbUrl(it) }
                    .orEmpty()
                out.add(SpotifyRemotePlaylist(id = id, title = title, subtitle = subtitle, thumb = thumb))
            }
            val candidateNext = root.spStr("next")
            nextUrl = candidateNext?.takeIf { it.startsWith("https://api.spotify.com/v1/") }
            pages++
        }
        Result.success(out)
    }

    suspend fun fetchRemotePlaylistCollection(pl: SpotifyRemotePlaylist): Result<SpotifyCollection> =
        withContext(Dispatchers.IO) {
            if (pl.isLikedSongs) {
                val pf = fetchLikedSongsViaPathfinder()
                if (pf.isSuccess) pf else fetchLikedSongsViaApi()
            } else {
                val pf = fetchPlaylistViaPathfinder(pl.id, fallbackTitle = pl.title)
                if (pf.isSuccess) return@withContext pf
                val apiRes = fetchPlaylistViaApi(pl.id, fallbackTitle = pl.title)
                if (apiRes.isSuccess) apiRes else fetchPublicEmbed("playlist", pl.id)
            }
        }

    private fun fetchLikedSongsViaApi(): Result<SpotifyCollection> {
        val seeds = mutableListOf<SpotifyTrackSeed>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
        var pages = 0

        while (nextUrl != null && pages < 6 && seeds.size < MAX_IMPORT_TRACKS) {
            val res = authGetJson(nextUrl)
            if (res.isFailure) {
                if (pages == 0) return Result.failure(res.exceptionOrNull()!!)
                break
            }
            val root = res.getOrThrow()
            for (el in root.spArr("items").orEmpty()) {
                val o = el.asSpObj() ?: continue
                val tr = o.spObj("item") ?: o.spObj("track") ?: continue
                parseApiTrackObject(tr)?.let { seeds.add(it) }
                if (seeds.size >= MAX_IMPORT_TRACKS) break
            }
            nextUrl = root.spStr("next")?.takeIf { it.startsWith("https://api.spotify.com/v1/") }
            pages++
        }

        return if (seeds.isEmpty()) {
            Result.failure(IOException("No se encontraron canciones en «Me gusta» de Spotify."))
        } else {
            Result.success(
                SpotifyCollection(
                    kind = "liked",
                    id = "LIKED_SONGS",
                    title = "Favoritas Spotify",
                    tracks = seeds,
                )
            )
        }
    }

    private fun fetchPlaylistViaApi(playlistId: String, fallbackTitle: String = "Spotify"): Result<SpotifyCollection> {
        if (!SPOTIFY_ID_RE.matches(playlistId)) {
            return Result.failure(IllegalArgumentException("ID de playlist inválido."))
        }
        val metaRes = authGetJson("https://api.spotify.com/v1/playlists/$playlistId?fields=name,images")
        val title = metaRes.getOrNull()?.let { cleanText(it.spStr("name")) }
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackTitle
        val cover = metaRes.getOrNull()?.spArr("images")
            ?.firstOrNull()?.asSpObj()?.spStr("url")
            ?.takeIf { SecurityValidators.isSafeThumbUrl(it) }
            .orEmpty()

        val seeds = mutableListOf<SpotifyTrackSeed>()
        // Spotify migró en 2026 de `/tracks` a `/items`; probar primero `/items` y respaldar con `/tracks`
        var nextUrl: String? = "https://api.spotify.com/v1/playlists/$playlistId/items?limit=100"
        var pages = 0

        while (nextUrl != null && pages < 4 && seeds.size < MAX_IMPORT_TRACKS) {
            var res = authGetJson(nextUrl)
            if (res.isFailure && pages == 0 && nextUrl.contains("/items?")) {
                val legacyUrl = "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=100"
                val legacyRes = authGetJson(legacyUrl)
                if (legacyRes.isSuccess) {
                    res = legacyRes
                }
            }
            if (res.isFailure) {
                if (pages == 0) return Result.failure(res.exceptionOrNull()!!)
                break
            }
            val root = res.getOrThrow()
            for (el in root.spArr("items").orEmpty()) {
                val o = el.asSpObj() ?: continue
                val tr = o.spObj("item") ?: o.spObj("track") ?: continue
                parseApiTrackObject(tr)?.let { seeds.add(it) }
                if (seeds.size >= MAX_IMPORT_TRACKS) break
            }
            nextUrl = root.spStr("next")?.takeIf { it.startsWith("https://api.spotify.com/v1/") }
            pages++
        }

        return if (seeds.isEmpty()) {
            Result.failure(IOException("La playlist de Spotify está vacía."))
        } else {
            Result.success(
                SpotifyCollection(
                    kind = "playlist",
                    id = playlistId,
                    title = title.take(36),
                    coverUrl = cover,
                    tracks = seeds,
                )
            )
        }
    }

    private fun parseApiTrackObject(tr: JsonObject): SpotifyTrackSeed? {
        if (tr.spStr("type").equals("episode", ignoreCase = true)) return null
        val name = cleanText(tr.spStr("name"))
        if (name.isEmpty()) return null
        val artists = tr.spArr("artists")
            ?.mapNotNull { cleanText(it.asSpObj()?.spStr("name")).takeIf { s -> s.isNotEmpty() } }
            ?.joinToString(", ")
            .orEmpty()
        val album = cleanText(tr.spObj("album")?.spStr("name"))
        val durMs = tr.spLong("duration_ms") ?: 0L
        return SpotifyTrackSeed(title = name, artist = artists, album = album, durationMs = durMs)
    }

    // ─── IMPORTADOR CSV (EXPORTIFY / TUNEMYMUSIC / SOUNDDIIZ) ───
    fun parseCsv(rawCsv: String, fallbackName: String): Result<SpotifyCollection> {
        val cleaned = rawCsv.removePrefix("\uFEFF")
        val rows = parseRfc4180Csv(cleaned)
        if (rows.size < 2) {
            return Result.failure(IllegalArgumentException("Archivo CSV vacío o sin canciones."))
        }
        val headers = rows.first().map { it.trim().lowercase() }
        val titleCol = headers.indexOfFirst {
            it == "track name" || it == "song" || it == "title" || it == "name" || it == "canción" || it == "título"
        }
        val artistCol = headers.indexOfFirst {
            it.startsWith("artist") || it == "artista" || it == "artistas"
        }
        val albumCol = headers.indexOfFirst {
            it.startsWith("album") || it == "álbum"
        }
        val durCol = headers.indexOfFirst {
            it.contains("duration") || it.contains("duración")
        }

        if (titleCol < 0) {
            return Result.failure(IllegalArgumentException("No se encontró columna «Track Name» / «Title» en el CSV."))
        }

        val seeds = mutableListOf<SpotifyTrackSeed>()
        for (row in rows.drop(1)) {
            if (seeds.size >= MAX_IMPORT_TRACKS) break
            val title = cleanText(row.getOrNull(titleCol))
            if (title.isEmpty()) continue
            val artist = cleanText(row.getOrNull(artistCol)).replace(";", ", ")
            val album = cleanText(row.getOrNull(albumCol))
            val rawDur = row.getOrNull(durCol)?.trim().orEmpty()
            val durMs = (rawDur.toLongOrNull()?.let { n -> if (n > 10_000L) n else n * 1000L }
                ?: (SecurityValidators.parseDurationSec(rawDur) * 1000L))
                .coerceIn(0L, 12L * 3600L * 1000L)
            seeds.add(SpotifyTrackSeed(title = title, artist = artist, album = album, durationMs = durMs))
        }

        if (seeds.isEmpty()) {
            return Result.failure(IllegalArgumentException("No se pudieron leer canciones del CSV."))
        }
        val plName = fallbackName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .trim()
            .ifBlank { "Spotify CSV" }
            .take(36)
        return Result.success(
            SpotifyCollection(
                kind = "csv",
                id = "csv_${plName.hashCode()}",
                title = plName,
                tracks = seeds,
            )
        )
    }

    private fun parseRfc4180Csv(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val len = text.length

        while (i < len) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < len && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == delimiter -> {
                    currentRow.add(field.toString())
                    field.setLength(0)
                }
                ch == '\r' || ch == '\n' -> {
                    if (ch == '\r' && i + 1 < len && text[i + 1] == '\n') i++
                    currentRow.add(field.toString())
                    field.setLength(0)
                    if (currentRow.any { it.isNotBlank() }) {
                        rows.add(currentRow.toList())
                        if (rows.size > MAX_IMPORT_TRACKS + 2) break
                    }
                    currentRow.clear()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            if (currentRow.any { it.isNotBlank() }) rows.add(currentRow.toList())
        }
        return rows
    }

    // ─── EMPAREJADOR INTELIGENTE SPOTIFY -> YOUTUBE MUSIC (PARALELO CON SEMAPHORE 4) ───
    suspend fun matchSeedsToYouTube(
        seeds: List<SpotifyTrackSeed>,
        onProgress: (matched: Int, total: Int) -> Unit,
    ): List<Track> = coroutineScope {
        val bounded = seeds.take(MAX_IMPORT_TRACKS)
        val total = bounded.size
        if (total == 0) return@coroutineScope emptyList()

        val sem = Semaphore(4)
        val completed = AtomicInteger(0)
        onProgress(0, total)

        val matchedIndexed = bounded.mapIndexed { idx, seed ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    ensureActive()
                    val best = matchSingleSeed(seed)
                    ensureActive()
                    val done = completed.incrementAndGet()
                    onProgress(done, total)
                    idx to best
                }
            }
        }.awaitAll()

        matchedIndexed
            .sortedBy { it.first }
            .mapNotNull { it.second }
            .distinctBy { it.id }
    }

    private suspend fun matchSingleSeed(seed: SpotifyTrackSeed): Track? {
        val primaryArtist = seed.artist.substringBefore(",").trim()
        val cleanTitle = cleanTitleForSearch(seed.title)
        val primaryQuery = buildQuery(cleanTitle, primaryArtist)
        if (primaryQuery.isEmpty()) return null

        var candidates = ytApi.search(primaryQuery, limit = 6, mode = "songs")
        if (candidates.isEmpty()) {
            candidates = ytApi.search(primaryQuery, limit = 5, mode = "all")
        }

        val targetTitleNorm = normalize(cleanTitle)
        val targetArtistNorm = normalize(primaryArtist)
        val targetDurSec = (seed.durationMs / 1000L).toInt()

        var (bestTrack, bestScore) = pickBestCandidate(candidates, targetTitleNorm, targetArtistNorm, targetDurSec)

        // Si el título tenía paréntesis/corchetes largos (ej. "[From ...]" o "(feat. ...)") y la puntuación es baja,
        // hacer una segunda búsqueda más limpia sin los paréntesis.
        val strippedTitle = PARENS_AND_BRACKETS_RE.replace(cleanTitle, " ").replace(MULTI_SPACE_RE, " ").trim()
        if ((bestTrack == null || bestScore < 45) &&
            strippedTitle.length >= 2 &&
            !strippedTitle.equals(cleanTitle, ignoreCase = true)
        ) {
            val fallbackQuery = buildQuery(strippedTitle, primaryArtist)
            val more = ytApi.search(fallbackQuery, limit = 6, mode = "songs")
            if (more.isNotEmpty()) {
                val (altTrack, altScore) = pickBestCandidate(
                    more,
                    normalize(strippedTitle),
                    targetArtistNorm,
                    targetDurSec,
                )
                if (altTrack != null && altScore > bestScore) {
                    bestTrack = altTrack
                    bestScore = altScore
                }
            }
        }
        return bestTrack?.let { SecurityValidators.sanitizeTrack(it) }
    }

    private fun buildQuery(title: String, artist: String): String = buildString {
        append(title)
        if (artist.isNotEmpty()) {
            append(' ')
            append(artist)
        }
    }.trim().take(160)

    private fun cleanTitleForSearch(raw: String): String {
        val cleaned = REMASTER_SUFFIX_RE.replace(raw, "").replace(MULTI_SPACE_RE, " ").trim()
        return cleaned.ifEmpty { raw.trim() }
    }

    private fun pickBestCandidate(
        candidates: List<Track>,
        targetTitleNorm: String,
        targetArtistNorm: String,
        targetDurSec: Int,
    ): Pair<Track?, Int> {
        var bestTrack: Track? = null
        var bestScore = Int.MIN_VALUE
        for (cand in candidates) {
            val score = scoreCandidate(
                cand = cand,
                targetTitleNorm = targetTitleNorm,
                targetArtistNorm = targetArtistNorm,
                targetDurSec = targetDurSec,
            )
            if (score > bestScore) {
                bestScore = score
                bestTrack = cand
            }
        }
        return bestTrack to bestScore
    }

    private fun scoreCandidate(
        cand: Track,
        targetTitleNorm: String,
        targetArtistNorm: String,
        targetDurSec: Int,
    ): Int {
        val candTitleNorm = normalize(cand.title)
        val candArtistNorm = normalize(cand.artist)
        var score = 0

        // 1. Coincidencia de título (con soporte completo Unicode: japonés, coreano, cirílico, español...)
        if (targetTitleNorm.isNotEmpty() && candTitleNorm.isNotEmpty()) {
            when {
                candTitleNorm == targetTitleNorm -> score += 60
                candTitleNorm.contains(targetTitleNorm) || targetTitleNorm.contains(candTitleNorm) -> score += 34
                else -> {
                    val targetWords = targetTitleNorm.split(' ').filter { it.length >= 2 }.toSet()
                    val candWords = candTitleNorm.split(' ').filter { it.length >= 2 }.toSet()
                    if (targetWords.isNotEmpty()) {
                        val overlap = targetWords.intersect(candWords).size
                        score += (overlap * 28) / targetWords.size
                    }
                }
            }
        }

        // 2. Coincidencia de artista principal
        if (targetArtistNorm.isNotEmpty()) {
            when {
                candArtistNorm.isNotEmpty() &&
                    (candArtistNorm.contains(targetArtistNorm) || targetArtistNorm.contains(candArtistNorm)) -> score += 45
                candTitleNorm.isNotEmpty() && candTitleNorm.contains(targetArtistNorm) -> score += 22
            }
        }

        // 3. Coincidencia de duración (filtra loops de 1h o clips cortos)
        if (targetDurSec > 0 && cand.duration > 0) {
            val diff = abs(cand.duration - targetDurSec)
            score += when {
                diff <= 4 -> 26
                diff <= 12 -> 14
                diff <= 25 -> 4
                diff > 90 -> -38
                else -> -12
            }
        }

        // 4. Penalizar versiones no deseadas (cover/karaoke/slowed/nightcore/remix/live) salvo que estén en el título original
        for (kw in UNWANTED_VARIANT_KEYWORDS) {
            if (candTitleNorm.contains(kw) && !targetTitleNorm.contains(kw)) {
                score -= 35
            }
        }

        return score
    }

    private fun extractCoverUrl(entity: JsonObject): String {
        val fromCoverArt = entity.spObj("coverArt")
            ?.spArr("sources")
            ?.firstOrNull()?.asSpObj()?.spStr("url")
        if (SecurityValidators.isSafeThumbUrl(fromCoverArt)) return fromCoverArt!!

        val fromVisual = entity.spObj("visualIdentity")
            ?.spArr("image")
            ?.firstOrNull()?.asSpObj()?.spStr("url")
        if (SecurityValidators.isSafeThumbUrl(fromVisual)) return fromVisual!!

        return ""
    }

    private fun cleanText(raw: String?): String = raw
        ?.replace('\u00A0', ' ')
        ?.replace(CONTROL_RE, "")
        ?.trim()
        ?.take(220)
        .orEmpty()

    private fun normalize(raw: String): String {
        val decomposed = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        return DIACRITICS_RE.replace(decomposed, "")
            .replace(NON_ALNUM_RE, " ")
            .replace(MULTI_SPACE_RE, " ")
            .trim()
    }

    companion object {
        private const val MAX_BODY_BYTES = 4L * 1024L * 1024L
        private const val MAX_IMPORT_TRACKS = 300
        private const val PATHFINDER_V2_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
        private const val PATHFINDER_V1_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val DEFAULT_PATHFINDER_HASHES = mapOf(
            "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
            "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
            "fetchPlaylist" to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
        )
        private val TRACKED_PATHFINDER_OPS = DEFAULT_PATHFINDER_HASHES.keys
        private val WEB_PLAYER_JS_RE = Regex(
            """src="(https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[0-9a-f]+\.js)""""
        )
        private val PATHFINDER_HASH_RE = Regex(""""([A-Za-z0-9_]+)","query","([0-9a-f]{64})"""")

        private val ALLOWED_AUTH_HOSTS = setOf(
            "api.spotify.com",
            "api-partner.spotify.com",
        )
        private val ALLOWED_PUBLIC_HOSTS = setOf(
            "open.spotify.com",
            "open.spotifycdn.com",
            "play.spotify.com",
            "spotify.link",
            "spoti.fi",
            "spotify.app.link",
        )
        private val ALLOWED_KINDS = setOf("playlist", "album", "track", "artist")
        private val SPOTIFY_ID_RE = Regex("^[A-Za-z0-9]{22}$")
        private val DIRECT_ENTITY_RE = Regex(
            "(?:(?:open|play)\\.spotify\\.com/(?:intl-[a-z-]+/)?(?:embed/)?(?:user/[^/]+/)?|spotify:)(playlist|album|track|artist)[/:]([A-Za-z0-9]{22})",
            RegexOption.IGNORE_CASE,
        )
        private val CANONICAL_ENTITY_RE = Regex(
            """(?:og:url|canonical)[^>]{0,120}(?:open|play)\.spotify\.com/(?:intl-[a-z-]+/)?(playlist|album|track|artist)/([A-Za-z0-9]{22})""",
            RegexOption.IGNORE_CASE,
        )
        private val EMBED_ENTITY_IN_TEXT_RE = Regex(
            "(?:open|play)\\.spotify\\.com/(?:intl-[a-z-]+/)?(playlist|album|track|artist)/([A-Za-z0-9]{22})",
            RegexOption.IGNORE_CASE,
        )
        private val NEXT_DATA_RE = Regex(
            """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val REMASTER_SUFFIX_RE = Regex(
            """\s*[-–]\s*(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?.*$|\s*[\(\[]\s*(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?\s*[\)\]]""",
            RegexOption.IGNORE_CASE,
        )
        private val PARENS_AND_BRACKETS_RE = Regex("""[\(\[][^\)\]]{2,80}[\)\]]""")
        private val CONTROL_RE = Regex("[\\r\\n\\u0000-\\u001F\\u007F]")
        private val DIACRITICS_RE = Regex("\\p{InCombiningDiacriticalMarks}+")
        // Conserva letras y dígitos Unicode (japonés, coreano, cirílico, griego, español, etc.)
        private val NON_ALNUM_RE = Regex("[^\\p{L}\\p{N}]+")
        private val MULTI_SPACE_RE = Regex("\\s+")
        private val UNWANTED_VARIANT_KEYWORDS = listOf(
            "karaoke",
            "cover",
            "8d",
            "slowed",
            "reverb",
            "nightcore",
            "sped up",
            "instrumental",
            "remix",
            "live",
            "acoustic",
            "tutorial",
            "reaction",
        )
    }
}

private fun JsonElement.asSpObj(): JsonObject? = this as? JsonObject
private fun JsonObject.spObj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.spArr(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.spStr(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.spInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.spLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
