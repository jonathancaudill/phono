package com.lightphone.spotify.playback.media3

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.dash.offline.DashDownloader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Banks progressive / clear-DASH [MediaItem]s into a stream LRU via
 * [CacheWriter] / [DashDownloader]. Playback must outrank this work through
 * the shared [PriorityTaskManager].
 *
 * Upstream factories are injected so TIDAL (HTTP) and future Spotify
 * (decrypt DataSource) share the same banker.
 */
@UnstableApi
class StreamBanker(
    context: Context,
    private val priorityTaskManager: PriorityTaskManager,
    private val bankDataSourceFactory: (
        Context,
        PriorityTaskManager,
        Int,
    ) -> CacheDataSource.Factory,
    private val threadName: String = "stream-bank",
    /**
     * Pseudo-schemes that mean "not yet resolved" (e.g. tidalstream://).
     * Banking is a no-op until the item has a real URI.
     */
    private val unresolvedSchemes: Set<String> = emptySet(),
    private val onEvent: ((BankEvent) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, threadName).apply { isDaemon = true }
    }

    private val generation = AtomicInteger(0)
    private val activeWriter = AtomicReference<CacheWriter?>(null)
    private val activeDownloader = AtomicReference<DashDownloader?>(null)
    private val inFlight = AtomicReference<Future<*>?>(null)
    private val idleLatch = AtomicReference(CountDownLatch(0))
    private val gate = Any()

    @Volatile
    var isBanking: Boolean = false
        private set

    /**
     * Mark banking busy before work is posted onto the player thread so
     * [awaitBankIdle] cannot return early. Pair with [clearPendingIfIdle] if
     * the posted work decides not to bank.
     */
    fun markPending() {
        synchronized(gate) {
            if (!isBanking) {
                isBanking = true
                if (idleLatch.get().count == 0L) {
                    idleLatch.set(CountDownLatch(1))
                }
            }
        }
    }

    fun clearPendingIfIdle() {
        synchronized(gate) {
            val future = inFlight.get()
            if (future != null && !future.isDone) return
            if (isBanking) {
                isBanking = false
                signalIdle()
            }
        }
    }

    /**
     * Cancel any in-flight bank and start banking [item] from [positionMs] through end.
     * No-op if the item still needs resolve.
     */
    fun bankCurrentToEnd(item: MediaItem, positionMs: Long) {
        submit(item, positionMs, warm = false)
    }

    /**
     * Warm an upcoming resolved item into the stream LRU (full resource from start).
     * Same machinery as [bankCurrentToEnd]; marked warm for events / metrics.
     */
    fun warmItem(item: MediaItem) {
        submit(item, positionMs = 0L, warm = true)
    }

    /**
     * Block until the current bank/warm finishes, is cancelled, or [timeoutMs] elapses.
     * Returns true if idle (complete/cancel/already idle); false on timeout.
     */
    fun awaitBankIdle(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        while (true) {
            if (!isBanking) return true
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (remainingMs == 0L) return !isBanking
            val latch = idleLatch.get()
            try {
                val slice = remainingMs.coerceAtMost(250L)
                if (latch.await(slice, TimeUnit.MILLISECONDS)) {
                    if (!isBanking) return true
                    // Latch opened but a new bank may have started — loop.
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return !isBanking
            }
        }
    }

    /** Bump generation and cancel writers so a skip does not keep burning CDN. */
    fun cancel() {
        val mediaId = lastMediaId
        generation.incrementAndGet()
        cancelActiveWork()
        synchronized(gate) {
            if (isBanking) {
                isBanking = false
                signalIdle()
                if (mediaId != null) emit(BankEvent.Cancelled(mediaId))
            }
        }
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    @Volatile
    private var lastMediaId: String? = null

    private fun submit(item: MediaItem, positionMs: Long, warm: Boolean) {
        val uri = item.localConfiguration?.uri ?: return
        if (uri == Uri.EMPTY) return
        val scheme = uri.scheme
        if (scheme != null && scheme in unresolvedSchemes) return

        val gen = generation.incrementAndGet()
        cancelActiveWork()
        lastMediaId = item.mediaId

        val latch = synchronized(gate) {
            isBanking = true
            val existing = idleLatch.get()
            if (existing.count > 0L) {
                existing
            } else {
                CountDownLatch(1).also { idleLatch.set(it) }
            }
        }
        emit(BankEvent.Started(item.mediaId, warm))

        val future = executor.submit {
            try {
                if (gen != generation.get()) {
                    emit(BankEvent.Cancelled(item.mediaId))
                    return@submit
                }
                priorityTaskManager.add(BANK_PRIORITY)
                try {
                    if (isDash(item)) {
                        bankDash(item, positionMs, gen)
                    } else {
                        bankProgressive(item, gen)
                    }
                    if (gen == generation.get()) {
                        emit(BankEvent.Complete(item.mediaId, warm))
                    } else {
                        emit(BankEvent.Cancelled(item.mediaId))
                    }
                } finally {
                    priorityTaskManager.remove(BANK_PRIORITY)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.d(TAG, "bank interrupted")
                emit(BankEvent.Cancelled(item.mediaId))
            } catch (e: Exception) {
                if (gen == generation.get()) {
                    Log.w(TAG, "bank failed for ${item.mediaId}: ${e.message?.take(160)}")
                    emit(BankEvent.Failed(item.mediaId, e.message?.take(160)))
                } else {
                    emit(BankEvent.Cancelled(item.mediaId))
                }
            } finally {
                synchronized(gate) {
                    if (gen == generation.get()) {
                        isBanking = false
                        activeWriter.set(null)
                        activeDownloader.set(null)
                        latch.countDown()
                    }
                }
            }
        }
        inFlight.set(future)
    }

    private fun signalIdle() {
        idleLatch.get().countDown()
    }

    private fun emit(event: BankEvent) {
        runCatching { onEvent?.invoke(event) }
    }

    private fun cancelActiveWork() {
        activeWriter.getAndSet(null)?.cancel()
        activeDownloader.getAndSet(null)?.cancel()
        inFlight.getAndSet(null)?.cancel(true)
    }

    private fun bankProgressive(item: MediaItem, gen: Int) {
        val local = item.localConfiguration ?: return
        val cacheKey = local.customCacheKey ?: local.uri.toString()
        val dataSpec = DataSpec.Builder()
            .setUri(local.uri)
            .setKey(cacheKey)
            .build()
        val dataSource = bankDataSourceFactory(
            appContext,
            priorityTaskManager,
            BANK_PRIORITY,
        ).createDataSourceForDownloading()
        val writer = CacheWriter(dataSource, dataSpec, /* temporaryBuffer= */ null, /* listener= */ null)
        activeWriter.set(writer)
        if (gen != generation.get()) {
            writer.cancel()
            return
        }
        Log.i(TAG, "banking progressive ${item.mediaId}")
        writer.cache()
        Log.i(TAG, "banked progressive ${item.mediaId}")
    }

    private fun bankDash(item: MediaItem, positionMs: Long, gen: Int) {
        val factory = bankDataSourceFactory(
            appContext,
            priorityTaskManager,
            BANK_PRIORITY,
        )
        val startUs = (positionMs.coerceAtLeast(0L) * 1_000L)
        val downloader = DashDownloader.Factory(factory)
            .setStartPositionUs(startUs)
            .setDurationUs(C.TIME_UNSET)
            .create(item)
        activeDownloader.set(downloader)
        if (gen != generation.get()) {
            downloader.cancel()
            return
        }
        Log.i(TAG, "banking dash ${item.mediaId} from ${positionMs}ms")
        downloader.download(/* progressListener= */ null)
        Log.i(TAG, "banked dash ${item.mediaId}")
    }

    companion object {
        private const val TAG = "StreamBank"
        /** Below [C.PRIORITY_PLAYBACK]; above background processing. */
        val BANK_PRIORITY: Int = C.PRIORITY_DOWNLOAD

        fun isDash(item: MediaItem): Boolean {
            val mime = item.localConfiguration?.mimeType
            if (mime != null && (
                    mime.equals(MimeTypes.APPLICATION_MPD, ignoreCase = true) ||
                        mime.contains("dash", ignoreCase = true)
                    )
            ) {
                return true
            }
            val path = item.localConfiguration?.uri?.path ?: return false
            return path.endsWith(".mpd", ignoreCase = true)
        }
    }
}
