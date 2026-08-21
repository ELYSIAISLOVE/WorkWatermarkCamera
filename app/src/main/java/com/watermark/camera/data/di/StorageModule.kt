package com.watermark.camera.data.di

import android.content.Context
import com.watermark.camera.data.repository.StorageRepository
import com.watermark.camera.data.repository.StorageRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for storage dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Singleton
    @Provides
    fun provideStorageRepository(
        @ApplicationContext context: Context
    ): StorageRepository {
        return StorageRepositoryImpl(context)
    }
}
