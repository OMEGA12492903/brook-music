package com.noctalia.music.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.noctalia.music.BuildConfig
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val currentVersionName: String,
    val currentVersionCode: Int,
    val latestVersionName: String,
    val latestVersionCode: Int?,
    val releaseNotes: String,
    val apkSizeBytes: Long?,
    val downloadUrl: String,
    val releasesPageUrl: String,
    val updateAvailable: Boolean,
)

/**
 * Comprobador y descargador seguro de actualizaciones de Brook desde GitHub Releases.
 * - Cliente OkHttp 100% aislado (sin cookies de Google, validación estricta de hosts GitHub por salto).
 * - Límite de 35 MiB por APK para evitar agotamiento de disco/memoria.
 */
class AppUpdater(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val updaterClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (!isAllowedGithubUrl(req.url.toString())) {
                throw IOException("Blocked non-GitHub update host: ${req.url.host}")
            }
            val clean = req.newBuilder()
                .removeHeader("Cookie")
                .removeHeader("Authorization")
                .build()
            chain.proceed(clean)
        })
        .build()

    suspend fun checkForUpdate(): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        val currentName = BuildConfig.VERSION_NAME
        val currentCode = BuildConfig.VERSION_CODE

        val endpoints = listOf(RELEASE_TAG_LATEST_API, RELEASE_LATEST_API)
        var lastErr = "No se pudo consultar GitHub Releases."

        for (ep in endpoints) {
            try {
                val req = Request.Builder()
                    .url(ep)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Brook-Android/$currentName")
                    .get()
                    .build()

                updaterClient.newCall(req).execute().use { resp ->
                    if (resp.code == 404) {
                        lastErr = "Aún no hay una release publicada en GitHub."
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        lastErr = "GitHub devolvió HTTP ${resp.code}."
                        return@use
                    }
                    val bodyStr = resp.body?.source()?.let { src ->
                        src.request(MAX_JSON_BYTES + 1)
                        if (src.buffer.size > MAX_JSON_BYTES) null else src.buffer.readUtf8()
                    } ?: run {
                        lastErr = "Respuesta de GitHub inválida."
                        return@use
                    }

                    val root = json.parseToJsonElement(bodyStr) as? JsonObject ?: run {
                        lastErr = "Respuesta de GitHub inválida."
                        return@use
                    }
                    val tagName = root.str("tag_name") ?: ""
                    val relName = root.str("name") ?: ""
                    val bodyText = root.str("body") ?: ""

                    val parsedVerName = extractSemver("$tagName $relName $bodyText") ?: tagName.ifBlank { currentName }
                    val parsedCode = Regex("versionCode[:=\\s]+(\\d+)", RegexOption.IGNORE_CASE)
                        .find(bodyText)?.groupValues?.getOrNull(1)?.toIntOrNull()

                    val assets = (root["assets"] as? JsonArray).orEmpty()
                    val apkAsset = assets
                        .mapNotNull { it as? JsonObject }
                        .firstOrNull { (it.str("name") ?: "").endsWith(".apk", ignoreCase = true) }

                    val rawDownload = apkAsset?.str("browser_download_url") ?: DIRECT_APK_FALLBACK_URL
                    val safeDownload = if (isAllowedGithubUrl(rawDownload)) rawDownload else DIRECT_APK_FALLBACK_URL
                    val apkSize = (apkAsset?.get("size") as? JsonPrimitive)?.longOrNull

                    val updateAvailable = when {
                        parsedCode != null -> parsedCode > currentCode
                        else -> compareSemver(parsedVerName, currentName) > 0
                    }

                    return@withContext Result.success(
                        AppUpdateInfo(
                            currentVersionName = currentName,
                            currentVersionCode = currentCode,
                            latestVersionName = parsedVerName,
                            latestVersionCode = parsedCode,
                            releaseNotes = bodyText.trim().take(320),
                            apkSizeBytes = apkSize,
                            downloadUrl = safeDownload,
                            releasesPageUrl = RELEASES_PAGE_URL,
                            updateAvailable = updateAvailable,
                        )
                    )
                }
            } catch (e: Exception) {
                lastErr = "Error de conexión con GitHub: ${e.message?.take(60) ?: "sin red"}"
            }
        }
        Result.failure(IOException(lastErr))
    }

    suspend fun downloadApk(
        url: String,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!isAllowedGithubUrl(url)) {
            return@withContext Result.failure(SecurityException("URL de actualización no autorizada."))
        }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "Brook-update.apk")
        val tmp = File(dir, "Brook-update.apk.tmp")

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Brook-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            updaterClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("Error al descargar APK (HTTP ${resp.code})."))
                }
                val body = resp.body ?: return@withContext Result.failure(IOException("Respuesta vacía."))
                val total = body.contentLength()
                if (total > MAX_APK_BYTES) {
                    return@withContext Result.failure(IOException("APK demasiado grande."))
                }

                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(16_384)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            copied += n
                            if (copied > MAX_APK_BYTES) {
                                throw IOException("APK excede el límite permitido.")
                            }
                            out.write(buf, 0, n)
                            if (total > 0L) {
                                onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }

            // Verificación criptográfica: exigir mismo packageName y misma firma RSA-4096 que la app instalada
            if (!verifyApkMatchesInstalledSignature(target)) {
                target.delete()
                return@withContext Result.failure(
                    SecurityException("Firma del APK descargado no coincide con Brook. Actualización bloqueada.")
                )
            }

            onProgress(1f)
            Result.success(target)
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(e)
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApkMatchesInstalledSignature(apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val installedDigests = getCertSha256Set(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                }
            )
            if (installedDigests.isEmpty()) return false

            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return false

            if (archiveInfo.packageName != context.packageName) return false
            // Prevenir ataques de downgrade: no permitir instalar un versionCode inferior al instalado
            val archiveVerCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.longVersionCode
            } else {
                archiveInfo.versionCode.toLong()
            }
            if (archiveVerCode < BuildConfig.VERSION_CODE.toLong()) return false

            val archiveDigests = getCertSha256Set(archiveInfo)
            archiveDigests.isNotEmpty() && archiveDigests == installedDigests
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun getCertSha256Set(pkgInfo: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = pkgInfo.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            pkgInfo.signatures
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun launchApkInstaller(apkFile: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(permIntent)
                return false
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updater.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openReleasesInBrowser() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val REPO_SLUG = "OMEGA12492903/brook-music"
        const val RELEASE_TAG_LATEST_API = "https://api.github.com/repos/$REPO_SLUG/releases/tags/latest"
        const val RELEASE_LATEST_API = "https://api.github.com/repos/$REPO_SLUG/releases/latest"
        const val DIRECT_APK_FALLBACK_URL = "https://github.com/$REPO_SLUG/releases/download/latest/Brook.apk"
        const val RELEASES_PAGE_URL = "https://github.com/$REPO_SLUG/releases/latest"

        private const val MAX_JSON_BYTES = 1L * 1024L * 1024L
        private const val MAX_APK_BYTES = 35L * 1024L * 1024L

        private val ALLOWED_GITHUB_ASSET_HOSTS = setOf(
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )

        fun isAllowedGithubUrl(url: String?): Boolean {
            val u = url?.trim() ?: return false
            return try {
                val parsed = URI(u).normalize()
                if (!parsed.scheme.equals("https", ignoreCase = true)) return false
                if (parsed.userInfo != null) return false
                val host = parsed.host?.lowercase()?.trimEnd('.') ?: return false
                val path = parsed.path ?: ""
                if (path.contains("..")) return false
                when {
                    host == "github.com" -> path.startsWith("/$REPO_SLUG/")
                    host == "api.github.com" -> path.startsWith("/repos/$REPO_SLUG/")
                    host in ALLOWED_GITHUB_ASSET_HOSTS -> true
                    else -> false
                }
            } catch (_: Exception) {
                false
            }
        }

        private fun extractSemver(text: String): String? {
            return Regex("v?(\\d+\\.\\d+\\.\\d+)").find(text)?.groupValues?.getOrNull(1)
        }

        fun compareSemver(a: String, b: String): Int {
            val pa = a.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val pb = b.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val va = pa.getOrElse(i) { 0 }
                val vb = pb.getOrElse(i) { 0 }
                if (va != vb) return va.compareTo(vb)
            }
            return 0
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
