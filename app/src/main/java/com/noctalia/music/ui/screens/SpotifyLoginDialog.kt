package com.noctalia.music.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.noctalia.music.data.AuthStore
import com.noctalia.music.ui.theme.NoctaliaColors
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val SPOTIFY_COLLECTION_URL = "https://open.spotify.com/collection/tracks"
private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2Fcollection%2Ftracks"
private const val BRIDGE_NAME = "BrookSpBridge"

/**
 * Puente público para WebView (`@JavascriptInterface` requiere clase y métodos públicos en Android/R8).
 * Valida un `expectedNonce` aleatorio por sesión para impedir llamadas desde iframes o scripts ajenos.
 */
class SpotifyTokenJsBridge(
    private val expectedNonce: String,
    private val onTokenCaptured: (bearer: String?, clientToken: String?, verifiedUser: Boolean) -> Unit,
    private val onLoggedInDetected: (isLoggedIn: Boolean) -> Unit,
) {
    @JavascriptInterface
    fun onToken(nonce: String?, bearer: String?, clientToken: String?, verifiedUser: Boolean) {
        if (nonce != expectedNonce) return
        onTokenCaptured(bearer, clientToken, verifiedUser)
    }

    @JavascriptInterface
    fun onPageLoggedIn(nonce: String?, isLoggedIn: Boolean) {
        if (nonce != expectedNonce) return
        onLoggedInDetected(isLoggedIn)
    }
}

private val ALLOWED_EXACT_SSO_HOSTS = setOf(
    "accounts.google.com",
    "accounts.youtube.com",
    "myaccount.google.com",
    "gds.google.com",
    "consent.google.com",
    "signin.google.com",
    "www.google.com",
    "apis.google.com",
    "policies.google.com",
    "ogs.google.com",
    "appleid.apple.com",
    "idmsa.apple.com",
    "gsa.apple.com",
    "appleid.cdn-apple.com",
    "challenges.cloudflare.com",
    "cdn.cookielaw.org",
)

private val ALLOWED_SUFFIX_DOMAINS = listOf(
    "spotify.com",
    "spotify.net",
    "scdn.co",
    "spotifycdn.com",
    "gstatic.com",
    "recaptcha.net",
    "facebook.com",
    "fbcdn.net",
    "hcaptcha.com",
    "arkoselabs.com",
    "funcaptcha.com",
    "onetrust.com",
)

private val GOOGLE_LOCALIZED_ACCOUNTS_RE = Regex("^accounts\\.google(\\.[a-z]{2,3}){1,2}$")

private fun isAllowedSpotifyLoginHost(host: String?): Boolean {
    val h = host?.lowercase()?.trimEnd('.') ?: return false
    if (h in ALLOWED_EXACT_SSO_HOSTS) return true
    if (GOOGLE_LOCALIZED_ACCOUNTS_RE.matches(h)) return true
    return ALLOWED_SUFFIX_DOMAINS.any { domain -> h == domain || h.endsWith(".$domain") }
}

private fun isOpenSpotifyUrl(rawUrl: String?): Boolean {
    val uri = try {
        rawUrl?.let { Uri.parse(it) }
    } catch (_: Exception) {
        null
    } ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("open.spotify.com", ignoreCase = true)
}

private fun isSpotifyApiHost(host: String?): Boolean {
    val h = host?.lowercase()?.trimEnd('.') ?: return false
    return h == "api.spotify.com" ||
        h == "api-partner.spotify.com" ||
        h == "spclient.wg.spotify.com" ||
        h.endsWith(".spclient.spotify.com")
}

private fun hasSpotifySessionCookie(): Boolean {
    return try {
        val cm = CookieManager.getInstance()
        val c1 = cm.getCookie("https://open.spotify.com").orEmpty()
        if (c1.contains("sp_dc=")) return true
        val c2 = cm.getCookie("https://spotify.com").orEmpty()
        if (c2.contains("sp_dc=")) return true
        val c3 = cm.getCookie("https://accounts.spotify.com").orEmpty()
        c3.contains("sp_dc=")
    } catch (_: Exception) {
        false
    }
}

