package com.lightphone.spotify.data.webapi

import com.lightphone.spotify.data.PagedResponse
import com.lightphone.spotify.data.SpotifyAlbumDetail
import com.lightphone.spotify.data.SpotifyAlbumSimple
import com.lightphone.spotify.data.SpotifyArtistDetail
import com.lightphone.spotify.data.AddPlaylistItemsBody
import com.lightphone.spotify.data.ChangePlaylistDetailsBody
import com.lightphone.spotify.data.CreatePlaylistBody
import com.lightphone.spotify.data.RemovePlaylistItemsBody
import com.lightphone.spotify.data.RemovePlaylistTrackRef
import com.lightphone.spotify.data.ReorderPlaylistItemsBody
import com.lightphone.spotify.data.SnapshotResponse
import com.lightphone.spotify.data.SpotifyCurrentUser
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifyPlaylistTrackItem
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.SpotifySavedTrack
import com.lightphone.spotify.data.SpotifySearchResults
import com.lightphone.spotify.data.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Spotify Web API client for api.spotify.com. Uses the user's dev-app OAuth
 * tokens from [WebApiAuth]. Honors Retry-After on 429.
 */
class SpotifyWebApi(private val auth: WebApiAuth) {

    companion object {
        private const val BASE_URL = "https://api.spotify.com/v1"
        private const val MAX_429_RETRIES = 4
        private const val DEFAULT_SEARCH_LIMIT = 8
        const val LIBRARY_PAGE_LIMIT = 50
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // Spotify may send explicit null for string fields on unavailable/local albums (e.g. playlist items).
        coerceInputValues = true
    }

