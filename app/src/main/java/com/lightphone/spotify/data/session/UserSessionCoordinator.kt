package com.lightphone.spotify.data.session

import com.lightphone.spotify.data.SpotifyRepository
import com.lightphone.spotify.data.local.LibraryRepository
import com.lightphone.spotify.data.webapi.WebApiAuth
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionEvent {
    data object SigningOut : SessionEvent
    data object SignedOut : SessionEvent
}

data class SignOutOptions(
    val clearWebApiTokens: Boolean = true,
    /** When true (default for Logout), client ID/secret are removed so Step 2 must be re-entered. */
    val clearDevAppCredentials: Boolean = true,
)

/**
 * Ordered tier teardown on sign-out. Prevents logout/login races and ensures
 * every user-bound cache layer is cleared.
 */
class UserSessionCoordinator(
    private val libraryRepository: LibraryRepository,
    private val spotifyRepository: SpotifyRepository,
    private val webApiAuth: WebApiAuth,
    private val clearTrackMetadata: () -> Unit,
    private val clearImageMemoryCache: () -> Unit = {},
    private val rustLogout: () -> Unit,
) {
    private val logoutMutex = Mutex()
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    suspend fun signOut(
        options: SignOutOptions = SignOutOptions(),
        onCancelInFlight: suspend () -> Unit = {},
    ) {
        logoutMutex.withLock {
            _events.emit(SessionEvent.SigningOut)
            onCancelInFlight()
            rustLogout()
            if (options.clearDevAppCredentials) {
                webApiAuth.clearAll()
            } else if (options.clearWebApiTokens) {
                webApiAuth.clearTokens()
            }
            libraryRepository.clearAllUserData()
            spotifyRepository.clearSessionCaches()
            clearTrackMetadata()
            clearImageMemoryCache()
            _events.emit(SessionEvent.SignedOut)
        }
    }
}
