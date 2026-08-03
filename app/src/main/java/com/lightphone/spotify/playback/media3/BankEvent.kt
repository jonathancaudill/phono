package com.lightphone.spotify.playback.media3

/**
 * Lifecycle of opportunistic stream-cache banking (current track or warm-ahead).
 * Shared by TIDAL today and Spotify-on-Media3 later.
 */
sealed interface BankEvent {
    val mediaId: String

    data class Started(override val mediaId: String, val warm: Boolean) : BankEvent
    data class Complete(override val mediaId: String, val warm: Boolean) : BankEvent
    data class Cancelled(override val mediaId: String) : BankEvent
    data class Failed(override val mediaId: String, val cause: String?) : BankEvent
}
