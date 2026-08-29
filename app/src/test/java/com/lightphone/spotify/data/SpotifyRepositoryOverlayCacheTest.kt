package com.lightphone.spotify.data

import com.lightphone.spotify.data.native.FakeNativeMetadataGateway
import com.lightphone.spotify.data.webapi.InMemorySharedPreferences
import com.lightphone.spotify.data.webapi.WebApiAuth
import com.lightphone.spotify.ffi.AlbumSummaryNative
import com.lightphone.spotify.ffi.ArtistDetailBundle
import com.lightphone.spotify.ffi.TrackInfo
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SpotifyRepositoryOverlayCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var repository: SpotifyRepository
    private lateinit var gateway: FakeNativeMetadataGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString("client_id", "test-client")
            .putString("client_secret", "test-secret")
            .putString("access_token", "test-access")
            .putString("refresh_token", "refresh-1")
            .putLong("expires_at_ms", System.currentTimeMillis() + 3_600_000L)
            .apply()
        val auth = WebApiAuth.createForTest(
            prefs = prefs,
            tokenClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
            tokenEndpoint = "http://127.0.0.1:${server.port}/token",
        )
        val webApi = com.lightphone.spotify.data.webapi.SpotifyWebApi(
            auth,
            baseUrl = server.url("/v1/").toString().removeSuffix("/"),
        )
        repository = SpotifyRepositoryTestHarness.create(webApi)
        gateway = FakeNativeMetadataGateway(loggedIn = true)
        gateway.artistDetailHandler = { id -> artistBundle(id) }
        repository.nativeMetadata = gateway
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun artistDetail_secondCallHitsCache() {
        val first = repository.artistDetail("a1")
        val second = repository.artistDetail("a1")
        assertEquals("Artist a1", first.artist.name)
        assertEquals(first, second)
        assertEquals(1, gateway.artistDetailCallCount)
        assertEquals(first, repository.cachedArtistDetail("a1"))
    }

    @Test
    fun artistDetail_differentIdFetchesAgain() {
        repository.artistDetail("a1")
        repository.artistDetail("a2")
        assertEquals(2, gateway.artistDetailCallCount)
        assertNotNull(repository.cachedArtistDetail("a1"))
        assertNotNull(repository.cachedArtistDetail("a2"))
    }

    @Test
    fun clearSessionCaches_dropsArtistPeek() {
        repository.artistDetail("a1")
        assertNotNull(repository.cachedArtistDetail("a1"))
        repository.clearSessionCaches()
        assertNull(repository.cachedArtistDetail("a1"))
        repository.artistDetail("a1")
        assertEquals(2, gateway.artistDetailCallCount)
    }

    private fun artistBundle(id: String) = ArtistDetailBundle(
        id = id,
        name = "Artist $id",
        imageUrl = null,
        genres = emptyList(),
        topTracks = listOf(
            TrackInfo(
                uri = "spotify:track:t$id",
                title = "Song",
                artists = "Artist $id",
                album = "Album",
                durationMs = 180_000,
                artUrl = null,
            ),
        ),
        albums = listOf(
            AlbumSummaryNative(
                id = "al$id",
                name = "LP",
                uri = "spotify:album:al$id",
                imageUrl = null,
                albumType = "album",
            ),
        ),
        singles = emptyList(),
    )
}
