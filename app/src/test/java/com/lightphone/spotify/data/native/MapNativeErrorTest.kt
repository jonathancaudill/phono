package com.lightphone.spotify.data.native

import com.lightphone.spotify.ffi.SpotifyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNativeErrorTest {

    @Test
    fun nativeSessionRequired_hasPlaybackMessage() {
        val msg = mapNativeError(NativeSessionRequiredException())
        assertTrue(msg.contains("playback", ignoreCase = true))
    }

    @Test
    fun spotifyExceptionNotLoggedIn_mapsPlaybackMessage() {
        val msg = mapNativeError(SpotifyException.NotLoggedIn())
        assertTrue(msg.contains("playback", ignoreCase = true))
    }

    @Test
    fun revisionConflict_mentionsPlaylistChanged() {
        val msg = mapNativeError(IllegalStateException("revision conflict on apply"))
        assertTrue(msg.contains("changed", ignoreCase = true))
    }

    @Test
    fun genericException_usesClassNameFallback() {
        val msg = mapNativeError(RuntimeException(""))
        assertTrue(msg.contains("RuntimeException"))
        assertTrue(msg.contains("try again", ignoreCase = true))
    }
}
