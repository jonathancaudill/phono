package com.lightphone.spotify.playback.download

import android.content.Context

/** Persists Fast / Balanced / Careful independently of playback settings.json. */
class DownloadPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun mode(): DownloadPaceMode {
        val raw = prefs.getString(KEY_MODE, DownloadPaceMode.BALANCED.name) ?: DownloadPaceMode.BALANCED.name
        return runCatching { DownloadPaceMode.valueOf(raw) }.getOrDefault(DownloadPaceMode.BALANCED)
    }

    fun setMode(mode: DownloadPaceMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "phono_download_pacing"
        private const val KEY_MODE = "pace_mode"
    }
}
