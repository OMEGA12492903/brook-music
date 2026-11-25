package com.noctalia.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.noctalia.music.data.LibraryPayload
import com.noctalia.music.data.Track
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.components.TrackCoverImage
import com.noctalia.music.ui.theme.NoctaliaColors

/**
 * Experiencia visual estilo Spotify Wrapped construida 100% localmente a partir de Brook Stats:
 * 5 tarjetas interactivas + botón para generar y compartir la tarjeta PNG resumen.
 */
@Composable
fun BrookWrappedDialog(
    library: LibraryPayload,
    onDismiss: () -> Unit,
    onShareWrapped: () -> Unit,
    onPlayTopList: (List<Track>) -> Unit,
) {
    val allStats = remember(library.stats) {
        library.stats.values.sortedByDescending { it.listenedSec * 2 + it.playCount * 30L }
    }
    val totalSec = remember(allStats) { allStats.sumOf { it.listenedSec } }
    val totalMin = if (totalSec > 0L) (totalSec / 60L).coerceAtLeast(1L) else 0L
    val totalPlays = remember(allStats) { allStats.sumOf { it.playCount } }
    val topArtists = remember(allStats) {
        allStats
            .groupBy { it.track.artist.substringBefore(",").trim().ifBlank { "Artista" } }
            .map { (name, list) ->
                val sec = list.sumOf { it.listenedSec }
                val plays = list.sumOf { it.playCount }
                val thumb = list.maxByOrNull { it.playCount }?.track?.thumb.orEmpty()
                Triple(name, plays to sec, thumb)
            }
            .sortedByDescending { it.second.second * 2 + it.second.first * 30L }
            .take(5)
    }

    val topArtist = topArtists.firstOrNull()
    val topSong = allStats.firstOrNull()
    val top5Songs = remember(allStats) { allStats.take(5) }
    val totalSlides = 5
    var slideIndex by remember { mutableIntStateOf(0) }

    val slideGradient = when (slideIndex) {
        0 -> listOf(Color(0xFF0A1D2E), Color(0xFF06111B), Color(0xFF150A24))
        1 -> listOf(Color(0xFF200C2B), Color(0xFF0A1424), Color(0xFF061018))
        2 -> listOf(Color(0xFF09252C), Color(0xFF0B1324), Color(0xFF070C14))
        3 -> listOf(Color(0xFF1B102E), Color(0xFF0B1C2C), Color(0xFF060B12))
        else -> listOf(Color(0xFF082230), Color(0xFF160D28), Color(0xFF060B12))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(slideGradient))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Barra de progreso de historias + botón cerrar
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(totalSlides) { i ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(
                                    if (i <= slideIndex) NoctaliaColors.CyanGlow
                                    else Color.White.copy(alpha = 0.22f)
                                )
                                .clickable { slideIndex = i }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "BROOK WRAPPED",
                        color = NoctaliaColors.CyanGlow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.6.sp,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = NoctaliaColors.TextPrimary)
                    }
                }
            }

            // Contenido principal animado por diapositiva
            AnimatedContent(
                targetState = slideIndex,
                transitionSpec = {
                    val forward = targetState >= initialState
                    (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(180)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(160)))
                },
                modifier = Modifier.weight(1f),
                label = "wrapped_slide",
            ) { idx ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (idx) {
                        0 -> {
                            val timeLabel = if (totalMin >= 60L) "${totalMin / 60L}h ${totalMin % 60L}m" else "$totalMin min"
                            val badge = when {
                                totalMin >= 600 -> "Melómano imparable"
                                totalMin >= 180 -> "Ritmo constante"
                                else -> "Explorador musical"
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                Text("Tu tiempo en Brook", color = NoctaliaColors.TextMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = timeLabel,
                                    color = NoctaliaColors.CyanGlow,
                                    fontSize = 54.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = "$totalPlays reproducciones en ${allStats.size} canciones distintas",
                                    color = NoctaliaColors.TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                )
                                NoctaliaGlassCard(cornerRadius = 99.dp, highlighted = true) {
                                    Text(
                                        text = badge,
                                        color = NoctaliaColors.CyanGlow,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }

                        1 -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Text("TU ARTISTA #1", color = NoctaliaColors.Magenta, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                                TrackCoverImage(
                                    url = topArtist?.third.orEmpty(),
                                    size = 190.dp,
                                    cornerRadius = 99.dp,
                                )
                                Text(
                                    text = topArtist?.first ?: "Sin datos aún",
                                    color = NoctaliaColors.TextPrimary,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center,
                                )
                                if (topArtist != null) {
                                    val artMin = (topArtist.second.second / 60L).coerceAtLeast(1L)
                                    val pct = ((topArtist.second.second * 100L) / totalSec.coerceAtLeast(1L)).coerceIn(1L, 100L)
                                    Text(
                                        text = "${topArtist.second.first} reproducciones · ${artMin} min ($pct% de tu tiempo)",
                                        color = NoctaliaColors.TextMuted,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }

                        2 -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Text("TU CANCIÓN EN BUCLE", color = NoctaliaColors.CyanGlow, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                                TrackCoverImage(
                                    url = topSong?.track?.thumb.orEmpty(),
                                    size = 200.dp,
                                    cornerRadius = 26.dp,
                                )
                                Text(
                                    text = topSong?.track?.title ?: "Sin datos aún",
                                    color = NoctaliaColors.TextPrimary,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (topSong != null) {
                                    Text(
                                        text = "${topSong.track.artist} · ${topSong.playCount} veces escuchada",
                                        color = NoctaliaColors.Cyan,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }

                        3 -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "Tu Top 5 canciones",
                                    color = NoctaliaColors.CyanGlow,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                top5Songs.forEachIndexed { pos, st ->
                                    NoctaliaGlassCard(cornerRadius = 16.dp, strong = pos == 0, modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Text(
                                                text = "#${pos + 1}",
                                                color = if (pos == 0) NoctaliaColors.CyanGlow else NoctaliaColors.Cyan,
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.width(30.dp),
                                            )
                                            TrackCoverImage(url = st.track.thumb, size = 46.dp, cornerRadius = 10.dp)
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = st.track.title,
                                                    color = NoctaliaColors.TextPrimary,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = "${st.track.artist} · ${st.playCount} rep.",
                                                    color = NoctaliaColors.TextMuted,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            NoctaliaGlassCard(cornerRadius = 24.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Text(
                                        text = "Tu póster Brook Wrapped",
                                        color = NoctaliaColors.CyanGlow,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                    TrackCoverImage(
                                        url = topSong?.track?.thumb.orEmpty(),
                                        size = 132.dp,
                                        cornerRadius = 22.dp,
                                    )
                                    val timeLabel = if (totalMin >= 60L) "${totalMin / 60L}h ${totalMin % 60L}m" else "$totalMin min"
                                    Text(
                                        text = "$timeLabel · $totalPlays reproducciones\nArtista #1: ${topArtist?.first ?: "—"}",
                                        color = NoctaliaColors.TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 20.sp,
                                    )
                                    Button(
                                        onClick = onShareWrapped,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NoctaliaColors.Cyan,
                                            contentColor = NoctaliaColors.OnCyan,
                                        ),
                                        shape = RoundedCornerShape(99.dp),
                                    ) {
                                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Compartir tarjeta PNG", fontWeight = FontWeight.ExtraBold)
                                    }
                                    if (allStats.isNotEmpty()) {
                                        Button(
                                            onClick = {
                                                onPlayTopList(allStats.take(25).map { it.track })
                                                onDismiss()
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NoctaliaColors.Glass,
                                                contentColor = NoctaliaColors.TextPrimary,
                                            ),
                                            shape = RoundedCornerShape(99.dp),
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Reproducir mi Top", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Controles Anterior / Siguiente historia
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (slideIndex > 0) NoctaliaColors.GlassStrong else Color.Transparent)
                        .clickable(enabled = slideIndex > 0) { slideIndex-- },
                    contentAlignment = Alignment.Center,
                ) {
                    if (slideIndex > 0) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Anterior", tint = NoctaliaColors.TextPrimary)
                    }
                }
                Text(
                    text = "${slideIndex + 1} / $totalSlides",
                    color = NoctaliaColors.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(NoctaliaColors.GlassStrong)
                        .clickable {
                            if (slideIndex < totalSlides - 1) slideIndex++ else onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (slideIndex < totalSlides - 1) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.Close,
                        contentDescription = "Siguiente",
                        tint = NoctaliaColors.CyanGlow,
                    )
                }
            }
        }
    }
}
