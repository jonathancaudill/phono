package com.lightphone.spotify.data.tidal

/** Observable TIDAL auth state (single-auth backend — no separate Step 2). */
sealed interface TidalSessionState {
    /** No stored tokens — login required. */
    data object NotAuthenticated : TidalSessionState

    /** Valid or refreshable access + refresh tokens. */
    data object Authenticated : TidalSessionState
}

/** Device-authorization grant handoff shown on the login screen. */
data class TidalDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)
