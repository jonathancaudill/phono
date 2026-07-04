package com.lightphone.spotify.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.lightphone.spotify.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * Native Android PCM output for librespot playback (Phase C).
 *
 * Rust drain thread calls [writePcmDirect] / [writePcmDirectBytes] via JNI.
 * Routing, [AudioAttributes], and recreate policy live here — not in cpal/AAudio.
 */
object PhonoAudioTrackSink {
    private const val TAG = "PhonoAudioTrack"
    private const val DEFAULT_SAMPLE_RATE = 44100
    private const val DEFAULT_CHANNELS = 2
    private const val FRAME_BYTES_STEREO_S16 = 4
    private const val MIN_RECREATE_INTERVAL_MS = 2_000L
    private const val STALL_RECREATE_MS = 200L
    private const val LOG_INTERVAL_MS = 5_000L
    private const val STALL_POLL_MS = 100L
    private const val DIRECT_BUFFER_BYTES = 8192

    private val lock = ReentrantLock()
    private var track: AudioTrack? = null
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var channels = DEFAULT_CHANNELS
    private var bytesPerFrame = FRAME_BYTES_STEREO_S16
    private var lastVolume = 1.0f
    private var transportPaused = false
    private var directWriteBuffer: ByteBuffer? = null
    private var writePending = ByteArray(0)

    private val positionTracker = PhonoAudioPositionTracker()
    private val coordinator = PhonoAudioSinkCoordinator()

    private val routingEventCount = AtomicInteger(0)
    private val deadObjectCount = AtomicInteger(0)
    private val writeErrorCount = AtomicInteger(0)
    private val recreateCount = AtomicInteger(0)
    private val drainPartialWrites = AtomicInteger(0)
    private val routingIgnoredCount = AtomicInteger(0)

    private var routeThread: HandlerThread? = null
    private var routeHandler: Handler? = null
    private val stallWatchRunnable = object : Runnable {
        override fun run() {
            val handler = routeHandler ?: return
            val keepWatching = lock.withLock {
                val t = track ?: return@withLock false
                if (transportPaused || t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    positionTracker.clearStall()
                    return@withLock false
                }
                val stalledMs = positionTracker.stalledMs(t, sampleRate, bytesPerFrame)
                if (stalledMs >= STALL_RECREATE_MS) {
                    Log.w(TAG, "playhead stalled ${stalledMs}ms — scheduling recreate")
                    coordinatorRecreate("stalled")
                }
                true
            }
            if (keepWatching) {
                handler.postDelayed(this, STALL_POLL_MS)
            }
        }
    }

    /** Exposed for [NativeInit.registerAudioSink] direct-buffer registration. */
    @JvmStatic
    fun prepareDirectBuffer(size: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(max(size, DIRECT_BUFFER_BYTES))
            .order(ByteOrder.LITTLE_ENDIAN)
        directWriteBuffer = buf
        return buf
    }

    @JvmStatic
    fun ensureInitialized() {
        lock.withLock {
            if (routeThread == null) {
                routeThread = HandlerThread("PhonoAudioRoute").apply { start() }
                routeHandler = Handler(routeThread!!.looper)
            }
        }
    }

    private fun cancelStallWatchCallbacks() {
        routeHandler?.removeCallbacks(stallWatchRunnable)
    }

    private fun startStallWatch() {
        cancelStallWatchCallbacks()
        routeHandler?.postDelayed(stallWatchRunnable, STALL_POLL_MS)
    }

    private fun stopStallWatch() {
        cancelStallWatchCallbacks()
        lock.withLock { positionTracker.clearStall() }
    }

    @JvmStatic
    fun start(sampleRate: Int, channels: Int): Boolean {
        lock.withLock {
            this.sampleRate = sampleRate
            this.channels = channels
            bytesPerFrame = channels * 2
            transportPaused = false
            writePending = ByteArray(0)
            positionTracker.reset()
            releaseTrackLocked()
            return createAndPlayLocked()
        }
    }

