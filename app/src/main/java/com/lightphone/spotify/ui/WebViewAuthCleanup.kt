package com.lightphone.spotify.ui

import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * Clears WebView cookies + HTML5 storage so OAuth SSO cannot auto-redirect
 * against a remounted PKCE state after logout or backend switch.
 */
object WebViewAuthCleanup {
    fun clear() {
        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
    }
}
