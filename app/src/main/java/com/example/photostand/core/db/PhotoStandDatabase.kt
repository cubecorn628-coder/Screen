// File: app/src/main/java/com/example/photostand/core/db/PhotoStandDatabase.kt
package com.example.photostand.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SharedPhotoEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PhotoStandDatabase : RoomDatabase() {
    abstract fun sharedPhotoDao(): SharedPhotoDao

    companion object {
        @Volatile
        private var INSTANCE: PhotoStandDatabase? = null

        fun getDatabase(context: Context): PhotoStandDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PhotoStandDatabase::class.java,
                    "photostand_local.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
