package com.noctalia.music.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.noctalia.music.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CloudSyncState(
    val configured: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val syncing: Boolean = false,
    val lastSyncTimeText: String = "",
    val lastError: String? = null,
)

/**
 * Copia de seguridad privada en tiempo real hacia tu repositorio privado `OMEGA12492903/brook-backup`.
 *
 * Seguridad estricta:
 * - El token Fine-Grained NUNCA va en el código ni en la APK: el usuario lo introduce una sola vez
 *   y se guarda cifrado en Android Keystore (AES-256-GCM) sin fallback a texto plano.
 * - Cliente OkHttp 100% aislado con `followRedirects(false)` y validación exacta de host/ruta:
 *   solo permite `https://api.github.com/repos/OMEGA12492903/brook-backup/contents/brook_cloud_backup.json`.
 * - Sincronización automática con debounce de 35s + mezcla inteligente (jamás borra stats ni favoritas).
 */
@OptIn(FlowPreview::class)
class GitHubCloudSync(
    private val context: Context,
    private val library: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true; encodeDefaults = true }

    private val prefs: SharedPreferences? = createEncryptedPrefsOrNull(context)
    @Volatile private var memoryToken: String? = null
    @Volatile private var lastKnownRemoteSha: String? = prefs?.getString(KEY_LAST_SHA, null)
    @Volatile private var lastSyncedSnapshot: Triple<Map<String, TrackStat>, List<Track>, Map<String, List<Track>>>? = null

    private val _state = MutableStateFlow(
        CloudSyncState(
            configured = getToken() != null,
            autoSyncEnabled = prefs?.getBoolean(KEY_AUTO_SYNC, true) ?: true,
            lastSyncTimeText = prefs?.getString(KEY_LAST_SYNC_TEXT, "") ?: "",
        )
    )
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            val u = req.url
            if (u.scheme != "https" ||
                u.host != "api.github.com" ||
                u.encodedPath != ALLOWED_CONTENTS_PATH
            ) {
                throw IOException("Blocked unauthorized cloud sync URL: ${u.host}${u.encodedPath}")
            }
            val clean = req.newBuilder()
                .removeHeader("Cookie")
                .build()
            chain.proceed(clean)
        })
        .build()

    init {
        startAutoSyncObserver()
        if (getToken() != null) {
            scope.launch { syncNow(silent = true) }
        }
    }

    private fun createEncryptedPrefsOrNull(ctx: Context): SharedPreferences? {
        repeat(2) { attempt ->
            try {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (_: Throwable) {
                if (attempt == 0) {
                    try {
                        ctx.deleteSharedPreferences(PREFS_NAME)
                        File(ctx.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml").delete()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return null
    }

    private fun sanitizeToken(raw: String?): String? {
        val clean = raw?.replace(CONTROL_AND_SPACE_RE, "")?.trim() ?: return null
        if (clean.length !in 20..300) return null
        if (!TOKEN_RE.matches(clean)) return null
        return clean
    }

    private fun getToken(): String? {
        val raw = memoryToken ?: prefs?.getString(KEY_TOKEN, null)
        val clean = sanitizeToken(raw)
        if (clean != null) memoryToken = clean
        return clean
    }

    fun isConfigured(): Boolean = getToken() != null

    fun saveToken(rawToken: String): Boolean {
        val clean = sanitizeToken(rawToken) ?: return false
        memoryToken = clean
        prefs?.edit()?.putString(KEY_TOKEN, clean)?.apply()
        val keystoreWarn = if (prefs == null) "Keystore no disponible; token solo en memoria esta sesión." else null
        _state.update { it.copy(configured = true, lastError = keystoreWarn) }
        return true
    }

    fun clearToken() {
        memoryToken = null
        lastKnownRemoteSha = null
        lastSyncedSnapshot = null
        prefs?.edit()
            ?.remove(KEY_TOKEN)
            ?.remove(KEY_LAST_SYNC_TEXT)
            ?.remove(KEY_LAST_SHA)
            ?.apply()
        _state.update {
            it.copy(
                configured = false,
                syncing = false,
                lastSyncTimeText = "",
                lastError = null,
            )
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_AUTO_SYNC, enabled)?.apply()
        _state.update { it.copy(autoSyncEnabled = enabled) }
    }

    fun openTokenCreationPage() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CREATE_TOKEN_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun startAutoSyncObserver() {
        scope.launch {
            library.state
                .map { Triple(it.stats, it.favorites, it.playlists) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(AUTO_SYNC_DEBOUNCE_MS)
                .collect { triple ->
                    if (triple == lastSyncedSnapshot) return@collect
                    if (_state.value.autoSyncEnabled && getToken() != null) {
                        syncNow(silent = true)
                    }
                }
        }
    }

    suspend fun restoreFromCloud(): Result<String> = syncNow(silent = false, pushAfterPull = false)

    suspend fun syncNow(
        silent: Boolean = false,
        pushAfterPull: Boolean = true,
    ): Result<String> = withContext(Dispatchers.IO) {
        val token = getToken()
            ?: return@withContext Result.failure(IllegalStateException("Configura primero tu token privado de GitHub."))

        syncMutex.withLock {
            _state.update { it.copy(syncing = true, lastError = if (silent) it.lastError else null) }
            try {
                // 1. Obtener estado actual en GitHub (SHA + contenido remoto si existe)
                val remoteFetch = fetchRemoteBackup(token)
                if (remoteFetch.isFailure) {
                    val msg = remoteFetch.exceptionOrNull()?.message ?: "Error al conectar con GitHub."
                    _state.update { it.copy(syncing = false, lastError = msg) }
                    return@withLock Result.failure(IOException(msg))
                }
                val (remoteSha, remotePayload) = remoteFetch.getOrThrow()

                // 2. Mezclar remoto cuando:
                //    - El usuario pidió Restaurar (!pushAfterPull), o
                //    - Es la primera vez / reinstalación (lastKnownRemoteSha == null), o
                //    - Otro dispositivo modificó el repo (remoteSha != lastKnownRemoteSha)
                //    Así evitamos resucitar favoritas/stats borradas en este mismo dispositivo.
                val shouldMergeRemote = !pushAfterPull ||
                    lastKnownRemoteSha == null ||
                    (remoteSha != null && remoteSha != lastKnownRemoteSha)
                if (remotePayload != null && shouldMergeRemote) {
                    withContext(Dispatchers.Main) {
                        library.mergeCloudBackup(remotePayload)
                    }
                }

                if (!pushAfterPull) {
                    val snap = withContext(Dispatchers.Main) { currentLibrarySnapshot() }
                    lastSyncedSnapshot = snap
                    if (remoteSha != null) {
                        lastKnownRemoteSha = remoteSha
                        prefs?.edit()?.putString(KEY_LAST_SHA, remoteSha)?.apply()
                    }
                    val stamp = nowStamp()
                    prefs?.edit()?.putString(KEY_LAST_SYNC_TEXT, stamp)?.apply()
                    _state.update { it.copy(syncing = false, lastSyncTimeText = stamp, lastError = null) }
                    return@withLock Result.success(
                        if (remotePayload != null) "Copia restaurada desde brook-backup ($stamp)."
                        else "Aún no había copia guardada en brook-backup."
                    )
                }

                // 3. Construir copia actualizada y evitar commits vacíos si GitHub ya tiene exactamente lo mismo
                val localPayload = withContext(Dispatchers.Main) { library.buildCloudBackupPayload() }
                val localSnap = Triple(localPayload.stats, localPayload.favorites, localPayload.playlists)
                val remoteMatchesLocal = remotePayload != null &&
                    remotePayload.stats == localPayload.stats &&
                    remotePayload.favorites == localPayload.favorites &&
                    remotePayload.playlists == localPayload.playlists

                if (remoteSha != null && remoteMatchesLocal) {
                    lastSyncedSnapshot = localSnap
                    lastKnownRemoteSha = remoteSha
                    prefs?.edit()?.putString(KEY_LAST_SHA, remoteSha)?.apply()
                    val stamp = _state.value.lastSyncTimeText.ifBlank { nowStamp() }
                    prefs?.edit()?.putString(KEY_LAST_SYNC_TEXT, stamp)?.apply()
                    _state.update { it.copy(syncing = false, lastSyncTimeText = stamp, lastError = null) }
                    return@withLock Result.success("Tu copia en brook-backup ya está al día.")
                }

                // 4. Subir a GitHub Contents API (con 1 reintento automático si hay conflicto SHA HTTP 409)
                val uploadRes = pushRemoteBackup(token, localPayload, remoteSha)
                val finalRes = if (uploadRes.isFailure && uploadRes.exceptionOrNull()?.message == "CONFLICT_409") {
                    val fresh = fetchRemoteBackup(token).getOrNull()
                    if (fresh?.second != null) {
                        withContext(Dispatchers.Main) {
                            library.mergeCloudBackup(fresh.second!!)
                        }
                    }
                    val mergedPayload = withContext(Dispatchers.Main) { library.buildCloudBackupPayload() }
                    pushRemoteBackup(token, mergedPayload, fresh?.first)
                } else {
                    uploadRes
                }

                finalRes.fold(
                    onSuccess = { newSha ->
                        lastSyncedSnapshot = withContext(Dispatchers.Main) { currentLibrarySnapshot() }
                        if (!newSha.isNullOrBlank()) {
                            lastKnownRemoteSha = newSha
                            prefs?.edit()?.putString(KEY_LAST_SHA, newSha)?.apply()
                        }
                        val stamp = nowStamp()
                        prefs?.edit()?.putString(KEY_LAST_SYNC_TEXT, stamp)?.apply()
                        _state.update { it.copy(syncing = false, lastSyncTimeText = stamp, lastError = null) }
                        Result.success("Copia guardada en brook-backup ($stamp).")
                    },
                    onFailure = { err ->
                        val msg = err.message ?: "No se pudo guardar en GitHub."
                        _state.update { it.copy(syncing = false, lastError = msg) }
                        Result.failure(IOException(msg))
                    },
                )
            } catch (e: Exception) {
                val msg = e.message?.take(120) ?: "Error de red al sincronizar."
                _state.update { it.copy(syncing = false, lastError = msg) }
                Result.failure(IOException(msg))
            }
        }
    }

    private fun currentLibrarySnapshot(): Triple<Map<String, TrackStat>, List<Track>, Map<String, List<Track>>> {
        val st = library.state.value
        return Triple(st.stats, st.favorites, st.playlists)
    }

    private fun fetchRemoteBackup(token: String): Result<Pair<String?, CloudBackupPayload?>> {
        val req = Request.Builder()
            .url(BACKUP_FILE_API_URL)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Brook-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    404 -> Result.success(null to null) // Primera sincronización: aún no existe el archivo
                    401, 403 -> Result.failure(
                        SecurityException("Token inválido o sin permiso Contents (Read & Write) en brook-backup.")
                    )
                    in 200..299 -> {
                        val raw = readBounded(resp) ?: return Result.failure(IOException("Copia remota demasiado grande."))
                        val root = json.parseToJsonElement(raw).jsonObject
                        val sha = (root["sha"] as? JsonPrimitive)?.contentOrNull
                        val contentB64 = (root["content"] as? JsonPrimitive)?.contentOrNull
                            ?.replace("\n", "")
                            ?.replace("\r", "")
                        val parsed = if (!contentB64.isNullOrBlank()) {
                            try {
                                val decoded = String(Base64.decode(contentB64, Base64.DEFAULT), Charsets.UTF_8)
                                json.decodeFromString<CloudBackupPayload>(decoded)
                            } catch (_: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                        Result.success(sha to parsed)
                    }
                    else -> Result.failure(IOException("GitHub devolvió HTTP ${resp.code}."))
                }
            }
        } catch (e: Exception) {
            Result.failure(IOException("Sin conexión con GitHub (${e.message?.take(45) ?: "red"})."))
        }
    }

    private fun pushRemoteBackup(
        token: String,
        payload: CloudBackupPayload,
        sha: String?,
    ): Result<String?> {
        val jsonStr = json.encodeToString(payload)
        val b64 = Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val bodyJson = buildJsonObject {
            put("message", "chore(brook): auto backup stats & library")
            put("content", b64)
            put("branch", BACKUP_BRANCH)
            if (!sha.isNullOrBlank()) {
                put("sha", sha)
            }
        }
        val req = Request.Builder()
            .url(BACKUP_FILE_API_URL)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Brook-Android/${BuildConfig.VERSION_NAME}")
            .put(bodyJson.toString().toRequestBody(JSON_MEDIA))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    in 200..299 -> {
                        val raw = readBounded(resp)
                        val newSha = try {
                            raw?.let {
                                val root = json.parseToJsonElement(it).jsonObject
                                ((root["content"] as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
                            }
                        } catch (_: Exception) {
                            null
                        }
                        Result.success(newSha)
                    }
                    409 -> Result.failure(IOException("CONFLICT_409"))
                    401, 403 -> Result.failure(
                        SecurityException("Token sin permiso de escritura (Contents: Read & Write) en brook-backup.")
                    )
                    else -> Result.failure(IOException("Error al subir copia (HTTP ${resp.code})."))
                }
            }
        } catch (e: Exception) {
            Result.failure(IOException("No se pudo conectar con GitHub."))
        }
    }

    private fun readBounded(resp: okhttp3.Response): String? {
        val src = resp.body?.source() ?: return null
        src.request(MAX_CLOUD_BYTES + 1)
        if (src.buffer.size > MAX_CLOUD_BYTES) return null
        return src.buffer.readUtf8()
    }

    private fun nowStamp(): String = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())

    companion object {
        const val BACKUP_REPO_SLUG = "OMEGA12492903/brook-backup"
        private const val BACKUP_BRANCH = "main"
        private const val ALLOWED_CONTENTS_PATH = "/repos/$BACKUP_REPO_SLUG/contents/brook_cloud_backup.json"
        private const val BACKUP_FILE_API_URL = "https://api.github.com$ALLOWED_CONTENTS_PATH"
        private const val CREATE_TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"

        private const val PREFS_NAME = "brook_github_sync_secure"
        private const val KEY_TOKEN = "gh_backup_pat"
        private const val KEY_AUTO_SYNC = "gh_auto_sync"
        private const val KEY_LAST_SYNC_TEXT = "gh_last_sync_txt"
        private const val KEY_LAST_SHA = "gh_last_sha"

        private const val AUTO_SYNC_DEBOUNCE_MS = 35_000L
        private const val MAX_CLOUD_BYTES = 4L * 1024L * 1024L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val CONTROL_AND_SPACE_RE = Regex("[\\s\\u0000-\\u001F\\u007F]")
        private val TOKEN_RE = Regex("^[A-Za-z0-9_-]{20,300}$")
    }
}
