package com.lightphone.spotify.ui.light

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-only artwork visibility prefs (not part of playback settings). Plain
 * SharedPreferences like [ThemePreferences], republished as process-wide flows
 * so list items and the now-playing screen react live to changes in Settings.
 *
 * Construct once (e.g. from App.onCreate) to prime the flows from disk; construct
 * again anywhere to flip a toggle.
 */
class ArtworkPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        synchronized(lock) {
            if (!loaded) {
                _listThumbnails.value = prefs.getBoolean(KEY_LIST, true)
                _nowPlayingArtwork.value = prefs.getBoolean(KEY_NOW_PLAYING, true)
                loaded = true
            }
        }
    }

    fun setListThumbnails(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIST, enabled).apply()
        _listThumbnails.value = enabled
    }

    fun setNowPlayingArtwork(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOW_PLAYING, enabled).apply()
        _nowPlayingArtwork.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "phono_artwork"
        private const val KEY_LIST = "list_thumbnails"
        private const val KEY_NOW_PLAYING = "now_playing_artwork"

        private val lock = Any()
        @Volatile
        private var loaded = false

        private val _listThumbnails = MutableStateFlow(true)

        /** Show cover thumbnails in track/album/playlist list rows. */
        val listThumbnails: StateFlow<Boolean> = _listThumbnails.asStateFlow()

        private val _nowPlayingArtwork = MutableStateFlow(true)

        /** Show the album cover on the now-playing screen. */
        val nowPlayingArtwork: StateFlow<Boolean> = _nowPlayingArtwork.asStateFlow()
    }
}
