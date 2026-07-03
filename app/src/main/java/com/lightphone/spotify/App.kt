package com.lightphone.spotify

import android.app.Application
import com.lightphone.spotify.playback.PlaybackController

class App : Application() {
    lateinit var controller: PlaybackController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = PlaybackController.get(this)
        controller.ensureEngineReady()
    }
}
