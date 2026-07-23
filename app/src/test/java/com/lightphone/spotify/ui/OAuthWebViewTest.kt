package com.lightphone.spotify.ui

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OAuthWebViewTest {
    @Test
    fun matchesRedirectUri_spotifyLoopbackWithQuery() {
        val redirect = "http://127.0.0.1:8898/login"
        val uri = Uri.parse("$redirect?code=abc&state=xyz")
        assertTrue(matchesRedirectUri(uri, redirect))
    }

    @Test
    fun matchesRedirectUri_tidalHttps() {
        val redirect = "https://tidal.com/android/login/auth"
        val uri = Uri.parse("$redirect?code=abc")
        assertTrue(matchesRedirectUri(uri, redirect))
    }

    @Test
    fun matchesRedirectUri_rejectsSuffixHost() {
        val redirect = "http://127.0.0.1:8898/login"
        val uri = Uri.parse("http://127.0.0.1:8898/login-evil?code=abc")
        assertFalse(matchesRedirectUri(uri, redirect))
    }

    @Test
    fun matchesRedirectUri_trailingSlash() {
        val redirect = "http://127.0.0.1:8898/login"
        val uri = Uri.parse("http://127.0.0.1:8898/login/?code=abc")
        assertTrue(matchesRedirectUri(uri, redirect))
    }
}
