package com.lightphone.spotify.playback.download

import kotlin.random.Random

/**
 * Session-shaped pin pacing. Every wait is `BASE + uniform(0, 0.50 × BASE)` —
 * BASE is a floor, jitter only adds time.
 *
 * Gaps follow **global remaining work**, not the current album size, so 20
 * albums share one large-queue policy. See docs/download-rate-limiting.md.
 */
enum class DownloadPaceMode {
    FAST,
    BALANCED,
    CAREFUL,
}

object DownloadPacing {
    const val SMALL_REMAINING = 15
    const val MEDIUM_REMAINING = 40

    const val SMALL_GAP_BASE_MS = 2_500L
    const val MEDIUM_GAP_BASE_MS = 10_000L
    const val LARGE_GAP_MIN_MS = 12_000L
    const val LARGE_GAP_MAX_MS = 40_000L
    const val LARGE_DURATION_FRACTION = 0.12

    const val CIRCUIT_TRIPS_BEFORE_STOP = 3
    val CIRCUIT_BASE_MS = longArrayOf(45_000L, 120_000L, 300_000L)

    const val CAREFUL_LONG_PAUSE_LARGE_BASE_MS = 90_000L
    const val CAREFUL_LONG_PAUSE_MEDIUM_BASE_MS = 45_000L
    const val CAREFUL_LONG_PAUSE_LARGE_P = 0.15
    const val CAREFUL_LONG_PAUSE_MEDIUM_P = 0.08

    const val TRANSIENT_RETRY_MAX = 3
    val TRANSIENT_RETRY_BASE_MS = longArrayOf(2_000L, 5_000L, 10_000L)

    /** BASE + uniform 0–50% of BASE. Never below [baseMs]. */
    fun jitter(baseMs: Long, random: Random = Random.Default): Long {
        if (baseMs <= 0L) return 0L
        val extra = (baseMs.toDouble() * random.nextDouble() * 0.50).toLong()
        return baseMs + extra
    }

    fun keyGapBaseMs(
        mode: DownloadPaceMode,
        remaining: Int,
        durationMs: Long,
    ): Long {
        if (mode == DownloadPaceMode.FAST) return SMALL_GAP_BASE_MS
        return when {
            remaining <= SMALL_REMAINING -> SMALL_GAP_BASE_MS
            remaining <= MEDIUM_REMAINING -> MEDIUM_GAP_BASE_MS
            else -> {
                val fromDuration = (durationMs.coerceAtLeast(0L) * LARGE_DURATION_FRACTION).toLong()
                fromDuration.coerceIn(LARGE_GAP_MIN_MS, LARGE_GAP_MAX_MS)
            }
        }
    }

    fun afterTrackWaitMs(
        mode: DownloadPaceMode,
        remaining: Int,
        durationMs: Long,
        random: Random = Random.Default,
    ): Long {
        val gap = jitter(keyGapBaseMs(mode, remaining, durationMs), random)
        val longPause = carefulLongPauseBaseMs(mode, remaining, random)
        return gap + if (longPause != null) jitter(longPause, random) else 0L
    }

    /**
     * Careful + large/medium queues only. Null when this track should not take
     * an extra pause (wrong mode, small remainder, or the coin flip missed).
     */
    fun carefulLongPauseBaseMs(
        mode: DownloadPaceMode,
        remaining: Int,
        random: Random = Random.Default,
    ): Long? {
        if (mode != DownloadPaceMode.CAREFUL) return null
        val (probability, base) = when {
            remaining > MEDIUM_REMAINING -> CAREFUL_LONG_PAUSE_LARGE_P to CAREFUL_LONG_PAUSE_LARGE_BASE_MS
            remaining > SMALL_REMAINING -> CAREFUL_LONG_PAUSE_MEDIUM_P to CAREFUL_LONG_PAUSE_MEDIUM_BASE_MS
            else -> return null
        }
        if (random.nextDouble() >= probability) return null
        return base
    }

    fun transientRetryWaitMs(attempt: Int, random: Random = Random.Default): Long {
        val index = (attempt - 1).coerceIn(0, TRANSIENT_RETRY_BASE_MS.lastIndex)
        return jitter(TRANSIENT_RETRY_BASE_MS[index], random)
    }

    /**
     * [retryAfterMs] from the server is the BASE when present; otherwise the
     * circuit table. Jitter is applied on top either way.
     */
    fun circuitWaitMs(
        tripNumber: Int,
        retryAfterMs: Long?,
        random: Random = Random.Default,
    ): Long {
        val header = retryAfterMs?.takeIf { it > 0L }
        val table = CIRCUIT_BASE_MS[(tripNumber - 1).coerceIn(0, CIRCUIT_BASE_MS.lastIndex)]
        val base = header ?: table
        return jitter(base, random)
    }

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
            lower.contains("resource_exhausted") ||
            lower.contains("audio key") ||
            lower.contains("aeskey") ||
            lower.contains("aes key")
    }

    fun looksPermanentFailure(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("no playable file") ||
            lower.contains("http 403") ||
            lower.contains("forbidden") ||
            lower.contains("invalid uri") ||
            lower.contains("not available")
    }

    fun isPermanentFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (looksPermanentFailure(current.message.orEmpty())) return true
            current = current.cause
        }
        return false
    }

    /**
     * Seconds (or ms if the value is already large) from common error strings.
     * Returns milliseconds, or null if nothing parseable.
     */
    fun parseRetryAfterMs(message: String): Long? {
        val patterns = listOf(
            Regex("""retry-after[=:\s]+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""for at least another (\d+) seconds""", RegexOption.IGNORE_CASE),
            Regex("""fastly-ratelimit-reset[=:\s]+(\d+)""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val match = pattern.find(message) ?: continue
            val raw = match.groupValues[1].toLongOrNull() ?: continue
            // Values >= 1000 are treated as milliseconds (Fastly epoch-delta or "Retry-After=20000").
            return if (raw >= 1_000L) raw else raw * 1_000L
        }
        return null
    }

    fun parseRetryAfterMs(error: Throwable): Long? {
        var current: Throwable? = error
        while (current != null) {
            parseRetryAfterMs(current.message.orEmpty())?.let { return it }
            current = current.cause
        }
        return null
    }
}
