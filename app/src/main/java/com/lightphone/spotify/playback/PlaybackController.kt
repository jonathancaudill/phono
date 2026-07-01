package com.lightphone.spotify.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.lightphone.spotify.NativeInit
import com.lightphone.spotify.data.AlbumDetailResult
import com.lightphone.spotify.data.ArtistDetailResult
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.data.mapWebApiError
import com.lightphone.spotify.data.local.DetailCacheRepository
import com.lightphone.spotify.data.local.LibraryRepository
import com.lightphone.spotify.data.local.LikedTrackEntity
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.data.PlaylistDetailResult
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.local.PlaylistEntity
import com.lightphone.spotify.data.local.SavedAlbumEntity
import com.lightphone.spotify.data.SpotifyRepository
import com.lightphone.spotify.data.SearchResults
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ffi.LibrespotEngine
import com.lightphone.spotify.data.webapi.SpotifyWebApi
import com.lightphone.spotify.data.webapi.WebApiAuth
import com.lightphone.spotify.ffi.NormalizationType
import kotlinx.serialization.json.Json
import com.lightphone.spotify.ffi.PlayerEventListener
import com.lightphone.spotify.ffi.SpotifyException
import com.lightphone.spotify.ffi.RepeatMode
import com.lightphone.spotify.ffi.StreamingQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

data class QueueUiItem(
    val uri: String,
    val title: String,
    val artists: String,
    val durationMs: Long,
)

data class QueueViewState(
    val nowPlaying: QueueUiItem? = null,
    val nextInQueue: List<QueueUiItem> = emptyList(),
    val contextLabel: String? = null,
    val nextFromContext: List<QueueUiItem> = emptyList(),
)

data class PlaybackUiState(
    val loggedIn: Boolean = false,
    /** False until the first cached-credential restore attempt finishes. */
    val authInitialized: Boolean = false,
    val webApiReady: Boolean = false,
    val connected: Boolean = true,
    val networkOnline: Boolean = true,
    val reconnecting: Boolean = false,
    val sessionExpired: Boolean = false,
    val statusMessage: String? = null,
    val currentUri: String? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0,
    val title: String? = null,
    val artist: String? = null,
    val artUrl: String? = null,
    val albumId: String? = null,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queue: QueueViewState = QueueViewState(),
    val error: String? = null,
)

/**
 * Owns the native [LibrespotEngine], bridges its events to a [StateFlow] for the
 * UI and to the [PlaybackService]'s MediaSession, and handles Android audio
 * focus (cpal/rodio does not participate in focus, so we drive pause/resume
 * here). Process-wide singleton.
 */