private fun wipeSpotifyWebView(webView: WebView?) {
    try {
        webView?.removeJavascriptInterface(BRIDGE_NAME)
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
 * Observador pasivo inyectado ÚNICAMENTE dentro de `https://open.spotify.com/...` (jamás en páginas
 * de contraseña o login SSO). Captura sin bloquear el Bearer autenticado y `client-token` emitidos
 * por el propio reproductor web oficial de Spotify (`/api/token`, `clienttoken.spotify.com`, fetch/XHR).
 */
private fun buildSpotifyCaptureJs(nonce: String): String = """
(function() {
  if (window.top !== window) return;
  if (location.hostname !== 'open.spotify.com') return;
  if (window.__brookSpHooked) return;
  window.__brookSpHooked = true;
  var __nonce = "$nonce";

  function notifyToken(bearer, clientToken, verifiedUser) {
    try {
      if (window.BrookSpBridge && typeof window.BrookSpBridge.onToken === 'function') {
        window.BrookSpBridge.onToken(__nonce, bearer || '', clientToken || '', !!verifiedUser);
      }
    } catch (e) {}
  }

  function notifyLoggedIn(flag) {
    try {
      if (window.BrookSpBridge && typeof window.BrookSpBridge.onPageLoggedIn === 'function') {
        window.BrookSpBridge.onPageLoggedIn(__nonce, !!flag);
      }
    } catch (e) {}
  }

  function isSpotifyUrl(u) {
    if (!u || typeof u !== 'string') return false;
    if (u.startsWith('/')) return true;
    try {
      var h = new URL(u, location.origin).hostname.toLowerCase();
      return h === 'spotify.com' || h.endsWith('.spotify.com');
    } catch (e) {
      return false;
    }
  }

  function inspectHeadersObj(hObj) {
    if (!hObj) return;
    var bearer = '';
    var ctok = '';
    try {
      if (typeof Headers !== 'undefined' && hObj instanceof Headers) {
        bearer = hObj.get('authorization') || hObj.get('Authorization') || '';
        ctok = hObj.get('client-token') || hObj.get('Client-Token') || '';
      } else if (Array.isArray(hObj)) {
        for (var i = 0; i < hObj.length; i++) {
          var pair = hObj[i];
          if (!pair || pair.length < 2) continue;
          var k = String(pair[0]).toLowerCase();
          if (k === 'authorization') bearer = String(pair[1]);
          else if (k === 'client-token') ctok = String(pair[1]);
        }
      } else if (typeof hObj === 'object') {
        var keys = Object.keys(hObj);
        for (var j = 0; j < keys.length; j++) {
          var kl = keys[j].toLowerCase();
          if (kl === 'authorization') bearer = String(hObj[keys[j]]);
          else if (kl === 'client-token') ctok = String(hObj[keys[j]]);
        }
      }
    } catch (e) {}
    if (bearer || ctok) notifyToken(bearer, ctok, false);
  }

  function inspectTokenJson(urlStr, data) {
    if (!data || typeof data !== 'object') return;
    try {
      if (urlStr.indexOf('/api/token') !== -1 || urlStr.indexOf('/get_access_token') !== -1) {
        if (data.accessToken && data.isAnonymous === false) {
          notifyLoggedIn(true);
          notifyToken('Bearer ' + data.accessToken, '', true);
        }
      } else if (urlStr.indexOf('clienttoken') !== -1) {
        var gt = data.granted_token && data.granted_token.token;
        if (gt) notifyToken('', gt, false);
      }
    } catch (e) {}
  }

  function decodeB64Utf8(b64) {
    var bin = atob(b64);
    try {
      var bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return new TextDecoder('utf-8').decode(bytes);
    } catch (e) {
      return bin;
    }
  }

  function extractUrl(input) {
    if (!input) return '';
    if (typeof input === 'string') return input;
    if (input.url) return String(input.url);
    if (input.href) return String(input.href);
    try { return String(input); } catch (e) { return ''; }
  }

  // 1. Comprobar scripts embebidos (initialState / appServerConfig / session)
  try {
    var initEl = document.getElementById('initialState');
    if (initEl && initEl.textContent) {
      var initObj = JSON.parse(decodeB64Utf8(initEl.textContent.trim()));
      if (initObj && initObj.session && (initObj.session.isAnonymous === false || initObj.session.me)) {
        notifyLoggedIn(true);
      }
    }
  } catch (e) {}
  try {
    var cfgEl = document.getElementById('appServerConfig');
    if (cfgEl && cfgEl.textContent) {
      var cfgObj = JSON.parse(decodeB64Utf8(cfgEl.textContent.trim()));
      if (cfgObj && cfgObj.isAnonymous === false) {
        notifyLoggedIn(true);
      }
    }
  } catch (e) {}
  try {
    var sesEl = document.getElementById('session');
    if (sesEl && sesEl.textContent) {
      var sesObj = JSON.parse(sesEl.textContent.trim());
      if (sesObj && sesObj.accessToken && sesObj.isAnonymous === false) {
        notifyLoggedIn(true);
        notifyToken('Bearer ' + sesObj.accessToken, '', true);
      }
    }
  } catch (e) {}

  // 2. Observar window.fetch sin modificar la petición ni bloquear
  if (typeof window.fetch === 'function') {
    var origFetch = window.fetch;
    window.fetch = function(input, init) {
      var urlStr = '';
      try {
        urlStr = extractUrl(input);
        if (isSpotifyUrl(urlStr)) {
          if (init && init.headers) inspectHeadersObj(init.headers);
          if (input && typeof input === 'object' && input.headers) inspectHeadersObj(input.headers);
        }
      } catch (e) {}
      var p = origFetch.apply(this, arguments);
      try {
        if (urlStr && (urlStr.indexOf('/api/token') !== -1 || urlStr.indexOf('/get_access_token') !== -1 || urlStr.indexOf('clienttoken') !== -1)) {
          p.then(function(resp) {
            try {
              if (resp && resp.ok) {
                resp.clone().json().then(function(d) { inspectTokenJson(urlStr, d); }).catch(function(){});
              }
            } catch (e) {}
          }).catch(function(){});
        }
      } catch (e) {}
      return p;
    };
  }

  // 3. Observar XMLHttpRequest sin modificar la petición
  if (typeof XMLHttpRequest !== 'undefined' && XMLHttpRequest.prototype) {
    var origOpen = XMLHttpRequest.prototype.open;
    var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
      try { this.__brookUrl = url ? String(url) : ''; } catch (e) {}
      return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
      try {
        if (isSpotifyUrl(this.__brookUrl || '')) {
          var kl = String(name).toLowerCase();
          if (kl === 'authorization') notifyToken(String(value), '', false);
          else if (kl === 'client-token') notifyToken('', String(value), false);
        }
      } catch (e) {}
      return origSetHeader.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function() {
      try {
        var u = this.__brookUrl || '';
        if (u.indexOf('/api/token') !== -1 || u.indexOf('/get_access_token') !== -1 || u.indexOf('clienttoken') !== -1) {
          this.addEventListener('load', function() {
            try {
              var txt = this.responseType === '' || this.responseType === 'text' ? this.responseText : '';
              if (txt) inspectTokenJson(u, JSON.parse(txt));
            } catch (e) {}
          });
        }
      } catch (e) {}
      return origSend.apply(this, arguments);
    };
  }
})();
"""

/**
 * Pantalla endurecida de inicio de sesión oficial de Spotify:
 * - FLAG_SECURE (bloquea capturas/grabación mientras inicias sesión).
 * - Permite hosts oficiales de Spotify y proveedores SSO (Google/Apple/Facebook + captchas) solo por HTTPS.
 * - Soporta ventanas emergentes SSO (`onCreateWindow`) redirigiéndolas de forma segura dentro del mismo WebView.
 * - Espera al borrado asíncrono de cookies antes de cargar el login (evita borrar el CSRF cookie de Spotify).
 * - Captura el Bearer autenticado y `client-token` mediante puente pasivo en `open.spotify.com` + respaldo
 *   en `shouldInterceptRequest`, verificando siempre sesión real (`isAnonymous === false` o cookie `sp_dc=`),
 *   y borra al instante todo rastro del WebView al cifrarlo en Android Keystore.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: (bearerToken: String, clientToken: String?) -> Unit,
) {
    val captured = remember { AtomicBoolean(false) }
    val bridgeNonce = remember { UUID.randomUUID().toString() }
    val captureJs = remember(bridgeNonce) { buildSpotifyCaptureJs(bridgeNonce) }
    val pendingBearer = remember { AtomicReference<String?>(null) }
    val pendingClient = remember { AtomicReference<String?>(null) }
    val pageLoggedIn = remember { AtomicBoolean(false) }
    val fallbackScheduled = remember { AtomicBoolean(false) }
    val reloadedOnce = remember { AtomicBoolean(false) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var isLoadingPage by remember { mutableStateOf(true) }
    var onOpenSpotify by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Inicia sesión para cargar tus playlists y Me gusta") }

    fun tryFinalizeCapture(view: WebView?, verifiedUser: Boolean) {
        if (captured.get()) return
        val isRealUser = verifiedUser || pageLoggedIn.get() || hasSpotifySessionCookie()
        if (!isRealUser) return

        val b = pendingBearer.get() ?: return
        val c = pendingClient.get()
        if (c != null && captured.compareAndSet(false, true)) {
            view?.post {
                statusText = "¡Cuenta conectada!"
                wipeSpotifyWebView(view)
                onLoginSuccess(b, c)
            }
        } else if (fallbackScheduled.compareAndSet(false, true)) {
            view?.post {
                statusText = "Sesión verificada · Finalizando conexión..."
            }
            // Esperar brevemente hasta 1.8s por si llega `client-token`; si no, continuar con Bearer
            view?.postDelayed({
                val finalBearer = pendingBearer.get()
                val finalClient = pendingClient.get()
                if (finalBearer != null && captured.compareAndSet(false, true)) {
                    wipeSpotifyWebView(view)
                    onLoginSuccess(finalBearer, finalClient)
                }
            }, 1800L)
        }
    }

    fun recordCandidateTokens(
        view: WebView?,
        rawBearer: String?,
        rawClient: String?,
        verifiedUser: Boolean,
    ) {
        if (captured.get()) return
        val isRealUser = verifiedUser || pageLoggedIn.get() || hasSpotifySessionCookie()
        val cleanClient = rawClient?.takeIf { it.isNotBlank() }?.let { AuthStore.sanitizeSpotifyToken(it) }
        if (cleanClient != null) pendingClient.set(cleanClient)

        if (isRealUser && !rawBearer.isNullOrBlank()) {
            val looksBearer = rawBearer.startsWith("Bearer ", ignoreCase = true) || verifiedUser
            if (looksBearer) {
                val cleanBearer = AuthStore.sanitizeSpotifyToken(rawBearer)
                if (cleanBearer != null) {
                    if (verifiedUser) pageLoggedIn.set(true)
                    pendingBearer.set(cleanBearer)
                }
            }
        }
        tryFinalizeCapture(view, verifiedUser)
    }

    Dialog(
        onDismissRequest = {
            captured.set(true)
            wipeSpotifyWebView(webViewRef)
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                captured.set(true)
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
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (canGoBack) {
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Atrás",
                            tint = NoctaliaColors.TextPrimary,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (canGoBack) 0.dp else 6.dp),
                ) {
                    Text(
                        text = "Conectar cuenta Spotify",
                        color = NoctaliaColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText,
                        color = if (onOpenSpotify) NoctaliaColors.CyanGlow else NoctaliaColors.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onOpenSpotify) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(NoctaliaColors.Cyan.copy(alpha = 0.22f))
                            .clickable {
                                statusText = "Sincronizando con Spotify..."
                                webViewRef?.loadUrl(SPOTIFY_COLLECTION_URL)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Sincronizar",
                            tint = NoctaliaColors.CyanGlow,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Conectar",
                            color = NoctaliaColors.CyanGlow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        captured.set(true)
                        wipeSpotifyWebView(webViewRef)
                        onDismiss()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cerrar",
                        tint = NoctaliaColors.TextPrimary,
                    )
                }
            }

            if (isLoadingPage) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = NoctaliaColors.CyanGlow,
                    trackColor = NoctaliaColors.Glass,
                )
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
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        // Usar UA nativo de Chrome Android sin "; wv" para que Google SSO y Spotify funcionen sin bloqueos
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")

                        val bridge = SpotifyTokenJsBridge(
                            expectedNonce = bridgeNonce,
                            onTokenCaptured = { bearer, clientToken, verifiedUser ->
                                post {
                                    if (captured.get()) return@post
                                    if (!isOpenSpotifyUrl(url)) return@post
                                    recordCandidateTokens(this@apply, bearer, clientToken, verifiedUser)
                                }
                            },
                            onLoggedInDetected = { isLoggedIn ->
                                post {
                                    if (captured.get() || !isLoggedIn) return@post
                                    if (!isOpenSpotifyUrl(url)) return@post
                                    pageLoggedIn.set(true)
                                    onOpenSpotify = true
                                    statusText = "Sesión detectada · Conectando tus playlists..."
                                    tryFinalizeCapture(this@apply, verifiedUser = true)
                                }
                            },
                        )
                        addJavascriptInterface(bridge, BRIDGE_NAME)

                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?,
                            ): Boolean {
                                val parent = view ?: return false
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                val handled = AtomicBoolean(false)
                                fun redirectAndDestroy(v: WebView?, targetUrl: String?) {
                                    if (!handled.compareAndSet(false, true)) return
                                    val uri = targetUrl?.let { Uri.parse(it) }
                                    if (uri != null &&
                                        uri.scheme.equals("https", ignoreCase = true) &&
                                        isAllowedSpotifyLoginHost(uri.host)
                                    ) {
                                        parent.post { parent.loadUrl(uri.toString()) }
                                    }
                                    v?.post {
                                        try {
                                            v.stopLoading()
                                            v.destroy()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                                val popup = WebView(parent.context).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.setGeolocationEnabled(false)
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    settings.userAgentString = parent.settings.userAgentString
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            v: WebView?,
                                            req: WebResourceRequest?,
                                        ): Boolean {
                                            redirectAndDestroy(v, req?.url?.toString())
                                            return true
                                        }

                                        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                            redirectAndDestroy(v, url)
                                        }
                                    }
                                }
                                // Autodestruir el popup tras 15 s si nunca llegó a navegar para evitar fugas de memoria
                                popup.postDelayed({ redirectAndDestroy(popup, null) }, 15_000L)
                                transport.webView = popup
                                resultMsg.sendToTarget()
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val uri = request?.url ?: return true
                                // Bloquea deep links `spotify://`, `intent://`, `http://`, etc. para no salir del WebView
                                if (!uri.scheme.equals("https", ignoreCase = true)) return true
                                return !isAllowedSpotifyLoginHost(uri.host)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoadingPage = true
                                canGoBack = view?.canGoBack() == true
                                val onOpen = isOpenSpotifyUrl(url)
                                onOpenSpotify = onOpen
                                if (onOpen) {
                                    statusText = "Cargando tu cuenta de Spotify..."
                                }
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                super.onPageCommitVisible(view, url)
                                if (!captured.get() && isOpenSpotifyUrl(url)) {
                                    try {
                                        CookieManager.getInstance().flush()
                                    } catch (_: Exception) {
                                    }
                                    view?.evaluateJavascript(captureJs, null)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoadingPage = false
                                canGoBack = view?.canGoBack() == true
                                if (captured.get() || !isOpenSpotifyUrl(url)) return

                                onOpenSpotify = true
                                try {
                                    CookieManager.getInstance().flush()
                                } catch (_: Exception) {
                                }
                                view?.evaluateJavascript(captureJs, null)

                                val hasSession = pageLoggedIn.get() || hasSpotifySessionCookie()
                                if (hasSession) {
                                    statusText = "Sesión detectada · Capturando acceso..."
                                    tryFinalizeCapture(view, verifiedUser = false)
                                    // Si el primer /api/token ocurrió antes de inyectar el hook, recargar una sola vez
                                    view?.postDelayed({
                                        if (!captured.get() &&
                                            pendingBearer.get() == null &&
                                            (pageLoggedIn.get() || hasSpotifySessionCookie()) &&
                                            reloadedOnce.compareAndSet(false, true)
                                        ) {
                                            statusText = "Activando conexión con tu biblioteca..."
                                            view.loadUrl(SPOTIFY_COLLECTION_URL)
                                        }
                                    }, 2500L)
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                if (!captured.get() && request != null) {
                                    val uri = request.url
                                    val host = uri?.host?.lowercase()?.trimEnd('.')
                                    if (uri?.scheme.equals("https", ignoreCase = true) && isSpotifyApiHost(host)) {
                                        val hdrs = request.requestHeaders.orEmpty()
                                        val rawAuth = hdrs.entries
                                            .firstOrNull { it.key.equals("authorization", ignoreCase = true) }
                                            ?.value
                                        val rawClient = hdrs.entries
                                            .firstOrNull { it.key.equals("client-token", ignoreCase = true) }
                                            ?.value
                                        if (!rawAuth.isNullOrBlank() || !rawClient.isNullOrBlank()) {
                                            view?.post {
                                                recordCandidateTokens(view, rawAuth, rawClient, verifiedUser = false)
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        // Esperar al callback de removeAllCookies ANTES de cargar login (evita borrar el CSRF cookie)
                        cm.removeAllCookies {
                            try {
                                cm.flush()
                            } catch (_: Exception) {
                            }
                            post {
                                if (!captured.get() && webViewRef === this) {
                                    loadUrl(SPOTIFY_LOGIN_URL)
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}
