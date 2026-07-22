package com.lightphone.spotify.data.tidal

import com.lightphone.spotify.data.SpotifyAlbumDetail
import com.lightphone.spotify.data.SpotifyAlbumSimple
import com.lightphone.spotify.data.SpotifyArtistDetail
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.SpotifySavedTrack
import com.lightphone.spotify.data.SpotifySearchResults
import com.lightphone.spotify.data.SpotifyTrack
import com.lightphone.spotify.data.SearchPagedResponse
import com.lightphone.spotify.data.webapi.LibraryPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TIDAL private-API (`api.tidal.com/v1`) client. Mirrors [com.lightphone.spotify.data.webapi.SpotifyWebApi]:
 * bearer auth from [TidalAuth], Retry-After/429 handling, and a 401 [Authenticator]
 * hook that refreshes the token once. Every request carries the `countryCode` query
 * param TIDAL requires; playlist mutations use the ETag concurrency token (the analog
 * of Spotify's `snapshot_id`).
 */
class TidalApiClient(
    private val auth: TidalAuth,
    private val baseUrl: String = BASE_URL,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (responseCount(response) >= 2) return null
                val bearer = try {
                    auth.refreshAfterUnauthorized() ?: return null
                } catch (_: TidalAuthException) {
                    return null
                }
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $bearer")
                    .build()
            }
        })
        .build()

    // --- library pages (drive LibraryRepository sync) ----------------------

    suspend fun savedTracksPage(offset: Int, limit: Int = PAGE_LIMIT): LibraryPage<SpotifySavedTrack> {
        val userId = requireUserId()
        val page = getSuspend<TidalList<TidalFavoriteTrack>>(
            "/users/$userId/favorites/tracks",
            "offset" to offset.coerceAtLeast(0).toString(),
            "limit" to limit.coerceIn(1, PAGE_LIMIT).toString(),
            "order" to "DATE",
            "orderDirection" to "DESC",
        )
        return LibraryPage(
            items = page.items.filter { it.item != null }.map { it.toSavedTrack() },
            total = page.totalNumberOfItems,
            offset = offset.coerceAtLeast(0),
        )
    }

    suspend fun savedAlbumsPage(offset: Int, limit: Int = PAGE_LIMIT): LibraryPage<SpotifySavedAlbum> {
        val userId = requireUserId()
        val page = getSuspend<TidalList<TidalFavoriteAlbum>>(
            "/users/$userId/favorites/albums",
            "offset" to offset.coerceAtLeast(0).toString(),
            "limit" to limit.coerceIn(1, PAGE_LIMIT).toString(),
            "order" to "DATE",
            "orderDirection" to "DESC",
        )
        return LibraryPage(
            items = page.items.filter { it.item != null }.map { it.toSavedAlbum() },
            total = page.totalNumberOfItems,
            offset = offset.coerceAtLeast(0),
        )
    }

    /** User-created + favorited playlists (mirrors Spotify's /me/playlists). */
    suspend fun playlistsPage(offset: Int, limit: Int = PAGE_LIMIT): LibraryPage<SpotifyPlaylistSimple> {
        val userId = requireUserId()
        val page = getSuspend<TidalList<TidalPlaylistRow>>(
            "/users/$userId/playlistsAndFavoritePlaylists",
            "offset" to offset.coerceAtLeast(0).toString(),
            "limit" to limit.coerceIn(1, PAGE_LIMIT).toString(),
            "order" to "DATE",
            "orderDirection" to "DESC",
        )
        return LibraryPage(
            items = page.items.mapNotNull { it.playlist?.toDomainSimple() }.filter { it.id.isNotBlank() },
            total = page.totalNumberOfItems,
            offset = offset.coerceAtLeast(0),
        )
    }

    // --- detail reads -------------------------------------------------------

    fun album(albumId: String): SpotifyAlbumDetail {
        val album = get<TidalAlbum>("/albums/$albumId")
        val tracks = paginate<TidalTrack>("/albums/$albumId/tracks", limit = 500)
        return album.toDomainDetail(tracks)
    }

    fun albumTracks(albumId: String): List<SpotifyTrack> =
        paginate<TidalTrack>("/albums/$albumId/tracks", limit = 500).map { it.toDomain() }

    fun artist(artistId: String): SpotifyArtistDetail =
        get<TidalArtist>("/artists/$artistId").toDomainDetail()

    fun artistTopTracks(artistId: String, limit: Int = 10): List<SpotifyTrack> =
        get<TidalList<TidalTrack>>(
            "/artists/$artistId/toptracks",
            "limit" to limit.coerceIn(1, 50).toString(),
        ).items.map { it.toDomain() }

    fun artistAlbums(artistId: String, limit: Int = 50): List<SpotifyAlbumSimple> =
        paginate<TidalAlbum>(
            "/artists/$artistId/albums",
            limit = limit.coerceIn(1, 50),
        ).map { it.toDomainSimple() }

    fun track(trackId: String): SpotifyTrack = get<TidalTrack>("/tracks/$trackId").toDomain()

    /** Public-ish user profile (username / first+last) for playlist owner labels. */
    fun userProfile(userId: String): TidalUser =
        get("/users/${userId.trim()}")

    fun playlist(uuid: String): SpotifyPlaylistDetail =
        get<TidalPlaylist>("/playlists/$uuid").toDomainDetail()

    fun playlistTracks(uuid: String, limit: Int = 500): List<SpotifyTrack> =
        paginate<TidalPlaylistTrackRow>("/playlists/$uuid/items", limit = limit)
            .mapNotNull { it.item?.toDomain() }

    fun search(query: String, limitPerType: Int = 8): SpotifySearchResults {
        val limit = limitPerType.coerceIn(1, 50).toString()
        val resp = get<TidalSearchResponse>(
            "/search",
            "query" to query,
            "limit" to limit,
            "types" to "ARTISTS,ALBUMS,TRACKS,PLAYLISTS",
        )
        return SpotifySearchResults(
            tracks = SearchPagedResponse(items = resp.tracks.items.map { it.toDomain() }),
            albums = SearchPagedResponse(items = resp.albums.items.map { it.toDomainSimple() }),
            artists = SearchPagedResponse(items = resp.artists.items.map { it.toDomain() }),
            playlists = SearchPagedResponse(items = resp.playlists.items.map { it.toDomainSimple() }),
        )
    }

    // --- favorites (save / remove) -----------------------------------------

    fun addFavoriteTrack(trackId: String) {
        val userId = requireUserId()
        postForm("/users/$userId/favorites/tracks", "trackIds" to trackId, "onArtifactNotFound" to "SKIP")
    }

    fun removeFavoriteTrack(trackId: String) {
        val userId = requireUserId()
        deletePath("/users/$userId/favorites/tracks/$trackId")
    }

    fun addFavoriteAlbum(albumId: String) {
        val userId = requireUserId()
        postForm("/users/$userId/favorites/albums", "albumIds" to albumId, "onArtifactNotFound" to "SKIP")
    }

    fun removeFavoriteAlbum(albumId: String) {
        val userId = requireUserId()
        deletePath("/users/$userId/favorites/albums/$albumId")
    }

    fun addFavoritePlaylist(uuid: String) {
        val userId = requireUserId()
        postForm("/users/$userId/favorites/playlists", "uuids" to uuid, "onArtifactNotFound" to "SKIP")
    }

    fun removeFavoritePlaylist(uuid: String) {
        val userId = requireUserId()
        deletePath("/users/$userId/favorites/playlists/$uuid")
    }

    // --- playlist edits (ETag = concurrency token) -------------------------

    /** Current ETag for a playlist, used as the `If-None-Match` concurrency token. */
    fun playlistETag(uuid: String): String? =
        headEtag("/playlists/$uuid/items", "limit" to "1", "offset" to "0")

    fun createPlaylist(name: String, description: String?): SpotifyPlaylistDetail {
        val userId = requireUserId()
        val body = postFormReturning(
            "/users/$userId/playlists",
            "title" to name,
            "description" to (description ?: ""),
        )
        return json.decodeFromString<TidalPlaylist>(body).toDomainDetail()
    }

    fun renamePlaylist(uuid: String, name: String, etag: String?): String {
        val currentEtag = etag ?: playlistETag(uuid)
        postForm("/playlists/$uuid", etag = currentEtag, "title" to name)
        return playlistETag(uuid) ?: currentEtag.orEmpty()
    }

    /** Append track(s). Returns the new ETag. */
    fun addPlaylistTracks(uuid: String, trackIds: List<String>, etag: String?, toIndex: Int?): String {
        if (trackIds.isEmpty()) return etag ?: playlistETag(uuid).orEmpty()
        val currentEtag = etag ?: playlistETag(uuid)
        val fields = buildList {
            add("trackIds" to trackIds.joinToString(","))
            add("onArtifactNotFound" to "SKIP")
            add("onDupes" to "ADD")
            if (toIndex != null) add("toIndex" to toIndex.toString())
        }
        val newEtag = postFormReturningEtag("/playlists/$uuid/items", currentEtag, fields)
        return newEtag ?: playlistETag(uuid) ?: currentEtag.orEmpty()
    }

    /** Remove the track at [index]. Returns the new ETag. */
    fun removePlaylistItem(uuid: String, index: Int, etag: String?): String {
        val currentEtag = etag ?: playlistETag(uuid)
        val newEtag = deleteReturningEtag("/playlists/$uuid/items/$index", currentEtag)
        return newEtag ?: playlistETag(uuid) ?: currentEtag.orEmpty()
    }

    /** Move the item at [fromIndex] to [toIndex]. Returns the new ETag. */
    fun movePlaylistItem(uuid: String, fromIndex: Int, toIndex: Int, etag: String?): String {
        val currentEtag = etag ?: playlistETag(uuid)
        val newEtag = postFormReturningEtag(
            "/playlists/$uuid/items/$fromIndex/move",
            currentEtag,
            listOf("toIndex" to toIndex.toString()),
        )
        return newEtag ?: playlistETag(uuid) ?: currentEtag.orEmpty()
    }

    fun deletePlaylist(uuid: String) = deletePath("/playlists/$uuid")

    // --- playback manifest --------------------------------------------------

    /**
     * Just-in-time signed manifest for [trackId] at exactly [quality].
     * Clear vs Widevine / BTS vs DASH handling lives in
     * [com.lightphone.spotify.playback.tidal.TidalStreamResolve].
     */
    internal fun playbackInfo(trackId: String, quality: String): TidalPlaybackInfo = get(
        "/tracks/$trackId/playbackinfopostpaywall",
        "audioquality" to quality,
        "playbackmode" to "STREAM",
        "assetpresentation" to "FULL",
        "prefetch" to "false",
    )

    // --- HTTP core ----------------------------------------------------------

    /** Blocking GET — safe on ExoPlayer loader threads (no [runBlocking] cancel surface). */
    private inline fun <reified T> get(path: String, vararg query: Pair<String, String>): T =
        json.decodeFromString(executeBlocking(buildGet(path, query.toList())).body)

    private suspend inline fun <reified T> getSuspend(path: String, vararg query: Pair<String, String>): T =
        withContext(Dispatchers.IO) {
            json.decodeFromString(executeWithRetry(buildGet(path, query.toList())).body)
        }

    /** Offset-paginated list fetch over TIDAL's `{items,totalNumberOfItems}` envelope. */
    private inline fun <reified T> paginate(path: String, limit: Int): List<T> {
        val results = mutableListOf<T>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (results.size < limit && offset < total) {
            val pageLimit = minOf(PAGE_LIMIT, limit - results.size)
            val page: TidalList<T> = get(
                path,
                "limit" to pageLimit.toString(),
                "offset" to offset.toString(),
            )
            total = page.totalNumberOfItems
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private fun postForm(path: String, vararg fields: Pair<String, String>) {
        postForm(path, etag = null, *fields)
    }

    private fun postForm(path: String, etag: String?, vararg fields: Pair<String, String>) {
        val request = buildRequest(path, emptyList(), etag)
            .post(formBody(fields.toList()))
            .build()
        runBlocking { executeWithRetry(request) }
    }

    private fun postFormReturning(path: String, vararg fields: Pair<String, String>): String {
        val request = buildRequest(path, emptyList(), null).post(formBody(fields.toList())).build()
        return runBlocking { executeWithRetry(request) }.body
    }

    private fun postFormReturningEtag(path: String, etag: String?, fields: List<Pair<String, String>>): String? {
        val request = buildRequest(path, emptyList(), etag).post(formBody(fields)).build()
        return runBlocking { executeWithRetry(request) }.etag
    }

    private fun deletePath(path: String) {
        val request = buildRequest(path, emptyList(), null).delete().build()
        runBlocking { executeWithRetry(request) }
    }

    private fun deleteReturningEtag(path: String, etag: String?): String? {
        val request = buildRequest(path, emptyList(), etag).delete().build()
        return runBlocking { executeWithRetry(request) }.etag
    }

    private fun headEtag(path: String, vararg query: Pair<String, String>): String? {
        val request = buildGet(path, query.toList())
        return runBlocking { executeWithRetry(request) }.etag
    }

    private fun buildGet(path: String, query: List<Pair<String, String>>): Request =
        buildRequest(path, query, null).get().build()

    private fun buildRequest(
        path: String,
        query: List<Pair<String, String>>,
        etag: String?,
    ): Request.Builder {
        require(path.startsWith("/")) { "TIDAL path must be relative, got: $path" }
        val urlBuilder = "$baseUrl$path".toHttpUrl().newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        urlBuilder.addQueryParameter("countryCode", auth.countryCode())
        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer ${auth.currentBearer()}")
            // orpheusdl / official Android client headers.
            .header("X-Tidal-Token", auth.clientId)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
        etag?.takeIf { it.isNotBlank() }?.let { builder.header("If-None-Match", it) }
        return builder
    }

    private fun formBody(fields: List<Pair<String, String>>): RequestBody {
        val form = FormBody.Builder()
        fields.forEach { (k, v) -> form.add(k, v) }
        return form.build()
    }

    private data class HttpResult(val body: String, val etag: String?)

    private fun executeBlocking(request: Request): HttpResult {
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
                    val etag = response.header("ETag")?.trim('"', ' ')
                    response.close()
                    return HttpResult(body, etag)
                }
                response.code == 401 -> throw TidalAuthException("TIDAL unauthorized — sign in again")
                else -> {
                    response.close()
                    throw IOException("HTTP ${response.code}: $body")
                }
            }
        }
        lastResponse?.close()
        throw IOException("HTTP 429: rate limited after $MAX_429_RETRIES retries")
    }

    private suspend fun executeWithRetry(request: Request): HttpResult {
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
                    val etag = response.header("ETag")?.trim('"', ' ')
                    response.close()
                    return HttpResult(body, etag)
                }
                response.code == 401 -> throw TidalAuthException("TIDAL unauthorized — sign in again")
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

    private fun requireUserId(): String =
        auth.userId() ?: throw TidalAuthException("TIDAL user id unavailable — sign in again")

    companion object {
        /**
         * Private catalog API. Community clients also use `api.tidalhifi.com/v1`
         * interchangeably (streamrip BASE); both host the same `/v1` surface.
         */
        private const val BASE_URL = "https://api.tidal.com/v1"
        /** Matches orpheusdl-tidal / official Android okhttp UA shape. */
        private const val USER_AGENT = "TIDAL_ANDROID/1039 okhttp/4.12.0"
        private const val MAX_429_RETRIES = 4
        const val PAGE_LIMIT = 50
    }
}
