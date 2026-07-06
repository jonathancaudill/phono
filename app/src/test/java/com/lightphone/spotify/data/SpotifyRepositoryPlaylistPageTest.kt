package com.lightphone.spotify.data

import android.content.SharedPreferences
import com.lightphone.spotify.data.native.FakeNativeMetadataGateway
import com.lightphone.spotify.data.webapi.WebApiAuth
import com.lightphone.spotify.ffi.SpotifyException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SpotifyRepositoryPlaylistPageTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var repository: SpotifyRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        prefs = InMemorySharedPreferences()
        seedTokens(access = "test-access", refresh = "refresh-1", expiresInSec = 3600)
        val auth = createAuth()
        val webApi = com.lightphone.spotify.data.webapi.SpotifyWebApi(
            auth,
            baseUrl = server.url("/v1/").toString().removeSuffix("/"),
        )
        repository = SpotifyRepositoryTestHarness.create(webApi)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun playlistLibraryPage_nativeFail_fallsBackToWebApi() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"items":[{"id":"web1","name":"From Web API","uri":"spotify:playlist:web1"}],"total":1,"limit":50,"offset":0}""",
                ),
        )
        val gateway = FakeNativeMetadataGateway(
            loggedIn = true,
            rootlistError = SpotifyException.NotLoggedIn(),
        )
        repository.nativeMetadata = gateway

        val page = repository.playlistLibraryPage(offset = 0, limit = 50)

        assertTrue(gateway.rootlistCallCount >= 1)
        assertEquals(1, server.requestCount)
        assertEquals("/v1/me/playlists?limit=50&offset=0", server.takeRequest().path)
        assertEquals("web1", page.items.single().id)
    }

    @Test
    fun currentUserId_nativeFail_fallsBackToWebApi() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"web-user","display_name":"Web User"}"""),
        )
        repository.nativeMetadata = FakeNativeMetadataGateway(
            loggedIn = true,
            usernameError = SpotifyException.NotLoggedIn(),
        )

        assertEquals("web-user", repository.currentUserId())
        assertEquals("/v1/me", server.takeRequest().path)
    }

    private fun createAuth(): WebApiAuth {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        prefs.edit()
            .putString("client_id", "test-client")
            .putString("client_secret", "test-secret")
            .apply()
        return WebApiAuth.createForTest(
            prefs = prefs,
            tokenClient = client,
            tokenEndpoint = "http://127.0.0.1:${server.port}/token",
        )
    }

    private fun seedTokens(access: String, refresh: String, expiresInSec: Long) {
        prefs.edit()
            .putString("client_id", "test-client")
            .putString("client_secret", "test-secret")
            .putString("access_token", access)
            .putString("refresh_token", refresh)
            .putLong("expires_at_ms", System.currentTimeMillis() + expiresInSec * 1000L)
            .apply()
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map.toMutableMap()

    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { pending[key] = values }

        override fun putInt(key: String, value: Int) = apply { pending[key] = value }

        override fun putLong(key: String, value: Long) = apply { pending[key] = value }

        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }

        override fun remove(key: String) = apply { removals.add(key) }

        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) map.clear()
            removals.forEach { map.remove(it) }
            pending.forEach { (key, value) -> map[key] = value }
            pending.clear()
            removals.clear()
            clearAll = false
        }
    }
}
