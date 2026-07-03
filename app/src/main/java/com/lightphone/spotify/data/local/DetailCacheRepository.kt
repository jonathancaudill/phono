package com.lightphone.spotify.data.local

import com.lightphone.spotify.data.SpotifyAlbumDetail
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistTrackItem
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DetailCacheRepository(
    private val database: PhonoDatabase,
    private val json: Json,
) {
    private val albumDao = database.albumDetailDao()
    private val playlistDetailDao = database.playlistDetailDao()
    private val playlistTrackUriDao = database.playlistTrackUriDao()
    private val savedAlbumDao = database.savedAlbumDao()
    private val playlistDao = database.playlistDao()

    suspend fun isSavedAlbumCached(albumId: String): Boolean =
        savedAlbumDao.exists(albumId)

    suspend fun isUserPlaylist(playlistId: String): Boolean =
        playlistDao.observeAll().first().any { it.playlist_id == playlistId }

    suspend fun getPinnedAlbumDetail(albumId: String): Pair<SpotifyAlbumDetail, Boolean>? {
        if (!savedAlbumDao.exists(albumId)) return null
        val row = albumDao.get(albumId) ?: return null
        if (System.currentTimeMillis() - row.fetched_at > PINNED_STALE_MS) return null
        return runCatching {
            json.decodeFromString<SpotifyAlbumDetail>(row.detail_json) to row.is_saved
        }.getOrNull()
    }

    suspend fun putPinnedAlbumDetail(albumId: String, album: SpotifyAlbumDetail, isSaved: Boolean) {
        if (!savedAlbumDao.exists(albumId)) return
        albumDao.upsert(
            AlbumDetailEntity(
                album_id = albumId,
                detail_json = json.encodeToString(album),
                is_saved = isSaved,
                fetched_at = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun getPinnedPlaylistDetail(
        playlistId: String,
    ): Triple<SpotifyPlaylistDetail, List<SpotifyPlaylistTrackItem>, String?>? {
        if (!isUserPlaylist(playlistId)) return null
        val row = playlistDetailDao.get(playlistId) ?: return null
        if (System.currentTimeMillis() - row.fetched_at > PINNED_STALE_MS) return null
        return runCatching {
            val detail = json.decodeFromString<SpotifyPlaylistDetail>(row.detail_json)
            val tracks = json.decodeFromString<List<SpotifyPlaylistTrackItem>>(row.tracks_json)
            Triple(detail, tracks, row.snapshot_id)
        }.getOrNull()
    }

    suspend fun putPinnedPlaylistDetail(
        playlistId: String,
        detail: SpotifyPlaylistDetail,
        tracks: List<SpotifyPlaylistTrackItem>,
        snapshotId: String?,
    ) {
        if (!isUserPlaylist(playlistId)) return
        playlistDetailDao.upsert(
            PlaylistDetailEntity(
                playlist_id = playlistId,
                detail_json = json.encodeToString(detail),
                tracks_json = json.encodeToString(tracks),
                snapshot_id = snapshotId,
                fetched_at = System.currentTimeMillis(),
            ),
        )
        playlistTrackUriDao.deleteForPlaylist(playlistId)
        val uris = tracks.mapNotNull { item ->
            item.track?.uri?.takeIf { it.isNotBlank() }?.let { uri ->
                PlaylistTrackUriEntity(playlist_id = playlistId, track_uri = normalizeUri(uri))
            }
        }
        if (uris.isNotEmpty()) {
            playlistTrackUriDao.insertAll(uris)
        }
    }

    suspend fun playlistContainsTrack(playlistId: String, trackUri: String): Boolean =
        playlistTrackUriDao.contains(playlistId, normalizeUri(trackUri))

    private fun normalizeUri(uri: String): String = uri.substringBefore('?').trim()

    companion object {
        private const val PINNED_STALE_MS = 24 * 60 * 60_000L
    }
}
