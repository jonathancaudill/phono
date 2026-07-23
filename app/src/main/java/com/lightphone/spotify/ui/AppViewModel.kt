package com.lightphone.spotify.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.lightphone.spotify.App
import com.lightphone.spotify.data.PlaylistFilter
import com.lightphone.spotify.data.SearchFilter
import com.lightphone.spotify.data.SearchResults
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.data.SpotifyAlbumDetail
import com.lightphone.spotify.data.SpotifyArtistDetail
import com.lightphone.spotify.data.SpotifyTrack
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.local.LikedTrackEntity
import com.lightphone.spotify.data.local.PlaylistEntity
import com.lightphone.spotify.data.local.SavedAlbumEntity
import com.lightphone.spotify.data.session.SessionEvent
import com.lightphone.spotify.data.backend.BackendCapabilities
import com.lightphone.spotify.data.backend.BackendChoice
import com.lightphone.spotify.data.backend.CollectionKind
import com.lightphone.spotify.data.backend.collectionUri
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.StreamingQuality
import com.lightphone.spotify.ui.components.PhonoContextMenuItem
import com.lightphone.spotify.playback.PlaybackController
import com.lightphone.spotify.playback.PlaybackUiState
import com.lightphone.spotify.playback.SettingsSnapshot
import com.lightphone.spotify.playback.download.DownloadStates
import com.lightphone.spotify.ui.light.ThemePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

