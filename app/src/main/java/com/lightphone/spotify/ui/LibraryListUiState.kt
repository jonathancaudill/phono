package com.lightphone.spotify.ui

import com.lightphone.spotify.data.PlaylistFilter
import com.lightphone.spotify.data.local.PlaylistEntity

data class LibraryListUiState<T>(
    val items: List<T> = emptyList(),
    /** Full library size reported by Spotify (for scrollbar virtual length). */
    val remoteTotal: Int = 0,
    /** True while sync state has unfetched pages (next_offset < remote_total). */
    val hasMore: Boolean = false,
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val appending: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean
        get() = items.isEmpty() && !initialLoading && error == null

    val canLoadMore: Boolean
        get() = hasMore && !appending && !initialLoading && !refreshing
}

data class PlaylistsUiState(
    val filter: PlaylistFilter = PlaylistFilter.All,
    val currentUserId: String? = null,
    val items: List<PlaylistEntity> = emptyList(),
    val remoteTotal: Int = 0,
    val hasMore: Boolean = false,
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val appending: Boolean = false,
    val error: String? = null,
) {
    val displayItems: List<PlaylistEntity>
        get() = when (filter) {
            PlaylistFilter.All -> items
            PlaylistFilter.YourPlaylists -> {
                val userId = currentUserId ?: return emptyList()
                items.filter { it.owner_id == userId }
            }
        }

    val displayRemoteTotal: Int
        get() = if (filter == PlaylistFilter.All) remoteTotal else displayItems.size

    val isEmpty: Boolean
        get() = displayItems.isEmpty() && !initialLoading && error == null

    val canLoadMore: Boolean
        get() = hasMore && !appending && !initialLoading && !refreshing
}
