package com.lightphone.spotify.data.native

import com.lightphone.spotify.ffi.ArtistDetailBundle
import com.lightphone.spotify.ffi.RootlistPageNative
import com.lightphone.spotify.ffi.PlaylistDetailBundle
import com.lightphone.spotify.ffi.PlaylistDetailNative

/** Login5 spclient bridge for playlist/artist metadata (Step 1 session). */
interface NativeMetadataGateway {
    fun requireLoggedIn()
    fun isLoggedIn(): Boolean
    fun sessionUsername(): String
    fun playlistDetail(playlistId: String, trackLimit: Int): PlaylistDetailBundle
    fun playlistRootlist(from: Int, length: Int): RootlistPageNative
    fun artistDetail(artistId: String, albumLimit: Int, topTrackLimit: Int): ArtistDetailBundle
    /** Login5 spclient user-profile-view; null when unavailable. */
    fun userDisplayName(username: String): String?
    fun createPlaylist(name: String, isPublic: Boolean): PlaylistDetailNative
    fun updatePlaylistMetadata(
        playlistId: String,
        revisionB64: String,
        name: String?,
        isPublic: Boolean?,
    ): String
    fun addPlaylistTracks(
        playlistId: String,
        revisionB64: String,
        uris: List<String>,
        position: Int?,
    ): String
    fun removePlaylistTracks(playlistId: String, revisionB64: String, uris: List<String>): String
    fun reorderPlaylistTracks(
        playlistId: String,
        revisionB64: String,
        rangeStart: Int,
        insertBefore: Int,
        rangeLength: Int,
    ): String
    fun followPlaylist(playlistUri: String)
    fun unfollowPlaylist(playlistUri: String)
    /** Idempotent add to the user's rootlist (library). */
    fun addToRootlist(playlistUri: String)
}
