package com.lightphone.spotify.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTierLogicTest {

    private fun link(
        hasInternet: Boolean = true,
        validated: Boolean = true,
        wifiOrEthernet: Boolean = false,
        unmetered: Boolean = false,
        downKbps: Int = 800,
        wifiVisibleMs: Long = 0,
    ) = LinkSnapshot(
        hasInternet = hasInternet,
        validated = validated,
        wifiOrEthernet = wifiOrEthernet,
        unmetered = unmetered,
        downKbps = downKbps,
        wifiVisibleMs = wifiVisibleMs,
    )

    @Test
    fun noInternetIsOffline() {
        assertEquals(
            NetworkTier.OFFLINE,
            StreamingPolicy.classifyLink(link(hasInternet = false)),
        )
        assertEquals(0, StreamingPolicy.prefetchAhead(NetworkTier.OFFLINE))
    }

    @Test
    fun unvalidatedIsPoor() {
        assertEquals(
            NetworkTier.POOR,
            StreamingPolicy.classifyLink(link(validated = false, downKbps = 5000)),
        )
    }

    @Test
    fun briefWifiBlipDoesNotPromoteToUnmetered() {
        assertEquals(
            NetworkTier.GOOD_METERED,
            StreamingPolicy.classifyLink(
                link(
                    wifiOrEthernet = true,
                    unmetered = true,
                    downKbps = 5000,
                    wifiVisibleMs = StreamingPolicy.WIFI_PREFER_AFTER_MS - 1,
                ),
            ),
        )
    }

    @Test
    fun wifiStableThirtySecondsPromotesToUnmetered() {
        assertEquals(
            NetworkTier.GOOD_UNMETERED,
            StreamingPolicy.classifyLink(
                link(
                    wifiOrEthernet = true,
                    unmetered = true,
                    downKbps = 5000,
                    wifiVisibleMs = StreamingPolicy.WIFI_PREFER_AFTER_MS,
                ),
            ),
        )
    }

    @Test
    fun bandwidthLadder() {
        assertEquals(NetworkTier.POOR, StreamingPolicy.classifyLink(link(downKbps = 100)))
        assertEquals(NetworkTier.FAIR, StreamingPolicy.classifyLink(link(downKbps = 400)))
        assertEquals(NetworkTier.GOOD_METERED, StreamingPolicy.classifyLink(link(downKbps = 1200)))
    }

    @Test
    fun prefetchIsBankFirstThenLookahead() {
        assertEquals(3, StreamingPolicy.prefetchAhead(NetworkTier.GOOD_UNMETERED))
        assertEquals(2, StreamingPolicy.prefetchAhead(NetworkTier.GOOD_METERED))
        assertEquals(1, StreamingPolicy.prefetchAhead(NetworkTier.FAIR))
        assertEquals(1, StreamingPolicy.prefetchAhead(NetworkTier.POOR))
        assertEquals(0, StreamingPolicy.prefetchAhead(NetworkTier.OFFLINE))
    }

    @Test
    fun hysteresisIgnoresSingleUpgradeSample() {
        var h = NetworkTierHysteresis(stable = NetworkTier.FAIR)
        val (next, upgraded) = h.observe(NetworkTier.GOOD_METERED)
        assertFalse(upgraded)
        assertEquals(NetworkTier.FAIR, next.stable)
        h = next
        val (next2, upgraded2) = h.observe(NetworkTier.GOOD_METERED)
        assertFalse(upgraded2)
        val (next3, upgraded3) = next2.observe(NetworkTier.GOOD_METERED)
        assertTrue(upgraded3)
        assertEquals(NetworkTier.GOOD_METERED, next3.stable)
    }

    @Test
    fun hysteresisNeedsTwoDowngrades() {
        var h = NetworkTierHysteresis(stable = NetworkTier.GOOD_METERED)
        val (n1, _) = h.observe(NetworkTier.POOR)
        assertEquals(NetworkTier.GOOD_METERED, n1.stable)
        val (n2, _) = n1.observe(NetworkTier.POOR)
        assertEquals(NetworkTier.POOR, n2.stable)
    }

    @Test
    fun hysteresisResetsWhenRawReturnsToStable() {
        var h = NetworkTierHysteresis(stable = NetworkTier.FAIR)
        h = h.observe(NetworkTier.GOOD_METERED).first
        h = h.observe(NetworkTier.FAIR).first
        assertEquals(0, h.upCount)
        val (next, upgraded) = h.observe(NetworkTier.GOOD_METERED)
        assertFalse(upgraded)
        assertEquals(1, next.upCount)
    }

    @Test
    fun cellularFlapDoesNotThrashPrefetchDepth() {
        var h = NetworkTierHysteresis(stable = NetworkTier.FAIR)
        val samples = listOf(
            NetworkTier.POOR, NetworkTier.FAIR, NetworkTier.GOOD_METERED,
            NetworkTier.POOR, NetworkTier.FAIR, NetworkTier.POOR, NetworkTier.FAIR,
        )
        for (raw in samples) {
            h = h.observe(raw).first
        }
        assertEquals(NetworkTier.FAIR, h.stable)
        assertEquals(1, StreamingPolicy.prefetchAhead(h.stable))
    }
}
