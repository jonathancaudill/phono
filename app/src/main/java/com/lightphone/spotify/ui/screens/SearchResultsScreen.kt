package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.data.SearchResults
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.EchoContentContainer
import com.lightphone.spotify.ui.components.EchoFallbackImage
import com.lightphone.spotify.ui.theme.EchoColors

@Composable
fun SearchResultsScreen(
    vm: AppViewModel,
    query: String,
    onBack: () -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTrack: (SearchResultItem.Track) -> Unit,
) {
    val state by vm.search.collectAsState()

    LaunchedEffect(query) {
        vm.submitSearch(query)
    }

    EchoContentContainer(
        title = "Results for $query",
        hideBackButton = false,
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.loading && state.results == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = EchoColors.Foreground)
                }
            }
            state.error != null && (state.results == null || state.results!!.isEmpty()) -> {
                Text(state.error!!, color = EchoColors.Error)
            }
            state.results == null || state.results!!.isEmpty() -> {
                Text(
                    "No results found for \"$query\".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = EchoColors.Placeholder,
                )
            }
            else -> {
                SearchResultsList(
                    results = state.results!!,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onPlayTrack = onPlayTrack,
                )
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    results: SearchResults,
    onOpenAlbum: (String, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTrack: (SearchResultItem.Track) -> Unit,
) {
    val sections = listOf(
        "Artists" to results.artists.map { SearchResultItem.Artist(it) },
        "Albums" to results.albums.map { SearchResultItem.Album(it) },
        "Tracks" to results.tracks.map { SearchResultItem.Track(it) },
        "Playlists" to results.playlists.map { SearchResultItem.Playlist(it) },
    ).filter { it.second.isNotEmpty() }

    LazyColumn(Modifier.fillMaxSize()) {
        sections.forEach { (section, sectionItems) ->
            item(key = "header-$section") {
                Text(
                    section,
                    style = MaterialTheme.typography.labelLarge,
                    color = EchoColors.Placeholder,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
            }
            items(sectionItems, key = { "${it::class.simpleName}-${it.id}" }) { item ->
                SearchResultRow(
                    item = item,
                    onClick = {
                        when (item) {
                            is SearchResultItem.Track -> onPlayTrack(item)
                            is SearchResultItem.Album -> onOpenAlbum(item.album.id, item.album.name)
                            is SearchResultItem.Artist -> onOpenArtist(item.artist.id)
                            is SearchResultItem.Playlist -> { /* playlists not yet supported */ }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SearchResultRow(item: SearchResultItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EchoFallbackImage(
            imageUrl = item.imageUrl,
            modifier = Modifier.size(50.dp),
        )
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = EchoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.Placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
