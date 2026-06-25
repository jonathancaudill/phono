package com.lightphone.spotify.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.lightphone.spotify.App
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
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.StreamingQuality
import com.lightphone.spotify.playback.PlaybackController
import com.lightphone.spotify.playback.PlaybackUiState
import com.lightphone.spotify.playback.SettingsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

data class AlbumDetailState(
    val loading: Boolean = false,
    val album: SpotifyAlbumDetail? = null,
    val isSaved: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

data class ArtistDetailState(
    val loading: Boolean = false,
    val artist: SpotifyArtistDetail? = null,
    val topTracks: List<SpotifyTrack> = emptyList(),
    val albums: List<com.lightphone.spotify.data.SpotifyAlbumSimple> = emptyList(),
    val error: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val results: SearchResults? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val filter: SearchFilter = SearchFilter.All,
)

data class PlayingExtrasState(
    val isTrackSaved: Boolean = false,
    val savePending: Boolean = false,
    val saveError: String? = null,
)

data class SettingsUiState(
    val streamingQuality: StreamingQuality = StreamingQuality.HIGH,
    val gaplessEnabled: Boolean = true,
    val normalizationEnabled: Boolean = false,
    val normalizationType: NormalizationType = NormalizationType.AUTO,
    val volumePercent: Int = 100,
    val proxy: String = "",
    val showAdvanced: Boolean = false,
)

data class PlaylistDetailTrackRow(
    val track: SpotifyTrack,
    val addedAt: String?,
    val uri: String,
)

data class PlaylistDetailState(
    val loading: Boolean = false,
    val detail: SpotifyPlaylistDetail? = null,
    val tracks: List<PlaylistDetailTrackRow> = emptyList(),
    val snapshotId: String? = null,
    val isEditable: Boolean = false,
    val editMode: Boolean = false,
    val mutating: Boolean = false,
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
    val error: String? = null,
    val statusMessage: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val controller: PlaybackController = (app as App).controller

    val playback: StateFlow<PlaybackUiState> = controller.state

    private val _likedTracks = MutableStateFlow(LibraryListUiState<LikedTrackEntity>())
    val likedTracks: StateFlow<LibraryListUiState<LikedTrackEntity>> = _likedTracks.asStateFlow()

    private val _savedAlbums = MutableStateFlow(LibraryListUiState<SavedAlbumEntity>())
    val savedAlbums: StateFlow<LibraryListUiState<SavedAlbumEntity>> = _savedAlbums.asStateFlow()

    private val _playlists = MutableStateFlow(LibraryListUiState<PlaylistEntity>())
    val playlists: StateFlow<LibraryListUiState<PlaylistEntity>> = _playlists.asStateFlow()

    private var likedTracksStarted = false
    private var savedAlbumsStarted = false
    private var playlistsStarted = false
    private var likedFillJob: Job? = null
    private var likedLookaheadJob: Job? = null
    private var savedFillJob: Job? = null
    private var savedLookaheadJob: Job? = null
    private var playlistsFillJob: Job? = null
    private var playlistsLookaheadJob: Job? = null
    private var likedFillRetries = 0
    private var savedFillRetries = 0
    private var playlistsFillRetries = 0

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

    private val _settings = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    private val _playlistDetail = MutableStateFlow(PlaylistDetailState())
    val playlistDetail: StateFlow<PlaylistDetailState> = _playlistDetail.asStateFlow()

    private val _createPlaylist = MutableStateFlow(CreatePlaylistState())
    val createPlaylist: StateFlow<CreatePlaylistState> = _createPlaylist.asStateFlow()

    private val _playlistPicker = MutableStateFlow(PlaylistPickerState())
    val playlistPicker: StateFlow<PlaylistPickerState> = _playlistPicker.asStateFlow()

    private var loadedPlaylistId: String? = null

    init {
        refreshSettings()
    }

    fun refreshSettings() {
        viewModelScope.launch {
            val snap = kotlinx.coroutines.withContext(Dispatchers.IO) {
                controller.loadSettings()
            }
            _settings.value = snap.toUiState(_settings.value.showAdvanced)
        }
    }

    fun beginLogin(): String = controller.beginLogin()

    fun completeLogin(code: String) {
        controller.completeLogin(code) { result ->
            if (result.isSuccess) onLoggedIn()
        }
    }

    fun saveWebApiCredentials(clientId: String, clientSecret: String) {
        controller.saveWebApiCredentials(clientId, clientSecret)
    }

    fun buildWebApiAuthorizeUrl(): String = controller.buildWebApiAuthorizeUrl()

    fun completeWebApiAuth(code: String, onResult: (Result<Unit>) -> Unit) {
        controller.completeWebApiAuth(code, onResult)
    }

    fun onLoggedIn() {
        // Library tabs load on first visit via ensureLikedTracksLoaded / ensureSavedAlbumsLoaded.
    }

    fun ensureLikedTracksLoaded() {
        if (likedTracksStarted) return
        likedTracksStarted = true
        _likedTracks.value = _likedTracks.value.copy(initialLoading = true)
        viewModelScope.launch {
            controller.likedTracksUiFlow().collect { (items, remoteTotal, hasMore) ->
                _likedTracks.update { it.copy(items = items, remoteTotal = remoteTotal, hasMore = hasMore) }
            }
        }
        refreshLikedTracks()
    }

    fun refreshLikedTracks() {
        if (scrubResyncLocked) { pendingLikedRefresh = true; return }
        if (likedFillJob?.isActive == true) return
        likedFillJob?.cancel()
        likedFillJob = null
        likedLookaheadJob?.cancel()
        likedLookaheadJob = null
        viewModelScope.launch {
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
                    _likedTracks.update { it.copy(error = e.message ?: "Could not load liked songs") }
                }
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

    private fun startLikedTracksFill() {
        if (likedFillJob?.isActive == true) return
        likedFillJob = viewModelScope.launch {
            _likedTracks.update { it.copy(appending = true) }
            try {
                runCatching { controller.fillRemainingLikedTracks() }
                    .onSuccess {
                        if (!controller.likedTracksNeedsFill()) {
                            likedFillRetries = 0
                        }
                    }
                    .onFailure { e ->
                        android.util.Log.e("Library", "fill liked tracks failed", e)
                        _likedTracks.update {
                            it.copy(error = it.error ?: "Library sync incomplete — pull to retry")
                        }
                        if (likedFillRetries < 3 && controller.likedTracksNeedsFill()) {
                            likedFillRetries++
                            viewModelScope.launch {
                                delay(2000L * likedFillRetries)
                                startLikedTracksFill()
                            }
                        }
                    }
            } finally {
                _likedTracks.update { it.copy(appending = false) }
            }
        }
    }

    fun resumeLikedTracksFillIfNeeded() {
        if (likedFillJob?.isActive == true) return
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
        _savedAlbums.value = _savedAlbums.value.copy(initialLoading = true)
        viewModelScope.launch {
            controller.savedAlbumsUiFlow().collect { (items, remoteTotal, hasMore) ->
                _savedAlbums.update { it.copy(items = items, remoteTotal = remoteTotal, hasMore = hasMore) }
            }
        }
        refreshSavedAlbums()
    }

    fun refreshSavedAlbums() {
        if (scrubResyncLocked) { pendingSavedRefresh = true; return }
        if (savedFillJob?.isActive == true) return
        savedFillJob?.cancel()
        savedFillJob = null
        savedLookaheadJob?.cancel()
        savedLookaheadJob = null
        viewModelScope.launch {
            val hadItems = _savedAlbums.value.items.isNotEmpty()
            if (!hadItems) {
                _savedAlbums.update { it.copy(initialLoading = true, error = null) }
            } else {
                _savedAlbums.update { it.copy(refreshing = true, error = null) }
            }
            runCatching { controller.refreshSavedAlbums() }
                .onFailure { e ->
                    _savedAlbums.update { it.copy(error = e.message ?: "Could not load albums") }
                }
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

    private fun startSavedAlbumsFill() {
        if (savedFillJob?.isActive == true) return
        savedFillJob = viewModelScope.launch {
            _savedAlbums.update { it.copy(appending = true) }
            try {
                runCatching { controller.fillRemainingSavedAlbums() }
                    .onSuccess {
                        if (!controller.savedAlbumsNeedsFill()) {
                            savedFillRetries = 0
                        }
                    }
                    .onFailure { e ->
                        android.util.Log.e("Library", "fill saved albums failed", e)
                        _savedAlbums.update {
                            it.copy(error = it.error ?: "Library sync incomplete — pull to retry")
                        }
                        if (savedFillRetries < 3 && controller.savedAlbumsNeedsFill()) {
                            savedFillRetries++
                            viewModelScope.launch {
                                delay(2000L * savedFillRetries)
                                startSavedAlbumsFill()
                            }
                        }
                    }
            } finally {
                _savedAlbums.update { it.copy(appending = false) }
            }
        }
    }

    fun resumeSavedAlbumsFillIfNeeded() {
        if (savedFillJob?.isActive == true) return
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
        _playlists.value = _playlists.value.copy(initialLoading = true)
        viewModelScope.launch {
            controller.playlistsUiFlow().collect { (items, remoteTotal, hasMore) ->
                _playlists.update { it.copy(items = items, remoteTotal = remoteTotal, hasMore = hasMore) }
            }
        }
        refreshPlaylists()
    }

    fun refreshPlaylists() {
        if (scrubResyncLocked) { pendingPlaylistsRefresh = true; return }
        if (playlistsFillJob?.isActive == true) return
        playlistsFillJob?.cancel()
        playlistsFillJob = null
        playlistsLookaheadJob?.cancel()
        playlistsLookaheadJob = null
        viewModelScope.launch {
            val hadItems = _playlists.value.items.isNotEmpty()
            if (!hadItems) {
                _playlists.update { it.copy(initialLoading = true, error = null) }
            } else {
                _playlists.update { it.copy(refreshing = true, error = null) }
            }
            runCatching { controller.refreshPlaylists() }
                .onFailure { e ->
                    _playlists.update { it.copy(error = e.message ?: "Could not load playlists") }
                }
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
        val items = _playlists.value.items
        if (items.isEmpty()) return
        if (targetIndex > items.lastIndex) {
            android.util.Log.w("Library", "scrub target $targetIndex > lastIndex ${items.lastIndex}")
            return
        }
        listState.scrollToItem(targetIndex)
    }

    private fun startPlaylistsFill() {
        if (playlistsFillJob?.isActive == true) return
        playlistsFillJob = viewModelScope.launch {
            _playlists.update { it.copy(appending = true) }
            try {
                runCatching { controller.fillRemainingPlaylists() }
                    .onSuccess {
                        if (!controller.playlistsNeedsFill()) {
                            playlistsFillRetries = 0
                        }
                    }
                    .onFailure { e ->
                        android.util.Log.e("Library", "fill playlists failed", e)
                        _playlists.update {
                            it.copy(error = it.error ?: "Library sync incomplete — pull to retry")
                        }
                        if (playlistsFillRetries < 3 && controller.playlistsNeedsFill()) {
                            playlistsFillRetries++
                            viewModelScope.launch {
                                delay(2000L * playlistsFillRetries)
                                startPlaylistsFill()
                            }
                        }
                    }
            } finally {
                _playlists.update { it.copy(appending = false) }
            }
        }
    }

    fun resumePlaylistsFillIfNeeded() {
        if (playlistsFillJob?.isActive == true) return
        viewModelScope.launch {
            if (controller.playlistsNeedsFill()) {
                playlistsFillRetries = 0
                startPlaylistsFill()
            }
        }
    }

    fun loadPlaylistDetail(playlistId: String) {
        if (loadedPlaylistId == playlistId && _playlistDetail.value.detail != null) return
        loadedPlaylistId = playlistId
        viewModelScope.launch {
            _playlistDetail.value = PlaylistDetailState(loading = true)
            runCatching { controller.playlistDetail(playlistId) }
                .onSuccess { result ->
                    _playlistDetail.value = PlaylistDetailState(
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
                    )
                }
                .onFailure { e ->
                    _playlistDetail.value = PlaylistDetailState(error = e.message ?: "Could not load playlist")
                }
        }
    }

    fun togglePlaylistEditMode() {
        _playlistDetail.update { it.copy(editMode = !it.editMode, mutationError = null) }
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
        viewModelScope.launch {
            _playlistPicker.value = PlaylistPickerState(trackUri = trackUri, loading = true)
            runCatching { controller.editablePlaylists() }
                .onSuccess { playlists ->
                    _playlistPicker.value = PlaylistPickerState(
                        trackUri = trackUri,
                        playlists = playlists,
                    )
                }
                .onFailure { e ->
                    _playlistPicker.value = PlaylistPickerState(
                        trackUri = trackUri,
                        error = e.message ?: "Could not load playlists",
                    )
                }
        }
    }

    fun addTrackToPlaylistFromPicker(playlistId: String, onAdded: () -> Unit) {
        val uri = _playlistPicker.value.trackUri
        if (uri.isBlank()) return
        viewModelScope.launch {
            _playlistPicker.update { it.copy(adding = true, error = null) }
            runCatching { controller.addTrackToPlaylist(playlistId, uri) }
                .onSuccess {
                    _playlistPicker.update { it.copy(adding = false, statusMessage = "Added to playlist") }
                    onAdded()
                }
                .onFailure { e ->
                    _playlistPicker.update {
                        it.copy(adding = false, error = e.message ?: "Could not add track")
                    }
                }
        }
    }

    fun loadAlbumDetail(albumId: String) {
        viewModelScope.launch {
            _albumDetail.value = AlbumDetailState(loading = true)
            runCatching { controller.albumDetail(albumId) }
                .onSuccess { result ->
                    _albumDetail.value = AlbumDetailState(
                        album = result.album,
                        isSaved = result.isSaved,
                    )
                }
                .onFailure { e ->
                    _albumDetail.value = AlbumDetailState(error = e.message ?: "Could not load album")
                }
        }
    }

    fun toggleAlbumSave(albumId: String) {
        viewModelScope.launch {
            val current = _albumDetail.value
            if (current.saving) return@launch
            _albumDetail.value = current.copy(saving = true)
            val result = runCatching {
                if (current.isSaved) controller.removeAlbum(albumId) else controller.saveAlbum(albumId)
            }
            _albumDetail.value = current.copy(
                saving = false,
                isSaved = if (result.isSuccess) !current.isSaved else current.isSaved,
                error = result.exceptionOrNull()?.message,
            )
        }
    }

    fun loadArtistDetail(artistId: String) {
        viewModelScope.launch {
            _artistDetail.value = ArtistDetailState(loading = true)
            runCatching { controller.artistDetail(artistId) }
                .onSuccess { result ->
                    _artistDetail.value = ArtistDetailState(
                        artist = result.artist,
                        topTracks = result.topTracks,
                        albums = result.albums,
                    )
                }
                .onFailure { e ->
                    _artistDetail.value = ArtistDetailState(error = e.message ?: "Could not load artist")
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
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _search.value = _search.value.copy(
                loading = true,
                query = trimmed,
                error = null,
                filter = SearchFilter.All,
            )
            runCatching {
                withTimeout(SEARCH_TIMEOUT_MS) { controller.search(trimmed) }
            }
                .onSuccess { results ->
                    _search.value = _search.value.copy(
                        query = trimmed,
                        results = results,
                        loading = false,
                        error = if (results.isEmpty()) "No results" else null,
                        filter = SearchFilter.All,
                    )
                }
                .onFailure { e ->
                    val message = when (e) {
                        is TimeoutCancellationException -> "Search timed out — try again."
                        else -> e.message ?: "Search failed"
                    }
                    _search.value = _search.value.copy(
                        query = trimmed,
                        loading = false,
                        error = message,
                    )
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
        onPlayPlaylist: (String, String?) -> Unit,
    ) {
        when (item) {
            is SearchResultItem.Track -> onPlayTrack(item)
            is SearchResultItem.Album -> onOpenAlbum(item.album.id, item.album.name)
            is SearchResultItem.Artist -> onOpenArtist(item.artist.id)
            is SearchResultItem.Playlist -> onPlayPlaylist(item.playlist.id, item.playlist.name)
        }
    }

    fun playArtistTopTrack(index: Int) {
        val tracks = _artistDetail.value.topTracks.map { it.toMetadata() }
        val label = _artistDetail.value.artist?.name
        playTracks(tracks, index, label)
    }

    fun refreshPlayingScreen() {
        val uri = playback.value.currentUri ?: return
        controller.refreshNowPlayingFromWebApi()
        viewModelScope.launch {
            _playingExtras.value = _playingExtras.value.copy(saveError = null)
            val saved = runCatching { controller.isTrackSaved(uri) }
                .getOrElse { e ->
                    _playingExtras.value = _playingExtras.value.copy(
                        saveError = e.message ?: "Could not check liked status",
                    )
                    return@launch
                }
            _playingExtras.value = _playingExtras.value.copy(isTrackSaved = saved)
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
            _playingExtras.value = PlayingExtrasState(
                isTrackSaved = if (result.isSuccess) !wasSaved else wasSaved,
                savePending = false,
                saveError = result.exceptionOrNull()?.message,
            )
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
    fun logout() = controller.logout()

    fun setStreamingQuality(quality: StreamingQuality) {
        _settings.value = _settings.value.copy(streamingQuality = quality)
        controller.setStreamingQuality(quality)
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

    fun setVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _settings.value = _settings.value.copy(volumePercent = clamped)
        controller.setVolume(clamped)
    }

    fun setProxy(proxy: String) {
        _settings.value = _settings.value.copy(proxy = proxy)
        val trimmed = proxy.trim()
        controller.setProxy(trimmed.ifEmpty { null })
    }

    fun toggleAdvancedSettings() {
        _settings.value = _settings.value.copy(showAdvanced = !_settings.value.showAdvanced)
    }

    fun clearAudioCache() = controller.clearAudioCache()

    companion object {
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val LOOKAHEAD_ROWS = 150
    }
}

private fun SettingsSnapshot.toUiState(showAdvanced: Boolean) = SettingsUiState(
    streamingQuality = streamingQuality,
    gaplessEnabled = gaplessEnabled,
    normalizationEnabled = normalizationEnabled,
    normalizationType = normalizationType,
    volumePercent = volumePercent,
    proxy = proxy.orEmpty(),
    showAdvanced = showAdvanced,
)