data class AlbumDetailState(
    val loading: Boolean = false,
    val requestedId: String? = null,
    val album: SpotifyAlbumDetail? = null,
    val isSaved: Boolean = false,
    val isSavedConfirmed: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

data class ArtistDetailState(
    val loading: Boolean = false,
    val requestedId: String? = null,
    val artist: SpotifyArtistDetail? = null,
    val topTracks: List<SpotifyTrack> = emptyList(),
    val albums: List<com.lightphone.spotify.data.SpotifyAlbumSimple> = emptyList(),
    val error: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val resultsQuery: String? = null,
    val results: SearchResults? = null,
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val refreshError: String? = null,
    val filter: SearchFilter = SearchFilter.All,
) {
    val displayResults: SearchResults?
        get() = results?.takeIf { resultsQuery == query }

    val hasDisplayableResults: Boolean
        get() = displayResults?.let { !it.isEmpty() } == true

    val isEmpty: Boolean
        get() = resultsQuery == query &&
            results != null &&
            results.isEmpty() &&
            !initialLoading &&
            !refreshing &&
            error == null &&
            refreshError == null
}

data class PlayingExtrasState(
    val isTrackSaved: Boolean = false,
    val savePending: Boolean = false,
    val saveError: String? = null,
)

data class SettingsUiState(
    val streamingQuality: StreamingQuality = StreamingQuality.NORMAL,
    val downloadQuality: StreamingQuality = StreamingQuality.HIGH,
    val tidalAudioQuality: com.lightphone.spotify.data.tidal.TidalAudioQuality =
        com.lightphone.spotify.data.tidal.TidalAudioQuality.DEFAULT,
    val tidalDownloadQuality: com.lightphone.spotify.data.tidal.TidalAudioQuality =
        com.lightphone.spotify.data.tidal.TidalAudioQuality.DEFAULT,
    val tidalReportPlays: Boolean = true,
    val gaplessEnabled: Boolean = true,
    val normalizationEnabled: Boolean = false,
    val normalizationType: NormalizationType = NormalizationType.AUTO,
    val proxy: String = "",
    val showAdvanced: Boolean = false,
    val darkTheme: Boolean = true,
)

data class PlaylistDetailTrackRow(
    val track: SpotifyTrack,
    val addedAt: String?,
    val uri: String,
)

data class PlaylistDetailState(
    val loading: Boolean = false,
    val requestedId: String? = null,
    val detail: SpotifyPlaylistDetail? = null,
    val tracks: List<PlaylistDetailTrackRow> = emptyList(),
    val snapshotId: String? = null,
    val isEditable: Boolean = false,
    val isInLibrary: Boolean = false,
    val editMode: Boolean = false,
    val mutating: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val mutationError: String? = null,
)

data class CreatePlaylistState(
    val creating: Boolean = false,
    val error: String? = null,
)

data class PlaylistPickerState(
    val trackUri: String = "",
    val loading: Boolean = false,
    val adding: Boolean = false,
    val playlists: List<PlaylistEntity> = emptyList(),
    val containingPlaylistIds: Set<String> = emptySet(),
    val selectedPlaylistIds: Set<String> = emptySet(),
    /** Whether the track is currently in Liked Songs (server/local truth at load). */
    val isInLikedSongs: Boolean = false,
    /** Checkbox state for the Liked Songs row (may differ from [isInLikedSongs] before apply). */
    val likedSongsSelected: Boolean = false,
    val error: String? = null,
    val statusMessage: String? = null,
) {
    val hasPendingChanges: Boolean
        get() = likedSongsSelected != isInLikedSongs || selectedPlaylistIds.isNotEmpty()
}

enum class ContextMenuAction {
    CopyLink,
    AddToPlaylists,
    RemoveFromLibrary,
    DeletePlaylist,
    Download,
    RemoveDownload,
}

/** Offline pin state for an album/playlist header icon. */
enum class CollectionDownloadUi {
    None,
    Downloading,
    Complete,
}

sealed interface ContextMenuTarget {
    data class Track(val uri: String, val id: String) : ContextMenuTarget
    data class Album(val albumId: String, val uri: String) : ContextMenuTarget
    data class Playlist(val playlistId: String, val uri: String, val ownerId: String) : ContextMenuTarget
}

data class DeletePlaylistConfirm(
    val playlistId: String,
    val name: String,
)

data class ContextMenuUiState(
    val target: ContextMenuTarget? = null,
    val showCopied: Boolean = false,
    val deleteConfirm: DeletePlaylistConfirm? = null,
    val navigateToPlaylistPickerUri: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val controller: PlaybackController = (app as App).ensureController()
    private val themePreferences = ThemePreferences(app)

    /** Active backend (Spotify vs TIDAL) — drives login/setup screen selection. */
    val backendChoice = controller.backendChoice

    val playback: StateFlow<PlaybackUiState> = controller.state

    /** Offline downloads (Room-backed). Empty when the backend does not support pins. */
    val downloads: StateFlow<List<com.lightphone.spotify.data.local.DownloadedTrackEntity>> =
        if (controller.capabilities.downloads) {
            com.lightphone.spotify.data.local.PhonoDatabase.get(app)
                .downloadedTrackDao()
                .observeAll()
                .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
        } else {
            MutableStateFlow(emptyList())
        }

    val downloadCollections: StateFlow<List<com.lightphone.spotify.data.local.DownloadedCollectionWithProgress>> =
        if (controller.capabilities.downloads) {
            com.lightphone.spotify.data.local.PhonoDatabase.get(app)
                .downloadedCollectionDao()
                .observeCollectionsWithProgress(
                    completedState = DownloadStates.COMPLETED,
                    queuedState = DownloadStates.QUEUED,
                    downloadingState = DownloadStates.DOWNLOADING,
                    restartingState = DownloadStates.RESTARTING,
                    failedState = DownloadStates.FAILED,
                )
                .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
        } else {
            MutableStateFlow(emptyList())
        }

    val downloadsSupported: Boolean = controller.capabilities.downloads
    val capabilities: BackendCapabilities = controller.capabilities

    /** Completed offline pin URIs for gray-out / availability checks. */
    val completedDownloadUris: StateFlow<Set<String>> =
        downloads
            .map { rows ->
                rows.asSequence()
                    .filter { it.state == DownloadStates.COMPLETED }
                    .map { it.uri }
                    .toSet()
            }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    fun isTrackDownloaded(uri: String): Boolean =
        downloadsSupported && uri in completedDownloadUris.value

    fun isNetworkOnline(): Boolean = playback.value.networkOnline

    fun observeDownloadCollectionTracks(
        collectionUri: String,
    ): StateFlow<List<com.lightphone.spotify.data.local.DownloadedTrackEntity>> {
        if (!downloadsSupported) return MutableStateFlow(emptyList())
        return com.lightphone.spotify.data.local.PhonoDatabase.get(getApplication())
            .downloadedCollectionDao()
            .observeTracksForCollection(collectionUri)
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** Pin a track for offline playback (uses download quality, not streaming). */
    fun downloadTrack(track: TrackMetadata) {
        if (!downloadsSupported) return
        val quality = controller.downloadQualityApiValue()
        controller.offlineDownloads.download(getApplication(), track, quality)
    }

    fun removeDownload(track: TrackMetadata) {
        if (!downloadsSupported) return
        val quality = downloads.value.firstOrNull { it.uri == track.uri }?.quality
            ?: controller.downloadQualityApiValue()
        controller.offlineDownloads.remove(getApplication(), track, quality)
    }

    fun removeDownloadCollection(collectionUri: String) {
        if (!downloadsSupported) return
        controller.offlineDownloads.removeCollection(getApplication(), collectionUri)
    }

    /** Download every track in the current album detail. */
    fun downloadCurrentAlbum() {
        if (!downloadsSupported) return
        val album = _albumDetail.value.album ?: return
        val tracks = album.tracks?.items.orEmpty().map { it.toMetadata() }
        if (tracks.isEmpty()) return
        val quality = controller.downloadQualityApiValue()
        controller.offlineDownloads.downloadCollection(
            context = getApplication(),
            collectionUri = collectionUri(
                backendChoice, CollectionKind.Album, album.id, album.uri,
            ),
            type = "album",
            name = album.name,
            artUrl = album.images.firstOrNull()?.url,
            tracks = tracks,
            quality = quality,
        )
    }

    fun removeCurrentAlbumDownloads() {
        if (!downloadsSupported) return
        val album = _albumDetail.value.album ?: return
        removeDownloadCollection(
            collectionUri(backendChoice, CollectionKind.Album, album.id, album.uri),
        )
    }

    /** Download every track in the current playlist detail. */
    fun downloadCurrentPlaylist() {
        if (!downloadsSupported) return
        val detail = _playlistDetail.value.detail ?: return
        val tracks = _playlistDetail.value.tracks.map { it.track.toMetadata() }
        if (tracks.isEmpty()) return
        val quality = controller.downloadQualityApiValue()
        controller.offlineDownloads.downloadCollection(
            context = getApplication(),
            collectionUri = collectionUri(
                backendChoice, CollectionKind.Playlist, detail.id, detail.uri,
            ),
            type = "playlist",
            name = detail.name,
            artUrl = detail.images?.firstOrNull()?.url,
            tracks = tracks,
            quality = quality,
        )
    }

    fun removeCurrentPlaylistDownloads() {
        if (!downloadsSupported) return
        val detail = _playlistDetail.value.detail ?: return
        removeDownloadCollection(
            collectionUri(backendChoice, CollectionKind.Playlist, detail.id, detail.uri),
        )
    }

    /** Aggregate download state for album/playlist header + menus. */
    fun collectionDownloadUi(trackUris: List<String>): CollectionDownloadUi {
        if (!downloadsSupported || trackUris.isEmpty()) return CollectionDownloadUi.None
        val byUri = downloads.value.associateBy { it.uri }
        var completed = 0
        var inProgress = 0
        for (uri in trackUris) {
            when (byUri[uri]?.state) {
                DownloadStates.COMPLETED -> completed++
                DownloadStates.DOWNLOADING,
                DownloadStates.QUEUED,
                DownloadStates.RESTARTING,
                -> inProgress++
                else -> Unit
            }
        }
        return when {
            completed == trackUris.size -> CollectionDownloadUi.Complete
            inProgress > 0 || (completed > 0 && completed < trackUris.size) ->
                CollectionDownloadUi.Downloading
            else -> CollectionDownloadUi.None
        }
    }

    /** True if this collection URI is fully (or partially) present in offline pins. */
    fun isCollectionDownloaded(collectionUri: String): Boolean {
        if (!downloadsSupported) return false
        val row = downloadCollections.value.firstOrNull { it.uri == collectionUri } ?: return false
        return row.track_count > 0 && row.completed_count >= row.track_count
    }

    fun isCollectionDownloading(collectionUri: String): Boolean {
        if (!downloadsSupported) return false
        val row = downloadCollections.value.firstOrNull { it.uri == collectionUri } ?: return false
        return row.in_progress_count > 0 ||
            (row.completed_count in 1 until row.track_count)
    }

    fun downloadAlbumById(albumId: String, uri: String = "") {
        if (!downloadsSupported) return
        viewModelScope.launch {
            runCatching {
                val result = controller.albumDetail(albumId)
                val album = result.album
                val tracks = album.tracks?.items.orEmpty().map { it.toMetadata() }
                if (tracks.isEmpty()) return@runCatching
                val quality = controller.downloadQualityApiValue()
                controller.offlineDownloads.downloadCollection(
                    context = getApplication(),
                    collectionUri = collectionUri(
                        backendChoice, CollectionKind.Album, albumId, uri.ifBlank { album.uri },
                    ),
                    type = "album",
                    name = album.name,
                    artUrl = album.images.firstOrNull()?.url,
                    tracks = tracks,
                    quality = quality,
                )
            }.onFailure { e ->
                android.util.Log.e("Downloads", "downloadAlbumById failed", e)
            }
        }
    }

    fun downloadPlaylistById(playlistId: String, uri: String = "") {
        if (!downloadsSupported) return
        viewModelScope.launch {
            runCatching {
                val result = controller.playlistDetail(playlistId)
                val detail = result.detail
                val tracks = result.tracks.mapNotNull { it.track?.toMetadata() }
                if (tracks.isEmpty()) return@runCatching
                val quality = controller.downloadQualityApiValue()
                controller.offlineDownloads.downloadCollection(
                    context = getApplication(),
                    collectionUri = collectionUri(
                        backendChoice,
                        CollectionKind.Playlist,
                        playlistId,
                        uri.ifBlank { detail.uri },
                    ),
                    type = "playlist",
                    name = detail.name,
                    artUrl = detail.images?.firstOrNull()?.url,
                    tracks = tracks,
                    quality = quality,
                )
            }.onFailure { e ->
                android.util.Log.e("Downloads", "downloadPlaylistById failed", e)
            }
        }
    }

    private val _likedTracks = MutableStateFlow(LibraryListUiState<LikedTrackEntity>())
    val likedTracks: StateFlow<LibraryListUiState<LikedTrackEntity>> = _likedTracks.asStateFlow()

    private val _libraryBootstrapping = MutableStateFlow(false)
    val libraryBootstrapping: StateFlow<Boolean> = _libraryBootstrapping.asStateFlow()

    private val _savedAlbums = MutableStateFlow(LibraryListUiState<SavedAlbumEntity>())
    val savedAlbums: StateFlow<LibraryListUiState<SavedAlbumEntity>> = _savedAlbums.asStateFlow()

    private val _playlists = MutableStateFlow(PlaylistsUiState())
    val playlists: StateFlow<PlaylistsUiState> = _playlists.asStateFlow()

    private var likedTracksStarted = false
    private var onLoggedInCalled = false
    private var savedAlbumsStarted = false
    private var playlistsStarted = false
    private var likedFillJob: Job? = null
    private var likedRefreshJob: Job? = null
    private var likedFillRetryJob: Job? = null
    private var likedLookaheadJob: Job? = null
    private var savedFillJob: Job? = null
    private var savedRefreshJob: Job? = null
    private var savedFillRetryJob: Job? = null
    private var savedLookaheadJob: Job? = null
    private var playlistsFillJob: Job? = null
    private var playlistsRefreshJob: Job? = null
    private var playlistsFillRetryJob: Job? = null
    private var playlistsLookaheadJob: Job? = null
    private var likedFillRetries = 0
    private var savedFillRetries = 0
    private var playlistsFillRetries = 0

    private var sessionGeneration = 0
    private var searchRequestId = 0L

    @Volatile private var scrubResyncLocked = false
    private var pendingLikedRefresh = false
    private var pendingSavedRefresh = false
    private var pendingPlaylistsRefresh = false

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _albumDetail = MutableStateFlow(AlbumDetailState())
    val albumDetail: StateFlow<AlbumDetailState> = _albumDetail.asStateFlow()

    private val _artistDetail = MutableStateFlow(ArtistDetailState())
    val artistDetail: StateFlow<ArtistDetailState> = _artistDetail.asStateFlow()

    private val _playingExtras = MutableStateFlow(PlayingExtrasState())
    val playingExtras: StateFlow<PlayingExtrasState> = _playingExtras.asStateFlow()

    private val _settings = MutableStateFlow(SettingsUiState(darkTheme = themePreferences.isDarkTheme()))
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    private val _playlistDetail = MutableStateFlow(PlaylistDetailState())
    val playlistDetail: StateFlow<PlaylistDetailState> = _playlistDetail.asStateFlow()

    private val _createPlaylist = MutableStateFlow(CreatePlaylistState())
    val createPlaylist: StateFlow<CreatePlaylistState> = _createPlaylist.asStateFlow()

    private val _playlistPicker = MutableStateFlow(PlaylistPickerState())
    val playlistPicker: StateFlow<PlaylistPickerState> = _playlistPicker.asStateFlow()

    private val _contextMenu = MutableStateFlow(ContextMenuUiState())
    val contextMenu: StateFlow<ContextMenuUiState> = _contextMenu.asStateFlow()

    private var loadedPlaylistId: String? = null
    private var playlistPickerLoadGen = 0

    init {
        refreshSettings()
        viewModelScope.launch {
            playback
                .map { it.currentUri }
                .distinctUntilChanged()
                .collect { uri -> onCurrentTrackChanged(uri) }
        }
        viewModelScope.launch {
            playback
                .map { it.networkOnline }
                .distinctUntilChanged()
                .collect { online ->
                    if (!online) clearLibrarySyncErrorsForOffline()
                }
        }
        viewModelScope.launch {
            controller.sessionEvents.collect { event ->
                when (event) {
                    SessionEvent.SigningOut -> cancelPlaylistLibraryJobs()
                    SessionEvent.SignedOut -> Unit
                }
            }
        }
        controller.onSessionRestored = { onPlaybackSessionRestored() }
    }

    /** Drop sync banners when offline — navbar already shows Device offline. */
    private fun clearLibrarySyncErrorsForOffline() {
        _likedTracks.update { it.copy(error = null, refreshing = false, initialLoading = false) }
        _savedAlbums.update { it.copy(error = null, refreshing = false, initialLoading = false) }
        _playlists.update { it.copy(error = null, refreshing = false, initialLoading = false) }
        _search.update { it.copy(error = null, refreshError = null, refreshing = false, initialLoading = false) }
    }

    private fun cancelPlaylistLibraryJobs() {
        playlistsRefreshJob?.cancel()
        playlistsFillJob?.cancel()
        playlistsFillRetryJob?.cancel()
        playlistsLookaheadJob?.cancel()
    }

    private fun onPlaybackSessionRestored() {
        clearStalePlaybackSignInErrors()
    }

    private fun clearStalePlaybackSignInErrors() {
        fun isStale(msg: String?): Boolean {
            if (msg == null) return false
            return msg.contains("sign in to spotify playback", ignoreCase = true) ||
                msg.contains("can't reach spotify playback", ignoreCase = true) ||
                msg.contains("playback sign-in", ignoreCase = true)
        }
        if (isStale(_playlists.value.error)) {
            _playlists.update { it.copy(error = null) }
            if (playlistsStarted && playlistsRefreshJob?.isActive != true) {
                refreshPlaylists()
            }
        }
        if (isStale(_playlistDetail.value.error)) {
            _playlistDetail.update { it.copy(error = null) }
        }
        if (isStale(_artistDetail.value.error)) {
            _artistDetail.update { it.copy(error = null) }
        }
    }

    private fun resetSessionUiState() {
        sessionGeneration++
        likedTracksStarted = false
        savedAlbumsStarted = false
        playlistsStarted = false
        onLoggedInCalled = false
        likedFillJob?.cancel()
        likedRefreshJob?.cancel()
        likedFillRetryJob?.cancel()
        savedFillJob?.cancel()
        savedRefreshJob?.cancel()
        savedFillRetryJob?.cancel()
        playlistsFillJob?.cancel()
        playlistsRefreshJob?.cancel()
        playlistsFillRetryJob?.cancel()
        likedLookaheadJob?.cancel()
        savedLookaheadJob?.cancel()
        playlistsLookaheadJob?.cancel()
        searchJob?.cancel()
        playingExtrasJob?.cancel()
        playingExtrasLoadedForUri = null
        likedFillRetries = 0
        savedFillRetries = 0
        playlistsFillRetries = 0
        pendingLikedRefresh = false
        pendingSavedRefresh = false
        pendingPlaylistsRefresh = false
        loadedPlaylistId = null
        // Increment (never reset to 0) so a stale pre-reset load's captured
        // generation can never collide with the next load's generation.
        playlistPickerLoadGen++
        _libraryBootstrapping.value = false
        _likedTracks.value = LibraryListUiState()
        _savedAlbums.value = LibraryListUiState()
        _playlists.value = PlaylistsUiState()
        _search.value = SearchUiState()
        _albumDetail.value = AlbumDetailState()
        _artistDetail.value = ArtistDetailState()
        _playlistDetail.value = PlaylistDetailState()
        _playlistPicker.value = PlaylistPickerState()
        _createPlaylist.value = CreatePlaylistState()
        _playingExtras.value = PlayingExtrasState()
        _contextMenu.value = ContextMenuUiState()
    }

    private fun onCurrentTrackChanged(uri: String?) {
        if (uri == null) {
            playingExtrasJob?.cancel()
            playingExtrasLoadedForUri = null
            _playingExtras.value = PlayingExtrasState()
            return
        }
        if (uri != playingExtrasLoadedForUri) {
            playingExtrasLoadedForUri = null
            _playingExtras.update {
                it.copy(isTrackSaved = false, savePending = false, saveError = null)
            }
        }
        refreshPlayingScreen()
    }

    fun refreshSettings() {
        viewModelScope.launch {
            val snap = kotlinx.coroutines.withContext(Dispatchers.IO) {
                controller.loadSettings()
            }
            val current = _settings.value
            _settings.value = snap.toUiState(
                showAdvanced = current.showAdvanced,
                darkTheme = current.darkTheme,
                downloadQuality = if (capabilities.spotifyStreamingQuality) {
                    controller.getSpotifyDownloadQuality()
                } else {
                    current.downloadQuality
                },
                tidalAudioQuality = if (capabilities.tidalStyleAudioQuality) {
                    controller.getTidalAudioQuality()
                } else {
                    current.tidalAudioQuality
                },
                tidalDownloadQuality = if (capabilities.tidalStyleAudioQuality) {
                    controller.getTidalDownloadQuality()
                } else {
                    current.tidalDownloadQuality
                },
                tidalReportPlays = if (capabilities.tidalStyleAudioQuality) {
                    controller.tidalReportPlaysEnabled()
                } else {
                    current.tidalReportPlays
                },
            )
        }
    }

    suspend fun beginLogin(): String = controller.beginLogin()

    fun clearLoginError() = controller.clearLoginError()

    fun completeLogin(code: String, state: String? = null) {
        controller.completeLogin(code, state) { result ->
            if (result.isSuccess) onLoggedIn()
        }
    }

    fun saveWebApiCredentials(clientId: String, clientSecret: String) {
        controller.saveWebApiCredentials(clientId, clientSecret)
    }

    fun buildWebApiAuthorizeUrl(): String = controller.buildWebApiAuthorizeUrl()

    fun completeWebApiAuth(code: String, state: String?, onResult: (Result<Unit>) -> Unit) {
        controller.completeWebApiAuth(code, state, onResult)
    }

    fun onLoggedIn() {
        // Spotify Liked/Albums need the Step-2 Web API bearer. Calling this after
        // playback-only login would start empty syncs, clear the splash, and leave
        // Albums broken until a force-stop — LightOS can't recover from that.
        if (backendChoice == BackendChoice.SPOTIFY && !playback.value.webApiReady) {
            return
        }
        if (onLoggedInCalled) {
            refreshLikedTracks()
            refreshSavedAlbums()
            refreshPlaylists()
            return
        }
        onLoggedInCalled = true
        val cacheEmpty =
            _likedTracks.value.items.isEmpty() &&
                _savedAlbums.value.items.isEmpty() &&
                _playlists.value.items.isEmpty()
        if (cacheEmpty) {
            _libraryBootstrapping.value = true
        }
        ensureLikedTracksLoaded()
        ensureSavedAlbumsLoaded()
        viewModelScope.launch {
            try {
                withTimeoutOrNull(WARM_TIMEOUT_MS) {
                    controller.warmSpclientSession()
                } ?: android.util.Log.w(
                    "AppViewModel",
                    "warmSpclientSession timed out after ${WARM_TIMEOUT_MS}ms; loading playlists anyway",
                )
                ensurePlaylistsLoaded()
                if (!_libraryBootstrapping.value) return@launch
                // First launch: hold the splash until first pages land, then drain
                // the rest of the library (even on cellular) so tabs open populated.
                withTimeoutOrNull(LIBRARY_BOOTSTRAP_TIMEOUT_MS) {
                    combine(likedTracks, savedAlbums, playlists) { liked, albums, lists ->
                        !liked.initialLoading && !albums.initialLoading && !lists.initialLoading
                    }.first { it }
                    coroutineScope {
                        listOf(
                            async { awaitLikedTracksFilled() },
                            async { awaitSavedAlbumsFilled() },
                            async { awaitPlaylistsFilled() },
                        ).awaitAll()
                    }
                } ?: android.util.Log.w(
                    "AppViewModel",
                    "library bootstrap timed out after ${LIBRARY_BOOTSTRAP_TIMEOUT_MS}ms; opening shell",
                )
            } finally {
                if (_libraryBootstrapping.value) {
                    _libraryBootstrapping.value = false
                }
            }
        }
    }

    fun ensureLikedTracksLoaded() {
        if (likedTracksStarted) return
        likedTracksStarted = true
        val gen = sessionGeneration
        _likedTracks.value = _likedTracks.value.copy(initialLoading = true)
        viewModelScope.launch {
            controller.likedTracksUiFlow().collect { (items, remoteTotal, hasMore) ->
                if (gen != sessionGeneration) return@collect
                _likedTracks.update { it.copy(items = items, remoteTotal = remoteTotal, hasMore = hasMore) }
            }
        }
        refreshLikedTracks()
    }

    fun refreshLikedTracks() {
        if (scrubResyncLocked) { pendingLikedRefresh = true; return }
        if (likedFillJob?.isActive == true) {
            pendingLikedRefresh = true
            return
        }
        if (!isNetworkOnline()) {
            _likedTracks.update {
                it.copy(error = null, refreshing = false, initialLoading = false)
            }
            return
        }
        likedFillJob?.cancel()
        likedFillJob = null
        likedFillRetryJob?.cancel()
        likedFillRetryJob = null
        likedLookaheadJob?.cancel()
        likedLookaheadJob = null
        likedRefreshJob?.cancel()
        likedRefreshJob = viewModelScope.launch {
            val gen = sessionGeneration
            val hadItems = _likedTracks.value.items.isNotEmpty()
            _likedTracks.update {
                it.copy(
                    refreshing = hadItems,
                    initialLoading = !hadItems,
                    error = null,
                )
            }
            runCatching { controller.refreshLikedTracks() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (gen == sessionGeneration && isNetworkOnline()) {
                        _likedTracks.update { it.copy(error = e.message ?: "Could not load liked songs") }
                    }
                }
            if (gen != sessionGeneration) return@launch
            _likedTracks.update { it.copy(refreshing = false, initialLoading = false) }
            if (controller.likedTracksNeedsFill()) {
                startLikedTracksFill()
            }
        }
    }

    fun ensureLikedTracksBufferAhead(lastVisible: Int) {
        if (likedFillJob?.isActive == true) return
        val state = _likedTracks.value
        val target = lastVisible + LOOKAHEAD_ROWS
        if (state.items.size >= target || !state.hasMore) return
        if (likedLookaheadJob?.isActive == true) return
        likedLookaheadJob = viewModelScope.launch {
            while (_likedTracks.value.items.size < target && _likedTracks.value.hasMore) {
                val hasMore = runCatching { controller.appendLikedTracks() }
                    .onFailure { e ->
                        android.util.Log.e("Library", "append liked tracks failed", e)
                    }
                    .getOrDefault(false)
                if (!hasMore) break
            }
        }
    }

    private fun startLikedTracksFill(force: Boolean = false) {
        if (likedFillJob?.isActive == true) return
        if (!force && !controller.isUnmeteredNetwork()) return
        likedFillJob = viewModelScope.launch {
            fillLikedTracksBlocking(force = force)
        }
    }

    /** Drain remaining liked pages. [force] ignores the Wi‑Fi-only gate (first-login splash). */
    private suspend fun fillLikedTracksBlocking(force: Boolean) {
        if (!force && !controller.isUnmeteredNetwork()) return
        if (!controller.likedTracksNeedsFill()) return
        val gen = sessionGeneration
        _likedTracks.update { it.copy(appending = true) }
        try {
            runCatching { controller.fillRemainingLikedTracks() }
                .onSuccess {
                    if (gen == sessionGeneration && !controller.likedTracksNeedsFill()) {
                        likedFillRetries = 0
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    android.util.Log.e("Library", "fill liked tracks failed", e)
                    if (gen != sessionGeneration) return@onFailure
                    _likedTracks.update {
                        it.copy(error = it.error ?: "Library sync incomplete — pull to retry")
                    }
                    if (!force && likedFillRetries < 3 && controller.likedTracksNeedsFill()) {
                        likedFillRetries++
                        likedFillRetryJob = viewModelScope.launch {
                            delay(2000L * likedFillRetries)
                            if (gen == sessionGeneration) startLikedTracksFill()
                        }
                    }
                }
        } finally {
            if (gen == sessionGeneration) {
                _likedTracks.update { it.copy(appending = false) }
                runPendingLibraryRefresh()
            }
        }
    }

    /** Join an in-flight fill, or start a forced drain (bootstrap). */
    private suspend fun awaitLikedTracksFilled() {
        likedFillJob?.takeIf { it.isActive }?.join()
        if (!controller.likedTracksNeedsFill()) return
        val job = viewModelScope.launch { fillLikedTracksBlocking(force = true) }
        likedFillJob = job
        job.join()
    }

    fun resumeLikedTracksFillIfNeeded() {
        if (likedFillJob?.isActive == true) return
        if (!controller.isUnmeteredNetwork()) return
        viewModelScope.launch {
            if (controller.likedTracksNeedsFill()) {
                likedFillRetries = 0
                startLikedTracksFill()
            }
        }
    }

    fun onScrubJumpStart() { scrubResyncLocked = true }

    fun onScrubJumpEnd() {
        scrubResyncLocked = false
        runPendingLibraryRefresh()
    }

    private fun runPendingLibraryRefresh() {
        if (pendingLikedRefresh) { pendingLikedRefresh = false; refreshLikedTracks() }
        if (pendingSavedRefresh) { pendingSavedRefresh = false; refreshSavedAlbums() }
        if (pendingPlaylistsRefresh) { pendingPlaylistsRefresh = false; refreshPlaylists() }
    }

    fun ensureSavedAlbumsLoaded() {
        if (savedAlbumsStarted) return
        savedAlbumsStarted = true
        val gen = sessionGeneration
        _savedAlbums.value = _savedAlbums.value.copy(initialLoading = true)
        viewModelScope.launch {
            controller.savedAlbumsUiFlow().collect { (items, remoteTotal, hasMore) ->
                if (gen != sessionGeneration) return@collect
                _savedAlbums.update { it.copy(items = items, remoteTotal = remoteTotal, hasMore = hasMore) }
            }
        }
        refreshSavedAlbums()
    }

    fun refreshSavedAlbums() {
        if (scrubResyncLocked) { pendingSavedRefresh = true; return }
        if (savedFillJob?.isActive == true) {
            pendingSavedRefresh = true
            return
        }
        if (!isNetworkOnline()) {
            _savedAlbums.update {
                it.copy(error = null, refreshing = false, initialLoading = false)
            }
            return
        }
        savedFillJob?.cancel()
        savedFillJob = null
        savedFillRetryJob?.cancel()
        savedFillRetryJob = null
        savedLookaheadJob?.cancel()
        savedLookaheadJob = null
        savedRefreshJob?.cancel()
        savedRefreshJob = viewModelScope.launch {
            val gen = sessionGeneration
            val hadItems = _savedAlbums.value.items.isNotEmpty()
            if (!hadItems) {
                _savedAlbums.update { it.copy(initialLoading = true, error = null) }
            } else {
                _savedAlbums.update { it.copy(refreshing = true, error = null) }
            }
            runCatching { controller.refreshSavedAlbums() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (gen == sessionGeneration && isNetworkOnline()) {
                        _savedAlbums.update { it.copy(error = e.message ?: "Could not load albums") }
                    }
                }
            if (gen != sessionGeneration) return@launch
            _savedAlbums.update { it.copy(refreshing = false, initialLoading = false) }
            if (controller.savedAlbumsNeedsFill()) {
                startSavedAlbumsFill()
            }
        }
    }

    fun ensureSavedAlbumsBufferAhead(lastVisible: Int) {
        if (savedFillJob?.isActive == true) return
        val state = _savedAlbums.value
        val target = lastVisible + LOOKAHEAD_ROWS
        if (state.items.size >= target || !state.hasMore) return
        if (savedLookaheadJob?.isActive == true) return
        savedLookaheadJob = viewModelScope.launch {
            while (_savedAlbums.value.items.size < target && _savedAlbums.value.hasMore) {
                val hasMore = runCatching { controller.appendSavedAlbums() }
                    .onFailure { e ->
                        android.util.Log.e("Library", "append saved albums failed", e)
                    }
                    .getOrDefault(false)
                if (!hasMore) break
            }
        }
    }

    suspend fun scrollLikedTracksToIndex(listState: LazyListState, targetIndex: Int) {
        val items = _likedTracks.value.items
        if (items.isEmpty()) return
        if (targetIndex > items.lastIndex) {
            android.util.Log.w("Library", "scrub target $targetIndex > lastIndex ${items.lastIndex}")
            return
        }
        listState.scrollToItem(targetIndex)
    }

    suspend fun scrollSavedAlbumsToIndex(listState: LazyListState, targetIndex: Int) {
        val items = _savedAlbums.value.items
        if (items.isEmpty()) return
        if (targetIndex > items.lastIndex) {
            android.util.Log.w("Library", "scrub target $targetIndex > lastIndex ${items.lastIndex}")
            return
        }
        listState.scrollToItem(targetIndex)
    }

    private fun startSavedAlbumsFill(force: Boolean = false) {
        if (savedFillJob?.isActive == true) return
        if (!force && !controller.isUnmeteredNetwork()) return
        savedFillJob = viewModelScope.launch {
            fillSavedAlbumsBlocking(force = force)
        }
    }

    private suspend fun fillSavedAlbumsBlocking(force: Boolean) {
        if (!force && !controller.isUnmeteredNetwork()) return
        if (!controller.savedAlbumsNeedsFill()) return
        val gen = sessionGeneration
        _savedAlbums.update { it.copy(appending = true) }
        try {
            runCatching { controller.fillRemainingSavedAlbums() }
                .onSuccess {
                    if (gen == sessionGeneration && !controller.savedAlbumsNeedsFill()) {
                        savedFillRetries = 0
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    android.util.Log.e("Library", "fill saved albums failed", e)
                    if (gen != sessionGeneration) return@onFailure
                    _savedAlbums.update {
                        it.copy(error = it.error ?: "Library sync incomplete — pull to retry")
                    }
                    if (!force && savedFillRetries < 3 && controller.savedAlbumsNeedsFill()) {
                        savedFillRetries++
                        savedFillRetryJob = viewModelScope.launch {
                            delay(2000L * savedFillRetries)
                            if (gen == sessionGeneration) startSavedAlbumsFill()
                        }
                    }
                }
        } finally {
            if (gen == sessionGeneration) {
                _savedAlbums.update { it.copy(appending = false) }
                runPendingLibraryRefresh()
            }
        }
    }

    private suspend fun awaitSavedAlbumsFilled() {
        savedFillJob?.takeIf { it.isActive }?.join()
        if (!controller.savedAlbumsNeedsFill()) return
        val job = viewModelScope.launch { fillSavedAlbumsBlocking(force = true) }
        savedFillJob = job
        job.join()
    }

    fun resumeSavedAlbumsFillIfNeeded() {
        if (savedFillJob?.isActive == true) return
        if (!controller.isUnmeteredNetwork()) return
        viewModelScope.launch {
            if (controller.savedAlbumsNeedsFill()) {
                savedFillRetries = 0
                startSavedAlbumsFill()
            }
        }
    }

    fun ensurePlaylistsLoaded() {
        if (playlistsStarted) return
        playlistsStarted = true
        val gen = sessionGeneration
        _playlists.value = _playlists.value.copy(initialLoading = true)
        viewModelScope.launch {
            controller.playlistsUiFlow().collect { (items, remoteTotal, hasMore) ->
                if (gen != sessionGeneration) return@collect
                _playlists.update {
                    it.copy(items = items, remoteTotal = remoteTotal, hasMore = hasMore)
                }
            }
        }
        viewModelScope.launch {
            runCatching { controller.currentUserId() }
                .onSuccess { userId -> _playlists.update { it.copy(currentUserId = userId) } }
        }
        refreshPlaylists()
    }

    fun setPlaylistsFilter(filter: PlaylistFilter) {
        _playlists.update { it.copy(filter = filter) }
    }

    fun refreshPlaylists() {
        if (scrubResyncLocked) { pendingPlaylistsRefresh = true; return }
        if (playlistsFillJob?.isActive == true) {
            pendingPlaylistsRefresh = true
            return
        }
        if (!isNetworkOnline()) {
            _playlists.update {
                it.copy(error = null, refreshing = false, initialLoading = false)
            }
            return
        }
        playlistsFillJob?.cancel()
        playlistsFillJob = null
        playlistsFillRetryJob?.cancel()
        playlistsFillRetryJob = null
        playlistsLookaheadJob?.cancel()
        playlistsLookaheadJob = null
        playlistsRefreshJob?.cancel()
        playlistsRefreshJob = viewModelScope.launch {
            val gen = sessionGeneration
            val hadItems = _playlists.value.items.isNotEmpty()
            if (!hadItems) {
                _playlists.update { it.copy(initialLoading = true, error = null) }
            } else {
                _playlists.update { it.copy(refreshing = true, error = null) }
            }
            runCatching { controller.refreshPlaylists() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (gen == sessionGeneration && isNetworkOnline()) {
                        _playlists.update { it.copy(error = e.message ?: "Could not load playlists") }
                    }
                }
            if (gen != sessionGeneration) return@launch
            _playlists.update { it.copy(refreshing = false, initialLoading = false) }
            if (controller.playlistsNeedsFill()) {
                startPlaylistsFill()
            }
        }
    }

    fun ensurePlaylistsBufferAhead(lastVisible: Int) {
        if (playlistsFillJob?.isActive == true) return
        val state = _playlists.value
        val target = lastVisible + LOOKAHEAD_ROWS
        if (state.items.size >= target || !state.hasMore) return
        if (playlistsLookaheadJob?.isActive == true) return
        playlistsLookaheadJob = viewModelScope.launch {
            while (_playlists.value.items.size < target && _playlists.value.hasMore) {
                val hasMore = runCatching { controller.appendPlaylists() }
                    .onFailure { e ->
                        android.util.Log.e("Library", "append playlists failed", e)
                    }
                    .getOrDefault(false)
                if (!hasMore) break
            }
        }
    }

    suspend fun scrollPlaylistsToIndex(listState: LazyListState, targetIndex: Int) {
        val items = _playlists.value.displayItems
        if (items.isEmpty()) return
        if (targetIndex > items.lastIndex) {
            android.util.Log.w("Library", "scrub target $targetIndex > lastIndex ${items.lastIndex}")
            return
        }
        listState.scrollToItem(targetIndex)
    }

    private fun startPlaylistsFill(force: Boolean = false) {
        if (playlistsFillJob?.isActive == true) return
        if (!force && !controller.isUnmeteredNetwork()) return
        playlistsFillJob = viewModelScope.launch {
            fillPlaylistsBlocking(force = force)
        }
    }

    private suspend fun fillPlaylistsBlocking(force: Boolean) {
        if (!force && !controller.isUnmeteredNetwork()) return
        if (!controller.playlistsNeedsFill()) return
        val gen = sessionGeneration
        _playlists.update { it.copy(appending = true) }
        try {
            runCatching { controller.fillRemainingPlaylists() }
                .onSuccess {
                    if (gen == sessionGeneration && !controller.playlistsNeedsFill()) {
                        playlistsFillRetries = 0
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    android.util.Log.e("Library", "fill playlists failed", e)
                    if (gen != sessionGeneration) return@onFailure
                    _playlists.update {
                        it.copy(error = it.error ?: "Library sync incomplete — pull to retry")
                    }
                    if (!force && playlistsFillRetries < 3 && controller.playlistsNeedsFill()) {
                        playlistsFillRetries++
                        playlistsFillRetryJob = viewModelScope.launch {
                            delay(2000L * playlistsFillRetries)
                            if (gen == sessionGeneration) startPlaylistsFill()
                        }
                    }
                }
        } finally {
            if (gen == sessionGeneration) {
                _playlists.update { it.copy(appending = false) }
                runPendingLibraryRefresh()
            }
        }
    }

    private suspend fun awaitPlaylistsFilled() {
        playlistsFillJob?.takeIf { it.isActive }?.join()
        if (!controller.playlistsNeedsFill()) return
        val job = viewModelScope.launch { fillPlaylistsBlocking(force = true) }
        playlistsFillJob = job
        job.join()
    }

    fun resumePlaylistsFillIfNeeded() {
        if (playlistsFillJob?.isActive == true) return
        if (!controller.isUnmeteredNetwork()) return
        viewModelScope.launch {
            if (controller.playlistsNeedsFill()) {
                playlistsFillRetries = 0
                startPlaylistsFill()
            }
        }
    }

    fun loadPlaylistDetail(playlistId: String) {
        if (loadedPlaylistId == playlistId &&
            _playlistDetail.value.detail?.id == playlistId &&
            !_playlistDetail.value.loading
        ) {
            return
        }
        loadedPlaylistId = playlistId
        _playlistDetail.value = PlaylistDetailState(
            loading = true,
            requestedId = playlistId,
        )
        viewModelScope.launch {
            runCatching { controller.playlistDetail(playlistId) }
                .onSuccess { result ->
                    // Bail if the user has since navigated to a different playlist (or
                    // signed out) while this request was in flight — otherwise a slow
                    // response for playlist A can land on top of playlist B's screen.
                    if (_playlistDetail.value.requestedId != playlistId) return@onSuccess
                    _playlistDetail.value = PlaylistDetailState(
                        requestedId = playlistId,
                        detail = result.detail,
                        tracks = result.tracks.mapNotNull { item ->
                            item.track?.let { track ->
                                PlaylistDetailTrackRow(
                                    track = track,
                                    addedAt = item.addedAt,
                                    uri = track.uri,
                                )
                            }
                        },
                        snapshotId = result.detail.snapshotId,
                        isEditable = result.isEditable,
                        isInLibrary = result.isInLibrary,
                    )
                }
                .onFailure { e ->
                    if (_playlistDetail.value.requestedId != playlistId) return@onFailure
                    _playlistDetail.value = PlaylistDetailState(
                        requestedId = playlistId,
                        error = e.message ?: "Could not load playlist",
                    )
                }
        }
    }

    fun togglePlaylistEditMode() {
        _playlistDetail.update { it.copy(editMode = !it.editMode, mutationError = null) }
    }

    fun togglePlaylistLibrary(playlistId: String) {
        viewModelScope.launch {
            val current = _playlistDetail.value
            if (current.saving || current.mutating) return@launch
            _playlistDetail.update { it.copy(saving = true, mutationError = null) }
            val result = runCatching {
                if (current.isInLibrary) {
                    controller.unfollowPlaylist(playlistId)
                } else {
                    controller.followPlaylist(playlistId)
                }
            }
            _playlistDetail.update {
                it.copy(
                    saving = false,
                    isInLibrary = if (result.isSuccess) !it.isInLibrary else it.isInLibrary,
                    mutationError = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun renamePlaylist(playlistId: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _playlistDetail.update { it.copy(mutating = true, mutationError = null) }
            runCatching { controller.renamePlaylist(playlistId, name) }
                .onSuccess { detail ->
                    _playlistDetail.update {
                        it.copy(detail = detail, mutating = false, snapshotId = detail.snapshotId)
                    }
                }
                .onFailure { e ->
                    _playlistDetail.update {
                        it.copy(mutating = false, mutationError = e.message)
                    }
                }
        }
    }

    fun removePlaylistTrack(playlistId: String, index: Int) {
        val state = _playlistDetail.value
        val row = state.tracks.getOrNull(index) ?: return
        viewModelScope.launch {
            _playlistDetail.update { it.copy(mutating = true, mutationError = null) }
            runCatching {
                controller.removeTrackFromPlaylist(playlistId, row.uri, state.snapshotId)
            }
                .onSuccess { snapshotId ->
                    val updated = state.tracks.toMutableList().apply { removeAt(index) }
                    _playlistDetail.update {
                        it.copy(
                            tracks = updated,
                            mutating = false,
                            snapshotId = snapshotId ?: it.snapshotId,
                            detail = it.detail?.copy(
                                tracks = com.lightphone.spotify.data.SpotifyPlaylistTracksRef(
                                    total = updated.size,
                                ),
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    _playlistDetail.update { it.copy(mutating = false, mutationError = e.message) }
                }
        }
    }

    fun movePlaylistTrack(playlistId: String, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val state = _playlistDetail.value
        if (fromIndex !in state.tracks.indices || toIndex !in state.tracks.indices) return
        viewModelScope.launch {
            _playlistDetail.update { it.copy(mutating = true, mutationError = null) }
            runCatching {
                controller.reorderPlaylistTrack(playlistId, fromIndex, toIndex, state.snapshotId)
            }
                .onSuccess { snapshotId ->
                    val updated = state.tracks.toMutableList()
                    val item = updated.removeAt(fromIndex)
                    updated.add(toIndex, item)
                    _playlistDetail.update {
                        it.copy(
                            tracks = updated,
                            mutating = false,
                            snapshotId = snapshotId ?: it.snapshotId,
                        )
                    }
                }
                .onFailure { e ->
                    _playlistDetail.update { it.copy(mutating = false, mutationError = e.message) }
                }
        }
    }

    fun playPlaylistFrom(playlistId: String, startIndex: Int) {
        viewModelScope.launch {
            val tracks = _playlistDetail.value.tracks
                .drop(startIndex)
                .map { it.track.toMetadata() }
            if (tracks.isEmpty()) {
                val fetched = runCatching { controller.playlistTracks(playlistId) }.getOrNull()
                if (!fetched.isNullOrEmpty()) {
                    playTracks(fetched, 0, _playlistDetail.value.detail?.name)
                }
            } else {
                playTracks(tracks, 0, _playlistDetail.value.detail?.name)
            }
        }
    }

    fun createPlaylist(name: String, isPublic: Boolean, onCreated: (String, String) -> Unit) {
        viewModelScope.launch {
            _createPlaylist.update { CreatePlaylistState(creating = true) }
            runCatching { controller.createPlaylist(name, isPublic) }
                .onSuccess { playlist ->
                    _createPlaylist.value = CreatePlaylistState()
                    onCreated(playlist.id, playlist.name)
                }
                .onFailure { e ->
                    _createPlaylist.value = CreatePlaylistState(
                        creating = false,
                        error = e.message ?: "Could not create playlist",
                    )
                }
        }
    }

    fun resetCreatePlaylistState() {
        _createPlaylist.value = CreatePlaylistState()
    }

    fun loadPlaylistPicker(trackUri: String) {
        ensurePlaylistsLoaded()
        controller.schedulePlaylistUriIndexSync()
        if (_playlistPicker.value.trackUri != trackUri) {
            applyPlaylistPickerInitialState(trackUri)
        }
        val gen = ++playlistPickerLoadGen
        viewModelScope.launch {
            loadPlaylistPickerMembership(trackUri, gen)
        }
    }

    /** Playlists the current user can add tracks to, from the library cache. */
    fun cachedEditablePlaylists(): List<PlaylistEntity> =
        editablePlaylistsFromState(_playlists.value.currentUserId)

    private fun applyPlaylistPickerInitialState(trackUri: String) {
        val cachedPlaylists = cachedEditablePlaylists()
        _playlistPicker.value = PlaylistPickerState(
            trackUri = trackUri,
            loading = cachedPlaylists.isEmpty(),
            playlists = cachedPlaylists,
        )
    }

    private suspend fun loadPlaylistPickerMembership(trackUri: String, gen: Int) {
        var userId = _playlists.value.currentUserId
        var playlists = cachedEditablePlaylists()

        if (playlists.isEmpty()) {
            if (userId == null) {
                userId = runCatching { controller.currentUserId() }.getOrNull()
            }
            runCatching { controller.refreshPlaylists() }
            playlists = runCatching { controller.editablePlaylists(userId) }
                .getOrElse { emptyList() }
            if (gen != playlistPickerLoadGen) return
            _playlistPicker.update { current ->
                if (current.trackUri != trackUri) return@update current
                current.copy(playlists = playlists, loading = playlists.isEmpty())
            }
        }

        if (gen != playlistPickerLoadGen) return

        val isInLikedSongs = runCatching { controller.isTrackSaved(trackUri) }
            .getOrDefault(false)

        val containing = if (playlists.isEmpty()) {
            emptySet()
        } else {
            runCatching {
                controller.playlistsContainingTrack(
                    trackUri,
                    playlists.map { it.playlist_id },
                )
            }.getOrDefault(emptySet())
        }

        if (gen != playlistPickerLoadGen) return
        _playlistPicker.update { current ->
            if (current.trackUri != trackUri) return@update current
            current.copy(
                loading = false,
                playlists = playlists,
                containingPlaylistIds = containing,
                isInLikedSongs = isInLikedSongs,
                likedSongsSelected = isInLikedSongs,
            )
        }
    }

    private fun editablePlaylistsFromState(userId: String?): List<PlaylistEntity> {
        if (userId == null) return emptyList()
        return _playlists.value.items.filter { playlist ->
            playlist.owner_id == userId || playlist.is_collaborative
        }
    }

    fun togglePlaylistPickerLikedSongs() {
        _playlistPicker.update { state ->
            if (state.adding) return@update state
            state.copy(
                likedSongsSelected = !state.likedSongsSelected,
                error = null,
                statusMessage = null,
            )
        }
    }

    fun togglePlaylistPickerSelection(playlistId: String) {
        _playlistPicker.update { state ->
            if (state.adding || playlistId in state.containingPlaylistIds) return@update state
            val next = state.selectedPlaylistIds.toMutableSet()
            if (playlistId in next) next.remove(playlistId) else next.add(playlistId)
            state.copy(selectedPlaylistIds = next, error = null, statusMessage = null)
        }
    }

    fun applyPlaylistPickerChanges(onDone: () -> Unit) {
        val state = _playlistPicker.value
        val uri = state.trackUri
        if (uri.isBlank()) return
        if (!state.hasPendingChanges) {
            onDone()
            return
        }
        val likedChanged = state.likedSongsSelected != state.isInLikedSongs
        val playlistIds = state.selectedPlaylistIds.toList()
        val playlistsById = state.playlists.associateBy { it.playlist_id }
        viewModelScope.launch {
            _playlistPicker.update { it.copy(adding = true, error = null, statusMessage = null) }
            var likedError: Throwable? = null
            if (likedChanged) {
                likedError = runCatching {
                    if (state.likedSongsSelected) controller.saveTrack(uri)
                    else controller.removeTrack(uri)
                }.exceptionOrNull()
            }
            val addResults = playlistIds.map { playlistId ->
                playlistId to runCatching {
                    controller.addTrackToPlaylist(
                        playlistId = playlistId,
                        uri = uri,
                        snapshotId = playlistsById[playlistId]?.snapshot_id,
                    )
                }
            }
            val succeeded = addResults.filter { it.second.isSuccess }.map { it.first }.toSet()
            val failed = addResults.filter { it.second.isFailure }
            if (likedError == null && failed.isEmpty()) {
                if (playback.value.currentUri == uri) {
                    _playingExtras.update {
                        it.copy(isTrackSaved = state.likedSongsSelected, saveError = null)
                    }
                    playingExtrasLoadedForUri = uri
                }
                for (playlistId in succeeded) {
                    val openDetail = _playlistDetail.value
                    if (openDetail.requestedId == playlistId) {
                        val meta = runCatching { controller.trackMetadataForUri(uri) }.getOrNull()
                        if (meta != null) {
                            val track = runCatching {
                                com.lightphone.spotify.data.SpotifyTrack(
                                    id = meta.uri.substringAfterLast(':'),
                                    name = meta.title,
                                    uri = meta.uri,
                                    durationMs = meta.durationMs,
                                    artists = meta.artists.split(" · ").filter { it.isNotBlank() }
                                        .map { com.lightphone.spotify.data.SpotifyArtist(name = it) },
                                    album = com.lightphone.spotify.data.SpotifyAlbumSimple(
                                        id = meta.albumId.orEmpty(),
                                        name = meta.album,
                                        images = meta.artUrl?.let {
                                            listOf(com.lightphone.spotify.data.SpotifyImage(url = it))
                                        } ?: emptyList(),
                                    ),
                                )
                            }.getOrNull()
                            if (track != null) {
                                _playlistDetail.update { detail ->
                                    detail.copy(
                                        tracks = detail.tracks + PlaylistDetailTrackRow(
                                            track = track,
                                            addedAt = null,
                                            uri = meta.uri,
                                        ),
                                        snapshotId = addResults.firstOrNull { it.first == playlistId }
                                            ?.second?.getOrNull() ?: detail.snapshotId,
                                    )
                                }
                            }
                        }
                    }
                }
                _playlistPicker.update {
                    it.copy(
                        adding = false,
                        isInLikedSongs = state.likedSongsSelected,
                        likedSongsSelected = state.likedSongsSelected,
                        selectedPlaylistIds = emptySet(),
                        containingPlaylistIds = it.containingPlaylistIds + succeeded,
                        statusMessage = "Saved",
                    )
                }
                onDone()
            } else {
                val messages = buildList {
                    likedError?.message?.let { add(it) }
                    failed.forEach { (id, result) ->
                        val name = playlistsById[id]?.name ?: id
                        add("$name: ${result.exceptionOrNull()?.message ?: "failed"}")
                    }
                }
                val partial = succeeded.isNotEmpty() || (likedChanged && likedError == null)
                _playlistPicker.update {
                    it.copy(
                        adding = false,
                        isInLikedSongs = if (likedError == null && likedChanged) {
                            state.likedSongsSelected
                        } else {
                            it.isInLikedSongs
                        },
                        likedSongsSelected = if (likedError == null && likedChanged) {
                            state.likedSongsSelected
                        } else {
                            it.likedSongsSelected
                        },
                        selectedPlaylistIds = if (partial) {
                            state.selectedPlaylistIds - succeeded
                        } else {
                            state.selectedPlaylistIds
                        },
                        containingPlaylistIds = it.containingPlaylistIds + succeeded,
                        error = messages.joinToString("\n"),
                        statusMessage = if (partial) "Partially saved" else null,
                    )
                }
            }
        }
    }

    fun addTrackToSelectedPlaylists(onAdded: () -> Unit) = applyPlaylistPickerChanges(onAdded)

    fun loadAlbumDetail(albumId: String) {
        _albumDetail.value = AlbumDetailState(loading = true, requestedId = albumId)
        viewModelScope.launch {
            val cachedSaved = runCatching { controller.isSavedAlbumCached(albumId) }
                .getOrDefault(false)
            _albumDetail.update {
                if (it.requestedId != albumId) return@update it
                it.copy(isSaved = cachedSaved, isSavedConfirmed = false)
            }
            runCatching { controller.albumDetail(albumId) }
                .onSuccess { result ->
                    if (_albumDetail.value.requestedId != albumId) return@onSuccess
                    _albumDetail.value = AlbumDetailState(
                        requestedId = albumId,
                        album = result.album,
                        isSaved = result.isSaved,
                        isSavedConfirmed = true,
                    )
                }
                .onFailure { e ->
                    if (_albumDetail.value.requestedId != albumId) return@onFailure
                    _albumDetail.value = AlbumDetailState(
                        requestedId = albumId,
                        error = e.message ?: "Could not load album",
                        isSaved = cachedSaved,
                    )
                }
        }
    }

    fun toggleAlbumSave(albumId: String) {
        viewModelScope.launch {
            val current = _albumDetail.value
            if (current.requestedId != albumId || current.saving) return@launch
            _albumDetail.update { if (it.requestedId == albumId) it.copy(saving = true) else it }
            val result = runCatching {
                if (current.isSaved) controller.removeAlbum(albumId) else controller.saveAlbum(albumId)
            }
            // Re-read the latest state before writing: the user may have navigated to a
            // different album while the save/remove call was in flight.
            _albumDetail.update { latest ->
                if (latest.requestedId != albumId) return@update latest
                latest.copy(
                    saving = false,
                    isSaved = if (result.isSuccess) !current.isSaved else latest.isSaved,
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun loadArtistDetail(artistId: String) {
        _artistDetail.value = ArtistDetailState(loading = true, requestedId = artistId)
        viewModelScope.launch {
            runCatching { controller.artistDetail(artistId) }
                .onSuccess { result ->
                    if (_artistDetail.value.requestedId != artistId) return@onSuccess
                    _artistDetail.value = ArtistDetailState(
                        requestedId = artistId,
                        artist = result.artist,
                        topTracks = result.topTracks,
                        albums = result.albums,
                    )
                }
                .onFailure { e ->
                    if (_artistDetail.value.requestedId != artistId) return@onFailure
                    _artistDetail.value = ArtistDetailState(
                        requestedId = artistId,
                        error = e.message ?: "Could not load artist",
                    )
                }
        }
    }

    private var searchJob: Job? = null

    fun updateSearchQuery(query: String) {
        _search.value = _search.value.copy(query = query)
    }

    fun submitSearch(query: String) {
        if (query.isBlank()) return
        val trimmed = query.trim()
        if (!isNetworkOnline()) {
            _search.update {
                it.copy(
                    query = trimmed,
                    error = null,
                    refreshError = null,
                    initialLoading = false,
                    refreshing = false,
                )
            }
            return
        }
        val requestId = ++searchRequestId
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val prev = _search.value
            val sameQuery = prev.resultsQuery == trimmed && prev.results != null
            _search.update {
                it.copy(
                    query = trimmed,
                    results = if (sameQuery) it.results else null,
                    resultsQuery = if (sameQuery) it.resultsQuery else null,
                    initialLoading = !sameQuery,
                    refreshing = sameQuery,
                    error = null,
                    refreshError = null,
                    filter = if (it.query != trimmed) SearchFilter.All else it.filter,
                )
            }
            try {
                val results = withTimeout(SEARCH_TIMEOUT_MS) { controller.search(trimmed) }
                if (requestId != searchRequestId) return@launch
                _search.update {
                    it.copy(
                        query = trimmed,
                        resultsQuery = trimmed,
                        results = results,
                        error = null,
                        refreshError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (requestId != searchRequestId) return@launch
                if (!isNetworkOnline()) {
                    _search.update {
                        it.copy(error = null, refreshError = null, initialLoading = false, refreshing = false)
                    }
                    return@launch
                }
                val message = when (e) {
                    is TimeoutCancellationException -> "Search timed out — try again."
                    else -> e.message ?: "Search failed"
                }
                _search.update { s ->
                    val keep = s.resultsQuery == trimmed && s.results != null
                    if (keep) {
                        s.copy(refreshError = message, error = null)
                    } else {
                        s.copy(error = message, refreshError = null)
                    }
                }
            } finally {
                if (requestId == searchRequestId) {
                    _search.update { it.copy(initialLoading = false, refreshing = false) }
                }
            }
        }
    }

    fun setSearchFilter(filter: SearchFilter) {
        _search.value = _search.value.copy(filter = filter)
    }

    fun playTracks(tracks: List<TrackMetadata>, startIndex: Int, contextLabel: String? = null) {
        if (tracks.isEmpty()) return
        controller.ensureServiceStarted()
        controller.play(tracks, startIndex, contextLabel)
    }

    fun playLikedFrom(index: Int) {
        viewModelScope.launch {
            val tracks = runCatching { controller.likedTracksForPlayback(index) }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                playTracks(tracks, 0, "Liked Songs")
            }
        }
    }

    fun playAlbumFrom(albumId: String, trackIndex: Int) {
        viewModelScope.launch {
            val detail = _albumDetail.value.album
            val tracks = detail?.tracks?.items?.map { it.toMetadata() }
                ?: runCatching { controller.albumTracks(albumId) }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                val label = detail?.name ?: tracks.firstOrNull()?.album
                playTracks(tracks, trackIndex.coerceIn(0, tracks.lastIndex), label)
            }
        }
    }

    fun playSearchTrack(item: SearchResultItem.Track) {
        val meta = item.track.toMetadata()
        playTracks(listOf(meta), 0, meta.album.ifBlank { null })
    }

    fun playSearchPlaylist(playlistId: String, playlistName: String? = null, onStarted: () -> Unit = {}) {
        viewModelScope.launch {
            val tracks = runCatching { controller.playlistTracks(playlistId) }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                playTracks(tracks, 0, playlistName)
                onStarted()
            }
        }
    }

    fun openSearchResult(
        item: SearchResultItem,
        onOpenAlbum: (String, String) -> Unit,
        onOpenArtist: (String) -> Unit,
        onPlayTrack: (SearchResultItem.Track) -> Unit,
        onOpenPlaylist: (String, String) -> Unit,
    ) {
        when (item) {
            is SearchResultItem.Track -> onPlayTrack(item)
            is SearchResultItem.Album -> onOpenAlbum(item.album.id, item.album.name)
            is SearchResultItem.Artist -> onOpenArtist(item.artist.id)
            is SearchResultItem.Playlist -> onOpenPlaylist(item.playlist.id, item.playlist.name)
        }
    }

    fun playArtistTopTrack(index: Int) {
        val tracks = _artistDetail.value.topTracks.map { it.toMetadata() }
        val label = _artistDetail.value.artist?.name
        playTracks(tracks, index, label)
    }

    private var playingExtrasLoadedForUri: String? = null
    private var playingExtrasJob: Job? = null

    fun refreshPlayingScreen() {
        val uri = playback.value.currentUri ?: return
        val state = playback.value
        if (state.title.isNullOrBlank() || state.durationMs <= 0L || state.artUrl.isNullOrBlank()) {
            controller.refreshNowPlayingFromWebApi()
        }
        if (uri == playingExtrasLoadedForUri) return
        val requestedUri = uri
        playingExtrasJob?.cancel()
        playingExtrasJob = viewModelScope.launch {
            val localHint = runCatching { controller.isLikedTrackCached(requestedUri) }
                .getOrDefault(false)
            if (playback.value.currentUri == requestedUri) {
                _playingExtras.update { it.copy(isTrackSaved = localHint, saveError = null) }
            }

            val saved = runCatching { controller.isTrackSaved(requestedUri) }
                .getOrElse { e ->
                    if (playback.value.currentUri != requestedUri) return@launch
                    _playingExtras.value = _playingExtras.value.copy(
                        saveError = e.message ?: "Could not check liked status",
                    )
                    return@launch
                }
            if (playback.value.currentUri != requestedUri) return@launch
            _playingExtras.value = _playingExtras.value.copy(isTrackSaved = saved, saveError = null)
            playingExtrasLoadedForUri = requestedUri
        }
    }

    fun saveCurrentTrack() {
        val uri = playback.value.currentUri ?: return
        viewModelScope.launch {
            val current = _playingExtras.value
            if (current.savePending || current.isTrackSaved) return@launch
            _playingExtras.value = current.copy(savePending = true, saveError = null)
            val result = runCatching { controller.saveTrack(uri) }
            // The user may have skipped to a different track while the save request
            // was in flight — onCurrentTrackChanged already reset _playingExtras for
            // it, so applying this result now would show the save state on the wrong
            // track.
            if (playback.value.currentUri != uri) return@launch
            _playingExtras.value = PlayingExtrasState(
                isTrackSaved = result.isSuccess,
                savePending = false,
                saveError = result.exceptionOrNull()?.message,
            )
            if (result.isSuccess) {
                playingExtrasLoadedForUri = uri
            }
        }
    }

    fun toggleCurrentTrackSave() {
        val uri = playback.value.currentUri ?: return
        viewModelScope.launch {
            val current = _playingExtras.value
            if (current.savePending) return@launch
            _playingExtras.value = current.copy(savePending = true, saveError = null)
            val wasSaved = current.isTrackSaved
            val result = runCatching {
                if (wasSaved) controller.removeTrack(uri) else controller.saveTrack(uri)
            }
            if (playback.value.currentUri != uri) return@launch
            _playingExtras.value = PlayingExtrasState(
                isTrackSaved = if (result.isSuccess) !wasSaved else wasSaved,
                savePending = false,
                saveError = result.exceptionOrNull()?.message,
            )
            if (result.isSuccess) {
                playingExtrasLoadedForUri = uri
            }
        }
    }

    fun resume() = controller.resume()
    fun pause() = controller.pause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun toggleShuffle() = controller.toggleShuffle()
    fun toggleRepeat() = controller.toggleRepeat()
    fun seek(positionMs: Long) = controller.seek(positionMs)
    fun addTrackToQueue(track: TrackMetadata) {
        controller.ensureServiceStarted()
        controller.addToQueue(track)
    }
    fun moveQueueItemUp(index: Int) = controller.moveQueueItemUp(index)
    fun moveQueueItemDown(index: Int) = controller.moveQueueItemDown(index)
    fun moveContextItemUp(index: Int) = controller.moveContextItemUp(index)
    fun moveContextItemDown(index: Int) = controller.moveContextItemDown(index)
    fun clearManualQueue() = controller.clearManualQueue()
    fun refreshQueue() = controller.refreshQueue()
    /**
     * Sign out, clear the backend binding, and invoke [onReadyForPicker] on the
     * main thread so the host can recreate into [com.lightphone.spotify.ui.screens.BackendPickerScreen].
     */
    fun logout(onReadyForPicker: (() -> Unit)? = null) {
        resetSessionUiState()
        controller.logout {
            com.lightphone.spotify.data.backend.BackendPreferences(getApplication()).clear()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                (getApplication() as App).clearController()
                onReadyForPicker?.invoke()
            }
        }
    }

    fun setStreamingQuality(quality: StreamingQuality) {
        _settings.value = _settings.value.copy(streamingQuality = quality)
        controller.setStreamingQuality(quality)
    }

    fun setDownloadQuality(quality: StreamingQuality) {
        // Future-only: never requeues or rewrites existing pins.
        _settings.value = _settings.value.copy(downloadQuality = quality)
        controller.setSpotifyDownloadQuality(quality)
    }

    fun setTidalAudioQuality(quality: com.lightphone.spotify.data.tidal.TidalAudioQuality) {
        _settings.value = _settings.value.copy(tidalAudioQuality = quality)
        controller.setTidalAudioQuality(quality)
    }

    fun setTidalDownloadQuality(quality: com.lightphone.spotify.data.tidal.TidalAudioQuality) {
        // Future-only: never requeues or rewrites existing pins.
        _settings.value = _settings.value.copy(tidalDownloadQuality = quality)
        controller.setTidalDownloadQuality(quality)
    }

    fun setTidalReportPlays(enabled: Boolean) {
        _settings.value = _settings.value.copy(tidalReportPlays = enabled)
        controller.setTidalReportPlaysEnabled(enabled)
    }

    fun setGaplessEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(gaplessEnabled = enabled)
        controller.setGaplessEnabled(enabled)
    }

    fun setNormalizationEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(normalizationEnabled = enabled)
        controller.setNormalizationEnabled(enabled)
    }

    fun setNormalizationType(type: NormalizationType) {
        _settings.value = _settings.value.copy(normalizationType = type)
        controller.setNormalizationType(type)
    }

    fun setProxy(proxy: String) {
        _settings.value = _settings.value.copy(proxy = proxy)
        val trimmed = proxy.trim()
        controller.setProxy(trimmed.ifEmpty { null })
    }

    fun toggleAdvancedSettings() {
        _settings.value = _settings.value.copy(showAdvanced = !_settings.value.showAdvanced)
    }

    fun setDarkTheme(enabled: Boolean) {
        _settings.value = _settings.value.copy(darkTheme = enabled)
        themePreferences.setDarkTheme(enabled)
    }

    fun clearAudioCache() = controller.clearAudioCache()

    fun showTrackContextMenu(uri: String, id: String) {
        _contextMenu.value = ContextMenuUiState(target = ContextMenuTarget.Track(uri, id))
    }

    fun showAlbumContextMenu(albumId: String, uri: String) {
        _contextMenu.value = ContextMenuUiState(
            target = ContextMenuTarget.Album(albumId, uri),
        )
    }

    fun showPlaylistContextMenu(playlistId: String, uri: String, ownerId: String) {
        _contextMenu.value = ContextMenuUiState(
            target = ContextMenuTarget.Playlist(playlistId, uri, ownerId),
        )
    }

    fun dismissContextMenu() {
        _contextMenu.update { it.copy(target = null) }
    }

    fun dismissCopiedOverlay() {
        _contextMenu.update { it.copy(showCopied = false) }
    }

    fun cancelDeletePlaylist() {
        _contextMenu.update { it.copy(deleteConfirm = null) }
    }

    fun consumeNavigateToPlaylistPicker() {
        _contextMenu.update { it.copy(navigateToPlaylistPickerUri = null) }
    }

    fun contextMenuItemsFor(target: ContextMenuTarget, currentUserId: String?): List<PhonoContextMenuItem> =
        when (target) {
            is ContextMenuTarget.Track -> listOf(
                PhonoContextMenuItem("Copy Link", ContextMenuAction.CopyLink),
                PhonoContextMenuItem("Add To Playlists", ContextMenuAction.AddToPlaylists),
                PhonoContextMenuItem("Remove From Library", ContextMenuAction.RemoveFromLibrary),
            )
            is ContextMenuTarget.Album -> buildList {
                add(PhonoContextMenuItem("Copy Link", ContextMenuAction.CopyLink))
                add(PhonoContextMenuItem("Remove From Library", ContextMenuAction.RemoveFromLibrary))
                if (downloadsSupported) {
                    val collUri = collectionUri(
                        backendChoice, CollectionKind.Album, target.albumId, target.uri,
                    )
                    if (isCollectionDownloaded(collUri) || isCollectionDownloading(collUri)) {
                        add(PhonoContextMenuItem("Remove download", ContextMenuAction.RemoveDownload))
                    } else {
                        add(PhonoContextMenuItem("Download", ContextMenuAction.Download))
                    }
                }
            }
            is ContextMenuTarget.Playlist -> buildList {
                add(PhonoContextMenuItem("Copy Link", ContextMenuAction.CopyLink))
                if (currentUserId != null && target.ownerId == currentUserId) {
                    add(PhonoContextMenuItem("Delete Playlist", ContextMenuAction.DeletePlaylist))
                }
                if (downloadsSupported) {
                    val collUri = collectionUri(
                        backendChoice, CollectionKind.Playlist, target.playlistId, target.uri,
                    )
                    if (isCollectionDownloaded(collUri) || isCollectionDownloading(collUri)) {
                        add(PhonoContextMenuItem("Remove download", ContextMenuAction.RemoveDownload))
                    } else {
                        add(PhonoContextMenuItem("Download", ContextMenuAction.Download))
                    }
                }
            }
        }

    fun onContextMenuAction(action: ContextMenuAction) {
        val target = _contextMenu.value.target ?: return
        when (action) {
            ContextMenuAction.CopyLink -> {
                dismissContextMenu()
                copyContextMenuLink(target)
            }
            ContextMenuAction.AddToPlaylists -> {
                if (target !is ContextMenuTarget.Track) return
                dismissContextMenu()
                _contextMenu.update { it.copy(navigateToPlaylistPickerUri = target.uri) }
            }
            ContextMenuAction.RemoveFromLibrary -> {
                dismissContextMenu()
                removeContextMenuFromLibrary(target)
            }
            ContextMenuAction.DeletePlaylist -> {
                if (target !is ContextMenuTarget.Playlist) return
                dismissContextMenu()
                _contextMenu.update {
                    it.copy(deleteConfirm = DeletePlaylistConfirm(target.playlistId, ""))
                }
            }
            ContextMenuAction.Download -> {
                dismissContextMenu()
                when (target) {
                    is ContextMenuTarget.Album ->
                        downloadAlbumById(target.albumId, target.uri)
                    is ContextMenuTarget.Playlist ->
                        downloadPlaylistById(target.playlistId, target.uri)
                    is ContextMenuTarget.Track -> Unit
                }
            }
            ContextMenuAction.RemoveDownload -> {
                dismissContextMenu()
                when (target) {
                    is ContextMenuTarget.Album ->
                        removeDownloadCollection(
                            collectionUri(
                                backendChoice, CollectionKind.Album, target.albumId, target.uri,
                            ),
                        )
                    is ContextMenuTarget.Playlist ->
                        removeDownloadCollection(
                            collectionUri(
                                backendChoice, CollectionKind.Playlist, target.playlistId, target.uri,
                            ),
                        )
                    is ContextMenuTarget.Track -> Unit
                }
            }
        }
    }

    fun confirmDeletePlaylist() {
        val confirm = _contextMenu.value.deleteConfirm ?: return
        _contextMenu.update { it.copy(deleteConfirm = null) }
        viewModelScope.launch {
            runCatching { controller.unfollowPlaylist(confirm.playlistId) }
                .onFailure { e ->
                    android.util.Log.e("Library", "deletePlaylist failed", e)
                }
        }
    }

    private fun copyContextMenuLink(target: ContextMenuTarget) {
        val url = when (target) {
            is ContextMenuTarget.Track -> spotifyShareUrl(target.uri, target.id, "track")
            is ContextMenuTarget.Album -> spotifyShareUrl(target.uri, target.albumId, "album")
            is ContextMenuTarget.Playlist -> spotifyShareUrl(target.uri, target.playlistId, "playlist")
        }
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Spotify link", url))
        _contextMenu.update { it.copy(showCopied = true) }
        viewModelScope.launch {
            delay(1750)
            if (_contextMenu.value.showCopied) dismissCopiedOverlay()
        }
    }

    private fun removeContextMenuFromLibrary(target: ContextMenuTarget) {
        viewModelScope.launch {
            runCatching {
                when (target) {
                    is ContextMenuTarget.Track -> controller.removeTrack(target.uri)
                    is ContextMenuTarget.Album -> controller.removeAlbum(target.albumId)
                    is ContextMenuTarget.Playlist -> controller.unfollowPlaylist(target.playlistId)
                }
            }.onFailure { e ->
                android.util.Log.e("Library", "removeFromLibrary failed", e)
            }
        }
    }

    companion object {
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val WARM_TIMEOUT_MS = 15_000L
        /** First-login splash: first pages + full library drain (Wi‑Fi or cellular). */
        private const val LIBRARY_BOOTSTRAP_TIMEOUT_MS = 45_000L
        private const val LOOKAHEAD_ROWS = 150
    }
}

private fun SettingsSnapshot.toUiState(
    showAdvanced: Boolean,
    darkTheme: Boolean,
    downloadQuality: StreamingQuality = StreamingQuality.HIGH,
    tidalAudioQuality: com.lightphone.spotify.data.tidal.TidalAudioQuality =
        com.lightphone.spotify.data.tidal.TidalAudioQuality.DEFAULT,
    tidalDownloadQuality: com.lightphone.spotify.data.tidal.TidalAudioQuality =
        com.lightphone.spotify.data.tidal.TidalAudioQuality.DEFAULT,
    tidalReportPlays: Boolean = true,
) = SettingsUiState(
    streamingQuality = streamingQuality,
    downloadQuality = downloadQuality,
    tidalAudioQuality = tidalAudioQuality,
    tidalDownloadQuality = tidalDownloadQuality,
    tidalReportPlays = tidalReportPlays,
    gaplessEnabled = gaplessEnabled,
    normalizationEnabled = normalizationEnabled,
    normalizationType = normalizationType,
    proxy = proxy.orEmpty(),
    showAdvanced = showAdvanced,
    darkTheme = darkTheme,
)
