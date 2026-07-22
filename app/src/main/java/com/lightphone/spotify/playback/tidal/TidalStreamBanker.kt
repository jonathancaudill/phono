package com.lightphone.spotify.playback.tidal

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.dash.offline.DashDownloader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Banks the remainder of the current TIDAL track into the stream LRU.
 *
 * Progressive BTS → [CacheWriter]; ClearDash → [DashDownloader] into stream cache
 * (never pins / DownloadManager). Playback outranks this work via [PriorityTaskManager].
 */
@UnstableApi
internal class TidalStreamBanker(
    context: Context,
    private val priorityTaskManager: PriorityTaskManager,
) {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tidal-bank").apply { isDaemon = true }
    }

    private val generation = AtomicInteger(0)
    private val activeWriter = AtomicReference<CacheWriter?>(null)
    private val activeDownloader = AtomicReference<DashDownloader?>(null)
    private val inFlight = AtomicReference<Future<*>?>(null)

    @Volatile
    var isBanking: Boolean = false
        private set

    /**
     * Cancel any in-flight bank and start banking [item] from [positionMs] through end.
     * No-op if the item still needs resolve (caller must resolve first).
     */
    fun bankCurrentToEnd(item: MediaItem, positionMs: Long) {
        val uri = item.localConfiguration?.uri ?: return
        if (uri == Uri.EMPTY || uri.scheme == TidalMediaCache.STREAM_SCHEME) return

        val gen = generation.incrementAndGet()
        cancelActiveWork()

        val future = executor.submit {
            isBanking = true
            try {
                if (gen != generation.get()) return@submit
                priorityTaskManager.add(BANK_PRIORITY)
                try {
                    if (isDash(item)) {
                        bankDash(item, positionMs, gen)
                    } else {
                        bankProgressive(item, gen)
                    }
                } finally {
                    priorityTaskManager.remove(BANK_PRIORITY)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.d(TAG, "bank interrupted")
            } catch (e: Exception) {
                if (gen == generation.get()) {
                    Log.w(TAG, "bank failed for ${item.mediaId}: ${e.message?.take(160)}")
                }
            } finally {
                if (gen == generation.get()) {
                    isBanking = false
                    activeWriter.set(null)
                    activeDownloader.set(null)
                }
            }
        }
        inFlight.set(future)
    }

    /** Bump generation and cancel writers so a skip does not keep burning CDN. */
    fun cancel() {
        generation.incrementAndGet()
        cancelActiveWork()
        isBanking = false
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
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
        val dataSource = TidalMediaCache.streamBankDataSourceFactory(
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
        val factory = TidalMediaCache.streamBankDataSourceFactory(
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

    private fun isDash(item: MediaItem): Boolean {
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

    companion object {
        private const val TAG = "TidalBank"
        /** Below [C.PRIORITY_PLAYBACK]; above background processing. */
        val BANK_PRIORITY: Int = C.PRIORITY_DOWNLOAD
    }
}
