package com.lightphone.spotify

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.lightphone.spotify.data.backend.BackendPreferences
import com.lightphone.spotify.playback.PlaybackController
import com.lightphone.spotify.playback.download.OfflinePinHygiene
import com.lightphone.spotify.ui.light.ThemePreferences

class App : Application() {
    /**
     * Null until a backend is chosen at first launch. The controller is
     * backend-specific (Spotify vs TIDAL), so it must not be built until the
     * [BackendPreferences] choice exists — see [ensureController].
     */
    var controller: PlaybackController? = null
        private set

    override fun onCreate() {
        super.onCreate()
        ThemePreferences(this).applyToController()
        if (BackendPreferences(this).isChosen()) {
            ensureController()
        }
    }

    /** Build the controller for the persisted backend choice (idempotent). */
    fun ensureController(): PlaybackController {
        controller?.let { return it }
        OfflinePinHygiene.enforce(this)
        val c = PlaybackController.get(this)
        controller = c
        if (!foregroundObserverRegistered) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(AppForegroundObserver(this))
            foregroundObserverRegistered = true
        }
        return c
    }

    /** Drop the controller after logout so the service picker can rebuild for a new choice. */
    fun clearController() {
        controller = null
        PlaybackController.clearInstance()
    }

    companion object {
        @Volatile
        private var foregroundObserverRegistered = false
    }
}
