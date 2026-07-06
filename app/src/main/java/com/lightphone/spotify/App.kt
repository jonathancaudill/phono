package com.lightphone.spotify

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.lightphone.spotify.playback.PlaybackController
import com.lightphone.spotify.ui.light.ThemePreferences

class App : Application() {
    lateinit var controller: PlaybackController
        private set

    override fun onCreate() {
        super.onCreate()
        ThemePreferences(this).applyToController()
        controller = PlaybackController.get(this)
        if (!foregroundObserverRegistered) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                AppForegroundObserver(controller),
            )
            foregroundObserverRegistered = true
        }
    }

    companion object {
        @Volatile
        private var foregroundObserverRegistered = false
    }
}
