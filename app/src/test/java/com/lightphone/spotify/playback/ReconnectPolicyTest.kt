package com.lightphone.spotify.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun deferMonitorOnlyWhenPausedAndBackground() {
        assertTrue(ReconnectPolicy.shouldDeferMonitor(playing = false, appForeground = false))
        assertFalse(ReconnectPolicy.shouldDeferMonitor(playing = false, appForeground = true))
        assertFalse(ReconnectPolicy.shouldDeferMonitor(playing = true, appForeground = false))
        assertFalse(ReconnectPolicy.shouldDeferMonitor(playing = true, appForeground = true))
    }

    @Test
    fun coalesceTransportOnlyWhileReconnecting() {
        assertTrue(ReconnectPolicy.shouldCoalesceTransport(reconnecting = true))
        assertFalse(ReconnectPolicy.shouldCoalesceTransport(reconnecting = false))
    }

    @Test
    fun availableReconnectsOnlyWhenSessionUnhealthy() {
        assertFalse(
            ReconnectPolicy.shouldForceReconnectOnAvailable(
                connected = true,
                reconnecting = false,
                sessionDead = false,
            ),
        )
        assertTrue(
            ReconnectPolicy.shouldForceReconnectOnAvailable(
                connected = false,
                reconnecting = false,
                sessionDead = false,
            ),
        )
        assertTrue(
            ReconnectPolicy.shouldForceReconnectOnAvailable(
                connected = true,
                reconnecting = true,
                sessionDead = false,
            ),
        )
        assertTrue(
            ReconnectPolicy.shouldForceReconnectOnAvailable(
                connected = true,
                reconnecting = false,
                sessionDead = true,
            ),
        )
    }

    @Test
    fun bankedPlayingTrackDoesNotTearDownOnHandoff() {
        assertFalse(
            ReconnectPolicy.shouldTearDownOnTransportHandoff(
                playing = true,
                reconnecting = false,
                fullyBuffered = true,
            ),
        )
        assertFalse(
            ReconnectPolicy.shouldTearDownOnForceReconnect(
                playing = true,
                fullyBuffered = true,
            ),
        )
    }

    @Test
    fun unbankedPlayingTrackStillTearsDownOnHandoff() {
        assertTrue(
            ReconnectPolicy.shouldTearDownOnTransportHandoff(
                playing = true,
                reconnecting = false,
                fullyBuffered = false,
            ),
        )
    }

    @Test
    fun alreadyReconnectingStillTearsDownEvenIfBanked() {
        assertTrue(
            ReconnectPolicy.shouldTearDownOnTransportHandoff(
                playing = false,
                reconnecting = true,
                fullyBuffered = true,
            ),
        )
    }

    @Test
    fun pausedDoesNotTearDownOnHandoff() {
        assertFalse(
            ReconnectPolicy.shouldTearDownOnTransportHandoff(
                playing = false,
                reconnecting = false,
                fullyBuffered = false,
            ),
        )
    }

    @Test
    fun stallWatchdogNeverForceReconnects() {
        assertFalse(ReconnectPolicy.shouldForceReconnectOnStall())
    }

    @Test
    fun audioTrackSinkDoesNotPauseOnRouteChange() {
        assertFalse(ReconnectPolicy.shouldPauseOnAudioRouteChange(useAudioTrackSink = true))
        assertTrue(ReconnectPolicy.shouldPauseOnAudioRouteChange(useAudioTrackSink = false))
    }

    @Test
    fun latestTransportCommandWins() {
        var activeJobId = 0
        fun submit(id: Int, prior: Int): Int {
            check(id > prior) { "commands are monotonically issued" }
            return id
        }
        activeJobId = submit(1, activeJobId)
        activeJobId = submit(2, activeJobId)
        activeJobId = submit(3, activeJobId)
        assertEquals(3, activeJobId)
    }
}

class TransportHandoffTrackerTest {

    private val wifi = 1
    private val cell = 0

    @Test
    fun singleTransportBlipIsIgnored() {
        val t = TransportHandoffTracker()
        t.commitTransport(cell)
        assertFalse(t.onCapabilities(wifi, validated = true, playingOrReconnecting = true))
        assertEquals(1, t.confirmCount)
    }

