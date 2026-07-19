package com.lightphone.spotify.data

import com.lightphone.spotify.data.local.PlaylistEntity
import com.lightphone.spotify.data.webapi.LibraryPage

/**
 * Backend-neutral metadata + library surface consumed by
 * [com.lightphone.spotify.playback.PlaybackController] and the UI.
 *
 * Extracted verbatim from the public surface of [SpotifyRepository] (the first
 * implementation). The `Spotify*` DTOs (`SpotifyTrack`, `SpotifyAlbumDetail`,
 * `SpotifyPlaylistDetail`, `SearchResults`, …) serve as the app's shared domain
 * model — both the Spotify and TIDAL repositories map their responses into these
 * shapes, distinguished only by the `spotify:`/`tidal:` URI scheme. This keeps the
 * shared Compose components and screens unchanged across backends.
 */
interface MusicRepository {
    // --- detail reads -------------------------------------------------------
    suspend fun albumDetail(albumId: String): AlbumDetailResult
    fun artistDetail(artistId: String): ArtistDetailResult
    suspend fun playlistDetail(playlistId: String, trackLimit: Int = 500): PlaylistDetailResult
    fun playlistTracks(playlistId: String, limit: Int = 100): List<TrackMetadata>
    fun albumTracks(albumId: String): List<TrackMetadata>
    fun trackMetadataForUri(uri: String): TrackMetadata?

    // --- search / discovery -------------------------------------------------
    fun search(query: String, limitPerType: Int = 8): SearchResults
    suspend fun dailyMixes(): List<SpotifyPlaylistSimple>

    // --- user / identity ----------------------------------------------------
    fun currentUserId(): String
    suspend fun currentUserIdSuspend(): String

    // --- saves / library membership ----------------------------------------
    fun isTrackSaved(uri: String): Boolean
    suspend fun isSavedAlbumCached(albumId: String): Boolean
    suspend fun saveTrack(uri: String)
    suspend fun removeTrack(uri: String)
    suspend fun saveAlbum(albumId: String)
    suspend fun removeAlbum(albumId: String)

    // --- playlist edits -----------------------------------------------------
    suspend fun createPlaylist(name: String, isPublic: Boolean): SpotifyPlaylistSimple
    suspend fun renamePlaylist(playlistId: String, name: String): SpotifyPlaylistDetail
    suspend fun addTrackToPlaylist(
        playlistId: String,
        uri: String,
        snapshotId: String? = null,
        position: Int? = null,
    ): String
    suspend fun removeTrackFromPlaylist(playlistId: String, uri: String, snapshotId: String?): String
    suspend fun reorderPlaylistTrack(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        snapshotId: String?,
    ): String
    suspend fun followPlaylist(playlistId: String)
    suspend fun unfollowPlaylist(playlistId: String)
    suspend fun editablePlaylists(userId: String? = null): List<PlaylistEntity>
    suspend fun playlistsContainingTrack(trackUri: String, playlistIds: List<String>): Set<String>
    suspend fun syncPlaylistUriIndex()

    // --- library paging (drives LibraryRepository sync) ---------------------
    suspend fun playlistLibraryPage(offset: Int, limit: Int): LibraryPage<SpotifyPlaylistSimple>

    // --- cache lifecycle ----------------------------------------------------
    suspend fun clearLibraryCache()
    fun clearSessionCaches()

    /**
     * Spotify concept: cached playback credentials exist but the live librespot
     * session is down (drives dead-session error copy). Backends without a
     * separate playback session (TIDAL) return false.
     */
    fun hasPlaybackCredsWithoutLiveSession(): Boolean
}
