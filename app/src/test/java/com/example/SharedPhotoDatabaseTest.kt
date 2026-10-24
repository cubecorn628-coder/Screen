// File: app/src/test/java/com/example/SharedPhotoDatabaseTest.kt
package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.photostand.core.db.PhotoStandDatabase
import com.example.photostand.core.db.SharedPhotoDao
import com.example.photostand.core.db.SharedPhotoEntity
import com.example.photostand.core.db.SharedPhotoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPhotoDatabaseTest {
    private lateinit var db: PhotoStandDatabase
    private lateinit var dao: SharedPhotoDao
    private lateinit var repository: SharedPhotoRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhotoStandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.sharedPhotoDao()
        repository = SharedPhotoRepository(dao)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndRetrievePhoto() = runTest {
        val photo = SharedPhotoEntity(
            filePath = "/data/user/0/photos/photo_test1.jpg",
            senderDeviceId = "ps-controller-1",
            senderDeviceName = "Living Room Phone",
            fileSizeBytes = 102400,
            mimeType = "image/jpeg"
        )

        val id = repository.savePhoto(photo)
        val retrieved = repository.getPhotoById(id).first()

        assertNotNull(retrieved)
        assertEquals("/data/user/0/photos/photo_test1.jpg", retrieved?.filePath)
        assertEquals("ps-controller-1", retrieved?.senderDeviceId)
        assertEquals("Living Room Phone", retrieved?.senderDeviceName)
        assertEquals(102400L, retrieved?.fileSizeBytes)
    }

    @Test
    fun deletePhotoById() = runTest {
        val photo = SharedPhotoEntity(
            filePath = "/data/user/0/photos/photo_test2.jpg",
            senderDeviceId = "ps-controller-2",
            senderDeviceName = "Bedroom Tablet"
        )

        val id = repository.savePhoto(photo)
        assertEquals(1, repository.allPhotos.first().size)

        repository.deletePhotoById(id)
        val afterDelete = repository.getPhotoById(id).first()
        assertNull(afterDelete)
        assertEquals(0, repository.allPhotos.first().size)
    }

    @Test
    fun latestPhotoQuery() = runTest {
        val photo1 = SharedPhotoEntity(
            filePath = "/photos/p1.jpg",
            senderDeviceId = "c1",
            senderDeviceName = "C1",
            timestamp = 1000L
        )
        val photo2 = SharedPhotoEntity(
            filePath = "/photos/p2.jpg",
            senderDeviceId = "c2",
            senderDeviceName = "C2",
            timestamp = 2000L
        )

        repository.savePhoto(photo1)
        repository.savePhoto(photo2)

        val latest = repository.latestPhoto.first()
        assertNotNull(latest)
        assertEquals("/photos/p2.jpg", latest?.filePath)
    }
}
