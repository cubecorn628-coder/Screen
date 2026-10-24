// File: app/src/main/java/com/example/photostand/core/db/SharedPhotoDao.kt
package com.example.photostand.core.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedPhotoDao {
    @Query("SELECT * FROM shared_photos ORDER BY timestamp DESC")
    fun getAllPhotos(): Flow<List<SharedPhotoEntity>>

    @Query("SELECT * FROM shared_photos ORDER BY timestamp DESC LIMIT 1")
    fun getLatestPhoto(): Flow<SharedPhotoEntity?>

    @Query("SELECT * FROM shared_photos WHERE id = :id")
    fun getPhotoById(id: Long): Flow<SharedPhotoEntity?>

    @Query("SELECT * FROM shared_photos WHERE isFavorite = 1 ORDER BY timestamp DESC")
    fun getFavoritePhotos(): Flow<List<SharedPhotoEntity>>

    @Query("SELECT COUNT(*) FROM shared_photos")
    fun getPhotoCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: SharedPhotoEntity): Long

    @Update
    suspend fun updatePhoto(photo: SharedPhotoEntity)

    @Delete
    suspend fun deletePhoto(photo: SharedPhotoEntity)

    @Query("DELETE FROM shared_photos WHERE id = :id")
    suspend fun deletePhotoById(id: Long)

    @Query("DELETE FROM shared_photos")
    suspend fun clearAll()
}
