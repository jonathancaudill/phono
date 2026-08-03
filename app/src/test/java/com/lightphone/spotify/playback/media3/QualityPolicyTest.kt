package com.lightphone.spotify.playback.media3

import com.lightphone.spotify.data.tidal.TidalAudioQuality
import com.lightphone.spotify.playback.NetworkTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityPolicyTest {
    private val ladder = listOf(
        TidalAudioQuality.EXTRA_LOW,
        TidalAudioQuality.LOW,
        TidalAudioQuality.HIGH,
        TidalAudioQuality.MAX,
    )

    private fun policy(initial: TidalAudioQuality = TidalAudioQuality.HIGH) =
        QualityPolicy(initial) { tier, requested ->
            QualityCeilings.cap(tier, requested, ladder)
        }

    @Test
    fun poorTierCapsMaxToLowAac() {
        val p = policy(TidalAudioQuality.MAX)
        assertEquals(
            TidalAudioQuality.LOW,
            p.effectiveForResolve(NetworkTier.POOR),
        )
    }

    @Test
    fun meteredCapsMaxToLossless() {
        val p = policy(TidalAudioQuality.MAX)
        assertEquals(
            TidalAudioQuality.HIGH,
            p.effectiveForResolve(NetworkTier.GOOD_METERED),
        )
    }

    @Test
    fun unmeteredAllowsMax() {
        val p = policy(TidalAudioQuality.MAX)
        assertEquals(
            TidalAudioQuality.MAX,
            p.effectiveForResolve(NetworkTier.GOOD_UNMETERED),
        )
    }

    @Test
    fun userChangeIsDeferredUntilCommit() {
        val p = policy(TidalAudioQuality.HIGH)
        p.applyNow(NetworkTier.GOOD_UNMETERED)
        assertEquals(TidalAudioQuality.HIGH, p.appliedQuality())

        p.setUserQuality(TidalAudioQuality.MAX)
        assertEquals(TidalAudioQuality.HIGH, p.appliedQuality())
        assertEquals(TidalAudioQuality.MAX, p.effectiveForResolve(NetworkTier.GOOD_UNMETERED))

        val committed = p.commitPending(NetworkTier.GOOD_UNMETERED)
        assertEquals(TidalAudioQuality.MAX, committed)
        assertEquals(TidalAudioQuality.MAX, p.appliedQuality())
    }

    @Test
    fun commitOnPoorAppliesCeiling() {
        val p = policy(TidalAudioQuality.MAX)
        p.setUserQuality(TidalAudioQuality.MAX)
        assertEquals(TidalAudioQuality.LOW, p.commitPending(NetworkTier.POOR))
    }
}

class CdnUrlRefresherTest {
    @Test
    fun detects403InMessage() {
        assertTrue(CdnUrlRefresher.isCdnAuthFailure(RuntimeException("HTTP 403 Forbidden")))
        assertTrue(CdnUrlRefresher.isCdnAuthFailure(RuntimeException("response code: 401")))
        assertFalse(CdnUrlRefresher.isCdnAuthFailure(RuntimeException("HTTP 500")))
    }

    @Test
    fun attemptCounterCapsRetries() {
        val attempts = CdnRefreshAttempts(maxAttempts = 2)
        assertTrue(attempts.tryBegin("tidal:track:1"))
        assertTrue(attempts.tryBegin("tidal:track:1"))
        assertFalse(attempts.tryBegin("tidal:track:1"))
        attempts.clear("tidal:track:1")
        assertTrue(attempts.tryBegin("tidal:track:1"))
    }
}
