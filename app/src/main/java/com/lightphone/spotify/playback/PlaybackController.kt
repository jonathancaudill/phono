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
import com.lightphone.spotify.data.SpotifyRepository
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.SearchResults
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ffi.LibrespotEngine
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.PlayerEventListener
import com.lightphone.spotify.ffi.SpotifyException
import com.lightphone.spotify.ffi.StreamingQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackUiState(
    val loggedIn: Boolean = false,
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
    val durationMs: Long = 0,
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
) : PlayerEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val repository = SpotifyRepository(engine)

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
        _state.update {
            recomputeStatusMessage(
                it.copy(
                    loggedIn = engine.isLoggedIn(),
                    networkOnline = isNetworkOnline(),
                ),
            )
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
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
            _state.update { it.copy(loggedIn = engine.isLoggedIn()) }
            onResult(ok)
        }
    }

    fun logout() {
        scope.launch {
            engine.logout()
            abandonFocus()
            _state.value = recomputeStatusMessage(
                PlaybackUiState(
                    loggedIn = false,
                    networkOnline = isNetworkOnline(),
                ),
            )
            onStateChanged?.invoke()
        }
    }

    // --- Transport ----------------------------------------------------------

    fun play(tracks: List<TrackMetadata>, startIndex: Int) {
        tracks.forEach { trackMetadata[normalizeUri(it.uri)] = it }
        tracks.getOrNull(startIndex)?.let { track ->
            _state.update {
                it.copy(
                    currentUri = normalizeUri(track.uri),
                    title = track.title,
                    artist = track.artists,
                    artUrl = track.artUrl,
                    durationMs = track.durationMs,
                    isLoading = true,
                    isPlaying = true,
                    positionMs = 0,
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
            runCatching { engine.playUris(uris, startIndex.toUInt()) }
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

    fun next() = scope.launch { engine.next() }
    fun previous() = scope.launch { engine.previous() }
    fun seek(positionMs: Long) = scope.launch { engine.seek(positionMs.toUInt()) }
    fun setVolume(percent: Int) = scope.launch { engine.setVolume(percent.coerceIn(0, 100).toUByte()) }

    fun loadSettings(): SettingsSnapshot = SettingsSnapshot(
        streamingQuality = engine.getStreamingQuality(),
        gaplessEnabled = engine.getGaplessEnabled(),
        normalizationEnabled = engine.getNormalizationEnabled(),
        normalizationType = engine.getNormalizationType(),
        volumePercent = engine.getVolume().toInt(),
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

    /** Search the catalog via the native engine (spclient context-resolve). */
    suspend fun searchTracks(query: String, limit: Int = 25): Result<List<TrackMetadata>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val tracks = engine.searchTracks(query, limit.toUInt()).map { it.toMetadata() }
                android.util.Log.i(
                    "Search",
                    "searchTracks returned ${tracks.size} results for '$query'; first='${tracks.firstOrNull()?.title}'",
                )
                Result.success(tracks)
            } catch (e: Throwable) {
                android.util.Log.e("Search", "searchTracks failed", e)
                Result.failure(Exception(mapSpotifyError(e)))
            }
        }

    /** Liked songs via spclient context-resolve (`spotify:user:<id>:collection`). */
    suspend fun likedTracks(limit: Int = 200): Result<List<TrackMetadata>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                Result.success(repository.likedTracks(limit))
            } catch (e: Throwable) {
                android.util.Log.e("Library", "likedTracks failed", e)
                Result.failure(Exception(mapSpotifyError(e)))
            }
        }

    suspend fun savedAlbums(limit: Int = 50): List<SpotifySavedAlbum> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.savedAlbums(limit)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "savedAlbums failed", e)
                throw Exception(mapSpotifyError(e))
            }
        }

    suspend fun albumDetail(albumId: String): AlbumDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.albumDetail(albumId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "albumDetail failed", e)
                throw Exception(mapSpotifyError(e))
            }
        }

    suspend fun artistDetail(artistId: String): ArtistDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.artistDetail(artistId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "artistDetail failed", e)
                throw Exception(mapSpotifyError(e))
            }
        }

    suspend fun search(query: String, limitPerType: Int = 5): SearchResults =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.search(query, limitPerType)
            } catch (e: Throwable) {
                android.util.Log.e("Search", "search failed", e)
                throw Exception(mapSpotifyError(e))
            }
        }

    suspend fun albumTracks(albumId: String): List<TrackMetadata> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.albumTracks(albumId)
            } catch (e: Throwable) {
                android.util.Log.e("Library", "albumTracks failed", e)
                throw Exception(mapSpotifyError(e))
            }
        }

    suspend fun isTrackSaved(uri: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.isTrackSaved(uri)
        }

    suspend fun saveTrack(uri: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.saveTrack(uri)
        }

    suspend fun removeTrack(uri: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.removeTrack(uri)
        }

    suspend fun saveAlbum(albumId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.saveAlbum(albumId)
        }

    suspend fun removeAlbum(albumId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.removeAlbum(albumId)
        }

    /** OAuth access token for spclient session auth. */
    suspend fun accessToken(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        engine.accessToken()
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
        _state.update { it.copy(currentUri = normalized, isLoading = false, error = null) }
        fetchMetadata(normalized)
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
        _state.update { it.copy(error = "Track unavailable") }
    }

    override fun onConnectionLost() {
        _state.update { recomputeStatusMessage(it.copy(connected = false, reconnecting = true)) }
    }

    override fun onConnectionRestored() {
        _state.update { recomputeStatusMessage(it.copy(connected = true, reconnecting = false)) }
    }

    override fun onError(message: String) {
        _state.update { it.copy(error = message) }
    }

    private fun fetchMetadata(uri: String) {
        val meta = trackMetadata[uri]
        if (meta == null) {
            _state.update { it.copy(title = null, artist = null, artUrl = null, durationMs = 0) }
            onStateChanged?.invoke()
            return
        }
        _state.update {
            it.copy(
                title = meta.title,
                artist = meta.artists,
                artUrl = meta.artUrl,
                durationMs = meta.durationMs,
            )
        }
        onStateChanged?.invoke()
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
                    PlaybackController(appContext, engine).also { instance = it }
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
    val volumePercent: Int,
    val proxy: String?,
)
