package com.lightphone.spotify.playback.tidal

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import com.lightphone.spotify.playback.media3.BankEvent
import com.lightphone.spotify.playback.media3.StreamBanker

/**
 * TIDAL stream banker — thin wrapper around shared [StreamBanker].
 * Banks / warms into [TidalMediaCache] stream LRU only (never pins).
 */
@UnstableApi
internal class TidalStreamBanker(
    context: Context,
    priorityTaskManager: PriorityTaskManager,
    onEvent: ((BankEvent) -> Unit)? = null,
) {
    private val banker = StreamBanker(
        context = context,
        priorityTaskManager = priorityTaskManager,
        bankDataSourceFactory = { ctx, ptm, priority ->
            TidalMediaCache.streamBankDataSourceFactory(ctx, ptm, priority)
        },
        threadName = "tidal-bank",
        unresolvedSchemes = setOf(TidalMediaCache.STREAM_SCHEME),
        onEvent = onEvent,
    )

    val isBanking: Boolean get() = banker.isBanking

    fun markPending() = banker.markPending()

    fun clearPendingIfIdle() = banker.clearPendingIfIdle()

    fun bankCurrentToEnd(item: MediaItem, positionMs: Long) =
        banker.bankCurrentToEnd(item, positionMs)

    fun warmItem(item: MediaItem) = banker.warmItem(item)

    fun awaitBankIdle(timeoutMs: Long): Boolean = banker.awaitBankIdle(timeoutMs)

    fun cancel() = banker.cancel()

    fun shutdown() = banker.shutdown()
}
