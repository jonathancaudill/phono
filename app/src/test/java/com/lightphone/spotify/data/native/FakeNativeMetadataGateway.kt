package com.lightphone.spotify.data.native

import com.lightphone.spotify.ffi.ArtistDetailBundle
import com.lightphone.spotify.ffi.PlaylistDetailBundle
import com.lightphone.spotify.ffi.PlaylistDetailNative
import com.lightphone.spotify.ffi.RootlistPageNative

class FakeNativeMetadataGateway(
    private val loggedIn: Boolean = false,
    private val username: String = "native-user",
    private val usernameError: Throwable? = null,
    private val rootlistError: Throwable? = null,
    private val rootlistPage: RootlistPageNative = RootlistPageNative(
        playlists = emptyList(),
        total = 0u,
    ),
) : NativeMetadataGateway {
    var rootlistCallCount = 0
        private set

    override fun requireLoggedIn() {
        if (!loggedIn) throw NativeSessionRequiredException()
    }

    override fun isLoggedIn(): Boolean = loggedIn

    override fun sessionUsername(): String {
        usernameError?.let { throw it }
        return username
    }

    override fun playlistDetail(playlistId: String, trackLimit: Int): PlaylistDetailBundle {
        throw UnsupportedOperationException()
    }

    override fun playlistRootlist(from: Int, length: Int): RootlistPageNative {
        rootlistCallCount++
        rootlistError?.let { throw it }
        return rootlistPage
    }

    override fun artistDetail(
        artistId: String,
        albumLimit: Int,
        topTrackLimit: Int,
    ): ArtistDetailBundle {
        throw UnsupportedOperationException()
    }

    override fun userDisplayName(username: String): String? = null

    override fun createPlaylist(name: String, isPublic: Boolean): PlaylistDetailNative {
        throw UnsupportedOperationException()
    }

    override fun updatePlaylistMetadata(
        playlistId: String,
        revisionB64: String,
        name: String?,
        isPublic: Boolean?,
    ): String {
        throw UnsupportedOperationException()
    }

    override fun addPlaylistTracks(
        playlistId: String,
        revisionB64: String,
        uris: List<String>,
        position: Int?,
    ): String {
        throw UnsupportedOperationException()
    }

    override fun removePlaylistTracks(
        playlistId: String,
        revisionB64: String,
        uris: List<String>,
    ): String {
        throw UnsupportedOperationException()
    }

    override fun reorderPlaylistTracks(
        playlistId: String,
        revisionB64: String,
        rangeStart: Int,
        insertBefore: Int,
        rangeLength: Int,
    ): String {
        throw UnsupportedOperationException()
    }

    override fun followPlaylist(playlistUri: String) {
        throw UnsupportedOperationException()
    }

    override fun unfollowPlaylist(playlistUri: String) {
        throw UnsupportedOperationException()
    }

    override fun addToRootlist(playlistUri: String) {
        throw UnsupportedOperationException()
    }
}
