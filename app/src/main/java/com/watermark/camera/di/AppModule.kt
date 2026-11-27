package com.watermark.camera.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.watermark.camera.data.local.AppDatabase
import com.watermark.camera.data.local.PhotoDao
import com.watermark.camera.data.processing.MemoryManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Application-level dependency injection module.
 *
 * Provides singleton instances of:
 * - Room database and DAOs
 * - SharedPreferences
 * - Coroutine dispatchers
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the application database.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "watermark_camera.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    /**
     * Provides the photo DAO.
     */
    @Provides
    fun providePhotoDao(database: AppDatabase): PhotoDao {
        return database.photoDao()
    }

    /**
     * Provides SharedPreferences for watermark configuration storage.
     */
    @Provides
    @Singleton
    fun provideSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        return context.getSharedPreferences(
            "watermark_camera_prefs",
            Context.MODE_PRIVATE
        )
    }

    /**
     * Provides the IO dispatcher for background operations.
     */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Provides the default dispatcher for CPU-intensive operations.
     */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Provides the main dispatcher for UI operations.
     */
    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    /**
     * Provides the memory manager for image processing pipeline.
     */
    @Provides
    @Singleton
    fun provideMemoryManager(): MemoryManager = MemoryManager()
}

/**
 * Qualifier for IO dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifier for default dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * Qualifier for main dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
