package com.lightphone.spotify.playback.tidal

import android.content.Context
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * TIDAL Media3 caches: durable download **pins** + evictable streaming **LRU**.
 *
 * Pins live under [filesDir] with [NoOpCacheEvictor] (DownloadManager writes only).
 * Stream LRU under [cacheDir] receives playback write-through and opportunistic banking.
 * Playback reads stream → pins → HTTP; only the stream cache is writable from playback.
 */
@UnstableApi
object TidalMediaCache {
    const val STREAM_SCHEME = "tidalstream"
    private const val DOWNLOAD_CACHE_DIR = "tidal-downloads"
    private const val STREAM_CACHE_DIR = "tidal-stream"
    private const val MPD_DIR = "tidal-mpd"
    private const val USER_AGENT = "TIDAL_ANDROID/1039 okhttp/4.12.0"

    /** Enough for ≥ one LOSSLESS/Max current track without instant thrash. */
    const val STREAM_CACHE_BYTES: Long = 256L * 1024L * 1024L

    val CACHE_KEY_FACTORY = CacheKeyFactory { spec ->
        spec.key ?: spec.uri.toString()
    }

    @Volatile
    private var pinCacheInstance: SimpleCache? = null

    @Volatile
    private var streamCacheInstance: SimpleCache? = null

    @Volatile
    private var databaseProviderInstance: DatabaseProvider? = null

    fun databaseProvider(context: Context): DatabaseProvider =
        databaseProviderInstance ?: synchronized(this) {
            databaseProviderInstance ?: StandaloneDatabaseProvider(context.applicationContext)
                .also { databaseProviderInstance = it }
        }

    /** Durable NoOp pin cache under filesDir (DownloadManager writes here only). */
    fun cache(context: Context): SimpleCache =
        pinCacheInstance ?: synchronized(this) {
            pinCacheInstance ?: SimpleCache(
                File(context.applicationContext.filesDir, DOWNLOAD_CACHE_DIR).also { it.mkdirs() },
                NoOpCacheEvictor(),
                databaseProvider(context),
            ).also { pinCacheInstance = it }
        }

    /** Evictable streaming LRU under cacheDir (playback + bank writes). */
    fun streamCache(context: Context): SimpleCache =
        streamCacheInstance ?: synchronized(this) {
            streamCacheInstance ?: SimpleCache(
                File(context.applicationContext.cacheDir, STREAM_CACHE_DIR).also { it.mkdirs() },
                LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES),
                databaseProvider(context),
            ).also { streamCacheInstance = it }
        }

    /** ClearDash / resolve MPD scratch — same durability as pins. */
    fun mpdDir(context: Context): File =
        File(context.applicationContext.filesDir, MPD_DIR).also { it.mkdirs() }

    fun httpFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

    /**
     * Playback: stream LRU (writable) → pin cache (read-only) → HTTP.
     * Optional [priorityTaskManager] coordinates with opportunistic bankers.
     */
    fun cacheDataSourceFactory(
        context: Context,
        priorityTaskManager: PriorityTaskManager? = null,
        upstreamPriority: Int? = null,
    ): CacheDataSource.Factory {
        val app = context.applicationContext
        val http = DefaultDataSource.Factory(app, httpFactory())
        val pinReadOnly = CacheDataSource.Factory()
            .setCache(cache(app))
            .setUpstreamDataSourceFactory(http)
            .setCacheWriteDataSinkFactory(null)
            .setCacheKeyFactory(CACHE_KEY_FACTORY)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val playback = CacheDataSource.Factory()
            .setCache(streamCache(app))
            .setUpstreamDataSourceFactory(pinReadOnly)
            .setCacheKeyFactory(CACHE_KEY_FACTORY)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (priorityTaskManager != null) {
            playback.setUpstreamPriorityTaskManager(priorityTaskManager)
            if (upstreamPriority != null) {
                playback.setUpstreamPriority(upstreamPriority)
            }
        }
        return playback
    }

    /**
     * Opportunistic bank/download into the stream LRU (not pins).
     * Use [CacheDataSource.Factory.createDataSourceForDownloading] for writers.
     */
    fun streamBankDataSourceFactory(
        context: Context,
        priorityTaskManager: PriorityTaskManager,
        priority: Int,
    ): CacheDataSource.Factory {
        val app = context.applicationContext
        return CacheDataSource.Factory()
            .setCache(streamCache(app))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app, httpFactory()))
            .setCacheKeyFactory(CACHE_KEY_FACTORY)
            .setUpstreamPriorityTaskManager(priorityTaskManager)
            .setUpstreamPriority(priority)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Upstream for [androidx.media3.exoplayer.offline.DownloadManager]:
     * file:// MPDs + https CDN segments.
     */
    fun downloadUpstreamFactory(context: Context): DefaultDataSource.Factory =
        DefaultDataSource.Factory(context.applicationContext, httpFactory())

    /** True if [cacheKey] has cached bytes (completed offline pin). */
    fun hasCachedContent(context: Context, cacheKey: String): Boolean {
        val spans = cache(context).getCachedSpans(cacheKey)
        return spans.any { it.isCached && it.length > 0 }
    }

    /** Drop streaming LRU only — never touches download pins. */
    fun clearStreamCache(context: Context) {
        val stream = streamCache(context)
        for (key in stream.keys.toList()) {
            stream.removeResource(key)
        }
    }
}
