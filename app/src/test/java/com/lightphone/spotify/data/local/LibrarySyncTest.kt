package com.lightphone.spotify.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lightphone.spotify.data.SpotifyAlbumSimple
import com.lightphone.spotify.data.SpotifyArtist
import com.lightphone.spotify.data.SpotifyPlaylistOwner
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.SpotifySavedTrack
import com.lightphone.spotify.data.SpotifyTrack
import com.lightphone.spotify.data.webapi.LibraryPage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibrarySyncTest {

    private lateinit var db: PhonoDatabase
    private var fetchCount = 0
    private var pages: MutableMap<Int, LibraryPage<SpotifySavedTrack>> = mutableMapOf()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhonoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fetchCount = 0
        pages = mutableMapOf()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun track(id: String, addedAt: String = "2024-01-01T00:00:00Z") = SpotifySavedTrack(
        addedAt = addedAt,
        track = SpotifyTrack(
            id = id,
            name = "Track $id",
            uri = "spotify:track:$id",
            artists = listOf(SpotifyArtist(name = "Artist")),
        ),
    )

    private fun sync(): LikedTracksSync = LikedTracksSync(db) { offset ->
        fetchCount++
        pages[offset] ?: LibraryPage(items = emptyList(), total = 0, offset = offset)
    }

    @Test
    fun refresh_unchangedComplete_skipsRewrite() = runBlocking {
        pages[0] = LibraryPage(
            items = listOf(track("a"), track("b")),
            total = 2,
            offset = 0,
        )
        val s = sync()
        assertTrue(s.refresh())
        val fetchesAfterInitial = fetchCount
        val countAfterInitial = db.likedTrackDao().count()

        assertFalse(s.refresh())
        assertEquals(fetchesAfterInitial + 1, fetchCount) // one head-check only
        assertEquals(countAfterInitial, db.likedTrackDao().count())
    }

    @Test
    fun refresh_incompleteFill_doesNotClear_resumesViaNeedsFill() = runBlocking {
        pages[0] = LibraryPage(
            items = listOf(track("a"), track("b")),
            total = 100,
            offset = 0,
        )
        val s = sync()
        assertTrue(s.refresh())
        val syncState = db.librarySyncDao().get(LibraryResource.LIKED_TRACKS)!!
        assertEquals(2, syncState.next_offset)
        assertEquals(100, syncState.remote_total)
        val countAfterPartial = db.likedTrackDao().count()
        assertEquals(2, countAfterPartial)

        // Relaunch head-check with same head — must NOT wipe partial cache.
        fetchCount = 0
        assertFalse(s.refresh())
        assertEquals(1, fetchCount)
        assertEquals(2, db.likedTrackDao().count())
        val after = db.librarySyncDao().get(LibraryResource.LIKED_TRACKS)!!
        assertEquals(2, after.next_offset)
        assertTrue(after.next_offset < after.remote_total)
    }

    @Test
    fun refresh_newHead_deltaPrependsWithoutClearingTail() = runBlocking {
        pages[0] = LibraryPage(
            items = listOf(track("a"), track("b"), track("c")),
            total = 3,
            offset = 0,
        )
        val s = sync()
        assertTrue(s.refresh())
        assertEquals(3, db.likedTrackDao().count())

        pages[0] = LibraryPage(
            items = listOf(track("new", "2024-06-01T00:00:00Z"), track("a"), track("b")),
            total = 4,
            offset = 0,
        )
        assertTrue(s.refresh())
        assertEquals(4, db.likedTrackDao().count())
        val syncState = db.librarySyncDao().get(LibraryResource.LIKED_TRACKS)!!
        assertEquals("spotify:track:new", syncState.head_id)
        assertEquals(4, syncState.next_offset)
        assertEquals(4, syncState.remote_total)
    }

    @Test
    fun refresh_nullLocalTimestamp_stillMatchesOnId() = runBlocking {
        pages[0] = LibraryPage(
            items = listOf(track("a"), track("b")),
            total = 2,
            offset = 0,
        )
        val s = sync()
        assertTrue(s.refresh())
        // Simulate optimistic like: head id set, timestamp cleared.
        val syncState = db.librarySyncDao().get(LibraryResource.LIKED_TRACKS)!!
        db.librarySyncDao().upsert(syncState.copy(head_added_at = null))

        assertFalse(s.refresh())
        assertEquals(2, db.likedTrackDao().count())
    }

    @Test
    fun libraryHeadMatches_ignoresTimestampWhenEitherNull() {
        assertTrue(
            libraryHeadMatches("id", null, "id", "2024-01-01T00:00:00Z"),
        )
        assertTrue(
            libraryHeadMatches("id", "2024-01-01T00:00:00Z", "id", null),
        )
        assertFalse(
            libraryHeadMatches("id", "a", "id", "b"),
        )
        assertFalse(
            libraryHeadMatches("a", null, "b", null),
        )
    }

    @Test
    fun playlistsRefresh_unresolvedOwners_doNotForceFullClear() = runBlocking {
        var playlistFetches = 0
        val page = LibraryPage(
            items = listOf(
                SpotifyPlaylistSimple(
                    id = "p1",
                    name = "Mix",
                    uri = "spotify:playlist:p1",
                    owner = SpotifyPlaylistOwner(id = "user1", displayName = "user1"),
                    snapshotId = null,
                ),
            ),
            total = 1,
            offset = 0,
        )
        val playlistSync = UserPlaylistsSync(db) {
            playlistFetches++
            page
        }
        assertTrue(playlistSync.refresh())
        // displayName == id → mapper stores empty owner_name (unresolved).
        assertTrue(db.playlistDao().hasUnresolvedOwnerNames())

        playlistFetches = 0
        assertFalse(playlistSync.refresh())
        assertEquals(1, playlistFetches)
        assertEquals(1, db.playlistDao().count())
    }

    @Test
    fun hasCachedLibrary_trueAfterInitialSync() = runBlocking {
        pages[0] = LibraryPage(items = listOf(track("a")), total = 1, offset = 0)
        val repo = LibraryRepository(
            db,
            likedTracksPageFetcher = { offset ->
                fetchCount++
                pages[offset] ?: LibraryPage(emptyList(), 0, offset)
            },
            savedAlbumsPageFetcher = { offset ->
                LibraryPage(emptyList(), 0, offset)
            },
            playlistsPageFetcher = { offset, _ ->
                LibraryPage(emptyList(), 0, offset)
            },
        )
        assertFalse(repo.hasCachedLibrary())
        repo.refreshLikedTracks()
        assertTrue(repo.hasCachedLibrary())
    }

    @Test
    fun albumsRefresh_deltaPrepend() = runBlocking {
        fun album(id: String) = SpotifySavedAlbum(
            addedAt = "2024-01-01T00:00:00Z",
            album = SpotifyAlbumSimple(id = id, name = "Album $id", uri = "spotify:album:$id"),
        )
        var albumPages = mapOf(
            0 to LibraryPage(items = listOf(album("a")), total = 1, offset = 0),
        )
        val albumSync = SavedAlbumsSync(db) { offset ->
            albumPages[offset] ?: LibraryPage(emptyList(), 0, offset)
        }
        assertTrue(albumSync.refresh())
        albumPages = mapOf(
            0 to LibraryPage(items = listOf(album("b"), album("a")), total = 2, offset = 0),
        )
        assertTrue(albumSync.refresh())
        assertEquals(2, db.savedAlbumDao().count())
        assertEquals("b", db.librarySyncDao().get(LibraryResource.SAVED_ALBUMS)?.head_id)
    }
}
