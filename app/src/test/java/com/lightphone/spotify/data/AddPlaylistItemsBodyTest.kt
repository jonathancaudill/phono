package com.lightphone.spotify.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AddPlaylistItemsBody.snapshotId] was added so add-track requests can be sent
 * with an optimistic-concurrency snapshot_id, matching Spotify's Web API contract.
 */
class AddPlaylistItemsBodyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun encode_withSnapshotId_includesSnapshotIdField() {
        val body = AddPlaylistItemsBody(uris = listOf("spotify:track:1"), snapshotId = "snap-abc")

        val encoded = json.encodeToString(AddPlaylistItemsBody.serializer(), body)

        assertTrue(encoded.contains("\"snapshot_id\":\"snap-abc\""))
    }

    @Test
    fun encode_withoutSnapshotId_omitsSnapshotIdField() {
        val body = AddPlaylistItemsBody(uris = listOf("spotify:track:1"))

        val encoded = json.encodeToString(AddPlaylistItemsBody.serializer(), body)

        assertFalse(encoded.contains("snapshot_id"))
    }

    @Test
    fun encode_includesPositionAndUrisAsBefore() {
        val body = AddPlaylistItemsBody(uris = listOf("spotify:track:1", "spotify:track:2"), position = 3)

        val encoded = json.encodeToString(AddPlaylistItemsBody.serializer(), body)

        assertTrue(encoded.contains("\"position\":3"))
        assertTrue(encoded.contains("spotify:track:1"))
        assertTrue(encoded.contains("spotify:track:2"))
    }

    @Test
    fun decode_jsonWithoutSnapshotId_defaultsToNull() {
        val decoded = json.decodeFromString(
            AddPlaylistItemsBody.serializer(),
            """{"uris":["spotify:track:1"]}""",
        )

        assertNull(decoded.snapshotId)
    }

    @Test
    fun decode_jsonWithSnapshotId_populatesField() {
        val decoded = json.decodeFromString(
            AddPlaylistItemsBody.serializer(),
            """{"uris":["spotify:track:1"],"snapshot_id":"snap-xyz"}""",
        )

        assertEquals("snap-xyz", decoded.snapshotId)
    }

    @Test
    fun roundTrip_preservesAllFields() {
        val original = AddPlaylistItemsBody(
            uris = listOf("spotify:track:1", "spotify:track:2"),
            position = 5,
            snapshotId = "snap-round-trip",
        )

        val encoded = json.encodeToString(AddPlaylistItemsBody.serializer(), original)
        val decoded = json.decodeFromString(AddPlaylistItemsBody.serializer(), encoded)

        assertEquals(original, decoded)
    }
}