class PlaybackController private constructor(
    private val appContext: Context,
    val engine: LibrespotEngine,
    private val webApiAuth: WebApiAuth,
) : PlayerEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val webApi = SpotifyWebApi(webApiAuth)
    private val database = PhonoDatabase.get(appContext)
    val libraryRepository = LibraryRepository(database, webApi)
    private val detailCache = DetailCacheRepository(
        database,
        Json { ignoreUnknownKeys = true },
    )
    private val repository = SpotifyRepository(webApi, libraryRepository, detailCache)

    /** uri -> metadata, populated when a list is played so the now-playing bar
     *  and MediaSession have title/artist/art without any extra network call. */
    private val trackMetadata = java.util.concurrent.ConcurrentHashMap<String, TrackMetadata>()

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var playWhenFocusReturns = false
    private var playJob: Job? = null

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** One stable listener — creating a new one on every play made Android fire
     *  AUDIOFOCUS_LOSS on the previous listener, which immediately paused playback. */
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                pauseTransport(userInitiated = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                playWhenFocusReturns = _state.value.isPlaying
                pauseTransport(userInitiated = false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (playWhenFocusReturns) {
                    playWhenFocusReturns = false
                    resumeTransport()
                }
            }
        }
    }

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** Set by [PlaybackService] so playback events can refresh the MediaSession. */
    @Volatile
    var onStateChanged: (() -> Unit)? = null

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseTransport(userInitiated = false)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                _state.update {
                    recomputeStatusMessage(it.copy(networkOnline = true, sessionExpired = false))
                }
                val current = _state.value
                if (current.reconnecting || !current.connected) {
                    runCatching { engine.forceReconnectCheck() }
                }
            }
        }

        override fun onLost(network: Network) {
            _state.update { recomputeStatusMessage(it.copy(networkOnline = false)) }
        }
    }

    init {
        engine.setListener(this)
        appContext.registerReceiver(
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
        val alreadyLoggedIn = engine.isLoggedIn()
        _state.update {
            recomputeStatusMessage(
                it.copy(
                    loggedIn = alreadyLoggedIn,
                    authInitialized = alreadyLoggedIn,
                    webApiReady = webApiAuth.isAuthorized(),
                    networkOnline = isNetworkOnline(),
                ),
            )
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        if (!alreadyLoggedIn) {
            tryCachedLogin { }
        }
    }

    // --- Auth ---------------------------------------------------------------

    fun beginLogin(): String = engine.beginLogin()

    fun completeLogin(code: String, onResult: (Result<Unit>) -> Unit) {
        scope.launch {
            val result = runCatching { engine.loginWithOauthCode(code) }
            result.onFailure { e ->
                val sessionExpired = e is SpotifyException.Auth
                _state.update {
                    recomputeStatusMessage(
                        it.copy(
                            loggedIn = engine.isLoggedIn(),
                            sessionExpired = sessionExpired,
                            error = mapSpotifyError(e),
                        ),
                    )
                }
            }
            result.onSuccess {
                _state.update {
                    recomputeStatusMessage(
                        it.copy(loggedIn = true, sessionExpired = false, error = null),
                    )
                }
            }
            onResult(result)
        }
    }

    fun tryCachedLogin(onResult: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching { engine.loginWithCachedCredentials() }.getOrDefault(false)
            _state.update {
                it.copy(loggedIn = engine.isLoggedIn(), authInitialized = true)
            }
            onResult(ok)
        }
    }

    fun logout() {
        scope.launch {
            engine.logout()
            webApiAuth.clearAll()
            repository.clearLibraryCache()
            abandonFocus()
            _state.value = recomputeStatusMessage(
                PlaybackUiState(
                    loggedIn = false,
                    authInitialized = true,
                    webApiReady = false,
                    networkOnline = isNetworkOnline(),
                ),
            )
            onStateChanged?.invoke()
        }
    }

    // --- Web API auth (Step 2) ----------------------------------------------

    fun hasWebApiCredentials(): Boolean = webApiAuth.hasCredentials()

    fun saveWebApiCredentials(clientId: String, clientSecret: String) {
        webApiAuth.saveCredentials(clientId, clientSecret)
    }

    fun buildWebApiAuthorizeUrl(): String = webApiAuth.buildAuthorizeUrl()

    fun completeWebApiAuth(code: String, onResult: (Result<Unit>) -> Unit) {
        scope.launch {
            val result = webApiAuth.exchangeCode(code)
            result.onSuccess {
                _state.update {
                    recomputeStatusMessage(it.copy(webApiReady = true, error = null))
                }
            }
            result.onFailure { e ->
                _state.update {
                    recomputeStatusMessage(
                        it.copy(
                            webApiReady = false,
                            error = mapWebApiError(e),
                        ),
                    )
                }
            }
            onResult(result)
        }
    }

    fun logoutWebApi() {
        webApiAuth.clearAll()
        _state.update {
            recomputeStatusMessage(it.copy(webApiReady = false))
        }
    }

    // --- Transport ----------------------------------------------------------

    fun play(tracks: List<TrackMetadata>, startIndex: Int, contextLabel: String? = null) {
        tracks.forEach { trackMetadata[normalizeUri(it.uri)] = it }
        tracks.getOrNull(startIndex)?.let { track ->
            _state.update {
                it.copy(
                    currentUri = normalizeUri(track.uri),
                    title = track.title,
                    artist = track.artists,
                    artUrl = track.artUrl,
                    albumId = track.albumId,
                    durationMs = track.durationMs,
                    isLoading = true,
                    isPlaying = true,
                    positionMs = 0,
                    shuffleEnabled = false,
                    repeatMode = RepeatMode.OFF,
                    error = null,
                )
            }
            onStateChanged?.invoke()
        }
        val uris = tracks.map { normalizeUri(it.uri) }
        playJob?.cancel()
        playJob = scope.launch {
            if (!ensureAudioFocus()) {
                android.util.Log.w("Playback", "audio focus denied")
                _state.update { it.copy(isPlaying = false, error = "Audio focus denied") }
                onStateChanged?.invoke()
                return@launch
            }
            runCatching { engine.playUris(uris, startIndex.toUInt(), contextLabel) }
                .onSuccess {
                    android.util.Log.i("Playback", "playUris index=$startIndex uri=${uris.getOrNull(startIndex)}")
                    _state.update { it.copy(isPlaying = true, isLoading = true) }
                    onStateChanged?.invoke()
                }
                .onFailure { e ->
                    android.util.Log.e("Playback", "playUris failed", e)
                    _state.update { it.copy(isPlaying = false, isLoading = false, error = e.message) }
                    onStateChanged?.invoke()
                }
        }
    }

    fun resume() = resumeTransport()

    fun pause() = pauseTransport(userInitiated = true)

    private fun resumeTransport() {
        scope.launch {
            if (!ensureAudioFocus()) return@launch
            engine.resume()
            _state.update { it.copy(isPlaying = true, isLoading = false) }
            onStateChanged?.invoke()
        }
    }

    /** Pause the engine and mirror state locally (don't wait on Mercury/player events). */
    private fun pauseTransport(userInitiated: Boolean) {
        scope.launch {
            engine.pause()
            _state.update { it.copy(isPlaying = false) }
            onStateChanged?.invoke()
            if (userInitiated) {
                // Keep focus so resume is instant; abandon only on end-of-queue.
            }
        }
    }

    fun next() = scope.launch {
        engine.next()
        syncPlaybackModes()
    }
    fun previous() = scope.launch {
        engine.previous()
        syncPlaybackModes()
    }
    fun seek(positionMs: Long) = scope.launch { engine.seek(positionMs.toUInt()) }
    fun toggleShuffle() = scope.launch {
        val enabled = engine.toggleShuffle()
        _state.update { it.copy(shuffleEnabled = enabled) }
        onStateChanged?.invoke()
    }
    fun toggleRepeat() = scope.launch {
        val mode = engine.toggleRepeat()
        _state.update { it.copy(repeatMode = mode) }
        onStateChanged?.invoke()
    }
    fun refreshQueue() {
        if (_state.value.reconnecting) return
        val snapshot = engine.getQueue()
        val queue = QueueViewState(
            nowPlaying = snapshot.nowPlayingUri?.let { uriToQueueItem(normalizeUri(it)) },
            nextInQueue = snapshot.nextInQueue.map { uriToQueueItem(normalizeUri(it)) },
            contextLabel = snapshot.contextLabel,
            nextFromContext = snapshot.nextFromContext.map { uriToQueueItem(normalizeUri(it)) },
        )
        _state.update { it.copy(queue = queue) }
        onStateChanged?.invoke()
        enrichQueueMetadata(queue.allUris())
    }

    private fun QueueViewState.allUris(): List<String> =
        buildList {
            nowPlaying?.uri?.let { add(it) }
            addAll(nextInQueue.map { it.uri })
            addAll(nextFromContext.map { it.uri })
        }

    private fun uriToQueueItem(uri: String): QueueUiItem {
        val cached = trackMetadata[uri]
        return QueueUiItem(
            uri = uri,
            title = cached?.title ?: "…",
            artists = cached?.artists.orEmpty(),
            durationMs = cached?.durationMs ?: 0L,
        )
    }

    private fun enrichQueueMetadata(uris: List<String>) {
        val missing = uris.filter { trackMetadata[it] == null }
        if (missing.isEmpty()) return
        scope.launch {
            for (uri in missing) {
                runCatching { repository.trackMetadataForUri(uri) }
                    .onSuccess { meta ->
                        if (meta != null) {
                            trackMetadata[uri] = meta
                            refreshQueue()
                        }
                    }
            }
        }
    }

    fun addToQueue(track: TrackMetadata) {
        trackMetadata[normalizeUri(track.uri)] = track
        val snapshot = engine.getQueue()
        if (_state.value.currentUri == null && snapshot.nowPlayingUri == null) {
            play(listOf(track), 0, track.album.ifBlank { track.title })
            return
        }
        scope.launch {
            runCatching { engine.addToQueue(normalizeUri(track.uri)) }
                .onSuccess { refreshQueue() }
                .onFailure { e ->
                    android.util.Log.w("Playback", "addToQueue failed", e)
                    _state.update { it.copy(error = e.message) }
                }
        }
    }

    fun clearManualQueue() = scope.launch {
        engine.clearManualQueue()
        refreshQueue()
    }

    fun moveQueueItemUp(index: Int) = scope.launch {
        runCatching { engine.moveQueueItemUp(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveQueueItemUp failed", e) }
    }

    fun moveQueueItemDown(index: Int) = scope.launch {
        runCatching { engine.moveQueueItemDown(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveQueueItemDown failed", e) }
    }

    fun moveContextItemUp(index: Int) = scope.launch {
        runCatching { engine.moveContextItemUp(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveContextItemUp failed", e) }
    }

    fun moveContextItemDown(index: Int) = scope.launch {
        runCatching { engine.moveContextItemDown(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveContextItemDown failed", e) }
    }

    fun loadSettings(): SettingsSnapshot = SettingsSnapshot(
        streamingQuality = engine.getStreamingQuality(),
        gaplessEnabled = engine.getGaplessEnabled(),
        normalizationEnabled = engine.getNormalizationEnabled(),
        normalizationType = engine.getNormalizationType(),
        proxy = engine.getProxy(),
    )

    fun setStreamingQuality(quality: StreamingQuality) =
        scope.launch { engine.setStreamingQuality(quality) }

    fun setGaplessEnabled(enabled: Boolean) =
        scope.launch { engine.setGaplessEnabled(enabled) }

    fun setNormalizationEnabled(enabled: Boolean) =
        scope.launch { engine.setNormalizationEnabled(enabled) }

    fun setNormalizationType(type: NormalizationType) =
        scope.launch { engine.setNormalizationType(type) }

    fun setProxy(proxy: String?) = scope.launch { engine.setProxy(proxy) }

    fun clearAudioCache() = scope.launch { engine.clearAudioCache() }

    /** Search tracks via Web API (catalog search, track type only). */
    suspend fun searchTracks(query: String, limit: Int = 25): Result<List<TrackMetadata>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val results = repository.search(query, limitPerType = limit.coerceIn(1, 10))
                val tracks = results.tracks.map { it.toMetadata() }.take(limit)
                android.util.Log.i(
                    "Search",
                    "searchTracks returned ${tracks.size} results for '$query'",
                )
                Result.success(tracks)
            } catch (e: Throwable) {
                android.util.Log.e("Search", "searchTracks failed", e)
                Result.failure(Exception(mapWebApiError(e)))
            }
        }

    fun likedTracksUiFlow(): Flow<Triple<List<LikedTrackEntity>, Int, Boolean>> =
        libraryRepository.likedTracksUiFlow()

    fun savedAlbumsUiFlow(): Flow<Triple<List<SavedAlbumEntity>, Int, Boolean>> =
        libraryRepository.savedAlbumsUiFlow()

    suspend fun refreshLikedTracks(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.refreshLikedTracks()
        }

    suspend fun likedTracksNeedsFill(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.likedTracksNeedsFill()
        }

    suspend fun appendLikedTracks(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.appendLikedTracks()
        }

    suspend fun refreshSavedAlbums(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.refreshSavedAlbums()
        }

    suspend fun savedAlbumsNeedsFill(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.savedAlbumsNeedsFill()
        }

    suspend fun appendSavedAlbums(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.appendSavedAlbums()
        }

    suspend fun fillRemainingLikedTracks(): Int =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.fillRemainingLikedTracks()
        }

    suspend fun fillRemainingSavedAlbums(): Int =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.fillRemainingSavedAlbums()
        }

    suspend fun likedTracksForPlayback(fromIndex: Int): List<TrackMetadata> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.likedTracksForPlayback(fromIndex)
        }

    fun playlistsUiFlow(): Flow<Triple<List<PlaylistEntity>, Int, Boolean>> =
        libraryRepository.playlistsUiFlow()

    suspend fun refreshPlaylists(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.refreshPlaylists()
        }

    suspend fun playlistsNeedsFill(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.playlistsNeedsFill()
        }

    suspend fun appendPlaylists(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.appendPlaylists()
        }

    suspend fun fillRemainingPlaylists(): Int =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.fillRemainingPlaylists()
        }

    suspend fun playlistDetail(playlistId: String): PlaylistDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.playlistDetail(playlistId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "playlistDetail failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun createPlaylist(name: String, isPublic: Boolean): SpotifyPlaylistSimple =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.createPlaylist(name, isPublic)
            } catch (e: Throwable) {
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun renamePlaylist(playlistId: String, name: String): SpotifyPlaylistDetail =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.renamePlaylist(playlistId, name)
            } catch (e: Throwable) {
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun addTrackToPlaylist(playlistId: String, uri: String): String? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.addTrackToPlaylist(playlistId, uri)
            } catch (e: Throwable) {
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun removeTrackFromPlaylist(playlistId: String, uri: String, snapshotId: String?): String? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.removeTrackFromPlaylist(playlistId, uri, snapshotId)
            } catch (e: Throwable) {
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun reorderPlaylistTrack(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        snapshotId: String?,
    ): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            repository.reorderPlaylistTrack(playlistId, fromIndex, toIndex, snapshotId)
        } catch (e: Throwable) {
            throw Exception(mapWebApiError(e))
        }
    }

    suspend fun editablePlaylists(): List<PlaylistEntity> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.editablePlaylists()
            } catch (e: Throwable) {
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun currentUserId(): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.currentUserId()
        }

    suspend fun albumDetail(albumId: String): AlbumDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.albumDetail(albumId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "albumDetail failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun artistDetail(artistId: String): ArtistDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.artistDetail(artistId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "artistDetail failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun search(query: String, limitPerType: Int = 8): SearchResults =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.search(query, limitPerType)
            } catch (e: Throwable) {
                android.util.Log.e("Search", "search failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun playlistTracks(playlistId: String, limit: Int = 100): List<TrackMetadata> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.playlistTracks(playlistId, limit)
            } catch (e: Throwable) {
                android.util.Log.e("Search", "playlistTracks failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun albumTracks(albumId: String): List<TrackMetadata> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.albumTracks(albumId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "albumTracks failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun isTrackSaved(uri: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.isTrackSaved(uri)
        }

    suspend fun isSavedAlbumCached(albumId: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.isSavedAlbumCached(albumId)
        }

    suspend fun playlistsContainingTrack(
        trackUri: String,
        playlistIds: List<String>,
    ): Set<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        repository.playlistsContainingTrack(trackUri, playlistIds)
    }

    suspend fun isLikedTrackCached(uri: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.isLikedTrackCached(uri)
        }

    suspend fun saveTrack(uri: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.saveTrack(uri)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "saveTrack failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun removeTrack(uri: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.removeTrack(uri)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "removeTrack failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun saveAlbum(albumId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.saveAlbum(albumId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "saveAlbum failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun removeAlbum(albumId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.removeAlbum(albumId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "removeAlbum failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun followPlaylist(playlistId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.followPlaylist(playlistId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "followPlaylist failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    suspend fun unfollowPlaylist(playlistId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.unfollowPlaylist(playlistId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "unfollowPlaylist failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    /** Fetch track metadata (art, title, duration) from the Web API for now-playing. */
    fun refreshNowPlayingFromWebApi() {
        val uri = _state.value.currentUri ?: return
        enrichNowPlayingFromWebApi(normalizeUri(uri))
    }

    suspend fun dailyMixes(): List<com.lightphone.spotify.data.SpotifyPlaylistSimple> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.dailyMixes()
            } catch (e: Throwable) {
                android.util.Log.e("Library", "dailyMixes failed", e)
                throw Exception(mapWebApiError(e))
            }
        }

    /** Start the MediaSessionService so OS media controls are available. */
    fun ensureServiceStarted() {
        runCatching {
            appContext.startService(Intent(appContext, PlaybackService::class.java))
        }
    }

    // --- Audio focus --------------------------------------------------------

    /** Acquire focus once; reuse the same request + listener for the session. */
    private fun ensureAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
            .also { focusRequest = it }
        hasAudioFocus = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasAudioFocus = false
    }

    // --- PlayerEventListener (called from Rust runtime threads) --------------

    override fun onTrackChanged(uri: String) {
        val normalized = normalizeUri(uri)
        val cached = trackMetadata[normalized]
        _state.update {
            it.copy(
                currentUri = normalized,
                isLoading = false,
                error = null,
                title = cached?.title ?: it.title,
                artist = cached?.artists ?: it.artist,
                artUrl = cached?.artUrl ?: it.artUrl,
                albumId = cached?.albumId ?: it.albumId,
                durationMs = if (cached != null && cached.durationMs > 0) {
                    cached.durationMs
                } else {
                    it.durationMs
                },
            )
        }
        syncPlaybackModes()
        fetchMetadata(normalized)
        refreshQueue()
        onStateChanged?.invoke()
    }

    override fun onLoading() {
        _state.update { it.copy(isLoading = true) }
        onStateChanged?.invoke()
    }

    override fun onPlaying(positionMs: Long) {
        _state.update { it.copy(isPlaying = true, isLoading = false, positionMs = positionMs) }
        onStateChanged?.invoke()
    }

    override fun onPaused(positionMs: Long) {
        _state.update { it.copy(isPlaying = false, positionMs = positionMs) }
        onStateChanged?.invoke()
    }

    override fun onPositionChanged(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun onEndOfTrack() {
        _state.update { it.copy(isPlaying = false, positionMs = 0) }
        abandonFocus()
        onStateChanged?.invoke()
    }

    override fun onUnavailable(uri: String) {
        // Rust auto-advances the queue; avoid sticky error state.
    }

    override fun onConnectionLost() {
        _state.update { recomputeStatusMessage(it.copy(connected = false, reconnecting = true)) }
    }

    override fun onConnectionRestored() {
        _state.update { recomputeStatusMessage(it.copy(connected = true, reconnecting = false)) }
        refreshQueue()
    }

    override fun onError(message: String) {
        _state.update { it.copy(error = message) }
    }

    override fun onQueueChanged() {
        refreshQueue()
    }

    private fun fetchMetadata(uri: String) {
        val normalized = normalizeUri(uri)
        val cached = trackMetadata[normalized]
        if (cached != null) {
            applyTrackMetadata(cached)
            if (cached.artUrl != null) return
        }
        enrichNowPlayingFromWebApi(normalized)
    }

    private fun enrichNowPlayingFromWebApi(uri: String) {
        scope.launch {
            runCatching { repository.trackMetadataForUri(uri) }
                .onSuccess { meta ->
                    if (meta == null) return@onSuccess
                    trackMetadata[uri] = meta
                    if (normalizeUri(_state.value.currentUri.orEmpty()) == uri) {
                        applyTrackMetadata(meta)
                    }
                }
                .onFailure { e ->
                    android.util.Log.w("Playback", "Web API now-playing enrich failed", e)
                }
        }
    }

    private fun applyTrackMetadata(meta: TrackMetadata) {
        _state.update {
            it.copy(
                title = meta.title,
                artist = meta.artists,
                artUrl = meta.artUrl,
                albumId = meta.albumId,
                durationMs = if (meta.durationMs > 0) meta.durationMs else it.durationMs,
            )
        }
        onStateChanged?.invoke()
    }

    private fun syncPlaybackModes() {
        _state.update {
            it.copy(
                shuffleEnabled = engine.getShuffle(),
                repeatMode = engine.getRepeatMode(),
            )
        }
    }

    private fun normalizeUri(uri: String): String = uri.substringBefore('?').trim()

    private fun isNetworkOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun recomputeStatusMessage(state: PlaybackUiState): PlaybackUiState {
        val message = when {
            state.sessionExpired -> "Session expired — sign out and sign in again."
            state.reconnecting -> "Reconnecting…"
            !state.networkOnline -> "No connection."
            else -> null
        }
        return state.copy(statusMessage = message)
    }

    private fun mapSpotifyError(e: Throwable): String {
        val httpMessage = e.message?.let { msg ->
            when {
                msg.startsWith("HTTP 429") -> {
                    val retrySec = Regex("retry after (\\d+)s").find(msg)?.groupValues?.get(1)
                    if (retrySec != null) {
                        "Spotify is busy — try again in ${retrySec}s."
                    } else {
                        "Spotify is busy — wait a moment and try again."
                    }
                }
                msg.startsWith("HTTP 401") || msg.startsWith("HTTP 403") ->
                    "Session expired — sign out and sign in again."
                msg.startsWith("HTTP") && !_state.value.networkOnline -> "No connection."
                msg.startsWith("HTTP") -> "Can't reach Spotify right now. Try again."
                else -> null
            }
        }
        val message = when (e) {
            is SpotifyException.Auth -> "Session expired — sign out and sign in again."
            is SpotifyException.Network ->
                if (!_state.value.networkOnline) "No connection."
                else "Can't reach Spotify right now. Try again."
            is SpotifyException.NotLoggedIn -> "Not signed in."
            else -> httpMessage ?: "Something went wrong. Try again."
        }
        if (e is SpotifyException.Auth) {
            _state.update { recomputeStatusMessage(it.copy(sessionExpired = true)) }
        }
        return message
    }

    companion object {
        @Volatile
        private var instance: PlaybackController? = null

        fun get(context: Context): PlaybackController {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    NativeInit.ensureLoaded(appContext)
                    val cacheDir = File(appContext.filesDir, "spotify-cache").apply { mkdirs() }
                    val engine = LibrespotEngine(cacheDir.absolutePath)
                    val webApiAuth = WebApiAuth(appContext)
                    PlaybackController(appContext, engine, webApiAuth).also { instance = it }
                }
            }
        }
    }
}

data class SettingsSnapshot(
    val streamingQuality: StreamingQuality,
    val gaplessEnabled: Boolean,
    val normalizationEnabled: Boolean,
    val normalizationType: NormalizationType,
    val proxy: String?,
)
