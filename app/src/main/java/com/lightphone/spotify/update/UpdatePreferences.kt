package com.lightphone.spotify.update

import android.content.Context

/**
 * Persisted state for the GitHub release updater: when we last asked GitHub, and
 * whether the user dismissed the prompt.
 *
 * The check is timestamp-driven rather than scheduled, so a phone that sits unused
 * for a week checks once on the next launch instead of catching up on missed runs.
 */
class UpdatePreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastCheckMs(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun markChecked(nowMs: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK, nowMs).apply()
    }

    /** True once the user picks IGNORE; suppresses automatic prompts until they check manually. */
    fun isIgnored(): Boolean = prefs.getBoolean(KEY_IGNORED, false)

    fun setIgnored(ignored: Boolean) {
        prefs.edit().putBoolean(KEY_IGNORED, ignored).apply()
    }

    fun isDue(nowMs: Long): Boolean = isDue(nowMs, lastCheckMs())

    companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        private const val PREFS_NAME = "phono_updates"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_IGNORED = "ignored"

        /** A backwards wall clock (timezone/NTP correction) counts as due rather than never. */
        internal fun isDue(nowMs: Long, lastCheckMs: Long): Boolean {
            val elapsed = nowMs - lastCheckMs
            return elapsed < 0 || elapsed >= CHECK_INTERVAL_MS
        }
    }
}
