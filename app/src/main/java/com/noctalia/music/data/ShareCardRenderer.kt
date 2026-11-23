package com.noctalia.music.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Generador local (100% privado, en Canvas Android) de tarjetas PNG listas para compartir
 * en WhatsApp, Instagram Stories o Telegram:
 * - Tarjeta de Letra (1-4 versos + portada + título + artista).
 * - Tarjeta de Brook Wrapped (tiempo total, artista #1, Top 5 canciones).
 */
object ShareCardRenderer {

    suspend fun shareLyricsCard(
        context: Context,
        track: Track,
        selectedLines: List<String>,
    ): Boolean = withContext(Dispatchers.IO) {
        val lines = selectedLines.map { it.trim() }.filter { it.isNotEmpty() }.take(4)
        if (lines.isEmpty()) return@withContext false
        val coverBmp = loadCoverBitmap(context, track.thumb)
        val accent = extractAccentColor(coverBmp)
        val bmp = renderLyricsBitmap(track, lines, coverBmp, accent)
        saveAndShareBitmap(context, bmp, "brook_letra.png", "Compartir letra · ${track.title}")
    }

    suspend fun shareWrappedCard(
        context: Context,
        totalMinutes: Long,
        totalPlays: Int,
        topArtistName: String,
        topArtistPlays: Int,
        topSongs: List<TrackStat>,
    ): Boolean = withContext(Dispatchers.IO) {
        val heroThumb = topSongs.firstOrNull()?.track?.thumb.orEmpty()
        val coverBmp = loadCoverBitmap(context, heroThumb)
        val accent = extractAccentColor(coverBmp)
        val bmp = renderWrappedBitmap(
            totalMinutes = totalMinutes,
            totalPlays = totalPlays,
            topArtistName = topArtistName,
            topArtistPlays = topArtistPlays,
            topSongs = topSongs.take(5),
            heroCover = coverBmp,
            accent = accent,
        )
        saveAndShareBitmap(context, bmp, "brook_wrapped.png", "Mi Brook Wrapped")
    }

    private suspend fun loadCoverBitmap(context: Context, url: String): Bitmap? {
        if (url.isBlank() || !SecurityValidators.isSafeThumbUrl(url)) return null
        return try {
            val req = ImageRequest.Builder(context)
                .data(url)
                .size(360)
                .allowHardware(false)
                .build()
            val res = context.imageLoader.execute(req)
            if (res is SuccessResult) (res.drawable as? BitmapDrawable)?.bitmap else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAccentColor(bmp: Bitmap?): Int {
        if (bmp == null || bmp.width <= 0 || bmp.height <= 0) return Color.rgb(42, 168, 196)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0L
        val stepX = (bmp.width / 12).coerceAtLeast(1)
        val stepY = (bmp.height / 12).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val px = bmp.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = (r + g + b) / 3
                if (lum in 35..225) {
                    rSum += r; gSum += g; bSum += b; count++
                }
                x += stepX
            }
            y += stepY
        }
        if (count == 0L) return Color.rgb(42, 168, 196)
        val hsv = FloatArray(3)
        Color.RGBToHSV((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt(), hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.42f, 0.72f)
        return Color.HSVToColor(hsv)
    }

    private fun renderLyricsBitmap(
        track: Track,
        lines: List<String>,
        cover: Bitmap?,
        accent: Int,
    ): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Fondo degradado profundo basado en la portada
        val darkTop = blendColor(accent, Color.rgb(7, 13, 20), 0.42f)
        val darkMid = blendColor(accent, Color.rgb(10, 18, 28), 0.74f)
        val darkBot = Color.rgb(5, 10, 16)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(darkTop, darkMid, darkBot), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Tarjeta interior estilo cristal Noctalia
        val cardRect = RectF(72f, 110f, w - 72f, h - 110f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(128, 8, 15, 24) }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent))
        }
        canvas.drawRoundRect(cardRect, 54f, 54f, cardPaint)
        canvas.drawRoundRect(cardRect, 54f, 54f, borderPaint)

        // Cabecera: portada + título + artista
        val coverSize = 156f
        val coverLeft = 132f
        val coverTop = 178f
        drawRoundedCover(canvas, cover, RectF(coverLeft, coverTop, coverLeft + coverSize, coverTop + coverSize), 28f, accent)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 180, 232, 246)
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val textLeft = coverLeft + coverSize + 34f
        val maxHdrW = (w - 132f - textLeft).toInt().coerceAtLeast(200)
        drawStaticText(canvas, track.title.take(44), titlePaint, textLeft, coverTop + 26f, maxHdrW, maxLines = 2)
        drawStaticText(canvas, track.artist.take(44), artistPaint, textLeft, coverTop + 106f, maxHdrW, maxLines = 1)

        // Comilla decorativa
        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 120, 235, 255)
            textSize = 112f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        canvas.drawText("“", 130f, 466f, quotePaint)

        // Versos seleccionados
        val lyricSize = when {
            lines.sumOf { it.length } > 180 -> 44f
            lines.size >= 4 -> 50f
            else -> 58f
        }
        val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = lyricSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val lyricWidth = w - 268
        val joined = lines.joinToString("\n\n")
        val lyricLayout = StaticLayout.Builder.obtain(joined, 0, joined.length, lyricPaint, lyricWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(10f, 1.06f)
            .setMaxLines(11)
            .build()
        val lyricY = (480f + ((h - 280f - 480f - lyricLayout.height).coerceAtLeast(0f) * 0.35f))
        canvas.save()
        canvas.translate(134f, lyricY)
        lyricLayout.draw(canvas)
        canvas.restore()

        // Pie de marca Brook
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 118, 228, 252)
            textSize = 30f
            letterSpacing = 0.16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("BROOK MUSIC", 134f, h - 166f, footerPaint)
        return bmp
    }

    private fun renderWrappedBitmap(
        totalMinutes: Long,
        totalPlays: Int,
        topArtistName: String,
        topArtistPlays: Int,
        topSongs: List<TrackStat>,
        heroCover: Bitmap?,
        accent: Int,
    ): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val topCol = blendColor(accent, Color.rgb(12, 22, 36), 0.36f)
        val botCol = Color.rgb(6, 10, 18)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(topCol, Color.rgb(13, 21, 34), botCol), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Etiqueta superior
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(118, 232, 255)
            textSize = 32f
            letterSpacing = 0.18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("BROOK WRAPPED", 86f, 128f, badgePaint)

        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 62f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Tu resumen musical", 86f, 206f, headPaint)

        // Tarjeta de portada + estadísticas principales
        val heroRect = RectF(80f, 256f, w - 80f, 540f)
        val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(135, 10, 18, 30) }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(120, 96, 220, 250)
        }
        canvas.drawRoundRect(heroRect, 42f, 42f, glassPaint)
        canvas.drawRoundRect(heroRect, 42f, 42f, strokePaint)

        drawRoundedCover(canvas, heroCover, RectF(118f, 294f, 326f, 502f), 30f, accent)

        val timeText = if (totalMinutes >= 60L) "${totalMinutes / 60L}h ${totalMinutes % 60L}m" else "${totalMinutes} min"
        val bigNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(122, 236, 255)
            textSize = 58f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 210, 224, 235)
            textSize = 28f
        }
        canvas.drawText(timeText, 364f, 362f, bigNumPaint)
        canvas.drawText("$totalPlays reproducciones registradas", 364f, 408f, subPaint)

        val artLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 130, 210)
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("ARTISTA #1 · $topArtistPlays rep.", 364f, 456f, artLabelPaint)
        val artNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        drawStaticText(canvas, topArtistName.take(28), artNamePaint, 364f, 466f, 590, maxLines = 1)

        // Top 5 Canciones
        val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(122, 236, 255)
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("TU TOP 5 CANCIONES", 86f, 622f, secPaint)

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(122, 236, 255)
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val songTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val songArtistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 198, 215, 228)
            textSize = 26f
        }

        var rowY = 664f
        topSongs.forEachIndexed { idx, st ->
            val rRect = RectF(80f, rowY, w - 80f, rowY + 104f)
            canvas.drawRoundRect(rRect, 26f, 26f, glassPaint)
            canvas.drawText("#${idx + 1}", 112f, rowY + 67f, numPaint)
            drawStaticText(canvas, st.track.title.take(36), songTitlePaint, 198f, rowY + 18f, 650, maxLines = 1)
            drawStaticText(canvas, "${st.track.artist.take(30)} · ${st.playCount} rep.", songArtistPaint, 198f, rowY + 62f, 650, maxLines = 1)
            rowY += 120f
        }

        canvas.drawText("BROOK MUSIC · 100% PRIVADO", 86f, h - 62f, subPaint)
        return bmp
    }

    private fun drawRoundedCover(canvas: Canvas, cover: Bitmap?, rect: RectF, radius: Float, accent: Int) {
        if (cover != null && cover.width > 0 && cover.height > 0) {
            // Recorte centrado (center-crop) mediante Matrix para no deformar portadas 16:9 o 4:3
            val dstW = rect.width()
            val dstH = rect.height()
            val scale = maxOf(dstW / cover.width.toFloat(), dstH / cover.height.toFloat())
            val dx = (dstW - cover.width * scale) * 0.5f
            val dy = (dstH - cover.height * scale) * 0.5f
            val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                })
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                this.shader = shader
            }
            canvas.save()
            canvas.translate(rect.left, rect.top)
            canvas.drawRoundRect(RectF(0f, 0f, dstW, dstH), radius, radius, paint)
            canvas.restore()
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            canvas.drawRoundRect(rect, radius, radius, p)
        }
    }

    private fun drawStaticText(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        x: Float,
        y: Float,
        width: Int,
        maxLines: Int,
    ) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(60))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.rgb(
            (Color.red(c1) * inv + Color.red(c2) * ratio).toInt().coerceIn(0, 255),
            (Color.green(c1) * inv + Color.green(c2) * ratio).toInt().coerceIn(0, 255),
            (Color.blue(c1) * inv + Color.blue(c2) * ratio).toInt().coerceIn(0, 255),
        )
    }

    private fun saveAndShareBitmap(context: Context, bmp: Bitmap, fileName: String, chooserTitle: String): Boolean {
        return try {
            val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
            val outFile = File(shareDir, fileName)
            outFile.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            bmp.recycle()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updater.fileprovider", outFile)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(chooserTitle, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            try { bmp.recycle() } catch (_: Exception) {}
            false
        }
    }
}
