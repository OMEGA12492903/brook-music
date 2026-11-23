package com.noctalia.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noctalia.music.data.LibraryPayload
import com.noctalia.music.data.Track
import com.noctalia.music.player.PlayerUiState
import com.noctalia.music.ui.MainUiState
import com.noctalia.music.ui.components.NoctaliaGlassCard
import com.noctalia.music.ui.components.NoctaliaPillChip
import com.noctalia.music.ui.components.TrackListShimmer
import com.noctalia.music.ui.components.TrackRowItem
import com.noctalia.music.ui.theme.NoctaliaColors

private val SEARCH_CHIPS = listOf(
    "Urbano" to "reggaeton urbano",
    "Phonk" to "phonk drift",
    "Lo-Fi" to "lofi beats",
    "Rock" to "rock hits",
    "Electrónica" to "electronic hits",
    "Anime OST" to "anime opening ost",
)

@Composable
fun SearchScreen(
    ui: MainUiState,
    player: PlayerUiState,
    library: LibraryPayload,
    onQueryChange: (String, Boolean) -> Unit,
    onModeChange: (String) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onAddQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onMoreTrackClick: (Track) -> Unit,
    onClearSearchHistory: () -> Unit,
) {
    val favIds = remember(library.favorites) { library.favorites.map { it.id }.toSet() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Buscar en YouTube Music",
            color = NoctaliaColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
        )

        OutlinedTextField(
            value = ui.searchQuery,
            onValueChange = { onQueryChange(it, false) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            placeholder = {
                Text(
                    text = if (ui.searchMode == "lyrics") "Escribe una frase que recuerdes de la letra..." else "Canción, artista o álbum...",
                    color = NoctaliaColors.TextDim,
                    fontSize = 14.sp,
                )
            },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = NoctaliaColors.CyanGlow)
            },
            trailingIcon = {
                if (ui.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("", true) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Limpiar", tint = NoctaliaColors.TextMuted)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onQueryChange(ui.searchQuery, true) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0x66000000),
                unfocusedContainerColor = Color(0x55000000),
                focusedBorderColor = NoctaliaColors.CyanGlow,
                unfocusedBorderColor = NoctaliaColors.BorderCyan,
                focusedTextColor = NoctaliaColors.TextPrimary,
                unfocusedTextColor = NoctaliaColors.TextPrimary,
                cursorColor = NoctaliaColors.CyanGlow,
            ),
        )

        // Sugerencias de autocompletado en vivo
        AnimatedVisibility(visible = ui.searchQuery.isNotBlank() && ui.searchSuggestions.isNotEmpty()) {
            NoctaliaGlassCard(cornerRadius = 16.dp, strong = true, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ui.searchSuggestions.take(5).forEachIndexed { idx, sug ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onQueryChange(sug, true) }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = NoctaliaColors.CyanGlow,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = sug,
                                color = NoctaliaColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (idx < ui.searchSuggestions.take(5).lastIndex) {
                            HorizontalDivider(color = NoctaliaColors.BorderCyan.copy(alpha = 0.25f))
                        }
                    }
                }
            }
        }

        // Búsquedas recientes cuando la barra está vacía
        AnimatedVisibility(visible = ui.searchQuery.isBlank() && library.searchHistory.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Rounded.History, contentDescription = null, tint = NoctaliaColors.Cyan, modifier = Modifier.size(15.dp))
                        Text("Búsquedas recientes", color = NoctaliaColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "Limpiar",
                        color = NoctaliaColors.Danger,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onClearSearchHistory),
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(library.searchHistory) { prevQ ->
                        NoctaliaPillChip(
                            label = prevQ,
                            active = false,
                            onClick = { onQueryChange(prevQ, true) },
                        )
                    }
                }
            }
        }

        // Filtros de búsqueda: Canciones / Artistas / Por letra / Todo
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(
                items = listOf(
                    "songs" to "Canciones",
                    "artist" to "Artista",
                    "lyrics" to "Por letra",
                    "all" to "Todo / Remixes",
                ),
                key = { it.first },
                contentType = { "search_mode" },
            ) { (mode, label) ->
                NoctaliaPillChip(
                    label = label,
                    active = ui.searchMode == mode,
                    onClick = { onModeChange(mode) },
                )
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SEARCH_CHIPS) { (label, query) ->
                NoctaliaPillChip(
                    label = label,
                    active = ui.searchQuery.equals(query, ignoreCase = true),
                    onClick = { onQueryChange(query, true) },
                )
            }
        }

        if (ui.searching) {
            TrackListShimmer(count = 6, modifier = Modifier.padding(top = 6.dp))
        } else {
            val showingRecent = ui.searchQuery.isBlank()
            val list = if (showingRecent) library.recent else ui.searchResults

            Text(
                text = when {
                    showingRecent && list.isNotEmpty() -> "Escuchadas recientemente"
                    showingRecent -> "Escribe cualquier canción o elige un género"
                    list.isNotEmpty() -> "${list.size} resultados · Audio Opus sin anuncios"
                    else -> "Sin resultados para «${ui.searchQuery}»"
                },
                color = NoctaliaColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 164.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { "sr_${it.id}" }) { track ->
                    TrackRowItem(
                        track = track,
                        isCurrent = player.currentTrack?.id == track.id,
                        isFavorite = track.id in favIds,
                        onPlay = { onPlayTrack(track) },
                        onAddQueue = { onAddQueue(track) },
                        onToggleFavorite = { onToggleFavorite(track) },
                        onMoreClick = { onMoreTrackClick(track) },
                    )
                }
            }
        }
    }
}
