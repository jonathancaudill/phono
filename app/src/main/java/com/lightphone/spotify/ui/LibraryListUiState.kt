package com.lightphone.spotify.ui

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
