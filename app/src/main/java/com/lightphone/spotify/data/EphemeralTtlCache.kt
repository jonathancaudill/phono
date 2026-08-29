package com.lightphone.spotify.data

/**
 * In-memory overlay metadata cache: hard cap + insert-time TTL + LRU.
 *
 * Memory rules (Light Phone III):
 * - [maxSize] is a hard cap; a new key never grows the map past it.
 * - Updating an existing key does not evict a neighbor.
 * - TTL is measured from insert, not last access, so browsing cannot pin entries forever.
 * - Expired entries are swept on get/put, not only when that key is requested.
 * - Values must be immutable data (no Context, View, or callback graphs).
 * - [clear] is the logout path; the map holds nothing after it.
 */
internal class EphemeralTtlCache<K : Any, V : Any>(
    private val ttlMs: Long,
    private val maxSize: Int,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(ttlMs > 0L) { "ttlMs must be positive" }
        require(maxSize > 0) { "maxSize must be positive" }
    }

    private data class Entry<V>(val value: V, val insertedAt: Long)

    private val lock = Any()
    private val map = LinkedHashMap<K, Entry<V>>(maxSize.coerceAtLeast(4), 0.75f, true)

    val size: Int
        get() = synchronized(lock) { map.size }

    fun get(key: K): V? = synchronized(lock) {
        val now = nowMs()
        sweepExpiredLocked(now)
        val entry = map[key] ?: return null
        if (now - entry.insertedAt >= ttlMs) {
            map.remove(key)
            return null
        }
        entry.value
    }

    fun put(key: K, value: V) {
        synchronized(lock) {
            val now = nowMs()
            sweepExpiredLocked(now)
            if (map.containsKey(key)) {
                map[key] = Entry(value, now)
                return
            }
            while (map.size >= maxSize) {
                val eldest = map.keys.firstOrNull() ?: break
                map.remove(eldest)
            }
            map[key] = Entry(value, now)
        }
    }

    fun remove(key: K) {
        synchronized(lock) { map.remove(key) }
    }

    fun clear() {
        synchronized(lock) { map.clear() }
    }

    private fun sweepExpiredLocked(now: Long) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.insertedAt >= ttlMs) {
                iterator.remove()
            }
        }
    }
}

/** Shared overlay metadata TTLs/caps so Spotify and TIDAL cannot drift. */
internal object OverlayMetadataCache {
    const val DETAIL_TTL_MS = 5 * 60_000L
    const val SEARCH_TTL_MS = 2 * 60_000L
    const val SEARCH_CAP = 25
    const val ALBUM_CAP = 20
    const val PLAYLIST_CAP = 10
    /** Discographies are the fattest payloads (up to [ARTIST_DISCOGRAPHY_LIMIT] albums + singles). */
    const val ARTIST_CAP = 8
}
