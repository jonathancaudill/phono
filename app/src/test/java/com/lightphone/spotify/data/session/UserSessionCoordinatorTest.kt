package com.lightphone.spotify.data.session

import com.lightphone.spotify.data.SpotifyRepository
import com.lightphone.spotify.data.local.LibraryRepository
import com.lightphone.spotify.data.webapi.WebApiAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

/**
 * [UserSessionCoordinator] guarantees an ordered, mutex-serialized teardown of
 * every user-bound cache tier on sign-out. All collaborators are mocked since
 * they require a real Android/network/DB environment to construct for real.
 *
 * `events` is a [kotlinx.coroutines.flow.MutableSharedFlow] with a buffer of 1
 * and no replay, so `signOut()` must always be exercised alongside an active
 * collector (a real, un-drained collector would otherwise deadlock on the
 * second `emit`, exactly as it would in production if nothing observes
 * [UserSessionCoordinator.events]).
 */
class UserSessionCoordinatorTest {

    private lateinit var libraryRepository: LibraryRepository
    private lateinit var spotifyRepository: SpotifyRepository
    private lateinit var webApiAuth: WebApiAuth

    @Before
    fun setUp() {
        libraryRepository = mock()
        spotifyRepository = mock()
        webApiAuth = mock()
    }

    private fun coordinator(
        clearTrackMetadata: () -> Unit = {},
        clearImageMemoryCache: () -> Unit = {},
        rustLogout: () -> Unit = {},
    ) = UserSessionCoordinator(
        libraryRepository = libraryRepository,
        spotifyRepository = spotifyRepository,
        webApiAuth = webApiAuth,
        clearTrackMetadata = clearTrackMetadata,
        clearImageMemoryCache = clearImageMemoryCache,
        rustLogout = rustLogout,
    )

    /** Runs [sut.signOut] in the background against a draining collector, then settles. */
    private fun TestScope.signOutAndDrain(
        sut: UserSessionCoordinator,
        options: SignOutOptions = SignOutOptions(),
        onCancelInFlight: () -> Unit = {},
        onEvent: (SessionEvent) -> Unit = {},
    ) {
        val collectorJob = launch { sut.events.collect { onEvent(it) } }
        launch { sut.signOut(options, onCancelInFlight) }
        advanceUntilIdle()
        collectorJob.cancel()
    }

    @Test
    fun signOut_defaultOptions_clearsWebApiTokensOnly() = runTest {
        val sut = coordinator()

        signOutAndDrain(sut)

        verify(webApiAuth).clearTokens()
        verify(webApiAuth, never()).clearAll()
    }

    @Test
    fun signOut_clearDevAppCredentials_clearsAllCredentialsInstead() = runTest {
        val sut = coordinator()

        signOutAndDrain(sut, options = SignOutOptions(clearDevAppCredentials = true))

        verify(webApiAuth).clearAll()
        verify(webApiAuth, never()).clearTokens()
    }

    @Test
    fun signOut_bothTokenOptionsDisabled_leavesWebApiAuthUntouched() = runTest {
        val sut = coordinator()

        signOutAndDrain(
            sut,
            options = SignOutOptions(clearWebApiTokens = false, clearDevAppCredentials = false),
        )

        verifyNoInteractions(webApiAuth)
    }

    @Test
    fun signOut_clearsLibraryCacheRepositoryCachesAndTrackMetadata() = runTest {
        var trackMetadataCleared = false
        var imageCacheCleared = false
        val sut = coordinator(
            clearTrackMetadata = { trackMetadataCleared = true },
            clearImageMemoryCache = { imageCacheCleared = true },
        )

        signOutAndDrain(sut)

        verify(libraryRepository).clearAllUserData()
        verify(spotifyRepository).clearSessionCaches()
        assertTrue(trackMetadataCleared)
        assertTrue(imageCacheCleared)
    }

    @Test
    fun signOut_imageCacheClearer_defaultsToNoOpWhenNotProvided() = runTest {
        val sut = UserSessionCoordinator(
            libraryRepository = libraryRepository,
            spotifyRepository = spotifyRepository,
            webApiAuth = webApiAuth,
            clearTrackMetadata = {},
            rustLogout = {},
        )

        // Should not throw even though clearImageMemoryCache was not supplied.
        signOutAndDrain(sut)

        verify(libraryRepository).clearAllUserData()
    }

    @Test
    fun signOut_invokesOnCancelInFlightAndRustLogoutBeforeClearingCaches() = runTest {
        var rustLoggedOut = false
        var cancelInvoked = false
        val sut = coordinator(rustLogout = { rustLoggedOut = true })

        signOutAndDrain(sut, onCancelInFlight = { cancelInvoked = true })

        assertTrue(cancelInvoked)
        assertTrue(rustLoggedOut)
        val order = inOrder(webApiAuth, libraryRepository, spotifyRepository)
        order.verify(webApiAuth).clearTokens()
        order.verify(libraryRepository).clearAllUserData()
        order.verify(spotifyRepository).clearSessionCaches()
    }

    @Test
    fun signOut_emitsSigningOutThenSignedOutEvents() = runTest {
        val sut = coordinator()
        val events = mutableListOf<SessionEvent>()

        signOutAndDrain(sut, onEvent = { events.add(it) })

        assertEquals(listOf(SessionEvent.SigningOut, SessionEvent.SignedOut), events)
    }

    @Test
    fun signOut_calledTwiceSequentially_completesBothTimes() = runTest {
        val sut = coordinator()
        val collectorJob = launch { sut.events.collect { } }

        launch { sut.signOut() }
        launch { sut.signOut() }
        advanceUntilIdle()
        collectorJob.cancel()

        verify(libraryRepository, times(2)).clearAllUserData()
        verify(spotifyRepository, times(2)).clearSessionCaches()
        verify(webApiAuth, times(2)).clearTokens()
    }

    @Test
    fun signOut_defaultOnCancelInFlight_isNoOpAndDoesNotThrow() = runTest {
        val sut = coordinator()

        // Omitting onCancelInFlight should use the no-op default without error.
        signOutAndDrain(sut)

        verify(libraryRepository).clearAllUserData()
    }
}