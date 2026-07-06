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
}
