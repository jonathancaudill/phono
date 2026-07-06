package com.lightphone.spotify.playback

/** Result of [PlaybackController.warmSpclientSession] — never throws. */
sealed interface WarmResult {
    data object Success : WarmResult
    data object NotSignedIn : WarmResult
    data class Failed(val message: String) : WarmResult
}
