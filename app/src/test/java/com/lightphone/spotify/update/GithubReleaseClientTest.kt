package com.lightphone.spotify.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GithubReleaseClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(currentVersion: String): GithubReleaseClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        return GithubReleaseClient(
            currentVersion = currentVersion,
            latestReleaseUrl = server.url("/releases/latest").toString(),
            client = http,
        )
    }

    @Test
    fun newerReleaseWithApk_isOffered() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson("v0.1.6")))

        val update = client("0.1.5").latestNewerRelease()

        assertNotNull(update)
        assertEquals("0.1.6", update!!.version)
        assertEquals(
            "https://github.com/jonathancaudill/phono/releases/download/v0.1.6/phono-v0.1.6.apk",
            update.apkUrl,
        )
        assertEquals(28802500L, update.apkBytes)
    }

    @Test
    fun sameVersion_isNotOffered() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson("v0.1.6")))
        assertNull(client("0.1.6").latestNewerRelease())
    }

    @Test
    fun newerReleaseWithoutApk_isNotOffered() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(releaseJson("v0.1.7", includeApk = false)),
        )
        assertNull(client("0.1.6").latestNewerRelease())
    }

    @Test
    fun http404_isNotOffered() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client("0.1.5").latestNewerRelease())
    }

    private fun releaseJson(tag: String, includeApk: Boolean = true): String {
        val assets = if (includeApk) {
            """
            [{
              "name": "phono-$tag.apk",
              "size": 28802500,
              "browser_download_url":
                "https://github.com/jonathancaudill/phono/releases/download/$tag/phono-$tag.apk"
            }]
            """.trimIndent()
        } else {
            "[]"
        }
        return """
            {
              "tag_name": "$tag",
              "draft": false,
              "prerelease": false,
              "assets": $assets
            }
        """.trimIndent()
    }
}