    private val jsonMediaType = "application/json".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (responseCount(response) >= 2) return null
                val refreshed = auth.refreshTokens()
                if (refreshed.isFailure) return null
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${auth.currentBearer()}")
                    .build()
            }
        })
        .build()

    suspend fun savedTracksPage(
        offset: Int,
        limit: Int = LIBRARY_PAGE_LIMIT,
    ): LibraryPage<SpotifySavedTrack> {
        val pageLimit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val path = "/me/tracks?limit=$pageLimit&offset=$safeOffset&market=from_token"
        val page = getSuspend<PagedResponse<SpotifySavedTrack?>>(path)
        return LibraryPage(
            items = page.items.filterNotNull().filter { it.track != null },
            total = page.total,
            offset = safeOffset,
        )
    }

    suspend fun savedAlbumsPage(
        offset: Int,
        limit: Int = LIBRARY_PAGE_LIMIT,
    ): LibraryPage<SpotifySavedAlbum> {
        val pageLimit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val path = "/me/albums?limit=$pageLimit&offset=$safeOffset&market=from_token"
        val page = getSuspend<PagedResponse<SpotifySavedAlbum?>>(path)
        return LibraryPage(
            items = page.items.filterNotNull().filter { it.album != null },
            total = page.total,
            offset = safeOffset,
        )
    }

    fun album(albumId: String): SpotifyAlbumDetail {
        val detail = get<SpotifyAlbumDetail>("/albums/$albumId")
        if (detail.tracks.items.isNotEmpty() && detail.tracks.total <= detail.tracks.items.size) {
            return detail
        }
        val allTracks = paginateTracks("/albums/$albumId/tracks", limit = 500)
        return detail.copy(tracks = PagedResponse(items = allTracks, total = allTracks.size))
    }

    fun artist(artistId: String): SpotifyArtistDetail =
        get("/artists/$artistId")

    fun artistAlbums(artistId: String, limit: Int = 50): List<SpotifyAlbumSimple> =
        paginateAlbums(
            path = "/artists/$artistId/albums",
            limit = limit.coerceIn(1, 50),
            extraQuery = mapOf("include_groups" to "album,single"),
        )

    fun track(trackId: String): SpotifyTrack = get("/tracks/$trackId")

    fun search(query: String, limitPerType: Int = DEFAULT_SEARCH_LIMIT): SpotifySearchResults {
        val limit = limitPerType.coerceIn(1, 10)
        val path = buildString {
            append("/search?q=").append(urlEncode(query))
            append("&type=artist,album,track,playlist")
            append("&limit=").append(limit)
            append("&market=from_token")
        }
        return get(path)
    }

    fun saveLibrary(uris: List<String>) {
        if (uris.isEmpty()) return
        put(libraryUrisPath(uris))
    }

    fun removeLibrary(uris: List<String>) {
        if (uris.isEmpty()) return
        delete(libraryUrisPath(uris))
    }

    fun libraryContains(uris: List<String>): List<Boolean> {
        if (uris.isEmpty()) return emptyList()
        return getRaw(libraryUrisPath(uris, contains = true)).let { body ->
            json.decodeFromString<List<Boolean>>(body)
        }
    }

    fun currentUser(): SpotifyCurrentUser = get("/me")

    suspend fun savedPlaylistsPage(
        offset: Int,
        limit: Int = LIBRARY_PAGE_LIMIT,
    ): LibraryPage<SpotifyPlaylistSimple> {
        val pageLimit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val page = getSuspend<PagedResponse<SpotifyPlaylistSimple?>>(
            "/me/playlists?limit=$pageLimit&offset=$safeOffset",
        )
        return LibraryPage(
            items = page.items.filterNotNull().filter { it.id.isNotBlank() },
            total = page.total,
            offset = safeOffset,
        )
    }

    fun myPlaylists(limit: Int = 50): List<SpotifyPlaylistSimple> =
        paginatePlaylists(limit.coerceIn(1, 50))

    fun playlist(playlistId: String): SpotifyPlaylistDetail =
        get("/playlists/$playlistId")

    suspend fun playlistItemsPage(
        playlistId: String,
        offset: Int,
        limit: Int = LIBRARY_PAGE_LIMIT,
    ): LibraryPage<SpotifyPlaylistTrackItem> {
        val pageLimit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val page = getSuspend<PagedResponse<SpotifyPlaylistTrackItem>>(
            "/playlists/$playlistId/items?limit=$pageLimit&offset=$safeOffset&market=from_token",
        )
        return LibraryPage(
            items = page.items.filter { it.track != null },
            total = page.total,
            offset = safeOffset,
        )
    }

    fun playlistItems(playlistId: String, limit: Int = 100): List<SpotifyTrack> {
        val items = paginatePlaylistItems(
            playlistId = playlistId,
            limit = limit.coerceIn(1, 500),
        )
        return items.mapNotNull { it.track }
    }

    fun createPlaylist(userId: String, name: String, isPublic: Boolean, description: String? = null): SpotifyPlaylistSimple {
        val body = json.encodeToString(
            CreatePlaylistBody.serializer(),
            CreatePlaylistBody(name = name, public = isPublic, description = description),
        )
        return post("/users/$userId/playlists", body)
    }

    fun changePlaylistDetails(
        playlistId: String,
        name: String? = null,
        isPublic: Boolean? = null,
        description: String? = null,
    ): SpotifyPlaylistSimple {
        val body = json.encodeToString(
            ChangePlaylistDetailsBody.serializer(),
            ChangePlaylistDetailsBody(name = name, public = isPublic, description = description),
        )
        return putReturning("/playlists/$playlistId", body)
    }

    fun addPlaylistItems(playlistId: String, uris: List<String>, position: Int? = null): String? {
        if (uris.isEmpty()) return null
        val body = json.encodeToString(
            AddPlaylistItemsBody.serializer(),
            AddPlaylistItemsBody(uris = uris, position = position),
        )
        val response = postRaw("/playlists/$playlistId/items", body)
        return if (response.isBlank()) null else json.decodeFromString<SnapshotResponse>(response).snapshotId
    }

    fun removePlaylistItems(
        playlistId: String,
        uris: List<String>,
        snapshotId: String? = null,
    ): String? {
        if (uris.isEmpty()) return null
        val body = json.encodeToString(
            RemovePlaylistItemsBody.serializer(),
            RemovePlaylistItemsBody(
                tracks = uris.map { RemovePlaylistTrackRef(uri = it) },
                snapshotId = snapshotId,
            ),
        )
        val response = deleteReturning("/playlists/$playlistId/items", body)
        return if (response.isBlank()) null else json.decodeFromString<SnapshotResponse>(response).snapshotId
    }

    fun reorderPlaylistItems(
        playlistId: String,
        rangeStart: Int,
        insertBefore: Int,
        rangeLength: Int = 1,
        snapshotId: String? = null,
    ): String? {
        val body = json.encodeToString(
            ReorderPlaylistItemsBody.serializer(),
            ReorderPlaylistItemsBody(
                rangeStart = rangeStart,
                insertBefore = insertBefore,
                rangeLength = rangeLength,
                snapshotId = snapshotId,
            ),
        )
        val response = putRaw("/playlists/$playlistId/items/reorder", body)
        return if (response.isBlank()) null else json.decodeFromString<SnapshotResponse>(response).snapshotId
    }

    fun unfollowPlaylist(playlistId: String) {
        removeLibrary(listOf("spotify:playlist:$playlistId"))
    }

    fun followPlaylist(playlistId: String) {
        saveLibrary(listOf("spotify:playlist:$playlistId"))
    }

    private fun paginateTracks(path: String, limit: Int): List<SpotifyTrack> {
        val results = mutableListOf<SpotifyTrack>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (results.size < limit && offset < total) {
            val pageLimit = minOf(LIBRARY_PAGE_LIMIT, limit - results.size)
            val fullPath = "$path?limit=$pageLimit&offset=$offset"
            val page = get<PagedResponse<SpotifyTrack>>(fullPath)
            total = page.total
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (offset >= total || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private fun paginateAlbums(
        path: String,
        limit: Int,
        extraQuery: Map<String, String> = emptyMap(),
    ): List<SpotifyAlbumSimple> {
        val results = mutableListOf<SpotifyAlbumSimple>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (results.size < limit && offset < total) {
            val pageLimit = minOf(LIBRARY_PAGE_LIMIT, limit - results.size)
            val query = buildString {
                append("?limit=").append(pageLimit)
                append("&offset=").append(offset)
                extraQuery.forEach { (k, v) ->
                    append("&").append(k).append("=").append(urlEncode(v))
                }
            }
            val page = get<PagedResponse<SpotifyAlbumSimple>>("$path$query")
            total = page.total
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (offset >= total || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private fun paginatePlaylists(limit: Int): List<SpotifyPlaylistSimple> {
        val results = mutableListOf<SpotifyPlaylistSimple>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (results.size < limit && offset < total) {
            val pageLimit = minOf(LIBRARY_PAGE_LIMIT, limit - results.size)
            val page = get<PagedResponse<SpotifyPlaylistSimple>>(
                "/me/playlists?limit=$pageLimit&offset=$offset",
            )
            total = page.total
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (offset >= total || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private fun paginatePlaylistItems(playlistId: String, limit: Int): List<SpotifyPlaylistTrackItem> {
        val results = mutableListOf<SpotifyPlaylistTrackItem>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (results.size < limit && offset < total) {
            val pageLimit = minOf(LIBRARY_PAGE_LIMIT, limit - results.size)
            val page = get<PagedResponse<SpotifyPlaylistTrackItem>>(
                "/playlists/$playlistId/items?limit=$pageLimit&offset=$offset&market=from_token",
            )
            total = page.total
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (offset >= total || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private inline fun <reified T> get(path: String): T {
        val body = getRaw(path)
        return json.decodeFromString(body)
    }

    private suspend inline fun <reified T> getSuspend(path: String): T {
        val body = getRawSuspend(path)
        return json.decodeFromString(body)
    }

    private fun getRaw(path: String): String {
        val request = authorizedRequest(path).build()
        return kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private suspend fun getRawSuspend(path: String): String = withContext(Dispatchers.IO) {
        val request = authorizedRequest(path).build()
        executeWithRetry(request)
    }

    private fun put(path: String) {
        val request = authorizedRequest(path)
            .put(ByteArray(0).toRequestBody(null))
            .build()
        kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun put(path: String, jsonBody: String) {
        val request = authorizedRequest(path)
            .put(jsonBody.toRequestBody(jsonMediaType))
            .build()
        kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun delete(path: String) {
        val request = authorizedRequest(path).delete().build()
        kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun delete(path: String, jsonBody: String) {
        val request = authorizedRequest(path)
            .delete(jsonBody.toRequestBody(jsonMediaType))
            .build()
        kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private inline fun <reified T> post(path: String, jsonBody: String): T {
        val body = postRaw(path, jsonBody)
        return if (body.isBlank()) {
            json.decodeFromString("{}")
        } else {
            json.decodeFromString(body)
        }
    }

    private inline fun <reified T> putReturning(path: String, jsonBody: String): T {
        val body = putRaw(path, jsonBody)
        return if (body.isBlank()) {
            json.decodeFromString("{}")
        } else {
            json.decodeFromString(body)
        }
    }

    private fun postRaw(path: String, jsonBody: String): String {
        val request = authorizedRequest(path)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()
        return kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun putRaw(path: String, jsonBody: String): String {
        val request = authorizedRequest(path)
            .put(jsonBody.toRequestBody(jsonMediaType))
            .build()
        return kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun deleteReturning(path: String, jsonBody: String): String {
        val request = authorizedRequest(path)
            .delete(jsonBody.toRequestBody(jsonMediaType))
            .build()
        return kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun authorizedRequest(path: String): Request.Builder {
        val url = if (path.startsWith("http")) path else "$BASE_URL$path"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${auth.currentBearer()}")
            .header("Accept", "application/json")
    }

    private suspend fun executeWithRetry(request: Request): String {
        var lastResponse: Response? = null
        for (attempt in 0 until MAX_429_RETRIES) {
            lastResponse?.close()
            lastResponse = client.newCall(request).execute()
            val response = lastResponse!!
            val body = response.body?.string() ?: ""
            when {
                response.code == 429 -> {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 2L
                    delay(retryAfter.coerceIn(1, 30) * 1000)
                    continue
                }
                response.isSuccessful -> {
                    response.close()
                    return body
                }
                response.code == 401 -> throw WebApiAuthException(
                    "Web API unauthorized — re-authorize Step 2",
                )
                else -> {
                    response.close()
                    throw IOException("HTTP ${response.code}: $body")
                }
            }
        }
        lastResponse?.close()
        throw IOException("HTTP 429: rate limited after $MAX_429_RETRIES retries")
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private fun libraryUrisPath(uris: List<String>, contains: Boolean = false): String {
        val encoded = uris.joinToString(",") { urlEncode(it) }
        val base = if (contains) "/me/library/contains" else "/me/library"
        return "$base?uris=$encoded"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
