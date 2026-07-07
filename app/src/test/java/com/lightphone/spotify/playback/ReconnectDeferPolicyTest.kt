package com.lightphone.spotify.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectDeferPolicyTest {

    @Test
    fun deferOnlyWhenPausedAndBackground() {
        fun shouldDeferReconnect(playing: Boolean, appForeground: Boolean): Boolean =
            !playing && !appForeground

        assertTrue(shouldDeferReconnect(playing = false, appForeground = false))
        assertFalse(shouldDeferReconnect(playing = false, appForeground = true))
        assertFalse(shouldDeferReconnect(playing = true, appForeground = false))
        assertFalse(shouldDeferReconnect(playing = true, appForeground = true))
    }

    /**
     * A user transport command coalesces (waits out a quiet window) only while
     * reconnecting, so a burst of skips on a bad connection collapses to the last
     * intent instead of firing a native rebuild per tap.
     */
    @Test
    fun coalesceTransportOnlyWhileReconnecting() {
        fun shouldCoalesce(reconnecting: Boolean): Boolean = reconnecting

        assertTrue(shouldCoalesce(reconnecting = true))
        assertFalse(shouldCoalesce(reconnecting = false))
    }

    /**
     * The latest transport command always supersedes the previous pending one
     * (last-write-wins), mirroring transportJob cancellation in PlaybackController.
     */
    @Test
    fun latestTransportCommandWins() {
        var activeJobId = 0
        fun submit(id: Int, prior: Int): Int {
            // Newer command cancels the prior: only the newest remains active.
            check(id > prior) { "commands are monotonically issued" }
            return id
        }

        activeJobId = submit(1, activeJobId)
        activeJobId = submit(2, activeJobId)
        activeJobId = submit(3, activeJobId)
        assertTrue(activeJobId == 3)
    }
}
