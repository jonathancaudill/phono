package com.lightphone.spotify.playback.tidal

import androidx.media3.common.C
import androidx.media3.common.Timeline

/**
 * Pure helpers for TIDAL resolve / look-ahead windows (unit-testable without ExoPlayer).
 */
internal object TidalPrefetchWindows {
    /**
     * Indices to keep resolved: [from] plus up to [ahead] following windows in
     * playback order (respects shuffle + repeat via [Timeline.getNextWindowIndex]).
     */
    fun resolvedIndices(
        timeline: Timeline,
        from: Int,
        ahead: Int,
        repeatMode: Int,
        shuffleModeEnabled: Boolean,
    ): List<Int> {
        if (timeline.isEmpty || from < 0 || from >= timeline.windowCount) return emptyList()
        val cappedAhead = ahead.coerceIn(0, 3)
        val out = ArrayList<Int>(cappedAhead + 1)
        out.add(from)
        var window = from
        repeat(cappedAhead) {
            val next = timeline.getNextWindowIndex(window, repeatMode, shuffleModeEnabled)
            if (next == C.INDEX_UNSET || next in out) return out
            out.add(next)
            window = next
        }
        return out
    }

    /** Inclusive index range used at playUris start (prev..start+ahead clamped). */
    fun playStartResolveRange(start: Int, lastIndex: Int, ahead: Int): IntRange {
        val from = (start - 1).coerceAtLeast(0)
        val to = (start + ahead.coerceIn(0, 3)).coerceAtMost(lastIndex)
        return from..to
    }
}
