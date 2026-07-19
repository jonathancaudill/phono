package com.lightphone.spotify.playback.tidal

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.lightphone.spotify.data.tidal.TidalApiClient
import com.lightphone.spotify.data.tidal.TidalAudioQuality
import java.io.File

/**
 * Shared Media3 cache + data source factory for TIDAL streaming and downloads.
 *
 * Upstream is [DefaultDataSource] so `file://` clear-DASH MPDs and `https://`
 * CDN segments both work. Downloads still use a progressive-only resolver.
 */
@UnstableApi
object TidalMediaCache {
    const val STREAM_SCHEME = "tidalstream"
    private const val CACHE_DIR = "tidal-media"
    private const val USER_AGENT = "TIDAL_ANDROID/1039 okhttp/4.12.0"

    @Volatile
    private var cacheInstance: SimpleCache? = null

    @Volatile
    private var databaseProviderInstance: DatabaseProvider? = null

    fun databaseProvider(context: Context): DatabaseProvider =
        databaseProviderInstance ?: synchronized(this) {
            databaseProviderInstance ?: StandaloneDatabaseProvider(context.applicationContext)
                .also { databaseProviderInstance = it }
        }

    fun cache(context: Context): SimpleCache =
        cacheInstance ?: synchronized(this) {
            cacheInstance ?: SimpleCache(
                File(context.applicationContext.cacheDir, CACHE_DIR),
                NoOpCacheEvictor(),
                databaseProvider(context),
            ).also { cacheInstance = it }
        }

    fun httpFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

    fun cacheDataSourceFactory(context: Context, api: TidalApiClient): CacheDataSource.Factory {
        val app = context.applicationContext
        val upstream = DefaultDataSource.Factory(app, httpFactory())
        return CacheDataSource.Factory()
            .setCache(cache(app))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Progressive-only resolver for [TidalDownloadCenter] (BTS URLs). */
    fun resolvingUpstreamFactory(api: TidalApiClient): ResolvingDataSource.Factory =
        ResolvingDataSource.Factory(
            httpFactory(),
            ResolvingDataSource.Resolver { dataSpec ->
                val uri = dataSpec.uri
                if (uri.scheme != STREAM_SCHEME) return@Resolver dataSpec
                val trackId = uri.host ?: uri.lastPathSegment ?: error("no track id in $uri")
                val quality = uri.getQueryParameter("q") ?: TidalAudioQuality.DEFAULT.apiValue
                when (val resolved = TidalStreamResolve.resolve(api, trackId, quality)) {
                    is TidalResolvedStream.Progressive ->
                        dataSpec.buildUpon().setUri(Uri.parse(resolved.url)).build()
                    is TidalResolvedStream.ClearDash ->
                        throw java.io.IOException(
                            "Offline download needs progressive BTS; got clear DASH @ ${resolved.audioQuality}. " +
                                "Try a lower quality.",
                        )
                }
            },
        )
}
