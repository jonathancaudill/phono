package com.lightphone.spotify.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground service that hosts the Media3 [MediaSession] backed by
 * [LibrespotPlayer]. Gives the OS lock-screen / notification media controls
 * while audio is produced natively by librespot.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val controller = PlaybackController.get(this)
        val player = LibrespotPlayer(controller)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
