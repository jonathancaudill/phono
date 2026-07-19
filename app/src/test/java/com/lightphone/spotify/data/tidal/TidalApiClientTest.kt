package com.lightphone.spotify.data.tidal

import com.lightphone.spotify.data.webapi.InMemorySharedPreferences
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class TidalApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: InMemorySharedPreferences

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString("access_token", "test-access")
            .putString("refresh_token", "test-refresh")
            .putLong("expires_at_ms", System.currentTimeMillis() + 3_600_000L)
            .putString("user_id", "42")
            .putString("country_code", "US")
            .apply()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(): TidalApiClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val auth = TidalAuth.createForTest(prefs, http, "http://unused", "http://unused")
        return TidalApiClient(auth, baseUrl = server.url("/v1").toString().trimEnd('/'))
    }

    @Test
    fun search_sendsBearerAndCountryCode_mapsResults() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "tracks": {"items": [{"id": 1, "title": "T", "duration": 100,
                     "artists": [{"id": 9, "name": "A"}], "album": {"id": 3, "title": "Alb"}}],
                     "totalNumberOfItems": 1},
                  "albums": {"items": [{"id": 3, "title": "Alb"}], "totalNumberOfItems": 1},
                  "artists": {"items": [{"id": 9, "name": "A"}], "totalNumberOfItems": 1},
                  "playlists": {"items": [], "totalNumberOfItems": 0}
                }
                """.trimIndent(),
            ),
        )

        val results = client().search("hello", limitPerType = 8)

        val request = server.takeRequest()
        assertEquals("Bearer test-access", request.getHeader("Authorization"))
        assertEquals(TidalAuth.DEFAULT_CLIENT_ID, request.getHeader("X-Tidal-Token"))
        assertTrue(request.path!!.contains("countryCode=US"))
        assertTrue(request.path!!.contains("query=hello"))
        assertEquals("tidal:track:1", results.tracks?.items?.first()?.uri)
        assertEquals("tidal:album:3", results.albums?.items?.first()?.uri)
        assertEquals("tidal:artist:9", results.artists?.items?.first()?.uri)
    }

    @Test
    fun savedTracksPage_mapsFavoritesEnvelope() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"items": [{"created": "2024-01-01", "item": {"id": 5, "title": "Fav"}}],
                 "totalNumberOfItems": 137}
                """.trimIndent(),
            ),
        )

        val page = kotlinx.coroutines.runBlocking { client().savedTracksPage(offset = 0) }

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/users/42/favorites/tracks"))
        assertEquals(137, page.total)
        assertEquals("tidal:track:5", page.items.first().track?.uri)
    }

    @Test
    fun addFavoriteTrack_postsFormWithTrackId() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client().addFavoriteTrack("777")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("/users/42/favorites/tracks"))
        assertTrue(request.body.readUtf8().contains("trackIds=777"))
    }

    @Test
    fun playlistETag_returnsResponseHeader() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"etag-123\"")
                .setBody("""{"items": [], "totalNumberOfItems": 0}"""),
        )

        val etag = client().playlistETag("abc-uuid")

        assertEquals("etag-123", etag)
    }

    @Test
    fun retriesOn429_thenSucceeds() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 3, "title": "Song", "duration": 10}"""))

        val track = client().track("3")

        assertEquals("tidal:track:3", track.uri)
        assertEquals(2, server.requestCount)
    }
}
