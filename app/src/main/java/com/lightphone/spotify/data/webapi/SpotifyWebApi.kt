package com.lightphone.spotify.data.webapi

import com.lightphone.spotify.data.PagedResponse
import com.lightphone.spotify.data.SpotifyAlbumDetail
import com.lightphone.spotify.data.SpotifyAlbumSimple
import com.lightphone.spotify.data.SpotifyArtist
import com.lightphone.spotify.data.SpotifyArtistDetail
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.SpotifySavedTrack
import com.lightphone.spotify.data.SpotifySearchResults
import com.lightphone.spotify.data.SpotifyTrack
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
        private const val LIBRARY_PAGE_LIMIT = 50
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
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

    fun likedTracks(limit: Int = 500): List<SpotifyTrack> {
        val saved = paginateSavedTracks(limit.coerceIn(1, 500))
        return saved.map { it.track }
    }

    fun savedAlbums(limit: Int = 500): List<SpotifySavedAlbum> =
        paginateSavedAlbums(limit.coerceIn(1, 500))

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
        val body = json.encodeToString(LibraryUris.serializer(), LibraryUris(uris))
        put("/me/library", body)
    }

    fun removeLibrary(uris: List<String>) {
        if (uris.isEmpty()) return
        val body = json.encodeToString(LibraryUris.serializer(), LibraryUris(uris))
        delete("/me/library", body)
    }

    fun libraryContains(uris: List<String>): List<Boolean> {
        if (uris.isEmpty()) return emptyList()
        val query = uris.joinToString(",")
        return getRaw("/me/library/contains?uris=${urlEncode(query)}").let { body ->
            json.decodeFromString<List<Boolean>>(body)
        }
    }

    fun myPlaylists(limit: Int = 50): List<SpotifyPlaylistSimple> =
        paginatePlaylists(limit.coerceIn(1, 50))

    fun playlistItems(playlistId: String, limit: Int = 100): List<SpotifyTrack> {
        val items = paginatePlaylistItems(
            playlistId = playlistId,
            limit = limit.coerceIn(1, 500),
        )
        return items.mapNotNull { it.item }
    }

    private fun paginateTracks(path: String, limit: Int): List<SpotifyTrack> {
        val results = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (results.size < limit) {
            val pageLimit = minOf(50, limit - results.size)
            val fullPath = "$path?limit=$pageLimit&offset=$offset"
            val page = get<PagedResponse<SpotifyTrack>>(fullPath)
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (page.next == null || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private fun paginateSavedTracks(limit: Int): List<SpotifySavedTrack> {
        val results = mutableListOf<SpotifySavedTrack>()
        var offset = 0
        while (results.size < limit) {
            val pageLimit = minOf(LIBRARY_PAGE_LIMIT, limit - results.size)
            val page = get<PagedResponse<SpotifySavedTrack>>(
                "/me/tracks?limit=$pageLimit&offset=$offset",
            )
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (page.next == null || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private fun paginateSavedAlbums(limit: Int): List<SpotifySavedAlbum> {
        val results = mutableListOf<SpotifySavedAlbum>()
        var offset = 0
        while (results.size < limit) {
            val pageLimit = minOf(LIBRARY_PAGE_LIMIT, limit - results.size)
            val page = get<PagedResponse<SpotifySavedAlbum>>(
                "/me/albums?limit=$pageLimit&offset=$offset",
            )
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (page.next == null || page.items.size < pageLimit) break
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
        while (results.size < limit) {
            val pageLimit = minOf(50, limit - results.size)
            val query = buildString {
                append("?limit=").append(pageLimit)
                append("&offset=").append(offset)
                extraQuery.forEach { (k, v) ->
                    append("&").append(k).append("=").append(urlEncode(v))
                }
            }
            val page = get<PagedResponse<SpotifyAlbumSimple>>("$path$query")
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (page.next == null || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private fun paginatePlaylists(limit: Int): List<SpotifyPlaylistSimple> {
        val results = mutableListOf<SpotifyPlaylistSimple>()
        var offset = 0
        while (results.size < limit) {
            val pageLimit = minOf(50, limit - results.size)
            val page = get<PagedResponse<SpotifyPlaylistSimple>>(
                "/me/playlists?limit=$pageLimit&offset=$offset",
            )
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (page.next == null || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private fun paginatePlaylistItems(playlistId: String, limit: Int): List<PlaylistTrackItem> {
        val results = mutableListOf<PlaylistTrackItem>()
        var offset = 0
        while (results.size < limit) {
            val pageLimit = minOf(50, limit - results.size)
            val page = get<PagedResponse<PlaylistTrackItem>>(
                "/playlists/$playlistId/items?limit=$pageLimit&offset=$offset",
            )
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (page.next == null || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private inline fun <reified T> get(path: String): T {
        val body = getRaw(path)
        return json.decodeFromString(body)
    }

    private fun getRaw(path: String): String {
        val request = authorizedRequest(path).build()
        return executeWithRetry(request)
    }

    private fun put(path: String, jsonBody: String) {
        val request = authorizedRequest(path)
            .put(jsonBody.toRequestBody(jsonMediaType))
            .build()
        executeWithRetry(request)
    }

    private fun delete(path: String, jsonBody: String) {
        val request = authorizedRequest(path)
            .delete(jsonBody.toRequestBody(jsonMediaType))
            .build()
        executeWithRetry(request)
    }

    private fun authorizedRequest(path: String): Request.Builder {
        val url = if (path.startsWith("http")) path else "$BASE_URL$path"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${auth.currentBearer()}")
            .header("Accept", "application/json")
    }

    private fun executeWithRetry(request: Request): String {
        var lastResponse: Response? = null
        for (attempt in 0 until MAX_429_RETRIES) {
            lastResponse?.close()
            lastResponse = client.newCall(request).execute()
            val response = lastResponse!!
            val body = response.body?.string() ?: ""
            when {
                response.code == 429 -> {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 2L
                    Thread.sleep(retryAfter.coerceIn(1, 30) * 1000)
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

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    @Serializable
    private data class LibraryUris(val uris: List<String>)

    @Serializable
    private data class PlaylistTrackItem(
        val item: SpotifyTrack? = null,
    )
}
