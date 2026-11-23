package com.noctalia.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.random.Random

data class ArtistMixCard(
    val artistName: String,
    val subtitle: String,
    val coverUrl: String,
    val seedTrackId: String,
)

data class HomeRecommendations(
    val personalized: Boolean = false,
    val forYouTracks: List<Track> = emptyList(),
    val discoverTracks: List<Track> = emptyList(),
    val becauseTitle: String = "",
    val becauseTracks: List<Track> = emptyList(),
    val artistMixes: List<ArtistMixCard> = emptyList(),
)

data class TasteProfile(
    val artistAffinity: Map<String, Float>,
    val topArtists: List<Pair<String, Float>>, // (displayName, score)
    val topSeeds: List<Track>,
    val skippedTrackIds: Set<String>,
    val knownTrackIds: Set<String>,
    val recentlyPlayedIds: Set<String>,
)

/**
 * Motor local de recomendaciones y Aleatorio Inteligente (100% privado, sin servidores externos):
 * - Aprende automáticamente de Brook Stats (minutos, reproducciones, escuchas completas y saltos rápidos < 18 s),
 *   tus Favoritas, tus Playlists locales (incluidas las importadas de Spotify) y tu historial reciente.
 * - Genera «Recomendados para ti», «Descubrimiento» (solo temas nuevos), «Mixes de tus artistas»
 *   y «Porque escuchas a ...» para la pantalla de Inicio.
 * - Alimenta la Radio automática inteligente y el Aleatorio inteligente con canciones y cantantes similares,
 *   evitando repetir el mismo artista seguido y penalizando lo que saltas rápido.
 */
