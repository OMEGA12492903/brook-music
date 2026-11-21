package com.noctalia.music.ui.screens

import android.annotation.SuppressLint
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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

// Lista estricta de hosts oficiales necesarios para OAuth/login de Google y YouTube Music
private val ALLOWED_LOGIN_EXACT_HOSTS = setOf(
    "accounts.google.com",
    "accounts.google.es",
    "accounts.youtube.com",
    "music.youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "consent.youtube.com",
    "consent.google.com",
    "myaccount.google.com",
    "gds.google.com",
    "ssl.gstatic.com",
    "fonts.gstatic.com",
)

private fun isAllowedLoginHost(host: String?): Boolean {
    val h = host?.lowercase()?.trimEnd('.') ?: return false
    return h in ALLOWED_LOGIN_EXACT_HOSTS
}

private fun wipeWebViewData(webView: WebView?) {
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
 * Pantalla endurecida de inicio de sesión oficial de Google para YouTube Music:
 * - FLAG_SECURE (bloquea capturas/grabación de pantalla mientras escribes tu contraseña).
 * - Deshabilita file://, content://, contenido mixto y ventanas emergentes.
 * - Solo permite hosts exactos de login oficial de Google/YouTube.
 * - Borra inmediatamente cookies en texto plano, caché e historial del WebView tras capturar
 *   la sesión hacia AuthStore (cifrado AES-256-GCM en Android Keystore).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: (String) -> Unit,
) {
    var captured by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = {
            wipeWebViewData(webViewRef)
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Activar FLAG_SECURE en la ventana del diálogo de login
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                wipeWebViewData(webViewRef)
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
                        text = "Conectar cuenta YouTube Music",
                        color = NoctaliaColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "Sesión cifrada localmente · Capturas bloqueadas",
                        color = NoctaliaColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
                IconButton(onClick = {
                    wipeWebViewData(webViewRef)
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
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")

                        val cm = CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val uri = request?.url ?: return true
                                if (!uri.scheme.equals("https", ignoreCase = true)) return true
                                return !isAllowedLoginHost(uri.host)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (captured || url == null) return
                                val parsed = try {
                                    java.net.URI(url)
                                } catch (_: Exception) {
                                    null
                                }
                                val isYtMusic = parsed != null &&
                                    parsed.scheme.equals("https", ignoreCase = true) &&
                                    parsed.host.equals("music.youtube.com", ignoreCase = true)
                                if (isYtMusic) {
                                    val rawCookie = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: ""
                                    val clean = AuthStore.sanitizeCookieHeader(rawCookie)
                                    if (clean != null) {
                                        captured = true
                                        // Borrar rastro en texto plano del WebView inmediatamente
                                        wipeWebViewData(view)
                                        onLoginSuccess(clean)
                                    }
                                }
                            }
                        }

                        loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F")
                    }
                },
            )
        }
    }
}
