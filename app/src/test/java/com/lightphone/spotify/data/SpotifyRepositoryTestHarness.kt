package com.lightphone.spotify.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lightphone.spotify.data.local.DetailCacheRepository
import com.lightphone.spotify.data.local.LibraryRepository
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.data.webapi.SpotifyWebApi
import kotlinx.serialization.json.Json

object SpotifyRepositoryTestHarness {

    fun create(context: Context, webApi: SpotifyWebApi): SpotifyRepository {
        val db = Room.inMemoryDatabaseBuilder(context, PhonoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val library = LibraryRepository(
            db,
            likedTracksPageFetcher = webApi::savedTracksPage,
            savedAlbumsPageFetcher = webApi::savedAlbumsPage,
            playlistsPageFetcher = webApi::savedPlaylistsPage,
        )
        val detail = DetailCacheRepository(
            db,
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
        )
        return SpotifyRepository(webApi, library, detail)
    }

    fun create(webApi: SpotifyWebApi): SpotifyRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return create(context, webApi)
    }
}