class RecommendationEngine(
    private val api: YTMusicApi,
    private val library: LibraryRepository,
) {
    @Volatile
    private var cachedHome: HomeRecommendations? = null
    @Volatile
    private var cachedHomeAtMs: Long = 0L
    @Volatile
    private var cachedHadTaste: Boolean = false
    @Volatile
    private var becauseArtistCursor: Int = 0

    fun primaryArtistDisplay(rawArtist: String): String {
        val cut = rawArtist
            .substringBefore(",")
            .substringBefore(" feat.")
            .substringBefore(" ft.")
            .substringBefore(" & ")
            .substringBefore(" x ")
            .trim()
        return cut.ifBlank { rawArtist.trim().ifBlank { "Artista" } }
    }

    fun normalizeArtistKey(rawArtist: String): String {
        val base = primaryArtistDisplay(rawArtist).lowercase()
        val decomposed = Normalizer.normalize(base, Normalizer.Form.NFD)
        return DIACRITICS_RE.replace(decomposed, "")
            .replace(NON_ALNUM_RE, " ")
            .replace(MULTI_SPACE_RE, " ")
            .trim()
    }

    fun buildTasteProfile(payload: LibraryPayload = library.state.value): TasteProfile {
        val now = System.currentTimeMillis()
        val artistScores = HashMap<String, Float>()
        val artistDisplayNames = HashMap<String, String>()
        val favIds = payload.favorites.map { it.id }.toHashSet()
        val skippedIds = HashSet<String>()
        val knownIds = HashSet<String>()
        val recentlyPlayedIds = HashSet<String>()

        fun addArtistScore(rawArtist: String, delta: Float) {
            val key = normalizeArtistKey(rawArtist)
            if (key.isEmpty()) return
            artistDisplayNames.putIfAbsent(key, primaryArtistDisplay(rawArtist))
            artistScores[key] = (artistScores[key] ?: 0f) + delta
        }

        // 1. Señales de Brook Stats (tiempo, reproducciones, escuchas completas y saltos rápidos)
        for (st in payload.stats.values) {
            knownIds.add(st.track.id)
            val ageMs = (now - st.lastPlayedMs).coerceAtLeast(0L)
            if (ageMs < 6L * 3600L * 1000L) recentlyPlayedIds.add(st.track.id)

            val recencyBoost = when {
                ageMs < 48L * 3600L * 1000L -> 7f
                ageMs < 7L * 24L * 3600L * 1000L -> 3.5f
                else -> 0f
            }
            val score = (st.listenedSec / 25f).coerceAtMost(45f) +
                (st.playCount * 2.5f).coerceAtMost(35f) +
                (st.completeCount * 5f).coerceAtMost(40f) +
                recencyBoost -
                (st.skipCount * 4.5f).coerceAtMost(45f)
            addArtistScore(st.track.artist, score)

            if (st.track.id !in favIds && st.skipCount >= 2 && st.skipCount > st.completeCount) {
                skippedIds.add(st.track.id)
            }
        }

        // 2. Señales de Favoritas (fuerte afinidad positiva)
        for (t in payload.favorites) {
            knownIds.add(t.id)
            addArtistScore(t.artist, 12f)
        }

        // 3. Señales de Playlists locales (incluyendo las importadas de Spotify / YT)
        for (list in payload.playlists.values) {
            for (t in list.take(80)) {
                knownIds.add(t.id)
                addArtistScore(t.artist, 3.2f)
            }
        }

        // 4. Recientes (ambiente musical actual)
        payload.recent.take(15).forEachIndexed { idx, t ->
            knownIds.add(t.id)
            if (idx < 6) recentlyPlayedIds.add(t.id)
            addArtistScore(t.artist, (6f - idx * 0.35f).coerceAtLeast(1f))
        }

        val topArtists = artistScores.entries
            .filter { it.value > 2f }
            .sortedByDescending { it.value }
            .take(12)
            .map { (key, score) -> (artistDisplayNames[key] ?: key) to score }

        // Seleccionar hasta 6 semillas diversas (máx. 1 pista por artista, evitando las saltadas)
        val seedCandidates = mutableListOf<Pair<Track, Float>>()
        payload.recent.take(6).forEachIndexed { idx, t ->
            if (t.id !in skippedIds) seedCandidates.add(t to (34f - idx * 3f))
        }
        payload.stats.values
            .filter { it.track.id !in skippedIds }
            .sortedByDescending { it.listenedSec * 2 + it.playCount * 30L + it.completeCount * 60L }
            .take(10)
            .forEachIndexed { idx, st ->
                seedCandidates.add(st.track to (32f - idx * 2f))
            }
        payload.favorites.take(8).forEachIndexed { idx, t ->
            seedCandidates.add(t to (26f - idx * 1.5f))
        }
        payload.playlists.values.flatten().take(12).forEach { t ->
            if (t.id !in skippedIds) seedCandidates.add(t to 16f)
        }

        val topSeeds = mutableListOf<Track>()
        val usedSeedArtists = HashSet<String>()
        val usedSeedIds = HashSet<String>()
        for ((track, _) in seedCandidates.sortedByDescending { it.second }) {
            val aKey = normalizeArtistKey(track.artist)
            if (track.id in usedSeedIds) continue
            if (aKey.isNotEmpty() && aKey in usedSeedArtists) continue
            topSeeds.add(track)
            usedSeedIds.add(track.id)
            if (aKey.isNotEmpty()) usedSeedArtists.add(aKey)
            if (topSeeds.size >= 6) break
        }

        return TasteProfile(
            artistAffinity = artistScores,
            topArtists = topArtists,
            topSeeds = topSeeds,
            skippedTrackIds = skippedIds,
            knownTrackIds = knownIds,
            recentlyPlayedIds = recentlyPlayedIds,
        )
    }

    fun hasFreshHomeCache(): Boolean {
        val prev = cachedHome ?: return false
        val hasTaste = buildTasteProfile().topSeeds.isNotEmpty()
        return cachedHadTaste == hasTaste && (System.currentTimeMillis() - cachedHomeAtMs) < HOME_CACHE_TTL_MS &&
            prev.forYouTracks.isNotEmpty()
    }

    /**
     * Genera el feed personalizado de Inicio (con caché de 12 minutos para 0 gasto de red/batería).
     */
    suspend fun buildHomeRecommendations(forceRefresh: Boolean = false): HomeRecommendations = withContext(Dispatchers.IO) {
        val profile = buildTasteProfile()
        val hasTaste = profile.topSeeds.isNotEmpty()
        val now = System.currentTimeMillis()
        val prev = cachedHome
        if (!forceRefresh &&
            prev != null &&
            (now - cachedHomeAtMs) < HOME_CACHE_TTL_MS &&
            cachedHadTaste == hasTaste
        ) {
            return@withContext prev
        }

        if (!hasTaste) {
            val trending = api.search("top hits musica 2026", limit = 16, mode = "songs")
            val result = HomeRecommendations(
                personalized = false,
                forYouTracks = trending,
            )
            if (trending.isNotEmpty()) {
                cachedHome = result
                cachedHomeAtMs = now
                cachedHadTaste = false
            }
            return@withContext result
        }

        // Elegir hasta 3 semillas diversas (rotando ligeramente si el usuario pulsa Actualizar)
        val pool = profile.topSeeds
        val chosenSeeds = if (forceRefresh && pool.size > 3) {
            pool.shuffled().take(3)
        } else {
            pool.take(3)
        }

        // Consultar las radios relacionadas en paralelo (~0.4-0.7s total)
        val radioBatches: List<Pair<Track, List<Track>>> = coroutineScope {
            chosenSeeds.map { seed ->
                async { seed to api.getRadio(seed.id, limit = 24) }
            }.awaitAll()
        }

        // Contar co-ocurrencias (si 2+ semillas distintas recomiendan la misma canción -> acierto muy alto)
        val coOccurrence = HashMap<String, Int>()
        val bestTrackObj = LinkedHashMap<String, Track>()
        val baseRankScore = HashMap<String, Float>()

        for ((seed, list) in radioBatches) {
            list.forEachIndexed { idx, track ->
                if (track.id == seed.id) return@forEachIndexed
                coOccurrence[track.id] = (coOccurrence[track.id] ?: 0) + 1
                bestTrackObj.putIfAbsent(track.id, track)
                val rankPts = (24 - idx).coerceAtLeast(1).toFloat()
                baseRankScore[track.id] = maxOf(baseRankScore[track.id] ?: 0f, rankPts)
            }
        }

        val scored = bestTrackObj.values.map { track ->
            val aKey = normalizeArtistKey(track.artist)
            val artistPts = (profile.artistAffinity[aKey] ?: 0f).coerceIn(-25f, 34f)
            val coPts = ((coOccurrence[track.id] ?: 1) - 1) * 22f
            val skipPenalty = if (track.id in profile.skippedTrackIds) -75f else 0f
            val noveltyPts = when {
                track.id !in profile.knownTrackIds -> 12f
                track.id in profile.recentlyPlayedIds -> -12f
                else -> 3f
            }
            val jitter = Random.nextFloat() * 5.5f
            val total = (baseRankScore[track.id] ?: 0f) + artistPts + coPts + skipPenalty + noveltyPts + jitter
            track to total
        }.sortedByDescending { it.second }

        val forYou = selectDiverse(scored.map { it.first }, maxPerArtist = 2, limit = 18)

        val discoverCandidates = scored
            .map { it.first }
            .filter { it.id !in profile.knownTrackIds && it.id !in profile.skippedTrackIds }
        val discover = selectDiverse(discoverCandidates, maxPerArtist = 2, limit = 12)

        // Sección «Porque escuchas a [Artista]»
        val becauseSeedIdx = if (radioBatches.isNotEmpty()) {
            val idx = becauseArtistCursor % radioBatches.size
            becauseArtistCursor = (becauseArtistCursor + 1) % 1000
            idx
        } else {
            0
        }
        val becausePair = radioBatches.getOrNull(becauseSeedIdx)
        val becauseArtistName = becausePair?.first?.let { primaryArtistDisplay(it.artist) }.orEmpty()
        val becauseTracks = if (becausePair != null) {
            val raw = becausePair.second
                .filter { it.id != becausePair.first.id && it.id !in profile.skippedTrackIds }
            selectDiverse(raw, maxPerArtist = 2, limit = 10)
        } else {
            emptyList()
        }

        // Tarjetas de «Mix de [Artista]» para tus top 4 cantantes con carátula real
        val artistMixes = buildArtistMixCards(profile)

        val finalForYou = if (forYou.isNotEmpty()) {
            forYou
        } else {
            api.search("top hits musica 2026", limit = 14, mode = "songs")
        }

        val result = HomeRecommendations(
            personalized = forYou.isNotEmpty(),
            forYouTracks = finalForYou,
            discoverTracks = discover,
            becauseTitle = if (becauseArtistName.isNotEmpty() && becauseTracks.isNotEmpty()) {
                "Porque escuchas a $becauseArtistName"
            } else {
                ""
            },
            becauseTracks = becauseTracks,
            artistMixes = artistMixes,
        )
        cachedHome = result
        cachedHomeAtMs = now
        cachedHadTaste = hasTaste
        result
    }

    private fun buildArtistMixCards(profile: TasteProfile): List<ArtistMixCard> {
        val payload = library.state.value
        val allKnownTracks = buildList {
            addAll(payload.stats.values.sortedByDescending { it.listenedSec + it.playCount * 20L }.map { it.track })
            addAll(payload.favorites)
            addAll(payload.recent)
            payload.playlists.values.forEach { addAll(it) }
        }
        val out = mutableListOf<ArtistMixCard>()
        val usedKeys = HashSet<String>()

        for ((displayName, _) in profile.topArtists) {
            val key = normalizeArtistKey(displayName)
            if (key.isEmpty() || !usedKeys.add(key)) continue
            val seed = allKnownTracks.firstOrNull { normalizeArtistKey(it.artist) == key } ?: continue
            out.add(
                ArtistMixCard(
                    artistName = displayName,
                    subtitle = "Con $displayName y similares",
                    coverUrl = seed.thumb,
                    seedTrackId = seed.id,
                )
            )
            if (out.size >= 4) break
        }
        return out
    }

    /**
     * Genera un mix completo de ~20 canciones para una tarjeta «Mix de [Artista]»,
     * mezclando sus mejores temas con cantantes y estilos similares.
     */
    suspend fun buildArtistMix(card: ArtistMixCard): List<Track> = withContext(Dispatchers.IO) {
        val profile = buildTasteProfile()
        val (radioTracks, artistHits) = coroutineScope {
            val rDef = async { api.getRadio(card.seedTrackId, limit = 24) }
            val aDef = async { api.search(card.artistName, limit = 10, mode = "artist") }
            rDef.await() to aDef.await()
        }
        val combined = LinkedHashMap<String, Track>()
        artistHits.take(4).forEach { if (it.id !in profile.skippedTrackIds) combined[it.id] = it }
        radioTracks.forEach { if (it.id !in profile.skippedTrackIds) combined.putIfAbsent(it.id, it) }
        artistHits.drop(4).forEach { if (it.id !in profile.skippedTrackIds) combined.putIfAbsent(it.id, it) }

        selectSpreadNoBackToBack(combined.values.toList(), maxPerArtist = 3, limit = 20)
    }

    /**
     * Radio inteligente y pool de Aleatorio Inteligente:
     * Combina la canción/cantante que está sonando ahora + la última canción que escuchaste bien en la sesión
     * + tu perfil de afinidad local, filtrando saltos rápidos y evitando repetir artista seguido.
     */
    suspend fun buildSmartRadioQueue(
        currentTrack: Track,
        recentSessionTracks: List<Track>,
        excludedIds: Set<String>,
        limit: Int = 16,
    ): List<Track> = withContext(Dispatchers.IO) {
        val profile = buildTasteProfile()
        val curArtistKey = normalizeArtistKey(currentTrack.artist)

        // Buscar una segunda semilla reciente de un cantante distinto (que no se haya saltado) para captar el "mood"
        val secondarySeed = recentSessionTracks.asReversed().firstOrNull { t ->
            t.id != currentTrack.id &&
                t.id !in profile.skippedTrackIds &&
                normalizeArtistKey(t.artist) != curArtistKey
        }

        val (primaryRadio, secondaryRadio) = coroutineScope {
            val pDef = async { api.getRadio(currentTrack.id, limit = 24) }
            val sDef = if (secondarySeed != null) {
                async { api.getRadio(secondarySeed.id, limit = 16) }
            } else {
                null
            }
            pDef.await() to (sDef?.await().orEmpty())
        }

        val secondaryIds = secondaryRadio.map { it.id }.toHashSet()
        val pool = LinkedHashMap<String, Pair<Track, Float>>()

        primaryRadio.forEachIndexed { idx, track ->
            if (track.id == currentTrack.id || track.id in excludedIds) return@forEachIndexed
            val aKey = normalizeArtistKey(track.artist)
            val base = (24 - idx).coerceAtLeast(1).toFloat() + 14f
            val coBonus = if (track.id in secondaryIds) 20f else 0f
            val tastePts = (profile.artistAffinity[aKey] ?: 0f).coerceIn(-25f, 26f) * 0.7f
            val sameAsCurrentPenalty = if (aKey.isNotEmpty() && aKey == curArtistKey && idx < 3) -8f else 0f
            val skipPenalty = if (track.id in profile.skippedTrackIds) -80f else 0f
            val recentPenalty = if (track.id in profile.recentlyPlayedIds) -12f else 5f
            val jitter = Random.nextFloat() * 5f
            pool[track.id] = track to (base + coBonus + tastePts + sameAsCurrentPenalty + skipPenalty + recentPenalty + jitter)
        }

        secondaryRadio.forEachIndexed { idx, track ->
            if (track.id == currentTrack.id || track.id in excludedIds || track.id in pool) return@forEachIndexed
            val aKey = normalizeArtistKey(track.artist)
            val base = (16 - idx).coerceAtLeast(1).toFloat()
            val tastePts = (profile.artistAffinity[aKey] ?: 0f).coerceIn(-25f, 26f) * 0.7f
            val skipPenalty = if (track.id in profile.skippedTrackIds) -80f else 0f
            val recentPenalty = if (track.id in profile.recentlyPlayedIds) -10f else 4f
            val jitter = Random.nextFloat() * 5f
            pool[track.id] = track to (base + tastePts + skipPenalty + recentPenalty + jitter)
        }

        val ranked = pool.values
            .filter { it.second > -30f }
            .sortedByDescending { it.second }
            .map { it.first }

        val source = if (ranked.isNotEmpty()) ranked else primaryRadio.filter { it.id !in excludedIds && it.id != currentTrack.id }
        selectSpreadNoBackToBack(source, maxPerArtist = 2, limit = limit, initialBlockedArtistKey = curArtistKey)
    }

    /**
     * Aleatorio ponderado: elige la siguiente canción de la cola evitando repetir el mismo cantante seguido,
     * penalizando pistas que sueles saltar rápido y favoreciendo tus gustos locales sin perder variedad.
     */
    fun pickWeightedShuffleNext(
        queue: List<Track>,
        currentTrack: Track?,
        recentArtistKeys: Set<String>,
    ): Track {
        if (queue.size <= 1) return queue.first()
        val profile = buildTasteProfile()
        val curArtistKey = currentTrack?.let { normalizeArtistKey(it.artist) }.orEmpty()

        val weights = FloatArray(queue.size)
        var totalWeight = 0f

        for (i in queue.indices) {
            val t = queue[i]
            val aKey = normalizeArtistKey(t.artist)
            var w = 10f
            val aff = (profile.artistAffinity[aKey] ?: 0f).coerceIn(-6f, 16f)
            w += aff * 0.35f

            if (aKey.isNotEmpty() && aKey == curArtistKey) {
                w *= 0.14f
            } else if (aKey.isNotEmpty() && aKey in recentArtistKeys) {
                w *= 0.45f
            }
            if (t.id in profile.skippedTrackIds) {
                w *= 0.12f
            }
            val safeW = w.coerceAtLeast(0.25f)
            weights[i] = safeW
            totalWeight += safeW
        }

        if (totalWeight <= 0f) return queue[Random.nextInt(queue.size)]
        var r = Random.nextFloat() * totalWeight
        for (i in queue.indices) {
            r -= weights[i]
            if (r <= 0f) return queue[i]
        }
        return queue.last()
    }

    private fun selectDiverse(tracks: List<Track>, maxPerArtist: Int, limit: Int): List<Track> {
        val out = mutableListOf<Track>()
        val artistCounts = HashMap<String, Int>()
        val seenIds = HashSet<String>()
        for (t in tracks) {
            if (!seenIds.add(t.id)) continue
            val aKey = normalizeArtistKey(t.artist)
            val cnt = artistCounts[aKey] ?: 0
            if (aKey.isNotEmpty() && cnt >= maxPerArtist) continue
            out.add(t)
            if (aKey.isNotEmpty()) artistCounts[aKey] = cnt + 1
            if (out.size >= limit) break
        }
        return out
    }

    private fun selectSpreadNoBackToBack(
        tracks: List<Track>,
        maxPerArtist: Int,
        limit: Int,
        initialBlockedArtistKey: String = "",
    ): List<Track> {
        val remaining = tracks.distinctBy { it.id }.toMutableList()
        val out = mutableListOf<Track>()
        val artistCounts = HashMap<String, Int>()
        var lastArtistKey = initialBlockedArtistKey

        while (remaining.isNotEmpty() && out.size < limit) {
            val pickIdx = remaining.indexOfFirst { t ->
                val k = normalizeArtistKey(t.artist)
                val cnt = artistCounts[k] ?: 0
                (k.isEmpty() || (k != lastArtistKey && cnt < maxPerArtist))
            }.takeIf { it >= 0 } ?: remaining.indexOfFirst { t ->
                val k = normalizeArtistKey(t.artist)
                (artistCounts[k] ?: 0) < maxPerArtist
            }
            if (pickIdx < 0) break
            val chosen = remaining.removeAt(pickIdx)
            val k = normalizeArtistKey(chosen.artist)
            out.add(chosen)
            if (k.isNotEmpty()) {
                artistCounts[k] = (artistCounts[k] ?: 0) + 1
                lastArtistKey = k
            }
        }
        return out
    }

    companion object {
        private const val HOME_CACHE_TTL_MS = 12L * 60L * 1000L
        private val DIACRITICS_RE = Regex("\\p{InCombiningDiacriticalMarks}+")
        private val NON_ALNUM_RE = Regex("[^\\p{L}\\p{N}]+")
        private val MULTI_SPACE_RE = Regex("\\s+")
    }
}
