package com.lightphone.spotify.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Bridges Media3's [Player] surface (for the MediaSession / lock-screen controls)
 * onto the native librespot engine via [PlaybackController]. Audio itself is
 * produced in Rust by rodio; this player only mirrors state and forwards
 * transport commands.
 */
class LibrespotPlayer(
    private val controller: PlaybackController,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Rust player events arrive on a background thread; Media3 requires main looper.
        controller.onStateChanged = { mainHandler.post { invalidateState() } }
    }

    override fun getState(): State {
        val s = controller.state.value

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_STOP,
            )
            .build()

        val playbackState = when {
            s.currentUri == null -> Player.STATE_IDLE
            s.isLoading -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(s.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setIsLoading(s.isLoading)
            .setContentPositionMs(s.positionMs)

        if (s.currentUri != null) {
            val metadata = MediaMetadata.Builder()
                .setTitle(s.title ?: "")
                .setArtist(s.artist ?: "")
                .apply { s.artUrl?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
            val item = MediaItem.Builder()
                .setUri(s.currentUri)
                .setMediaId(s.currentUri)
                .setMediaMetadata(metadata)
                .build()
            val durationUs = if (s.durationMs > 0) s.durationMs * 1000 else C.TIME_UNSET
            val data = MediaItemData.Builder(s.currentUri)
                .setMediaItem(item)
                .setDurationUs(durationUs)
                .build()
            builder.setPlaylist(listOf(data))
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.resume() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.next()
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.previous()
            else -> controller.seek(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        controller.onStateChanged = null
        return Futures.immediateVoidFuture()
    }
}
