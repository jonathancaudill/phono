package com.lightphone.spotify.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lightphone.spotify.R

/**
 * Foreground service that hosts the Media3 [MediaSession] backed by
 * [LibrespotPlayer]. Engine creation is deferred until first playback or login.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        promoteToForeground(getString(R.string.playback_notification_initializing))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            promoteToForeground(getString(R.string.playback_notification_initializing))
        }
        ensureEngineAndSession()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        ensureEngineAndSession()
        return mediaSession
    }

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        val s = PlaybackController.get(this).state.value
        val shouldForeground = startInForegroundRequired || s.isPlaying || s.isLoading
        if (shouldForeground && !foregroundStarted) {
            promoteToForeground()
        }
        super.onUpdateNotification(session, shouldForeground)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val s = PlaybackController.get(this).state.value
        if (s.isPlaying || s.isLoading) {
            return
        }
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        PlaybackEngineHolder.clearService()
        foregroundStarted = false
        super.onDestroy()
    }

    private fun ensureEngineAndSession() {
        if (mediaSession != null) return
        val controller = PlaybackController.get(this)
        PlaybackEngineHolder.ensureEngineAttached(this, controller)
        if (mediaSession != null) return
        mediaSession = MediaSession.Builder(this, LibrespotPlayer(controller)).build()
        PlaybackEngineHolder.markServiceReady()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.playback_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.playback_notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun promoteToForeground(
        contentText: String = getString(R.string.playback_notification_fallback),
    ) {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (!foregroundStarted) {
            Log.i(TAG, "startForeground (first promotion)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val NOTIFICATION_CHANNEL_ID = "phono_playback"
        private const val NOTIFICATION_ID = 0x70686f6e // "phon"
    }
}