    @JvmStatic
    fun writePcmDirect(buffer: ByteBuffer, size: Int): Int {
        lock.withLock {
            val t = track ?: return AudioTrack.ERROR_INVALID_OPERATION
            if (!buffer.isDirect) return AudioTrack.ERROR_BAD_VALUE
            return writeNonBlockingLocked(t, buffer, size)
        }
    }

    @JvmStatic
    fun writePcmDirectBytes(data: ByteArray, offset: Int, length: Int): Int {
        lock.withLock {
            val t = track ?: return AudioTrack.ERROR_INVALID_OPERATION
            if (length <= 0) return 0
            // Drain thread is dedicated — blocking write is correct here (player thread never blocks).
            val written = t.write(data, offset, length, AudioTrack.WRITE_BLOCKING)
            return handleWriteResultLocked(written, length)
        }
    }

    @JvmStatic
    fun flush() {
        lock.withLock {
            writePending = ByteArray(0)
            positionTracker.resetWrittenFrames()
            runCatching { track?.flush() }
        }
    }

    /** Transport pause — keep ring + track state; no flush. */
    @JvmStatic
    fun pauseOutput() {
        lock.withLock {
            transportPaused = true
            runCatching {
                track?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.pause()
            }
        }
        stopStallWatch()
    }

    @JvmStatic
    fun resumeOutput() {
        lock.withLock {
            transportPaused = false
            runCatching {
                track?.takeIf { it.playState != AudioTrack.PLAYSTATE_PLAYING }?.play()
            }
        }
        if (track != null) {
            startStallWatch()
        }
    }

    @JvmStatic
    fun stop() {
        stopStallWatch()
        lock.withLock {
            transportPaused = false
            writePending = ByteArray(0)
            track?.let { t ->
                runCatching {
                    if (t.playState != AudioTrack.PLAYSTATE_STOPPED) {
                        t.stop()
                    }
                    t.flush()
                }
            }
            positionTracker.reset()
        }
    }

    @JvmStatic
    fun release() {
        lock.withLock { releaseTrackLocked() }
    }

    @JvmStatic
    fun requestRecreate() {
        routeHandler?.post { coordinatorRecreate("dead_object") }
    }

    @JvmStatic
    fun recreate(): Boolean {
        lock.withLock {
            recreateCount.incrementAndGet()
            val wasPlaying = track?.playState == AudioTrack.PLAYSTATE_PLAYING && !transportPaused
            releaseTrackLocked()
            writePending = ByteArray(0)
            positionTracker.reset()
            val ok = createAndPlayLocked()
            return ok && (wasPlaying || !transportPaused)
        }
    }

    @JvmStatic
    fun setVolume(volume: Float): Boolean {
        lastVolume = volume.coerceIn(0f, 1f)
        track?.setVolume(lastVolume)
        return true
    }

    @JvmStatic
    fun getRoutingEventCount(): Int = routingEventCount.get()

    @JvmStatic
    fun getDeadObjectCount(): Int = deadObjectCount.get()

    @JvmStatic
    fun getWriteErrorCount(): Int = writeErrorCount.get()

    @JvmStatic
    fun getRecreateCount(): Int = recreateCount.get()

    @JvmStatic
    fun getDrainPartialWrites(): Int = drainPartialWrites.get()

    @JvmStatic
    fun getPendingOutputMs(): Int = lock.withLock {
        track?.let { positionTracker.pendingMs(it, sampleRate, bytesPerFrame) } ?: 0
    }

    /** Kotlin-side ring metric stub — Rust ring occupancy is authoritative in debug metrics. */
    @JvmStatic
    fun getRingOccupancyMs(): Int = 0

    /** Audible position adjustment for MediaSession (DelayMs). */
    @JvmStatic
    fun getOutputDelayMs(): Int = getPendingOutputMs()

    private fun createAndPlayLocked(): Boolean {
        ensureInitialized()
        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return false
        }
        val halfSecondBytes = sampleRate * bytesPerFrame / 2
        var bufferSize = max(minBuf * 4, halfSecondBytes)
        val minLatencyBytes = sampleRate * bytesPerFrame / 4
        val maxLatencyBytes = sampleRate * bytesPerFrame * 3 / 4
        bufferSize = bufferSize.coerceIn(minLatencyBytes, maxLatencyBytes)

        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val newTrack = buildTrackWithRetry(attributes, format, bufferSize, minBuf * 4)
                ?: return false

