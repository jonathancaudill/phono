package com.lightphone.spotify.data.backend

/**
 * Soft feature gates derived from the active [BackendChoice]. UI should branch
 * on these instead of comparing against [BackendChoice.TIDAL] / [BackendChoice.SPOTIFY]
 * for download and quality surfaces.
 */
data class BackendCapabilities(
    /** Offline pin downloads (Downloads tab, album/playlist headers, hold menus). */
    val downloads: Boolean,
    /** TIDAL Low/High/Max ladder + report-plays toggle. */
    val tidalStyleAudioQuality: Boolean,
    /** Spotify 96/160/320 streaming quality ladder. */
    val spotifyStreamingQuality: Boolean,
) {
    companion object {
        fun forChoice(choice: BackendChoice): BackendCapabilities = when (choice) {
            BackendChoice.TIDAL -> BackendCapabilities(
                downloads = true,
                tidalStyleAudioQuality = true,
                spotifyStreamingQuality = false,
            )
            // Enabled once SpotifyDownloadCenter is wired.
            BackendChoice.SPOTIFY -> BackendCapabilities(
                downloads = true,
                tidalStyleAudioQuality = false,
                spotifyStreamingQuality = true,
            )
        }
    }
}

enum class CollectionKind(val path: String) {
    Album("album"),
    Playlist("playlist"),
}

/**
 * Canonical collection URI for the active backend. Prefer [existing] when non-blank.
 */
fun collectionUri(
    choice: BackendChoice,
    kind: CollectionKind,
    id: String,
    existing: String = "",
): String {
    if (existing.isNotBlank()) return existing
    val scheme = when (choice) {
        BackendChoice.SPOTIFY -> "spotify"
        BackendChoice.TIDAL -> "tidal"
    }
    return "$scheme:${kind.path}:$id"
}
