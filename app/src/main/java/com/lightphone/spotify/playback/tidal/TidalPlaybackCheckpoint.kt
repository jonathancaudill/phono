package com.lightphone.spotify.playback.tidal

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * TTL-bounded TIDAL playback checkpoint for process-death recovery.
 *
 * Intentional duplicate of rust `playback_checkpoint.rs` (same 20-minute TTL,
 * 30-second debounce, paused restore). Spotify's queue lives in Rust; TIDAL's
 * lives in ExoPlayer. A shared UniFFI queue is not the plan — Spotify is
 * heading toward Kotlin.
 */
object TidalPlaybackCheckpoint {
    /** Match rust `CHECKPOINT_TTL`. */
    const val TTL_MS: Long = 20 * 60 * 1000L

    /** Match rust `CHECKPOINT_DEBOUNCE`. */
    const val DEBOUNCE_MS: Long = 30_000L

    private const val FILE_NAME = "tidal_playback_checkpoint.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun file(baseDir: File): File = File(baseDir, FILE_NAME)

    fun save(baseDir: File, snapshot: Snapshot) {
        if (snapshot.mediaIds.isEmpty()) {
            delete(baseDir)
            return
        }
        val payload = Wire(
            savedAtUnixMs = System.currentTimeMillis(),
            mediaIds = snapshot.mediaIds,
            currentIndex = snapshot.currentIndex,
            positionMs = snapshot.positionMs,
            manualIds = snapshot.manualIds,
            contextLabel = snapshot.contextLabel,
            shuffle = snapshot.shuffle,
            repeat = snapshot.repeat,
        )
        runCatching {
            file(baseDir).writeText(json.encodeToString(payload))
        }
    }

    fun save(context: Context, snapshot: Snapshot) =
        save(context.applicationContext.filesDir, snapshot)

    fun loadIfFresh(baseDir: File): Snapshot? {
        val path = file(baseDir)
        val raw = runCatching { path.readText() }.getOrNull() ?: return null
        val wire = runCatching { json.decodeFromString<Wire>(raw) }.getOrNull()
        if (wire == null) {
            delete(baseDir)
            return null
        }
        val age = System.currentTimeMillis() - wire.savedAtUnixMs
        if (age > TTL_MS) {
            delete(baseDir)
            return null
        }
        if (wire.mediaIds.isEmpty()) {
            delete(baseDir)
            return null
        }
        val index = wire.currentIndex.coerceIn(0, wire.mediaIds.lastIndex)
        return Snapshot(
            mediaIds = wire.mediaIds,
            currentIndex = index,
            positionMs = wire.positionMs.coerceAtLeast(0L),
            manualIds = wire.manualIds,
            contextLabel = wire.contextLabel,
            shuffle = wire.shuffle,
            repeat = wire.repeat,
        )
    }

    fun loadIfFresh(context: Context): Snapshot? =
        loadIfFresh(context.applicationContext.filesDir)

    fun delete(baseDir: File) {
        runCatching { file(baseDir).delete() }
    }

    fun delete(context: Context) = delete(context.applicationContext.filesDir)

    data class Snapshot(
        val mediaIds: List<String>,
        val currentIndex: Int,
        val positionMs: Long,
        val manualIds: List<String>,
        val contextLabel: String?,
        val shuffle: Boolean,
        val repeat: String,
    )

    @Serializable
    internal data class Wire(
        val savedAtUnixMs: Long,
        val mediaIds: List<String>,
        val currentIndex: Int,
        val positionMs: Long,
        val manualIds: List<String> = emptyList(),
        val contextLabel: String? = null,
        val shuffle: Boolean = false,
        val repeat: String = "OFF",
    )
}
