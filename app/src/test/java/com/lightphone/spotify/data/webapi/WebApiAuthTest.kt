package com.lightphone.spotify.data.webapi

import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest

class WebApiAuthTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: InMemorySharedPreferences

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = okhttp3.mockwebserver.QueueDispatcher()
        prefs = InMemorySharedPreferences()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun currentBearer_freshToken_doesNotHitNetwork() {
        seedTokens(access = "fresh-access", refresh = "refresh-1", expiresInSec = 3600)
        val auth = createAuth()

        assertEquals("fresh-access", auth.currentBearer())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun concurrentRefresh_nearExpiry_issuesSinglePost() {
        seedTokens(access = "stale-access", refresh = "refresh-1", expiresInSec = 30)
        val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestCount.incrementAndGet()
                return MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"access_token":"new-access","token_type":"Bearer","expires_in":3600}""")
            }
        }
        val auth = createAuth()
        val pool = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(10)
        val tokens = mutableListOf<String>()

        repeat(10) {
            pool.submit {
                try {
                    synchronized(tokens) { tokens.add(auth.currentBearer()) }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(1, requestCount.get())
        assertTrue(tokens.all { it == "new-access" })
    }

    @Test
    fun invalidGrant_afterRotation_doesNotClearSession() {
        seedTokens(access = "stale-access", refresh = "old-refresh", expiresInSec = 30)
        val requestStarted = AtomicBoolean(false)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.set(true)
                releaseResponse.await(5, TimeUnit.SECONDS)
                return MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":"invalid_grant","error_description":"Token revoked"}""")
            }
        }
        val auth = createAuth()
        val pool = Executors.newSingleThreadExecutor()
        val refreshFuture = pool.submit {
            try {
                auth.currentBearer()
            } catch (_: WebApiAuthException) {
                null
            }
        }
        val deadline = System.currentTimeMillis() + 5_000L
        while (!requestStarted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        prefs.edit()
            .putString("access_token", "winner-access")
            .putString("refresh_token", "new-refresh")
            .putLong("expires_at_ms", System.currentTimeMillis() + 3_600_000L)
            .apply()
        releaseResponse.countDown()
        refreshFuture.get(5, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals("winner-access", prefs.getString("access_token", null))
        assertEquals("new-refresh", prefs.getString("refresh_token", null))
        assertEquals(WebApiSessionState.Authorized, auth.sessionState.value)
    }

    @Test
    fun invalidGrant_genuineRevocation_clearsTokens() {
        seedTokens(access = "stale-access", refresh = "only-refresh", expiresInSec = 30)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":"invalid_grant","error_description":"Token revoked"}""")
        }
        val auth = createAuth()

        try {
            auth.currentBearer()
        } catch (_: WebApiAuthException) {
            // Expected
        }

        assertEquals(null, prefs.getString("access_token", null))
        assertEquals(null, prefs.getString("refresh_token", null))
        assertEquals(WebApiSessionState.Expired, auth.sessionState.value)
    }

    @Test
    fun refreshSuccess_returnsNewAccessToken() {
        seedTokens(access = "stale-access", refresh = "refresh-1", expiresInSec = 30)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """{"access_token":"rotated-access","token_type":"Bearer",""" +
                            """"expires_in":3600,"refresh_token":"refresh-2"}""",
                    )
        }
        val auth = createAuth()

        assertEquals("rotated-access", auth.currentBearer())
        assertEquals("rotated-access", prefs.getString("access_token", null))
        assertEquals("refresh-2", prefs.getString("refresh_token", null))
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

/** Minimal in-memory [SharedPreferences] for JVM unit tests. */
class InMemorySharedPreferences : SharedPreferences {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = map.toMap()

    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (map[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners.remove(listener)
    }

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
            val changed = mutableSetOf<String>()
            if (clearAll) {
                changed.addAll(map.keys)
                map.clear()
                clearAll = false
            }
            removals.forEach { key ->
                if (map.remove(key) != null) changed.add(key)
            }
            pending.forEach { (key, value) ->
                map[key] = value
                changed.add(key)
            }
            pending.clear()
            removals.clear()
            changed.forEach { key ->
                listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) }
            }
        }
    }
}
