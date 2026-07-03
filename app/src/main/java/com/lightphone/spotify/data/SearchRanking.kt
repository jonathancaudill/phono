package com.lightphone.spotify.data

/**
 * Client-side search ranking: pick a top result, then interleave the remainder
 * for cross-type variety. Spotify pre-sorts each type bucket; we blend them.
 */
object SearchRanking {

    private const val MAX_LIST_ITEMS = 20
    private const val RANK_POOL_SIZE = 8

    data class RankedOutput(
        val topResult: SearchResultItem?,
        val rankedItems: List<SearchResultItem>,
    )

    fun rank(query: String, results: SearchResults): RankedOutput {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return RankedOutput(null, emptyList())

        val shortQuery = q.split(Regex("\\s+")).count { it.isNotEmpty() } <= 1

        val scored = buildList {
            results.artists.forEachIndexed { index, artist ->
                add(
                    Scored(
                        item = SearchResultItem.Artist(artist),
                        score = scoreName(q, artist.name, artist.popularity, index, shortQuery, isArtist = true),
                    ),
                )
            }
            results.tracks.forEachIndexed { index, track ->
                add(
                    Scored(
                        item = SearchResultItem.Track(track),
                        score = scoreName(q, track.name, track.popularity, index, shortQuery, isTrack = true),
                    ),
                )
            }
            results.albums.forEachIndexed { index, album ->
                add(
                    Scored(
                        item = SearchResultItem.Album(album),
                        score = scoreName(q, album.name, album.popularity, index, shortQuery),
                    ),
                )
            }
            results.playlists.forEachIndexed { index, playlist ->
                add(
                    Scored(
                        item = SearchResultItem.Playlist(playlist),
                        score = scoreName(q, playlist.name, popularity = 0, index, shortQuery),
                    ),
                )
            }
        }

        if (scored.isEmpty()) return RankedOutput(null, emptyList())

        val topResult = scored.maxWithOrNull(
            compareBy<Scored> { it.score }
                .thenBy { playabilityBonus(it.item) },
        )!!.item
        val topUri = topResult.uri

        val artistItems = results.artists
            .map { SearchResultItem.Artist(it) }
            .filter { it.uri != topUri && it.id.isNotBlank() }
        val trackItems = results.tracks
            .map { SearchResultItem.Track(it) }
            .filter { it.uri != topUri && it.id.isNotBlank() }
        val albumItems = results.albums
            .map { SearchResultItem.Album(it) }
            .filter { it.uri != topUri && it.id.isNotBlank() }
        val playlistItems = results.playlists
            .map { SearchResultItem.Playlist(it) }
            .filter { it.uri != topUri && it.id.isNotBlank() }

        val rankedItems = interleave(artistItems, trackItems, albumItems, playlistItems)
            .distinctBy { it.uri }
            .take(MAX_LIST_ITEMS)

        return RankedOutput(topResult, rankedItems)
    }

    private data class Scored(val item: SearchResultItem, val score: Int)

    private fun scoreName(
        query: String,
        name: String,
        popularity: Int,
        index: Int,
        shortQuery: Boolean,
        isArtist: Boolean = false,
        isTrack: Boolean = false,
    ): Int {
        val normalized = name.trim().lowercase()
        var score = 0
        when {
            normalized == query -> score += 1000
            normalized.startsWith(query) -> score += 500
            query in normalized -> score += 200
        }
        score += (RANK_POOL_SIZE - index).coerceAtLeast(0) * 10
        score += popularity.coerceIn(0, 100)
        when {
            shortQuery && isArtist -> score += 30
            !shortQuery && isTrack -> score += 30
        }
        return score
    }

    /** Prefer playable entities when relevance scores tie. */
    private fun playabilityBonus(item: SearchResultItem): Int = when (item) {
        is SearchResultItem.Track -> 2
        is SearchResultItem.Album -> 1
        else -> 0
    }

    private fun interleave(
        artists: List<SearchResultItem>,
        tracks: List<SearchResultItem>,
        albums: List<SearchResultItem>,
        playlists: List<SearchResultItem>,
    ): List<SearchResultItem> {
        val pools = listOf(artists, tracks, albums, playlists)
        val indices = IntArray(pools.size)
        val out = mutableListOf<SearchResultItem>()
        var added = true
        while (added && out.size < MAX_LIST_ITEMS) {
            added = false
            for (poolIndex in pools.indices) {
                val pool = pools[poolIndex]
                val i = indices[poolIndex]
                if (i < pool.size) {
                    out.add(pool[i])
                    indices[poolIndex] = i + 1
                    added = true
                    if (out.size >= MAX_LIST_ITEMS) break
                }
            }
        }
        return out
    }
}
