package com.lightphone.spotify.playback.download

import android.content.Context
import android.util.Log
import com.lightphone.spotify.data.local.PhonoDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Low-lift TOS guard: if Phono has not seen a network for 30 days, wipe offline
 * download pins (Spotify + TIDAL). Streaming cache and credentials are untouched.
 */
object OfflinePinHygiene {
    private const val TAG = "OfflinePinHygiene"
    private const val PREFS = "phono_offline_hygiene"
    private const val KEY_LAST_ONLINE_MS = "last_online_ms"
    private const val MAX_OFFLINE_MS = 30L * 24 * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Call whenever the device has a usable network. */
    fun markOnline(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ONLINE_MS, System.currentTimeMillis())
            .apply()
    }

    /**
     * Seed last-online on first run; if stale, wipe pins asynchronously.
     * Safe to call on every cold start.
     */
    fun enforce(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_ONLINE_MS, 0L)
        if (last == 0L) {
            prefs.edit().putLong(KEY_LAST_ONLINE_MS, now).apply()
            return
        }
        if (now - last < MAX_OFFLINE_MS) return
        Log.w(TAG, "no network for 30+ days — wiping offline pins")
        scope.launch {
            wipePins(app)
            prefs.edit().putLong(KEY_LAST_ONLINE_MS, System.currentTimeMillis()).apply()
        }
    }

    private suspend fun wipePins(context: Context) {
        runCatching {
            val db = PhonoDatabase.get(context)
            db.downloadedCollectionDao().clearAll()
            db.downloadedTrackDao().clearAll()
        }.onFailure { Log.e(TAG, "Room wipe failed", it) }

        for (name in listOf("tidal-downloads", "tidal-mpd", "spotify-downloads")) {
            runCatching { File(context.filesDir, name).deleteRecursively() }
                .onFailure { Log.e(TAG, "delete $name failed", it) }
        }
    }
}
