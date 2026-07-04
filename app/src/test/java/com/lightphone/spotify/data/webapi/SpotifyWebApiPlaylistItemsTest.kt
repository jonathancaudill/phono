package com.lightphone.spotify.data.webapi

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * These guard clauses reject empty URI lists before any network call is made,
 * so they can be exercised without a live HTTP layer.
 */
class SpotifyWebApiPlaylistItemsTest {

    private val webApi = SpotifyWebApi(mock<WebApiAuth>())

    @Test
    fun addPlaylistItems_withEmptyUris_throwsWithoutNetworkCall() {
        val error = assertThrows(IllegalStateException::class.java) {
            webApi.addPlaylistItems(playlistId = "playlist123", uris = emptyList())
        }

        assertTrue(error.message.orEmpty().contains("No URIs to add"))
    }

    @Test
    fun removePlaylistItems_withEmptyUris_throwsWithoutNetworkCall() {
        val error = assertThrows(IllegalStateException::class.java) {
            webApi.removePlaylistItems(playlistId = "playlist123", uris = emptyList())
        }

        assertTrue(error.message.orEmpty().contains("removePlaylistItems requires at least one URI"))
    }
}