            positionTracker.onTrackCreated(newTrack)
            newTrack.addOnRoutingChangedListener({ routedTrack ->
                routingEventCount.incrementAndGet()
                val deviceId = routedTrack.routedDevice?.id ?: Int.MIN_VALUE
                positionTracker.onRoutingChanged(deviceId)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "routing event deviceId=$deviceId (capabilities-first, no recreate)")
                }
            }, routeHandler)

            newTrack.setVolume(lastVolume)
            if (!transportPaused) {
                newTrack.play()
                startStallWatch()
            }
            track = newTrack
            Log.i(
                TAG,
                "AudioTrack started rate=$sampleRate ch=$channels buf=$bufferSize " +
                    "minBuf=$minBuf routedDevice=${newTrack.routedDevice?.id}",
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack create failed", e)
            false
        }
    }

    private fun buildTrackWithRetry(
        attributes: AudioAttributes,
        format: AudioFormat,
        bufferSize: Int,
        retrySize: Int,
    ): AudioTrack? {
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack build failed buf=$bufferSize, retrying", e)
            try {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(retrySize)
                    .build()
            } catch (e2: Exception) {
                Log.e(TAG, "AudioTrack retry build failed", e2)
                null
            }
        }
    }

    private fun writeNonBlockingLocked(t: AudioTrack, buffer: ByteBuffer, size: Int): Int {
        if (writePending.isNotEmpty()) {
            val pendingWritten = t.write(writePending, 0, writePending.size, AudioTrack.WRITE_BLOCKING)
            val handled = handleWriteResultLocked(pendingWritten, writePending.size)
            if (handled <= 0) return handled
            if (handled < writePending.size) {
                drainPartialWrites.incrementAndGet()
                writePending = writePending.copyOfRange(handled, writePending.size)
                return handled
            }
            writePending = ByteArray(0)
        }

        val dup = buffer.duplicate()
        dup.position(buffer.position())
        dup.limit(buffer.position() + size)
        val written = t.write(dup, size, AudioTrack.WRITE_BLOCKING)
        val handled = handleWriteResultLocked(written, size)
        if (handled <= 0) return handled
        if (handled < size) {
            drainPartialWrites.incrementAndGet()
            val slice = ByteArray(size - handled)
            dup.position(buffer.position() + handled)
            dup.get(slice)
            writePending = slice
        }
        return handled
    }

    private fun coordinatorRecreate(reason: String) {
        if (!coordinator.tryBeginRecreate()) return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - coordinator.lastRecreateAtMs < MIN_RECREATE_INTERVAL_MS) {
                routingIgnoredCount.incrementAndGet()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "recreate debounced reason=$reason")
                }
                return
            }
            lock.withLock {
                if (track == null) return@withLock
                coordinator.lastRecreateAtMs = now
                Log.i(TAG, "Recreating AudioTrack reason=$reason")
                recreateCount.incrementAndGet()
                val wasPlaying = track?.playState == AudioTrack.PLAYSTATE_PLAYING && !transportPaused
                releaseTrackLocked()
                writePending = ByteArray(0)
                positionTracker.reset()
                if (wasPlaying) {
                    createAndPlayLocked()
                } else {
                    stopStallWatch()
                }
            }
        } finally {
            coordinator.endRecreate()
        }
    }

    private fun handleWriteResultLocked(written: Int, attempted: Int): Int {
        if (written > 0) {
            val frames = written / bytesPerFrame
            positionTracker.onFramesWritten(frames.toLong())
            positionTracker.maybeLogStats(track, sampleRate, bytesPerFrame)
            return written
        }
        if (written == 0) {
            // WRITE_NON_BLOCKING backpressure — not an error.
            return 0
        }
        writeErrorCount.incrementAndGet()
        when (written) {
            AudioTrack.ERROR_DEAD_OBJECT -> {
                deadObjectCount.incrementAndGet()
                Log.w(TAG, "ERROR_DEAD_OBJECT — scheduling track recreate")
                routeHandler?.post { coordinatorRecreate("dead_object") }
            }
            AudioTrack.ERROR_INVALID_OPERATION -> {
                Log.w(TAG, "ERROR_INVALID_OPERATION on write — retry play()")
                runCatching {
                    track?.takeIf { !transportPaused }?.play()
                }
            }
            else -> Log.w(TAG, "AudioTrack write error: $written attempted=$attempted")
        }
        return written
    }

    private fun releaseTrackLocked() {
        cancelStallWatchCallbacks()
        positionTracker.clearStall()
        track?.let { t ->
            runCatching {
                if (t.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    t.stop()
                }
                t.flush()
                t.release()
            }.onFailure { e -> Log.w(TAG, "releaseTrack error", e) }
        }
        track = null
        positionTracker.reset()
    }
}

