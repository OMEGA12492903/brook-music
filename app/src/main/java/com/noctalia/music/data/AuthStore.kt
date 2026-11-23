package com.noctalia.music.data

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.MessageDigest

/**
 * Almacena la cookie de sesión de YouTube Music cifrada localmente con Android Keystore (AES-256-GCM).
 * Política estricta: NUNCA guarda cookies en texto plano en disco. Si Keystore falla de forma
 * irrecuperable en una ROM defectuosa, mantiene la sesión únicamente en memoria RAM.
 */
class AuthStore(private val context: Context) {

    private val prefs: SharedPreferences? = createEncryptedPrefsOrNull(context)
    @Volatile
    private var memoryCookie: String? = null
    @Volatile
    private var memoryLabel: String = "Cuenta YouTube Music"
    @Volatile
    private var memorySpotifyToken: String? = null
    @Volatile
    private var memorySpotifyClientToken: String? = null

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
                    // Recuperar una sola vez si el archivo cifrado previo se corrompió
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

    fun isHardwareEncryptedStorageActive(): Boolean = prefs != null

    fun saveCookie(rawCookie: String, accountLabel: String = "Cuenta YouTube Music") {
        val sanitized = sanitizeCookieHeader(rawCookie) ?: return
        val label = accountLabel.replace(CONTROL_CHARS_RE, "").trim().take(80).ifEmpty { "Cuenta YouTube Music" }
        memoryCookie = sanitized
        memoryLabel = label
        prefs?.edit()
            ?.putString(KEY_COOKIE, sanitized)
            ?.putString(KEY_LABEL, label)
            ?.apply()
    }

    fun getCookie(): String? {
        val raw = memoryCookie ?: prefs?.getString(KEY_COOKIE, null)
        val clean = raw?.let { sanitizeCookieHeader(it) }
        if (clean != null) memoryCookie = clean
        return clean
    }

    fun getAccountLabel(): String = prefs?.getString(KEY_LABEL, memoryLabel) ?: memoryLabel

    fun isLoggedIn(): Boolean = getCookie() != null

    fun clearSession() {
        memoryCookie = null
        prefs?.edit()?.remove(KEY_COOKIE)?.remove(KEY_LABEL)?.apply()
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (_: Exception) {
        }
    }

    // ─── SESIÓN CIFRADA DE SPOTIFY (INDEPENDIENTE DE YOUTUBE MUSIC) ───
    fun saveSpotifySession(rawBearerToken: String, rawClientToken: String? = null): Boolean {
        val cleanBearer = sanitizeSpotifyToken(rawBearerToken) ?: return false
        val cleanClient = rawClientToken?.let { sanitizeSpotifyToken(it) }
        memorySpotifyToken = cleanBearer
        memorySpotifyClientToken = cleanClient
        prefs?.edit()?.apply {
            putString(KEY_SPOTIFY_TOKEN, cleanBearer)
            if (cleanClient != null) {
                putString(KEY_SPOTIFY_CLIENT_TOKEN, cleanClient)
            } else {
                remove(KEY_SPOTIFY_CLIENT_TOKEN)
            }
            apply()
        }
        return true
    }

    fun getSpotifyBearerToken(): String? {
        val raw = memorySpotifyToken ?: prefs?.getString(KEY_SPOTIFY_TOKEN, null)
        val clean = raw?.let { sanitizeSpotifyToken(it) }
        if (clean != null) memorySpotifyToken = clean
        return clean
    }

    fun getSpotifyClientToken(): String? {
        val raw = memorySpotifyClientToken ?: prefs?.getString(KEY_SPOTIFY_CLIENT_TOKEN, null)
        val clean = raw?.let { sanitizeSpotifyToken(it) }
        if (clean != null) memorySpotifyClientToken = clean
        return clean
    }

    fun isSpotifyLoggedIn(): Boolean = getSpotifyBearerToken() != null

    fun clearSpotifySession() {
        memorySpotifyToken = null
        memorySpotifyClientToken = null
        prefs?.edit()
            ?.remove(KEY_SPOTIFY_TOKEN)
            ?.remove(KEY_SPOTIFY_CLIENT_TOKEN)
            ?.apply()
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (_: Exception) {
        }
    }

    fun buildSapisidHashAuthHeader(cookie: String, origin: String = "https://music.youtube.com"): String? {
        val sapisid = extractSapisid(cookie) ?: return null
        val ts = System.currentTimeMillis() / 1000L
        val raw = "$ts $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hex"
    }

    companion object {
        private const val PREFS_NAME = "noctalia_auth_secure"
        private const val KEY_COOKIE = "ytm_cookie"
        private const val KEY_LABEL = "ytm_label"
        private const val KEY_SPOTIFY_TOKEN = "sp_bearer_token"
        private const val KEY_SPOTIFY_CLIENT_TOKEN = "sp_client_token"
        private val CONTROL_CHARS_RE = Regex("[\\r\\n\\u0000-\\u001F\\u007F]")
        private val COOKIE_NAME_RE = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val SPOTIFY_TOKEN_RE = Regex("^[A-Za-z0-9._~+/=-]{20,2048}$")

        fun sanitizeSpotifyToken(raw: String?): String? {
            val clean = raw
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")
                ?.replace(CONTROL_CHARS_RE, "")
                ?.trim()
                ?: return null
            return clean.takeIf { SPOTIFY_TOKEN_RE.matches(it) }
        }

        // Solo conservar cookies estrictamente necesarias para autenticar la biblioteca en YouTube Music
        private val ALLOWED_COOKIE_NAMES = setOf(
            "SAPISID",
            "APISID",
            "SID",
            "HSID",
            "SSID",
            "LOGIN_INFO",
            "__Secure-1PAPISID",
            "__Secure-3PAPISID",
            "__Secure-1PSID",
            "__Secure-3PSID",
            "__Secure-1PSIDTS",
            "__Secure-3PSIDTS",
            "__Secure-1PSIDCC",
            "__Secure-3PSIDCC",
            "SIDCC",
            "SOCS",
            "CONSENT",
        )

        fun sanitizeCookieHeader(raw: String): String? {
            if (raw.isBlank() || raw.length > 16_384) return null
            val kept = mutableListOf<String>()
            for (part in raw.split(";")) {
                val clean = part.replace(CONTROL_CHARS_RE, "").trim()
                val eq = clean.indexOf('=')
                if (eq <= 0) continue
                val name = clean.substring(0, eq).trim()
                val value = clean.substring(eq + 1).trim()
                if (name in ALLOWED_COOKIE_NAMES && COOKIE_NAME_RE.matches(name) && value.isNotEmpty() && value.length <= 2048) {
                    kept.add("$name=$value")
                }
            }
            val joined = kept.joinToString("; ")
            return if (extractSapisid(joined) != null) joined else null
        }

        fun extractSapisid(cookie: String): String? {
            val pairs = cookie.split(";").map { it.trim() }
            for (target in listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")) {
                val match = pairs.firstOrNull { it.startsWith("$target=") }
                if (match != null) {
                    val value = match.substringAfter("=").replace(CONTROL_CHARS_RE, "").trim()
                    if (value.isNotEmpty()) return value
                }
            }
            return null
        }
    }
}
