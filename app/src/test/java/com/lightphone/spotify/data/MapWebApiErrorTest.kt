package com.lightphone.spotify.data

import com.lightphone.spotify.data.webapi.WebApiAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapWebApiErrorTest {

    @Test
    fun http401_mapsToReauthorizeMessage() {
        val msg = mapWebApiError(Exception("HTTP 401: Unauthorized"))
        assertEquals("Web API session expired — re-authorize Step 2.", msg)
    }

    @Test
    fun http403_playlistItems_mapsToPolicyMessage() {
        val msg = mapWebApiError(
            Exception("HTTP 403: Forbidden https://api.spotify.com/v1/playlists/abc/items"),
        )
        assertTrue(msg.contains("aren't available through the Web API"))
        assertTrue(msg.contains("own or collaborate"))
    }

    @Test
    fun http403_playlistMetadata_mapsToPlaylistUnavailable() {
        val msg = mapWebApiError(
            Exception("HTTP 403: Forbidden https://api.spotify.com/v1/playlists/abc"),
        )
        assertEquals("This playlist isn't available through the Web API.", msg)
    }

    @Test
    fun http403_other_mapsToGenericDenied() {
        val msg = mapWebApiError(Exception("HTTP 403: Forbidden"))
        assertEquals("Spotify denied access to this content.", msg)
    }

    @Test
    fun webApiAuthException_preservesMessage() {
        val msg = mapWebApiError(
            WebApiAuthException("Session expired — re-authorize your dev app in Step 2"),
        )
        assertEquals("Session expired — re-authorize your dev app in Step 2", msg)
    }
}