/** ExoPlayer-style playhead smoothing and pending latency estimate. */
private class PhonoAudioPositionTracker {
    private companion object {
        const val LOG_INTERVAL_MS = 5_000L
    }

    private var totalFramesWritten = 0L
    private var lastRoutedDeviceId = Int.MIN_VALUE
    private var lastLogAtMs = 0L
    private var writeCallCount = 0L
    private var lastPlayedFrames = 0L
    private var lastPlayedAtMs = 0L
    private var stallStartedAtMs = 0L

    fun reset() {
        totalFramesWritten = 0L
        lastPlayedFrames = 0L
        lastPlayedAtMs = 0L
        stallStartedAtMs = 0L
        writeCallCount = 0L
        lastLogAtMs = SystemClock.elapsedRealtime()
    }

    fun resetWrittenFrames() {
        totalFramesWritten = 0L
    }

    fun onTrackCreated(track: AudioTrack) {
        lastRoutedDeviceId = track.routedDevice?.id ?: Int.MIN_VALUE
        reset()
    }

    fun onRoutingChanged(deviceId: Int) {
        lastRoutedDeviceId = deviceId
    }

    fun onFramesWritten(frames: Long) {
        totalFramesWritten += frames
        writeCallCount++
    }

    fun pendingMs(track: AudioTrack, sampleRate: Int, bytesPerFrame: Int): Int {
        val pendingFrames = pendingFrames(track)
        return (pendingFrames * 1000L / sampleRate).toInt().coerceAtLeast(0)
    }

    fun stalledMs(track: AudioTrack, sampleRate: Int, bytesPerFrame: Int): Long {
        val played = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val now = SystemClock.elapsedRealtime()
        if (played != lastPlayedFrames) {
            lastPlayedFrames = played
            lastPlayedAtMs = now
            stallStartedAtMs = 0L
            return 0L
        }
        if (pendingFrames(track) <= sampleRate / 20) {
            stallStartedAtMs = 0L
            return 0L
        }
        if (stallStartedAtMs == 0L) {
            stallStartedAtMs = now
        }
        return now - stallStartedAtMs
    }

    fun clearStall() {
        stallStartedAtMs = 0L
    }

    fun maybeLogStats(track: AudioTrack?, sampleRate: Int, bytesPerFrame: Int) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogAtMs < LOG_INTERVAL_MS) return
        lastLogAtMs = now
        val t = track ?: return
        val pending = pendingFrames(t)
        Log.i(
            "PhonoAudioTrack",
            "pcm stats writes=$writeCallCount writtenFrames=$totalFramesWritten " +
                "playedFrames=${t.playbackHeadPosition} pendingFrames=$pending " +
                "(~${pending * 1000 / sampleRate}ms)",
        )
    }

    private fun pendingFrames(track: AudioTrack): Long {
        val played = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        var pending = totalFramesWritten - played
        if (pending < 0) pending += 0x1_0000_0000L
        return pending
    }
}

private class PhonoAudioSinkCoordinator {
    private val recreating = AtomicBoolean(false)
    @Volatile var lastRecreateAtMs = 0L

    fun tryBeginRecreate(): Boolean = recreating.compareAndSet(false, true)

    fun endRecreate() {
        recreating.set(false)
    }
}
