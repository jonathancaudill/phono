package com.lightphone.spotify.playback.download

import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Shared offline-pin pacing for Spotify and TIDAL.
 *
 * Hard-set (not a user preference). 400–1200 ms between tracks serialized the
 * queue but still looked like a burst to audio-key / playbackinfo.
 * See docs/download-rate-limiting.md.
 */
object DownloadPacing {
    const val TRACK_GAP_MIN_MS = 2_500L
    const val TRACK_GAP_MAX_MS = 5_000L
    const val RATE_LIMIT_COOLDOWN_MS = 20_000L
    const val RATE_LIMIT_RETRY_MAX = 8

    fun isRateLimited(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (looksRateLimited(current.message.orEmpty())) return true
            current = current.cause
        }
        return false
    }

    fun looksRateLimited(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("429") ||
            lower.contains("too many requests") ||
            lower.contains("rate limit") ||
            lower.contains("rate limited") ||
            lower.contains("resource exhausted") ||
            lower.contains("resource_exhausted")
    }

    /** Jittered pause after a pin attempt so the next key/playbackinfo is not immediate. */
    suspend fun afterTrack(): Long {
        val waitMs = Random.nextLong(TRACK_GAP_MIN_MS, TRACK_GAP_MAX_MS + 1)
        delay(waitMs)
        return waitMs
    }

    /** Extra pause when a 429 / rate-limit error is already in hand. */
    suspend fun afterRateLimit() {
        delay(RATE_LIMIT_COOLDOWN_MS)
    }
}
