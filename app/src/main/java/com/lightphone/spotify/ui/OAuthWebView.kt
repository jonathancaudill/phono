package com.lightphone.spotify.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

private const val TAG = "OAuthWebView"

/** Auto-submit Spotify /authorize consent when the SPA stalls in WebView. */
private const val AUTHORIZE_AUTO_SUBMIT_JS = """
(function() {
  function tryClick() {
    var selectors = [
      'button[data-testid="auth-accept"]',
      'button[data-testid="login-button"]',
      'button[type="submit"]',
      'button[data-encore-id="buttonPrimary"]'
    ];
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) { el.click(); return 'clicked:' + selectors[i]; }
    }
    var buttons = document.querySelectorAll('button');
    for (var j = 0; j < buttons.length; j++) {
      var t = (buttons[j].textContent || '').toLowerCase();
      if (/agree|accept|authorize|continue|yes|allow/.test(t)) {
        buttons[j].click(); return 'clicked:text';
      }
    }
    return 'no-button';
  }
  tryClick();
  setTimeout(tryClick, 400);
  setTimeout(tryClick, 1200);
  return tryClick();
})();
"""

/**
 * Shared OAuth WebView setup for Spotify + TIDAL login.
 *
 * TIDAL redirects to HTTPS (`https://tidal.com/android/login/auth`), so a missed
 * intercept still leaves a loadable URL. Spotify redirects to cleartext loopback
 * (`http://127.0.0.1:…`); if [WebViewClient.shouldOverrideUrlLoading] does not fire
 * (common after email-OTP SPA navigations), the load is blocked unless loopback
 * cleartext is permitted — and we still need [onPageStarted] / history callbacks
 * as a safety net to harvest `?code=`.
 */
internal fun WebView.configureOAuthWebView(
    redirectUri: String,
    /** v0.0.1 shipped mobile Chrome; keep that default for Spotify Step 1. */
    userAgent: String =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36",
    onAuthorizationCode: (code: String, state: String?) -> Unit,
) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.userAgentString = userAgent
    Log.e(TAG, "configure redirect=$redirectUri ua=$userAgent")
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(this, true)

    var consumed = false
    fun tryConsume(uri: Uri, via: String): Boolean {
        val matched = matchesRedirectUri(uri, redirectUri) ||
            uri.toString().startsWith(redirectUri)
        if (!matched) return false
        if (consumed) {
            Log.e(TAG, "redirect already consumed via=$via url=$uri")
            return true
        }
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        Log.e(TAG, "redirect hit via=$via code=${code != null} state=${state != null} url=$uri")
        if (code == null) return true
        consumed = true
        onAuthorizationCode(code, state)
        return true
    }

    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
            val msg = consoleMessage ?: return super.onConsoleMessage(consoleMessage)
            Log.e(
                TAG,
                "console[${msg.messageLevel()}] ${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}",
            )
            return true
        }
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val uri = request?.url ?: return false
            Log.e(TAG, "nav override main=${request.isForMainFrame} redirect=${request.isRedirect} url=$uri")
            if (tryConsume(uri, "override")) return true
            return launchExternalIfNeeded(view?.context, uri)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            Log.e(TAG, "pageStarted url=$url")
            val uri = url?.let(Uri::parse) ?: return
            if (tryConsume(uri, "pageStarted")) {
                view?.stopLoading()
                return
            }
            if (url.contains("/authorize", ignoreCase = true)) {
                view?.evaluateJavascript(AUTHORIZE_AUTO_SUBMIT_JS, null)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.e(TAG, "pageFinished url=$url")
            val uri = url?.let(Uri::parse) ?: return
            if (tryConsume(uri, "pageFinished")) {
                view?.stopLoading()
                return
            }
            if (url.contains("/authorize", ignoreCase = true)) {
                view?.evaluateJavascript(AUTHORIZE_AUTO_SUBMIT_JS) { result ->
                    Log.e(TAG, "authorize auto-submit result=$result")
                }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            Log.e(TAG, "history url=$url reload=$isReload")
            val uri = url?.let(Uri::parse) ?: return
            if (tryConsume(uri, "history")) view?.stopLoading()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            Log.e(
                TAG,
                "receivedError main=${request?.isForMainFrame} code=${error?.errorCode} " +
                    "desc=${error?.description} url=${request?.url}",
            )
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: android.webkit.WebResourceResponse?,
        ) {
            if (request?.isForMainFrame != true) return
            Log.e(
                TAG,
                "httpError status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} url=${request.url}",
            )
        }
    }
}

/**
 * Exact scheme+host+port+path match against [redirect] — NOT `toString().startsWith(...)`,
 * which would also accept `$redirect-evil-suffix`.
 */
internal fun matchesRedirectUri(url: Uri, redirect: String): Boolean {
    val expected = Uri.parse(redirect)
    val urlPath = url.path?.trimEnd('/')?.ifEmpty { "/" } ?: "/"
    val expectedPath = expected.path?.trimEnd('/')?.ifEmpty { "/" } ?: "/"
    return url.scheme.equals(expected.scheme, ignoreCase = true) &&
        url.host.equals(expected.host, ignoreCase = true) &&
        effectivePort(url) == effectivePort(expected) &&
        urlPath == expectedPath
}

private fun effectivePort(uri: Uri): Int {
    val port = uri.port
    if (port != -1) return port
    return when (uri.scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
}

/** mailto / intent / custom schemes — leave http(s) to the WebView. */
private fun launchExternalIfNeeded(context: Context?, uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme == "http" || scheme == "https") return false
    Log.e(TAG, "external scheme url=$uri")
    if (context == null) return true
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        true
    }
}
