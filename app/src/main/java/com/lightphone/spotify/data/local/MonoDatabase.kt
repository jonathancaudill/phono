package com.lightphone.spotify.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [
        LikedTrackEntity::class,
        SavedAlbumEntity::class,
        PlaylistEntity::class,
        LibrarySyncStateEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(LibraryResourceConverter::class)
abstract class MonoDatabase : RoomDatabase() {
    abstract fun likedTrackDao(): LikedTrackDao
    abstract fun savedAlbumDao(): SavedAlbumDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun librarySyncDao(): LibrarySyncDao

    companion object {
        @Volatile
        private var instance: MonoDatabase? = null

        fun get(context: Context): MonoDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MonoDatabase::class.java,
                    "mono_library.db",
                )
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}

class LibraryResourceConverter {
    @TypeConverter
    fun fromResource(value: LibraryResource): String = value.name

    @TypeConverter
    fun toResource(value: String): LibraryResource = LibraryResource.valueOf(value)
}
