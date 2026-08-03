package com.lightphone.spotify.playback.media3

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger

/**
 * Detects expired / unauthorized CDN responses so callers can re-resolve
 * playbackinfo (or equivalent) and [androidx.media3.common.Player.replaceMediaItem]
 * with the **same** customCacheKey.
 */
object CdnUrlRefresher {
    const val DEFAULT_MAX_ATTEMPTS = 2

    fun isCdnAuthFailure(error: Throwable?): Boolean {
        var t: Throwable? = error
        while (t != null) {
            when (t) {
                is HttpDataSource.InvalidResponseCodeException -> {
                    val code = t.responseCode
                    if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        code == HttpURLConnection.HTTP_FORBIDDEN ||
                        code == 410
                    ) {
                        return true
                    }
                }
                is PlaybackException -> {
                    // Fall through to cause chain / message heuristics.
                }
            }
            val msg = t.message.orEmpty()
            if (msg.contains("403") ||
                msg.contains("401") ||
                msg.contains("HTTP 403", ignoreCase = true) ||
                msg.contains("HTTP 401", ignoreCase = true) ||
                msg.contains("response code: 403", ignoreCase = true) ||
                msg.contains("response code: 401", ignoreCase = true)
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }

    fun isRetryableIo(error: Throwable?): Boolean {
        if (isCdnAuthFailure(error)) return true
        var t: Throwable? = error
        while (t != null) {
            if (t is IOException) return true
            t = t.cause
        }
        return false
    }
}

/**
 * Per-track attempt counter so a single expired URL can be refreshed a few
 * times without looping forever on a hard 403.
 */
class CdnRefreshAttempts(
    private val maxAttempts: Int = CdnUrlRefresher.DEFAULT_MAX_ATTEMPTS,
) {
    private val counts = HashMap<String, AtomicInteger>()

    @Synchronized
    fun tryBegin(mediaId: String): Boolean {
        val n = counts.getOrPut(mediaId) { AtomicInteger(0) }
        return n.incrementAndGet() <= maxAttempts
    }

    @Synchronized
    fun clear(mediaId: String) {
        counts.remove(mediaId)
    }

    @Synchronized
    fun clearAll() {
        counts.clear()
    }
}
