package com.lightphone.spotify.playback.tidal

/**
 * How to rebuild the ExoPlayer list when the user starts a new context while
 * "Next in queue" items exist. Matches Rust [QueueState.set_queue]: keep
 * upcoming manuals, replace now-playing + the context tail.
 *
 * With no manuals, the original context list and start index are unchanged so
 * skip-previous into earlier album tracks stays as it is today.
 */
object TidalQueueSplice {
    data class Plan(
        val playUris: List<String>,
        val startIndex: Int,
        val manualIds: List<String>,
    )

    fun plan(
        contextUris: List<String>,
        startIndex: Int,
        upcomingManualIds: List<String>,
    ): Plan {
        if (contextUris.isEmpty()) {
            return Plan(emptyList(), 0, emptyList())
        }
        val start = startIndex.coerceIn(0, contextUris.lastIndex)
        if (upcomingManualIds.isEmpty()) {
            return Plan(contextUris, start, emptyList())
        }
        val playUris = buildList {
            add(contextUris[start])
            addAll(upcomingManualIds)
            addAll(contextUris.subList(start + 1, contextUris.size))
        }
        return Plan(playUris, startIndex = 0, manualIds = upcomingManualIds)
    }
}
