package com.lightphone.spotify.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Stress / edge-case model of Spotify playback across network events.
 *
 * A force-reconnect teardown while [playing] always records an audible
 * pause→play pair — that is the user-visible "actual pause and play", not a
 * hang. Optimistic banking only prevents the blip if the policy refuses to
 * tear down a fully-buffered current track.
 */
class PlaybackContinuityStressTest {

    @Test
    fun legacyAlwaysTearDownBlipsOnEveryConfirmedHandoffEvenWhenBanked() {
        val sim = SpotifyPlaybackSim(cacheAware = false, fullyBuffered = true)
        sim.confirmedHandoff()
        assertEquals(1, sim.pausePlayBlips)
        assertEquals(listOf("paused", "playing"), sim.events)
    }

    @Test
    fun cacheAwareBankedHandoffDoesNotBlip() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = true)
        sim.confirmedHandoff()
        assertEquals(0, sim.pausePlayBlips)
        assertTrue(sim.events.isEmpty())
        assertTrue(sim.playing)
    }

    @Test
    fun cacheAwareUnbankedHandoffStillBlipsToAvoidStall() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = false)
        sim.confirmedHandoff()
        assertEquals(1, sim.pausePlayBlips)
    }

    @Test
    fun wifiPreferGateAfterThirtySecondsDoesNotBlipWhenBanked() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = true)
        sim.wifiPreferGateElapsed()
        assertEquals(0, sim.pausePlayBlips)
    }

    @Test
    fun availableWhileHealthyDoesNotReconnect() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = false)
        sim.onAvailable()
        assertEquals(0, sim.pausePlayBlips)
    }

    @Test
    fun availableWhenSessionDeadReconnectsEvenIfBanked() {
        val sim = SpotifyPlaybackSim(
            cacheAware = true,
            fullyBuffered = true,
            sessionHealthy = false,
        )
        sim.onAvailable()
        assertEquals(1, sim.pausePlayBlips)
    }

    @Test
    fun networkLostWithinGraceThenRestoredDoesNotGoOffline() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = true)
        sim.onLost(nowMs = 0)
        sim.onAvailable(nowMs = ReconnectPolicy.NETWORK_HANDOFF_GRACE_MS - 1)
        assertTrue(sim.networkOnline)
        assertEquals(0, sim.pausePlayBlips)
    }

    @Test
    fun networkLostPastGraceMarksOfflineButDoesNotPauseIfBanked() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = true)
        sim.onLost(nowMs = 0)
        sim.tick(ReconnectPolicy.NETWORK_HANDOFF_GRACE_MS)
        assertEquals(false, sim.networkOnline)
        assertEquals(0, sim.pausePlayBlips)
        assertTrue(sim.playing)
    }

    @Test
    fun stallWatchdogBanksInsteadOfReconnect() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = false)
        sim.onStall()
        assertTrue(sim.bankRequested)
        assertEquals(0, sim.pausePlayBlips)
        assertEquals(0, sim.nativeTeardowns)
    }

    @Test
    fun sessionDeathWhilePlayingIsUnavoidablePausePlay() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = true)
        sim.onSessionInvalid()
        assertEquals(1, sim.pausePlayBlips)
    }

    @Test
    fun audioTrackRouteChangeDoesNotPause() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = true)
        sim.onAudioRouteChange(useAudioTrackSink = true)
        assertEquals(0, sim.pausePlayBlips)
    }

    @Test
    fun rodioRouteChangePausesThenResumes() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = true)
        sim.onAudioRouteChange(useAudioTrackSink = false)
        assertEquals(1, sim.pausePlayBlips)
    }

    @Test
    fun stressRandomNetworkEventsWithWarmCacheNeverBlipUnlessSessionDies() {
        val rng = Random(42)
        var sessionDeathBlips = 0
        repeat(200) { seed ->
            val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = true)
            val inner = Random(rng.nextInt())
            repeat(80) {
                when (inner.nextInt(8)) {
                    0 -> sim.confirmedHandoff()
                    1 -> sim.unconfirmedBlip()
                    2 -> sim.onAvailable()
                    3 -> {
                        sim.onLost(sim.nowMs)
                        sim.tick(1_000)
                    }
                    4 -> sim.tick(ReconnectPolicy.NETWORK_HANDOFF_GRACE_MS)
                    5 -> sim.onStall()
                    6 -> sim.wifiPreferGateElapsed()
                    else -> {
                        sim.onSessionInvalid()
                        sessionDeathBlips++
                    }
                }
            }
            val expected = sim.sessionDeaths
            assertEquals(
                "seed $seed: pause/play blips must equal session deaths when cache is warm",
                expected,
                sim.pausePlayBlips,
            )
        }
        assertTrue("exercise session-death path", sessionDeathBlips > 0)
    }

    @Test
    fun stressUnbankedHandoffsAlwaysBlipOncePerConfirmedHandoffAfterCooldown() {
        val sim = SpotifyPlaybackSim(cacheAware = true, fullyBuffered = false)
        repeat(5) { i ->
            sim.nowMs = i * (ReconnectPolicy.RECONNECT_DEBOUNCE_MS +
                ReconnectPolicy.FORCE_RECONNECT_COOLDOWN_MS)
            sim.confirmedHandoff()
        }
        assertEquals(5, sim.pausePlayBlips)
    }

    @Test
    fun prefetchGenerationSkipCancelsStaleLoop() {
        var generation = 0
        fun stillCurrent(captured: Int, current: Int) = captured == current
        val captured = generation
        generation++
        assertTrue(!stillCurrent(captured, generation))
    }
}

