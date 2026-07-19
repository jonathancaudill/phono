package com.lightphone.spotify

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Marks the app visible to the Rust reconnect monitor and warms the librespot
 * session when the user opens the app (before playlist delta sync).
 *
 * Resolves the controller from [App] each time so logout → re-pick backend still
 * talks to the live instance (not a torn-down singleton).
 */
class AppForegroundObserver(
    private val app: App,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        val controller = app.controller ?: return
        controller.setAppForeground(true)
        controller.warmSpclientSessionAsync()
    }

    override fun onStop(owner: LifecycleOwner) {
        app.controller?.setAppForeground(false)
    }
}
