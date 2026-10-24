// File: app/src/main/java/com/example/photostand/core/db/SharedPhotoRepository.kt
package com.example.photostand.core.db

import kotlinx.coroutines.flow.Flow

class SharedPhotoRepository(private val dao: SharedPhotoDao) {

    val allPhotos: Flow<List<SharedPhotoEntity>> = dao.getAllPhotos()

    val latestPhoto: Flow<SharedPhotoEntity?> = dao.getLatestPhoto()

    val favoritePhotos: Flow<List<SharedPhotoEntity>> = dao.getFavoritePhotos()

    val photoCount: Flow<Int> = dao.getPhotoCount()

    fun getPhotoById(id: Long): Flow<SharedPhotoEntity?> = dao.getPhotoById(id)

    suspend fun savePhoto(photo: SharedPhotoEntity): Long = dao.insertPhoto(photo)

    suspend fun updatePhoto(photo: SharedPhotoEntity) = dao.updatePhoto(photo)

    suspend fun deletePhoto(photo: SharedPhotoEntity) = dao.deletePhoto(photo)

    suspend fun deletePhotoById(id: Long) = dao.deletePhotoById(id)

    suspend fun clearHistory() = dao.clearAll()
}