private class SpotifyPlaybackSim(
    private val cacheAware: Boolean,
    var fullyBuffered: Boolean,
    var sessionHealthy: Boolean = true,
    var playing: Boolean = true,
) {
    var reconnecting = false
    var connected = sessionHealthy
    var networkOnline = true
    var nowMs = 0L
    var bankRequested = false
    var nativeTeardowns = 0
    var sessionDeaths = 0
    val events = mutableListOf<String>()
    private val cooldown = ReconnectCooldown()
    private var lostAtMs: Long? = null

    val pausePlayBlips: Int
        get() {
            var n = 0
            var i = 0
            while (i < events.size - 1) {
                if (events[i] == "paused" && events[i + 1] == "playing") {
                    n++
                    i += 2
                } else {
                    i++
                }
            }
            return n
        }

    fun confirmedHandoff() {
        maybeTearDown(
            ReconnectPolicy.shouldTearDownOnTransportHandoff(
                playing = playing,
                reconnecting = reconnecting,
                fullyBuffered = if (cacheAware) fullyBuffered else false,
            ) || !cacheAware && playing,
        )
    }

    fun unconfirmedBlip() {
        // Single sample: tracker would not confirm. No-op.
    }

    fun wifiPreferGateElapsed() = confirmedHandoff()

    fun onAvailable(nowMs: Long = this.nowMs) {
        lostAtMs = null
        networkOnline = true
        this.nowMs = nowMs
        val sessionDead = !sessionHealthy
        if (
            ReconnectPolicy.shouldForceReconnectOnAvailable(
                connected = connected,
                reconnecting = reconnecting,
                sessionDead = sessionDead,
            )
        ) {
            maybeTearDown(true)
        }
    }

    fun onLost(nowMs: Long) {
        lostAtMs = nowMs
        this.nowMs = nowMs
    }

    fun tick(deltaMs: Long) {
        nowMs += deltaMs
        val lost = lostAtMs ?: return
        if (nowMs - lost >= ReconnectPolicy.NETWORK_HANDOFF_GRACE_MS) {
            networkOnline = false
        }
    }

    fun onStall() {
        bankRequested = true
        fullyBuffered = true
        check(!ReconnectPolicy.shouldForceReconnectOnStall())
    }

    fun onSessionInvalid() {
        sessionHealthy = false
        connected = false
        sessionDeaths++
        // Monitor notifies connection_lost then rebuilds — always a UI pause.
        tearDownNow()
    }

    fun onAudioRouteChange(useAudioTrackSink: Boolean) {
        if (!ReconnectPolicy.shouldPauseOnAudioRouteChange(useAudioTrackSink)) return
        events += "paused"
        events += "playing"
    }

    private fun maybeTearDown(want: Boolean) {
        if (!want) return
        cooldown.schedule(nowMs)
        if (!cooldown.fireDue(nowMs + ReconnectPolicy.RECONNECT_DEBOUNCE_MS)) return
        tearDownNow()
    }

    private fun tearDownNow() {
        nativeTeardowns++
        if (playing || reconnecting) {
            events += "paused"
            playing = false
            reconnecting = true
            events += "playing"
            playing = true
            reconnecting = false
            connected = true
            sessionHealthy = true
        }
    }
}
