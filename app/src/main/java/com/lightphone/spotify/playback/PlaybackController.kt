package com.lightphone.spotify.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.lightphone.spotify.BuildConfig
import com.lightphone.spotify.audio.PhonoAudioTrackSink
import com.lightphone.spotify.data.AlbumDetailResult
import com.lightphone.spotify.data.ArtistDetailResult
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.data.mapRepositoryError
import com.lightphone.spotify.data.mapWebApiError
import com.lightphone.spotify.data.native.NativeMetadataGateway
import com.lightphone.spotify.data.native.mapNativeError
import com.lightphone.spotify.data.local.DetailCacheRepository
import com.lightphone.spotify.data.local.LibraryRepository
import com.lightphone.spotify.data.local.LikedTrackEntity
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.data.PlaylistDetailResult
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.local.PlaylistEntity
import com.lightphone.spotify.data.local.SavedAlbumEntity
import com.lightphone.spotify.data.MusicRepository
import com.lightphone.spotify.data.SpotifyRepository
import com.lightphone.spotify.data.SearchResults
import coil.Coil
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.data.backend.BackendChoice
import com.lightphone.spotify.data.tidal.TidalApiClient
import com.lightphone.spotify.data.tidal.TidalAuth
import com.lightphone.spotify.data.tidal.TidalRepository
import com.lightphone.spotify.data.tidal.TidalSessionState
import com.lightphone.spotify.playback.backend.PlaybackBackend
import com.lightphone.spotify.playback.backend.PlaybackEventListener
import com.lightphone.spotify.playback.tidal.TidalPlaybackBackend
import com.lightphone.spotify.data.webapi.SpotifyWebApi
import com.lightphone.spotify.data.webapi.WebApiAuth
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.RepeatMode
import com.lightphone.spotify.ffi.SpotifyException
import com.lightphone.spotify.ffi.StreamingQuality
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
    /** False until the first cached-credential restore attempt finishes (login flow only). */
    val authInitialized: Boolean = true,
    val webApiReady: Boolean = false,
    val webApiSessionState: com.lightphone.spotify.data.webapi.WebApiSessionState =
        com.lightphone.spotify.data.webapi.WebApiSessionState.NotConfigured,
    val connected: Boolean = true,
    val networkOnline: Boolean = true,
    val reconnecting: Boolean = false,
    val sessionExpired: Boolean = false,
    val statusMessage: String? = null,
    val currentUri: String? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    /** True when playback position is stalled (buffer underrun). */
    val isBuffering: Boolean = false,
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
    val backendChoice: BackendChoice,
    private val webApiAuth: WebApiAuth,
) : PlaybackEventListener {

    /** Set by [PlaybackService] via [attachBackend]. */
    @Volatile
    private var engineReady = false
    private lateinit var backend: PlaybackBackend

    /** TIDAL auth (single-auth backend). Null on the Spotify build path. */
    private val tidalAuth: TidalAuth? =
        if (backendChoice == BackendChoice.TIDAL) TidalAuth(appContext) else null
    private val tidalApi: TidalApiClient? = tidalAuth?.let { TidalApiClient(it) }

    private val streamingPolicy = StreamingPolicy(this)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes engine transport calls so play/pause/skip cannot race EndOfTrack. */
    private val transportMutex = Mutex()

    /**
     * Serializes everything that mutates login/session state at the native engine
     * level: sign-out, OAuth code exchange, cached-credential login, and the
     * spclient warm-up. Without this, a fast logout-then-login (or a background
     * warm firing mid-logout) can run `loginWithOauthCode` concurrently with
     * `rustLogout()`'s credential wipe + `session.shutdown()`, tearing credentials
     * or leaving a half-built session.
     */
    private val sessionLifecycleMutex = Mutex()

    @Volatile
    private var signingOut = false

    @Volatile
    private var appForegroundRequested = false

    /** Invoked after playback session reconnects (warm or monitor). */
    @Volatile
    var onSessionRestored: (() -> Unit)? = null

    private val webApi = SpotifyWebApi(webApiAuth)
    private val database = PhonoDatabase.get(appContext)
    val libraryRepository = when (backendChoice) {
        BackendChoice.SPOTIFY -> LibraryRepository(
            database,
            likedTracksPageFetcher = { offset -> webApi.savedTracksPage(offset) },
            savedAlbumsPageFetcher = { offset -> webApi.savedAlbumsPage(offset) },
            playlistsPageFetcher = { offset, _ -> webApi.savedPlaylistsPage(offset) },
        )
        BackendChoice.TIDAL -> LibraryRepository(
            database,
            likedTracksPageFetcher = { offset -> tidalApi!!.savedTracksPage(offset) },
            savedAlbumsPageFetcher = { offset -> tidalApi!!.savedAlbumsPage(offset) },
            playlistsPageFetcher = { offset, limit -> tidalApi!!.playlistsPage(offset, limit) },
        )
    }
    private val detailCache = DetailCacheRepository(
        database,
        Json { ignoreUnknownKeys = true },
    )
    private val repository: MusicRepository = when (backendChoice) {
        BackendChoice.SPOTIFY -> SpotifyRepository(webApi, libraryRepository, detailCache)
        BackendChoice.TIDAL -> TidalRepository(tidalApi!!, tidalAuth!!, libraryRepository)
    }

    /** uri -> metadata, populated when a list is played so the now-playing bar
     *  and MediaSession have title/artist/art without any extra network call. */
    private val trackMetadata = java.util.concurrent.ConcurrentHashMap<String, TrackMetadata>()

    private val sessionCoordinator = com.lightphone.spotify.data.session.UserSessionCoordinator(
        libraryRepository = libraryRepository,
        musicRepository = repository,
        webApiAuth = if (backendChoice == BackendChoice.SPOTIFY) webApiAuth else null,
        clearTrackMetadata = { trackMetadata.clear() },
        clearImageMemoryCache = { Coil.imageLoader(appContext).memoryCache?.clear() },
        rustLogout = {
            if (engineReady) {
                runCatching { requireBackend().logout() }
            }
            tidalAuth?.clearAll()
            clearPlaybackCredentialFiles()
        },
    )

    val sessionEvents = sessionCoordinator.events

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var playWhenFocusReturns = false
    /** The latest user-initiated transport coroutine (play/next/previous/seek).
     *  A new command cancels the previous one so rapid taps coalesce to the most
     *  recent intent instead of each firing a native load / rebuild. */
    private var transportJob: Job? = null
    private var stallWatchdogJob: Job? = null
    @Volatile
    private var lastPositionMs: Long = 0
    @Volatile
    private var lastPositionAtMs: Long = 0
    /** False until Rust emits Playing/PositionChanged — avoids false stall when lastPositionAtMs is 0. */
    @Volatile
    private var playbackPulseSeen: Boolean = false
    private var networkLostGraceJob: Job? = null
    private var reconnectDebounceJob: Job? = null
    private var audioRouteDebounceJob: Job? = null
    private var lastTransport: Int? = null
    private var pendingTransport: Int? = null
    private var transportConfirmCount = 0

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

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            handleAudioRouteChange()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handleAudioRouteChange()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkLostGraceJob?.cancel()
            scope.launch {
                _state.update {
                    recomputeStatusMessage(it.copy(networkOnline = true, sessionExpired = false))
                }
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps != null) {
                    streamingPolicy.onCapabilitiesChanged(caps)
                }
                val current = _state.value
                val sessionDead = engineReady && !requireBackend().isSessionConnected()
                if (!current.connected || current.reconnecting || sessionDead) {
                    debouncedForceReconnect()
                }
            }
        }

        override fun onLost(network: Network) {
            networkLostGraceJob?.cancel()
            networkLostGraceJob = scope.launch {
                delay(NETWORK_HANDOFF_GRACE_MS)
                _state.update { recomputeStatusMessage(it.copy(networkOnline = false)) }
                streamingPolicy.onOffline()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            streamingPolicy.onCapabilitiesChanged(caps)
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    NetworkCapabilities.TRANSPORT_WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    NetworkCapabilities.TRANSPORT_CELLULAR
                else -> null
            }
            if (transport != null && lastTransport != null && transport != lastTransport) {
                if (pendingTransport == transport) {
                    transportConfirmCount++
                } else {
                    pendingTransport = transport
                    transportConfirmCount = 1
                }
            } else if (transport == lastTransport) {
                pendingTransport = null
                transportConfirmCount = 0
            }
            lastTransport = transport ?: lastTransport
            if (
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                transportConfirmCount >= TRANSPORT_CONFIRM_SAMPLES &&
                (_state.value.isPlaying || _state.value.reconnecting)
            ) {
                pendingTransport = null
                transportConfirmCount = 0
                debouncedForceReconnect()
            }
        }
    }

    init {
        appContext.registerReceiver(
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            Context.RECEIVER_NOT_EXPORTED,
        )
        when (backendChoice) {
            BackendChoice.SPOTIFY -> {
                scope.launch {
                    webApiAuth.sessionState.collect { state ->
                        _state.update {
                            recomputeStatusMessage(
                                it.copy(
                                    webApiReady = state is com.lightphone.spotify.data.webapi.WebApiSessionState.Authorized,
                                    webApiSessionState = state,
                                ),
                            )
                        }
                    }
                }
                _state.update {
                    recomputeStatusMessage(
                        it.copy(
                            webApiReady = webApiAuth.sessionState.value is
                                com.lightphone.spotify.data.webapi.WebApiSessionState.Authorized,
                            webApiSessionState = webApiAuth.sessionState.value,
                            networkOnline = isNetworkOnline(),
                            loggedIn = hasCachedPlaybackCredentials(),
                            authInitialized = true,
                        ),
                    )
                }
            }
            BackendChoice.TIDAL -> {
                // Single-auth backend: there is no Step-2 dev-app screen, so webApiReady
                // is always true; login state comes from TidalAuth.
                tidalAuth?.let { auth ->
                    scope.launch {
                        auth.sessionState.collect { state ->
                            _state.update {
                                // Keep webApiReady pinned so logout/login never
                                // surfaces the Spotify Web API credentials screen.
                                it.copy(
                                    loggedIn = state is TidalSessionState.Authenticated,
                                    webApiReady = true,
                                )
                            }
                        }
                    }
                }
                _state.update {
                    recomputeStatusMessage(
                        it.copy(
                            webApiReady = true,
                            networkOnline = isNetworkOnline(),
                            loggedIn = hasCachedPlaybackCredentials(),
                            authInitialized = true,
                        ),
                    )
                }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
        connectivityManager.activeNetwork?.let { net ->
            connectivityManager.getNetworkCapabilities(net)?.let { caps ->
                streamingPolicy.onCapabilitiesChanged(caps)
            }
        }
    }

    /** Wire the playback backend after lazy creation (login or first playback). */
    fun attachBackend(backend: PlaybackBackend) {
        if (engineReady) return
        this.backend = backend
        backend.setListener(this)
        // Spotify-only: bridge Login5 spclient metadata + live-session probe.
        (repository as? SpotifyRepository)?.let { spRepo ->
            backend.nativeMetadataGateway?.let { spRepo.nativeMetadata = it }
            spRepo.playbackSessionConnected = {
                engineReady && runCatching { requireBackend().isSessionConnected() }.getOrDefault(false)
            }
        }
        libraryRepository.playlistLibraryPageFetcher = { offset, limit ->
            repository.playlistLibraryPage(offset, limit)
        }
        engineReady = true
        runCatching { requireBackend().setAppForeground(appForegroundRequested) }
        val alreadyLoggedIn = backend.isLoggedIn()
        _state.update {
            recomputeStatusMessage(
                it.copy(
                    loggedIn = alreadyLoggedIn,
                    authInitialized = true,
                    connected = backend.isSessionConnected(),
                ),
            )
        }
        applyPendingSettings()
        startStallWatchdog()
        if (alreadyLoggedIn) {
            warmSpclientSessionAsync()
        }
    }

    fun setAppForeground(foreground: Boolean) {
        appForegroundRequested = foreground
        if (engineReady) {
            runCatching { requireBackend().setAppForeground(foreground) }
        }
    }

    /** Fire-and-forget warm for lifecycle / attach paths. */
    fun warmSpclientSessionAsync() {
        scope.launch {
            warmSpclientSession()
        }
    }

    /**
     * Ensure Step 1 librespot session is live. Idempotent; safe to call on every app open.
     * Does not throw — callers inspect [WarmResult].
     */
    suspend fun warmSpclientSession(): WarmResult {
        if (signingOut) return WarmResult.NotSignedIn
        return sessionLifecycleMutex.withLock {
            if (signingOut) return WarmResult.NotSignedIn
            if (!ensureEngineReady()) {
                return WarmResult.Failed("Playback service not ready")
            }
            if (!requireBackend().isLoggedIn()) {
                return WarmResult.NotSignedIn
            }
            return runCatching { requireBackend().ensurePlaybackReady() }.fold(
                onSuccess = {
                    if (!signingOut) {
                        syncConnectedFromEngine()
                        onSessionRestored?.invoke()
                    }
                    WarmResult.Success
                },
                onFailure = { e ->
                    if (!signingOut) {
                        syncConnectedFromEngine()
                    }
                    WarmResult.Failed(mapSpotifyError(e))
                },
            )
        }
    }

    private fun syncConnectedFromEngine() {
        if (!engineReady) return
        val connected = runCatching { requireBackend().isSessionConnected() }.getOrDefault(false)
        _state.update {
            recomputeStatusMessage(it.copy(connected = connected, reconnecting = false))
        }
    }

    private fun hasCachedPlaybackCredentials(): Boolean = when (backendChoice) {
        BackendChoice.SPOTIFY ->
            File(appContext.filesDir, "spotify-cache/creds/credentials.json").exists()
        BackendChoice.TIDAL -> tidalAuth?.isAuthorized() == true
    }

    /** Belt-and-suspenders: ensure disk creds are gone even if the engine was never attached. */
    private fun clearPlaybackCredentialFiles() {
        val credDir = File(appContext.filesDir, "spotify-cache/creds")
        listOf(
            "credentials.json",
            "oauth_refresh_token",
            "oauth_access_cache.json",
        ).forEach { name -> File(credDir, name).delete() }
    }

    fun isUnmeteredNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun applyPendingSettings() {
        if (!engineReady) return
        val eng = requireBackend()
        pendingSettings.streamingQuality?.let { eng.setStreamingQuality(it) }
        pendingSettings.gaplessEnabled?.let { eng.setGaplessEnabled(it) }
        pendingSettings.normalizationEnabled?.let { eng.setNormalizationEnabled(it) }
        pendingSettings.normalizationType?.let { eng.setNormalizationType(it) }
        pendingSettings.proxy?.let { eng.setProxy(it) }
    }

    private fun launchTransport(block: suspend () -> Unit): Job =
        scope.launch {
            transportMutex.withLock { block() }
        }

    /**
     * Launch a user-initiated transport command that supersedes any prior pending
     * one. Cancels the previous [transportJob] so rapid skips collapse to the last
     * intent. While reconnecting, waits out a short coalesce window first so a
     * flurry of taps on a bad connection triggers at most one native rebuild/load
     * for the final target instead of one per tap.
     */
    private fun launchTransportExclusive(block: suspend () -> Unit): Job {
        transportJob?.cancel()
        val job = scope.launch {
            if (_state.value.reconnecting) {
                delay(TRANSPORT_COALESCE_MS)
            }
            transportMutex.withLock { block() }
        }
        transportJob = job
        return job
    }

    private fun requireBackend(): PlaybackBackend {
        check(engineReady) { "Playback engine not ready — call ensureServiceStarted() first" }
        return backend
    }

    /** Exposed for [StreamingPolicy]. */
    internal val appContextInternal: Context get() = appContext

    fun bufferCurrentToEnd() {
        if (!engineReady) return
        runCatching { requireBackend().bufferCurrentToEnd() }
    }

    fun prefetchUpcoming(ahead: Int) {
        if (!engineReady || ahead <= 0) return
        runCatching { requireBackend().prefetchUpcoming(ahead.toUInt()) }
    }

    private fun handleAudioRouteChange() {
        if (!engineReady) return
        if (BuildConfig.USE_AUDIOTRACK_SINK) {
            // Path C: PhonoAudioTrackSink owns routing via OnRoutingChangedListener.
            // Only recreate the Rust sink wrapper if Kotlin metrics show repeated failures.
            audioRouteDebounceJob?.cancel()
            audioRouteDebounceJob = scope.launch {
                delay(AUDIO_ROUTE_DEBOUNCE_MS)
                val deadObjects = runCatching {
                    PhonoAudioTrackSink.getDeadObjectCount()
                }.getOrDefault(0)
                if (deadObjects > 0) {
                    runCatching { requireBackend().recreateAudioSink() }
                }
            }
            return
        }
        audioRouteDebounceJob?.cancel()
        audioRouteDebounceJob = scope.launch {
            delay(AUDIO_ROUTE_DEBOUNCE_MS)
            val wasPlaying = _state.value.isPlaying
            if (wasPlaying) pauseTransport(userInitiated = false)
            runCatching { requireBackend().recreateAudioSink() }
            if (wasPlaying && hasAudioFocus) resumeTransport()
        }
    }

    private fun debouncedForceReconnect() {
        reconnectDebounceJob?.cancel()
        reconnectDebounceJob = scope.launch {
            delay(RECONNECT_DEBOUNCE_MS)
            // Serialize with user transport so a network-handoff session shutdown
            // cannot land in the middle of a play/skip at the FFI boundary.
            transportMutex.withLock {
                if (engineReady) {
                    runCatching { requireBackend().forceReconnectCheck() }
                }
            }
        }
    }

    private fun startStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = scope.launch {
            while (isActive) {
                delay(STALL_POLL_MS)
                if (!engineReady || !playbackPulseSeen) continue
                val s = _state.value
                if (!s.isPlaying || s.isLoading || s.currentUri == null) continue
                val stalledFor = System.currentTimeMillis() - lastPositionAtMs
                when {
                    stalledFor > STALL_BUFFERING_MS -> {
                        // Buffer only — do NOT forceReconnectCheck here; shutting down the
                        // session mid-play drops Active and can exit(1) in librespot player.
                        setBuffering(true)
                        streamingPolicy.onPlaybackStall()
                    }
                    else -> if (s.isBuffering) setBuffering(false)
                }
            }
        }
    }

    private fun markPlaybackPulse() {
        playbackPulseSeen = true
        lastPositionAtMs = System.currentTimeMillis()
    }

    private fun resetPlaybackPulse() {
        playbackPulseSeen = false
        lastPositionAtMs = 0
    }

    private fun setBuffering(buffering: Boolean) {
        if (_state.value.isBuffering == buffering) return
        _state.update { it.copy(isBuffering = buffering) }
        onStateChanged?.invoke()
    }

    // --- Auth ---------------------------------------------------------------

    fun beginLogin(): String {
        ensureEngineReady()
        return requireBackend().beginLogin()
    }

    fun completeLogin(code: String, state: String?, onResult: (Result<Unit>) -> Unit) {
        ensureEngineReady()
        if (!engineReady) {
            onResult(Result.failure(IllegalStateException("Playback engine not ready")))
            return
        }
        scope.launch {
            val result = sessionLifecycleMutex.withLock {
                runCatching { requireBackend().loginWithOauthCode(code, state) }
            }
            result.onFailure { e ->
                val sessionExpired = e is SpotifyException.Auth
                _state.update {
                    recomputeStatusMessage(
                        it.copy(
                            loggedIn = requireBackend().isLoggedIn(),
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
        if (!engineReady) {
            ensureEngineReady()
        }
        if (!engineReady) {
            onResult(false)
            return
        }
        scope.launch {
            val ok = sessionLifecycleMutex.withLock {
                runCatching { requireBackend().loginWithCachedCredentials() }.getOrDefault(false)
            }
            _state.update {
                it.copy(loggedIn = requireBackend().isLoggedIn(), authInitialized = true)
            }
            onResult(ok)
        }
    }

    fun logout(onSignedOut: (() -> Unit)? = null) {
        scope.launch {
            signingOut = true
            try {
                sessionLifecycleMutex.withLock {
                    sessionCoordinator.signOut(
                        onCancelInFlight = {
                            // Cancel every job that can still reach into the native engine,
                            // then join the ones that make FFI calls so none of them can be
                            // mid-call when rustLogout()'s credential wipe + session
                            // shutdown runs right after this callback returns.
                            reconnectDebounceJob?.cancel()
                            audioRouteDebounceJob?.cancel()
                            networkLostGraceJob?.cancel()
                            playlistUriIndexJob?.cancel()
                            transportJob?.cancel()
                            transportJob?.join()
                            reconnectDebounceJob?.join()
                            audioRouteDebounceJob?.join()
                        },
                    )
                }
                abandonFocus()
                _state.value = recomputeStatusMessage(
                    PlaybackUiState(
                        loggedIn = false,
                        authInitialized = true,
                        // TIDAL has no Step-2 Web API; Spotify resets to NotConfigured.
                        webApiReady = backendChoice == BackendChoice.TIDAL,
                        webApiSessionState =
                            com.lightphone.spotify.data.webapi.WebApiSessionState.NotConfigured,
                        networkOnline = isNetworkOnline(),
                    ),
                )
                onStateChanged?.invoke()
                onSignedOut?.invoke()
            } finally {
                signingOut = false
            }
        }
    }

    // --- Web API auth (Step 2) ----------------------------------------------

    fun hasWebApiCredentials(): Boolean = webApiAuth.hasCredentials()

    fun saveWebApiCredentials(clientId: String, clientSecret: String) {
        webApiAuth.saveCredentials(clientId, clientSecret)
    }

    fun buildWebApiAuthorizeUrl(): String = webApiAuth.buildAuthorizeUrl()

    fun completeWebApiAuth(code: String, state: String?, onResult: (Result<Unit>) -> Unit) {
        scope.launch {
            val result = webApiAuth.exchangeCode(code, state)
            result.onSuccess {
                _state.update {
                    recomputeStatusMessage(
                        it.copy(
                            webApiReady = true,
                            webApiSessionState = com.lightphone.spotify.data.webapi.WebApiSessionState.Authorized,
                            error = null,
                        ),
                    )
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
        ensureServiceStarted()
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
                    isPlaying = false,
                    positionMs = 0,
                    shuffleEnabled = false,
                    repeatMode = RepeatMode.OFF,
                    error = null,
                )
            }
            onStateChanged?.invoke()
        }
        val uris = tracks.map { normalizeUri(it.uri) }
        transportJob?.cancel()
        transportJob = launchTransport {
            if (!ensureAudioFocus()) {
                android.util.Log.w("Playback", "audio focus denied")
                _state.update { it.copy(isPlaying = false, error = "Audio focus denied") }
                onStateChanged?.invoke()
            } else if (!ensureEngineReady()) {
                android.util.Log.w("Playback", "engine not ready")
                _state.update { it.copy(isPlaying = false, error = "Playback service not ready") }
                onStateChanged?.invoke()
            } else {
                resetPlaybackPulse()
                runCatching { requireBackend().playUris(uris, startIndex.toUInt(), contextLabel) }
                    .onSuccess {
                        android.util.Log.i(
                            "Playback",
                            "playUris index=$startIndex uri=${uris.getOrNull(startIndex)}",
                        )
                        _state.update { it.copy(isLoading = true) }
                        onStateChanged?.invoke()
                    }
                    .onFailure { e ->
                        android.util.Log.e("Playback", "playUris failed", e)
                        _state.update { it.copy(isPlaying = false, isLoading = false, error = e.message) }
                        onStateChanged?.invoke()
                    }
            }
        }
    }

    fun resume() = resumeTransport()

    fun pause() = pauseTransport(userInitiated = true)

    private fun resumeTransport() {
        launchTransport {
            if (ensureEngineReady() && ensureAudioFocus()) {
                requireBackend().resume()
                _state.update { it.copy(isLoading = true) }
                onStateChanged?.invoke()
            }
        }
    }

    /** Pause the engine and mirror state locally (don't wait on Mercury/player events). */
    private fun pauseTransport(userInitiated: Boolean) {
        launchTransport {
            if (engineReady) {
                requireBackend().pause()
                _state.update { it.copy(isPlaying = false) }
                onStateChanged?.invoke()
                if (userInitiated) {
                    // Keep focus so resume is instant; abandon only on end-of-queue.
                }
            }
        }
    }

    fun next() = launchTransportExclusive {
        if (ensureEngineReady()) {
            requireBackend().next()
            syncPlaybackModes()
        }
    }
    fun previous() = launchTransportExclusive {
        if (ensureEngineReady()) {
            requireBackend().previous()
            syncPlaybackModes()
        }
    }
    fun seek(positionMs: Long) = launchTransportExclusive {
        if (ensureEngineReady()) {
            requireBackend().seek(positionMs.toUInt())
        }
    }
    fun toggleShuffle() = scope.launch {
        if (!ensureEngineReady()) return@launch
        val enabled = requireBackend().toggleShuffle()
        _state.update { it.copy(shuffleEnabled = enabled) }
        onStateChanged?.invoke()
    }
    fun toggleRepeat() = scope.launch {
        if (!ensureEngineReady()) return@launch
        val mode = requireBackend().toggleRepeat()
        _state.update { it.copy(repeatMode = mode) }
        onStateChanged?.invoke()
    }
    fun refreshQueue() {
        if (!engineReady) return
        val snapshot = requireBackend().getQueue()
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

    suspend fun trackMetadataForUri(uri: String): TrackMetadata? {
        val normalized = normalizeUri(uri)
        return trackMetadata[normalized] ?: repository.trackMetadataForUri(normalized)
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
        if (!engineReady) {
            play(listOf(track), 0, track.album.ifBlank { track.title })
            return
        }
        val snapshot = requireBackend().getQueue()
        if (_state.value.currentUri == null && snapshot.nowPlayingUri == null) {
            play(listOf(track), 0, track.album.ifBlank { track.title })
            return
        }
        scope.launch {
            runCatching { requireBackend().addToQueue(normalizeUri(track.uri)) }
                .onSuccess { refreshQueue() }
                .onFailure { e ->
                    android.util.Log.w("Playback", "addToQueue failed", e)
                    _state.update { it.copy(error = e.message) }
                }
        }
    }

    fun clearManualQueue() = scope.launch {
        if (!engineReady) return@launch
        requireBackend().clearManualQueue()
        refreshQueue()
    }

    fun moveQueueItemUp(index: Int) = scope.launch {
        if (!engineReady) return@launch
        runCatching { requireBackend().moveQueueItemUp(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveQueueItemUp failed", e) }
    }

    fun moveQueueItemDown(index: Int) = scope.launch {
        if (!engineReady) return@launch
        runCatching { requireBackend().moveQueueItemDown(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveQueueItemDown failed", e) }
    }

    fun moveContextItemUp(index: Int) = scope.launch {
        if (!engineReady) return@launch
        runCatching { requireBackend().moveContextItemUp(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveContextItemUp failed", e) }
    }

    fun moveContextItemDown(index: Int) = scope.launch {
        if (!engineReady) return@launch
        runCatching { requireBackend().moveContextItemDown(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveContextItemDown failed", e) }
    }

    fun loadSettings(): SettingsSnapshot {
        if (!engineReady) {
            return SettingsSnapshot(
                streamingQuality = pendingSettings.streamingQuality ?: StreamingQuality.NORMAL,
                gaplessEnabled = pendingSettings.gaplessEnabled ?: true,
                normalizationEnabled = pendingSettings.normalizationEnabled ?: false,
                normalizationType = pendingSettings.normalizationType ?: NormalizationType.AUTO,
                proxy = pendingSettings.proxy,
            )
        }
        val eng = requireBackend()
        return SettingsSnapshot(
            streamingQuality = eng.getStreamingQuality(),
            gaplessEnabled = eng.getGaplessEnabled(),
            normalizationEnabled = eng.getNormalizationEnabled(),
            normalizationType = eng.getNormalizationType(),
            proxy = eng.getProxy(),
        )
    }

    fun setStreamingQuality(quality: StreamingQuality) {
        pendingSettings.streamingQuality = quality
        scope.launch {
            if (ensureEngineReady()) requireBackend().setStreamingQuality(quality)
        }
    }

    fun getTidalAudioQuality(): com.lightphone.spotify.data.tidal.TidalAudioQuality =
        (backend as? com.lightphone.spotify.playback.tidal.TidalPlaybackBackend)
            ?.getTidalAudioQuality()
            ?: tidalAuth?.audioQuality()
            ?: com.lightphone.spotify.data.tidal.TidalAudioQuality.DEFAULT

    fun setTidalAudioQuality(quality: com.lightphone.spotify.data.tidal.TidalAudioQuality) {
        tidalAuth?.setAudioQuality(quality)
        scope.launch {
            if (ensureEngineReady()) {
                (requireBackend() as? com.lightphone.spotify.playback.tidal.TidalPlaybackBackend)
                    ?.setTidalAudioQuality(quality)
            }
        }
    }

    fun setGaplessEnabled(enabled: Boolean) {
        pendingSettings.gaplessEnabled = enabled
        scope.launch {
            if (ensureEngineReady()) requireBackend().setGaplessEnabled(enabled)
        }
    }

    fun setNormalizationEnabled(enabled: Boolean) {
        pendingSettings.normalizationEnabled = enabled
        scope.launch {
            if (ensureEngineReady()) requireBackend().setNormalizationEnabled(enabled)
        }
    }

    fun setNormalizationType(type: NormalizationType) {
        pendingSettings.normalizationType = type
        scope.launch {
            if (ensureEngineReady()) requireBackend().setNormalizationType(type)
        }
    }

    fun setProxy(proxy: String?) {
        pendingSettings.proxy = proxy
        scope.launch {
            if (ensureEngineReady()) requireBackend().setProxy(proxy)
        }
    }

    fun clearAudioCache() = scope.launch {
        if (ensureEngineReady()) requireBackend().clearAudioCache()
    }

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
            } catch (e: CancellationException) {
                throw e
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

    private var playlistUriIndexJob: Job? = null
    @Volatile
    private var playlistUriIndexPending = false

    private data class PendingPlaybackSettings(
        var streamingQuality: StreamingQuality? = null,
        var gaplessEnabled: Boolean? = null,
        var normalizationEnabled: Boolean? = null,
        var normalizationType: NormalizationType? = null,
        var proxy: String? = null,
    )

    private val pendingSettings = PendingPlaybackSettings()

    /** Snapshot-gated rebuild of playlist track URI index (lazy — playlist picker). */
    fun schedulePlaylistUriIndexSync() {
        if (playlistUriIndexJob?.isActive == true) {
            playlistUriIndexPending = true
            return
        }
        playlistUriIndexPending = false
        playlistUriIndexJob = scope.launch {
            runCatching { repository.syncPlaylistUriIndex() }
                .onFailure { e ->
                    android.util.Log.w("PlaylistUriIndex", "sync failed", e)
                }
            if (playlistUriIndexPending) {
                playlistUriIndexPending = false
                schedulePlaylistUriIndexSync()
            }
        }
    }

    suspend fun playlistDetail(playlistId: String): PlaylistDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.playlistDetail(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "playlistDetail failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun createPlaylist(name: String, isPublic: Boolean): SpotifyPlaylistSimple =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.createPlaylist(name, isPublic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun renamePlaylist(playlistId: String, name: String): SpotifyPlaylistDetail =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.renamePlaylist(playlistId, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun addTrackToPlaylist(
        playlistId: String,
        uri: String,
        snapshotId: String? = null,
    ): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.addTrackToPlaylist(playlistId, uri, snapshotId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun removeTrackFromPlaylist(playlistId: String, uri: String, snapshotId: String?): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.removeTrackFromPlaylist(playlistId, uri, snapshotId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun reorderPlaylistTrack(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        snapshotId: String?,
    ): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            repository.reorderPlaylistTrack(playlistId, fromIndex, toIndex, snapshotId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
        }
    }

    suspend fun editablePlaylists(userId: String? = null): List<PlaylistEntity> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.editablePlaylists(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun currentUserId(): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.currentUserIdSuspend()
        }

    suspend fun albumDetail(albumId: String): AlbumDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.albumDetail(albumId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "albumDetail failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun artistDetail(artistId: String): ArtistDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.artistDetail(artistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "artistDetail failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun search(query: String, limitPerType: Int = 8): SearchResults =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.search(query, limitPerType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Search", "search failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun playlistTracks(playlistId: String, limit: Int = 100): List<TrackMetadata> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.playlistTracks(playlistId, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Search", "playlistTracks failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun albumTracks(albumId: String): List<TrackMetadata> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.albumTracks(albumId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "albumTracks failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "saveTrack failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun removeTrack(uri: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.removeTrack(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "removeTrack failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun saveAlbum(albumId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.saveAlbum(albumId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "saveAlbum failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun removeAlbum(albumId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.removeAlbum(albumId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "removeAlbum failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun followPlaylist(playlistId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.followPlaylist(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "followPlaylist failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun unfollowPlaylist(playlistId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.unfollowPlaylist(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "unfollowPlaylist failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "dailyMixes failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    /** Start the MediaSessionService so OS media controls and FGS are available. */
    fun ensureServiceStarted() {
        val intent = Intent(appContext, PlaybackService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        } catch (e: ForegroundServiceStartNotAllowedException) {
            android.util.Log.w("Playback", "startForegroundService blocked; falling back", e)
            runCatching { appContext.startService(intent) }
        } catch (e: IllegalStateException) {
            android.util.Log.w("Playback", "FGS start failed; falling back", e)
            runCatching { appContext.startService(intent) }
        }
    }

    /** Start service and attach native engine on first playback/login need. */
    fun ensureEngineReady(): Boolean {
        ensureServiceStarted()
        PlaybackEngineHolder.ensureEngineAttached(appContext, this)
        return engineReady
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
        markPlaybackPulse()
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
        lastPositionMs = positionMs
        markPlaybackPulse()
        val audible = audiblePositionMs(positionMs)
        _state.update {
            recomputeStatusMessage(
                it.copy(
                    isPlaying = true,
                    isLoading = false,
                    isBuffering = false,
                    positionMs = audible,
                    connected = true,
                    reconnecting = false,
                ),
            )
        }
        streamingPolicy.onTrackActive()
        onStateChanged?.invoke()
    }

    override fun onPaused(positionMs: Long) {
        resetPlaybackPulse()
        _state.update { it.copy(isPlaying = false, positionMs = audiblePositionMs(positionMs)) }
        onStateChanged?.invoke()
    }

    override fun onPositionChanged(positionMs: Long) {
        lastPositionMs = positionMs
        markPlaybackPulse()
        _state.update { it.copy(positionMs = audiblePositionMs(positionMs), isBuffering = false) }
    }

    /** Subtract AudioTrack + ring latency from stream position (ExoPlayer DelayMs). */
    private fun audiblePositionMs(streamPositionMs: Long): Long {
        if (!BuildConfig.USE_AUDIOTRACK_SINK) return streamPositionMs
        val delayMs = runCatching { PhonoAudioTrackSink.getOutputDelayMs() }.getOrDefault(0)
        return (streamPositionMs - delayMs).coerceAtLeast(0L)
    }

    override fun onBuffering(stalled: Boolean) {
        _state.update { it.copy(isBuffering = stalled, isLoading = stalled) }
        onStateChanged?.invoke()
    }

    override fun onEndOfTrack() {
        resetPlaybackPulse()
        _state.update { it.copy(isPlaying = false, positionMs = 0) }
        abandonFocus()
        refreshQueue()
        onStateChanged?.invoke()
    }

    override fun onUnavailable(uri: String) {
        // Rust auto-advances the queue; avoid sticky error state.
    }

    override fun onConnectionLost() {
        _state.update {
            recomputeStatusMessage(
                it.copy(
                    connected = false,
                    reconnecting = true,
                    isPlaying = false,
                    isBuffering = false,
                ),
            )
        }
        onStateChanged?.invoke()
    }

    override fun onConnectionRestored() {
        syncConnectedFromEngine()
        refreshQueue()
        onSessionRestored?.invoke()
        onStateChanged?.invoke()
    }

    override fun onError(message: String) {
        _state.update { it.copy(error = message, isPlaying = false) }
        onStateChanged?.invoke()
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
        if (!engineReady) return
        _state.update {
            it.copy(
                shuffleEnabled = requireBackend().getShuffle(),
                repeatMode = requireBackend().getRepeatMode(),
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
        private const val STALL_POLL_MS = 2000L
        private const val STALL_BUFFERING_MS = 8000L
        private const val NETWORK_HANDOFF_GRACE_MS = 3000L
        private const val RECONNECT_DEBOUNCE_MS = 6000L
        private const val AUDIO_ROUTE_DEBOUNCE_MS = 400L
        private const val TRANSPORT_CONFIRM_SAMPLES = 2

        /** Coalesce window for rapid transport taps while reconnecting so a burst
         *  of skips triggers a single native load/rebuild for the final target. */
        private const val TRANSPORT_COALESCE_MS = 300L

        @Volatile
        private var instance: PlaybackController? = null

        fun get(context: Context): PlaybackController {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val choice = com.lightphone.spotify.data.backend.BackendPreferences(context)
                        .choice()
                        ?: error("PlaybackController requires a BackendChoice — pick a service first")
                    PlaybackController(
                        appContext = context.applicationContext,
                        backendChoice = choice,
                        webApiAuth = PlaybackEngineHolder.webApiAuth(context),
                    )
                }.also { instance = it }
            }
        }

        /** Tear down the singleton after logout so a new backend pick can rebuild. */
        fun clearInstance() {
            synchronized(this) {
                val old = instance ?: return
                instance = null
                old.scope.cancel()
                runCatching {
                    old.appContext.stopService(Intent(old.appContext, PlaybackService::class.java))
                }
                PlaybackEngineHolder.resetForBackendSwitch()
            }
        }
    }

    /** Build the concrete [PlaybackBackend] for the active [backendChoice]. */
    @androidx.media3.common.util.UnstableApi
    internal fun createBackend(): PlaybackBackend = when (backendChoice) {
        BackendChoice.SPOTIFY ->
            com.lightphone.spotify.playback.backend.LibrespotPlaybackBackend(
                PlaybackEngineHolder.createEngine(appContext),
            )
        BackendChoice.TIDAL ->
            TidalPlaybackBackend(appContext, tidalAuth!!, tidalApi!!)
    }
}

data class SettingsSnapshot(
    val streamingQuality: StreamingQuality,
    val gaplessEnabled: Boolean,
    val normalizationEnabled: Boolean,
    val normalizationType: NormalizationType,
    val proxy: String?,
)
