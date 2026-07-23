package com.lightphone.spotify.data.backend

import android.content.Context

/**
 * The streaming backend a phono install is bound to. Chosen once at first launch
 * (see `BackendPickerScreen`); switching means signing out and re-picking.
 */
enum class BackendChoice {
    SPOTIFY,
    TIDAL,
    ;

    companion object {
        fun fromKey(key: String?): BackendChoice? = when (key) {
            SPOTIFY.name -> SPOTIFY
            TIDAL.name -> TIDAL
            else -> null
        }
    }
}

/**
 * Tiny non-encrypted prefs store for the single-active-backend selection. This is
 * a benign UI routing flag, not a secret, so plain [android.content.SharedPreferences]
 * is fine (tokens live in each backend's own EncryptedSharedPreferences).
 */
class BackendPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Null until the user picks a backend on first launch. */
    fun choice(): BackendChoice? = BackendChoice.fromKey(prefs.getString(KEY_CHOICE, null))

    fun isChosen(): Boolean = choice() != null

    fun setChoice(choice: BackendChoice) {
        prefs.edit().putString(KEY_CHOICE, choice.name).commit()
    }

    fun clear() {
        prefs.edit().remove(KEY_CHOICE).commit()
    }

    companion object {
        private const val PREFS_NAME = "phono_backend_choice"
        private const val KEY_CHOICE = "choice"
    }
}
