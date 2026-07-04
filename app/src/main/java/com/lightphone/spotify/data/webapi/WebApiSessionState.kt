package com.lightphone.spotify.data.webapi

/**
 * Observable Step 2 (dev-app Web API) session state.
 */
sealed interface WebApiSessionState {
    /** No client ID / secret configured. */
    data object NotConfigured : WebApiSessionState

    /** Valid or refreshable access + refresh tokens. */
    data object Authorized : WebApiSessionState

    /** Credentials retained; tokens cleared (e.g. invalid_grant). */
    data object Expired : WebApiSessionState
}