    @Test
    fun twoValidatedSamplesConfirmHandoff() {
        val t = TransportHandoffTracker()
        t.commitTransport(cell)
        assertFalse(t.onCapabilities(wifi, validated = true, playingOrReconnecting = true))
        assertTrue(t.onCapabilities(wifi, validated = true, playingOrReconnecting = true))
    }

    @Test
    fun unvalidatedSamplesNeverConfirm() {
        val t = TransportHandoffTracker()
        t.commitTransport(cell)
        assertFalse(t.onCapabilities(wifi, validated = false, playingOrReconnecting = true))
        assertFalse(t.onCapabilities(wifi, validated = false, playingOrReconnecting = true))
        assertFalse(t.onCapabilities(wifi, validated = false, playingOrReconnecting = true))
    }

    @Test
    fun sameTransportFlapResetsCounter() {
        val t = TransportHandoffTracker()
        t.commitTransport(cell)
        assertFalse(t.onCapabilities(wifi, validated = true, playingOrReconnecting = true))
        assertFalse(t.onCapabilities(cell, validated = true, playingOrReconnecting = true))
        assertEquals(0, t.confirmCount)
        assertFalse(t.onCapabilities(wifi, validated = true, playingOrReconnecting = true))
        assertEquals(1, t.confirmCount)
    }

    @Test
    fun pausedDoesNotConfirmEvenAfterTwoSamples() {
        val t = TransportHandoffTracker()
        t.commitTransport(cell)
        t.onCapabilities(wifi, validated = true, playingOrReconnecting = false)
        assertFalse(t.onCapabilities(wifi, validated = true, playingOrReconnecting = false))
    }

    @Test
    fun promotingLastTransportOnFirstSampleMakesConfirmUnreachable() {
        // Characterization of the pre-fix controller: assigning lastTransport on
        // the first wifi sample made the second sample look like "same transport"
        // and reset the counter. Two samples could never confirm.
        var last: Int? = cell
        var pending: Int? = null
        var count = 0
        fun sample(transport: Int) {
            if (last != null && transport != last) {
                if (pending == transport) count++ else {
                    pending = transport
                    count = 1
                }
            } else if (transport == last) {
                pending = null
                count = 0
            }
            last = transport
        }
        sample(wifi)
        sample(wifi)
        assertTrue("buggy last= assignment never reaches 2", count < 2)
    }

    @Test
    fun rapidCellularWifiFlappingNeverConfirms() {
        val t = TransportHandoffTracker()
        t.commitTransport(cell)
        repeat(50) { i ->
            val next = if (i % 2 == 0) wifi else cell
            assertFalse(
                "flap $i confirmed a handoff",
                t.onCapabilities(next, validated = true, playingOrReconnecting = true),
            )
        }
    }
}

class ReconnectCooldownTest {

    @Test
    fun debounceDropsUntilWindowElapses() {
        val c = ReconnectCooldown(debounceMs = 6_000, nativeCooldownMs = 5_000)
        c.schedule(0)
        assertFalse(c.fireDue(5_999))
        assertTrue(c.fireDue(6_000))
    }

    @Test
    fun newerScheduleReplacesPending() {
        val c = ReconnectCooldown(debounceMs = 6_000, nativeCooldownMs = 5_000)
        c.schedule(0)
        c.schedule(4_000)
        assertFalse(c.fireDue(6_000))
        assertTrue(c.fireDue(10_000))
    }

    @Test
    fun nativeCooldownBlocksSecondTeardown() {
        val c = ReconnectCooldown(debounceMs = 0, nativeCooldownMs = 5_000)
        assertTrue(c.tryNative(0))
        assertFalse(c.tryNative(4_999))
        assertTrue(c.tryNative(5_000))
    }

    @Test
    fun burstOfHandoffsCollapsesToOneNativeTeardown() {
        val c = ReconnectCooldown()
        var native = 0
        c.schedule(0)
        c.schedule(1_000)
        c.schedule(2_000)
        if (c.fireDue(8_000)) native++
        c.schedule(8_500)
        if (c.fireDue(9_000)) native++
        assertEquals(1, native)
    }
}
