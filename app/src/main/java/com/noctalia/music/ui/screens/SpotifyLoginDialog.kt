package com.noctalia.music.ui.screens

import android.annotation.SuppressLint
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.noctalia.music.data.AuthStore
import com.noctalia.music.ui.theme.NoctaliaColors
import java.util.concurrent.atomic.AtomicBoolean

private val ALLOWED_SPOTIFY_LOGIN_HOSTS = setOf(
    "accounts.spotify.com",
    "open.spotify.com",
    "www.spotify.com",
    "login.spotify.com",
    "challenge.spotify.com",
    "accounts.google.com",
    "accounts.google.es",
    "gds.google.com",
    "consent.google.com",
    "appleid.apple.com",
    "www.facebook.com",
    "m.facebook.com",
    "ssl.gstatic.com",
    "fonts.gstatic.com",
    "www.google.com",
    "www.recaptcha.net",
    "recaptcha.net",
)

private const val SPOTIFY_DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private fun isAllowedSpotifyLoginHost(host: String?): Boolean {
    val h = host?.lowercase()?.trimEnd('.') ?: return false
    return h in ALLOWED_SPOTIFY_LOGIN_HOSTS
}

private fun wipeSpotifyWebView(webView: WebView?) {
    try {
        webView?.stopLoading()
        webView?.clearHistory()
        webView?.clearCache(true)
        webView?.clearFormData()
    } catch (_: Exception) {
    }
    try {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    } catch (_: Exception) {
    }
}

/**
 * Pantalla endurecida de inicio de sesión oficial de Spotify:
 * - FLAG_SECURE (bloquea capturas/grabación mientras inicias sesión).
 * - Solo permite hosts oficiales de login de Spotify y proveedores SSO (Google/Apple/Facebook).
 * - Captura exclusivamente el Bearer autenticado (verificando presencia de cookie `sp_dc=`)
 *   y `client-token` de las peticiones del reproductor web oficial, borrando al instante
 *   todas las cookies y caché del WebView tras cifrarlo en Android Keystore.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: (bearerToken: String, clientToken: String?) -> Unit,
) {
    val captured = remember { AtomicBoolean(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = {
            wipeSpotifyWebView(webViewRef)
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                wipeSpotifyWebView(webViewRef)
                try {
                    webViewRef?.destroy()
                } catch (_: Exception) {
                }
                webViewRef = null
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NoctaliaColors.BgDeep)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NoctaliaColors.GlassStrong)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Conectar cuenta Spotify",
                        color = NoctaliaColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "Inicia sesión para cargar tus listas privadas y Me gusta",
                        color = NoctaliaColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
                IconButton(onClick = {
                    wipeSpotifyWebView(webViewRef)
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cerrar",
                        tint = NoctaliaColors.TextPrimary,
                    )
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setGeolocationEnabled(false)
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.userAgentString = SPOTIFY_DESKTOP_UA

                        val cm = CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val uri = request?.url ?: return true
                                if (!uri.scheme.equals("https", ignoreCase = true)) return true
                                return !isAllowedSpotifyLoginHost(uri.host)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                if (!captured.get() && request != null) {
                                    val uri = request.url
                                    val host = uri?.host?.lowercase()?.trimEnd('.')
                                    if (uri?.scheme.equals("https", ignoreCase = true) &&
                                        (host == "api.spotify.com" || host == "api-partner.spotify.com")
                                    ) {
                                        val cookies = try {
                                            CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
                                        } catch (_: Exception) {
                                            ""
                                        }
                                        // Solo capturar si existe sp_dc= (sesión autenticada real, nunca invitado anónimo)
                                        if (cookies.contains("sp_dc=")) {
                                            val hdrs = request.requestHeaders.orEmpty()
                                            val rawAuth = hdrs.entries
                                                .firstOrNull { it.key.equals("authorization", ignoreCase = true) }
                                                ?.value
                                            val rawClient = hdrs.entries
                                                .firstOrNull { it.key.equals("client-token", ignoreCase = true) }
                                                ?.value
                                            val cleanBearer = if (rawAuth != null && rawAuth.startsWith("Bearer ", ignoreCase = true)) {
                                                AuthStore.sanitizeSpotifyToken(rawAuth)
                                            } else {
                                                null
                                            }
                                            val cleanClient = rawClient?.let { AuthStore.sanitizeSpotifyToken(it) }
                                            if (cleanBearer != null && captured.compareAndSet(false, true)) {
                                                view?.post {
                                                    wipeSpotifyWebView(view)
                                                    onLoginSuccess(cleanBearer, cleanClient)
                                                }
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                    }
                },
            )
        }
    }
}
