package com.lightphone.spotify.data.native

import com.lightphone.spotify.data.ArtistDetailResult
import com.lightphone.spotify.data.PlaylistDetailResult
import com.lightphone.spotify.data.SpotifyAlbumSimple
import com.lightphone.spotify.data.SpotifyArtist
import com.lightphone.spotify.data.SpotifyArtistDetail
import com.lightphone.spotify.data.SpotifyImage
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistOwner
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifyPlaylistTrackItem
import com.lightphone.spotify.data.SpotifyPlaylistTracksRef
import com.lightphone.spotify.data.SpotifyTrack
import com.lightphone.spotify.data.toSpotifyTrack
import com.lightphone.spotify.ffi.AlbumSummaryNative
import com.lightphone.spotify.ffi.ArtistDetailBundle
import com.lightphone.spotify.ffi.EntityInfo
import com.lightphone.spotify.ffi.PlaylistDetailBundle
import com.lightphone.spotify.ffi.PlaylistDetailNative
import com.lightphone.spotify.ffi.SpotifyException
import com.lightphone.spotify.data.toPlaylistSimple
import java.time.Instant

object NativeMetadataAdapter {

    fun toPlaylistDetailResult(
        bundle: PlaylistDetailBundle,
        currentUsername: String,
        isInLibrary: Boolean,
    ): PlaylistDetailResult {
        val detail = toPlaylistDetail(bundle.detail)
        val tracks = bundle.tracks.map { row ->
            SpotifyPlaylistTrackItem(
                addedAt = if (row.addedAtMs > 0) {
                    Instant.ofEpochMilli(row.addedAtMs).toString()
                } else {
                    null
                },
                track = row.track.toSpotifyTrack(),
            )
        }
        val isEditable = detail.collaborative ||
            detail.owner?.id == currentUsername ||
            detail.owner?.displayName == currentUsername
        return PlaylistDetailResult(
            detail = detail,
            tracks = tracks,
            currentUserId = currentUsername,
            isEditable = isEditable,
            isInLibrary = isInLibrary,
        )
    }

    fun toPlaylistDetail(native: PlaylistDetailNative): SpotifyPlaylistDetail {
        val images = native.imageUrl?.let { url ->
            listOf(SpotifyImage(url = url))
        }
        return SpotifyPlaylistDetail(
            id = native.id,
            name = native.name,
            uri = native.uri.ifBlank { "spotify:playlist:${native.id}" },
            images = images,
            owner = SpotifyPlaylistOwner(
                id = native.ownerId,
                displayName = native.ownerName.ifBlank { native.ownerId },
            ),
            snapshotId = native.revisionB64,
            tracks = SpotifyPlaylistTracksRef(total = native.trackCount.toInt()),
            public = native.isPublic,
            collaborative = native.collaborative,
            description = native.description.takeIf { it.isNotBlank() },
        )
    }

    fun toPlaylistSimple(native: PlaylistDetailNative): SpotifyPlaylistSimple =
        toPlaylistDetail(native).toPlaylistSimple()

    fun toPlaylistSimple(entity: EntityInfo): SpotifyPlaylistSimple {
        val images = entity.artUrl?.let { listOf(SpotifyImage(url = it)) }
        return SpotifyPlaylistSimple(
            id = entity.id,
            name = entity.name,
            uri = entity.uri.ifBlank { "spotify:playlist:${entity.id}" },
            images = images,
            owner = SpotifyPlaylistOwner(
                id = entity.subtitle,
                displayName = entity.subtitle,
            ),
            tracks = SpotifyPlaylistTracksRef(total = entity.trackCount.toInt()),
        )
    }

    fun toArtistDetailResult(bundle: ArtistDetailBundle): ArtistDetailResult {
        val images = bundle.imageUrl?.let { listOf(SpotifyImage(url = it)) }.orEmpty()
        return ArtistDetailResult(
            artist = SpotifyArtistDetail(
                id = bundle.id,
                name = bundle.name,
                uri = "spotify:artist:${bundle.id}",
                images = images,
                genres = bundle.genres,
            ),
            topTracks = bundle.topTracks.map { it.toSpotifyTrack() },
            albums = bundle.albums.map { it.toAlbumSimple(bundle.name) },
        )
    }

    private fun AlbumSummaryNative.toAlbumSimple(artistName: String): SpotifyAlbumSimple =
        SpotifyAlbumSimple(
            id = id,
            name = name,
            uri = uri.ifBlank { "spotify:album:$id" },
            images = imageUrl?.let { listOf(SpotifyImage(url = it)) }.orEmpty(),
            artists = listOf(SpotifyArtist(name = artistName)),
            albumType = albumType,
        )
}

fun mapNativeError(
    e: Throwable,
    hasPlaybackCredsWithoutLiveSession: Boolean = false,
): String = when {
    hasPlaybackCredsWithoutLiveSession &&
        (e is NativeSessionRequiredException ||
            e is SpotifyException.NotLoggedIn ||
            e.message?.contains("not logged in", ignoreCase = true) == true) ->
        "Can't reach Spotify playback right now. Pull to refresh."
    e is NativeSessionRequiredException -> e.message ?: "Playback sign-in required."
    e is SpotifyException.NotLoggedIn ->
        "Sign in to Spotify playback to load this."
    e is SpotifyException ->
        e.message ?: "Spotify error — try again."
    else -> {
        val msg = e.message.orEmpty()
        when {
            msg.contains("not logged in", ignoreCase = true) ->
                "Sign in to Spotify playback to load this."
            msg.contains("revision", ignoreCase = true) ->
                "Playlist changed elsewhere — try again."
            else -> msg.takeIf { it.isNotBlank() }
                ?: "${e::class.simpleName ?: "Error"} — try again."
        }
    }
}
