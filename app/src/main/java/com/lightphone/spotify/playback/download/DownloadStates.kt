package com.lightphone.spotify.playback.download

/**
 * Room/UI download state contract.
 *
 * Values match Media3 [androidx.media3.exoplayer.offline.Download] STATE_* ints so
 * existing Downloads UI and [DownloadedCollectionDao.observeCollectionsWithProgress]
 * stay unchanged. Spotify's custom downloader writes the same ints without importing
 * Media3.
 */
object DownloadStates {
    const val QUEUED = 0
    const val STOPPED = 1
    const val DOWNLOADING = 2
    const val COMPLETED = 3
    const val FAILED = 4
    const val REMOVING = 5
    const val RESTARTING = 7

    fun isActive(state: Int): Boolean = when (state) {
        QUEUED, DOWNLOADING, RESTARTING -> true
        else -> false
    }

    fun shouldSkipEnqueue(state: Int): Boolean = when (state) {
        COMPLETED, DOWNLOADING, QUEUED, RESTARTING -> true
        else -> false
    }

    fun isTerminal(state: Int): Boolean = when (state) {
        COMPLETED, FAILED, REMOVING -> true
        else -> false
    }
}
