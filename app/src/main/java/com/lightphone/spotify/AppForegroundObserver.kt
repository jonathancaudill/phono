package com.lightphone.spotify

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.lightphone.spotify.playback.PlaybackController

/**
 * Marks the app visible to the Rust reconnect monitor and warms the librespot
 * session when the user opens the app (before playlist delta sync).
 */
class AppForegroundObserver(
    private val controller: PlaybackController,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        controller.setAppForeground(true)
        controller.warmSpclientSessionAsync()
    }

    override fun onStop(owner: LifecycleOwner) {
        controller.setAppForeground(false)
    }
}
