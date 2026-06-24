package com.lightphone.spotify.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightphone.spotify.App
import com.lightphone.spotify.data.SearchFilter
import com.lightphone.spotify.data.SearchResults
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.data.SpotifyAlbumDetail
import com.lightphone.spotify.data.SpotifyArtistDetail
import com.lightphone.spotify.data.SpotifyTrack
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.LikedTrackEntity
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

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val controller: PlaybackController = (app as App).controller

    val playback: StateFlow<PlaybackUiState> = controller.state

    private val _likedTracks = MutableStateFlow(LibraryListUiState<LikedTrackEntity>())
    val likedTracks: StateFlow<LibraryListUiState<LikedTrackEntity>> = _likedTracks.asStateFlow()

    private val _savedAlbums = MutableStateFlow(LibraryListUiState<SavedAlbumEntity>())
    val savedAlbums: StateFlow<LibraryListUiState<SavedAlbumEntity>> = _savedAlbums.asStateFlow()

    private var likedTracksStarted = false
    private var savedAlbumsStarted = false
    private var likedFillJob: Job? = null
    private var likedLookaheadJob: Job? = null
    private var savedFillJob: Job? = null
    private var savedLookaheadJob: Job? = null

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
                    .onFailure { e ->
                        android.util.Log.e("Library", "fill liked tracks failed", e)
                    }
            } finally {
                _likedTracks.update { it.copy(appending = false) }
            }
        }
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

    private fun startSavedAlbumsFill() {
        if (savedFillJob?.isActive == true) return
        savedFillJob = viewModelScope.launch {
            _savedAlbums.update { it.copy(appending = true) }
            try {
                runCatching { controller.fillRemainingSavedAlbums() }
                    .onFailure { e ->
                        android.util.Log.e("Library", "fill saved albums failed", e)
                    }
            } finally {
                _savedAlbums.update { it.copy(appending = false) }
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

    fun playTracks(tracks: List<TrackMetadata>, startIndex: Int) {
        if (tracks.isEmpty()) return
        controller.ensureServiceStarted()
        controller.play(tracks, startIndex)
    }

    fun playLikedFrom(index: Int) {
        viewModelScope.launch {
            val tracks = runCatching { controller.likedTracksForPlayback(index) }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                playTracks(tracks, 0)
            }
        }
    }

    fun playAlbumFrom(albumId: String, trackIndex: Int) {
        viewModelScope.launch {
            val detail = _albumDetail.value.album
            val tracks = detail?.tracks?.items?.map { it.toMetadata() }
                ?: runCatching { controller.albumTracks(albumId) }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                playTracks(tracks, trackIndex.coerceIn(0, tracks.lastIndex))
            }
        }
    }

    fun playSearchTrack(item: SearchResultItem.Track) {
        playTracks(listOf(item.track.toMetadata()), 0)
    }

    fun playSearchPlaylist(playlistId: String, onStarted: () -> Unit = {}) {
        viewModelScope.launch {
            val tracks = runCatching { controller.playlistTracks(playlistId) }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                playTracks(tracks, 0)
                onStarted()
            }
        }
    }

    fun openSearchResult(
        item: SearchResultItem,
        onOpenAlbum: (String, String) -> Unit,
        onOpenArtist: (String) -> Unit,
        onPlayTrack: (SearchResultItem.Track) -> Unit,
        onPlayPlaylist: (String) -> Unit,
    ) {
        when (item) {
            is SearchResultItem.Track -> onPlayTrack(item)
            is SearchResultItem.Album -> onOpenAlbum(item.album.id, item.album.name)
            is SearchResultItem.Artist -> onOpenArtist(item.artist.id)
            is SearchResultItem.Playlist -> onPlayPlaylist(item.playlist.id)
        }
    }

    fun playArtistTopTrack(index: Int) {
        val tracks = _artistDetail.value.topTracks.map { it.toMetadata() }
        playTracks(tracks, index)
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
    fun seek(positionMs: Long) = controller.seek(positionMs)
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
