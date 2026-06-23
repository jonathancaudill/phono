package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.data.SearchResults
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoFallbackImage
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.components.tap
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n

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

    MonoContentContainer(
        title = "Results for $query",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
        horizontalPadding = n(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = n(20)),
        ) {
            val flat = state.results?.let { flatten(it) }
            when {
                state.loading && state.results == null -> Unit
                state.error != null && flat.isNullOrEmpty() ->
                    EmptyListMessage(state.error!!)
                flat.isNullOrEmpty() ->
                    EmptyListMessage("No results found for \"$query\".")
                else -> CustomScrollView(verticalArrangement = Arrangement.spacedBy(n(8))) {
                    items(flat, key = { "${it::class.simpleName}-${it.id}" }) { item ->
                        SearchResultRow(item) {
                            when (item) {
                                is SearchResultItem.Track -> onPlayTrack(item)
                                is SearchResultItem.Album -> onOpenAlbum(item.album.id, item.album.name)
                                is SearchResultItem.Artist -> onOpenArtist(item.artist.id)
                                is SearchResultItem.Playlist -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Flat ordering mirrors mono's search results: album first, then everything else. */
private fun flatten(results: SearchResults): List<SearchResultItem> {
    val albums = results.albums.map { SearchResultItem.Album(it) }
    val tracks = results.tracks.map { SearchResultItem.Track(it) }
    val artists = results.artists.map { SearchResultItem.Artist(it) }
    val playlists = results.playlists.map { SearchResultItem.Playlist(it) }
    return albums + tracks + artists + playlists
}

@Composable
private fun SearchResultRow(item: SearchResultItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = n(50))
            .tap(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoFallbackImage(
            imageUrl = item.imageUrl,
            placeholderText = "?",
            placeholderIconSize = n(24),
            modifier = Modifier.size(n(50)),
        )
        Spacer(Modifier.width(n(15)))
        Column(
            Modifier
                .weight(1f)
                .padding(end = n(10)),
        ) {
            StyledText(
                item.title,
                size = 22,
                lineHeight = 24,
                color = MonoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StyledText(
                item.subtitle,
                size = 16,
                lineHeight = 18,
                color = MonoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
