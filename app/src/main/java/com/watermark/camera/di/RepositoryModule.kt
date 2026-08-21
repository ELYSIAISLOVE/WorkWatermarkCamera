package com.watermark.camera.di

import com.watermark.camera.data.repository.CameraRepositoryImpl
import com.watermark.camera.domain.repository.CameraRepository
import com.watermark.camera.domain.repository.DatabaseRepository
import com.watermark.camera.domain.repository.LocationRepository
import com.watermark.camera.domain.repository.MetadataRepository
import com.watermark.camera.domain.repository.SensorRepository
import com.watermark.camera.domain.repository.StorageRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for binding repository interfaces to their implementations.
 *
 * All bindings are singleton-scoped since repositories should live
 * for the entire application lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCameraRepository(
        impl: CameraRepositoryImpl
    ): CameraRepository

    @Binds
    @Singleton
    abstract fun bindStorageRepository(
        impl: com.watermark.camera.data.repository.StorageRepositoryImpl
    ): StorageRepository

    @Binds
    @Singleton
    abstract fun bindDatabaseRepository(
        impl: com.watermark.camera.data.repository.DatabaseRepositoryImpl
    ): DatabaseRepository

    @Binds
    @Singleton
    abstract fun bindMetadataRepository(
        impl: com.watermark.camera.data.repository.MetadataRepositoryImpl
    ): MetadataRepository

    @Binds
    @Singleton
    abstract fun bindLocationRepository(
        impl: com.watermark.camera.data.repository.LocationRepositoryImpl
    ): LocationRepository

    // TODO: Bind remaining repositories as implementations are created
    //
    // @Binds
    // @Singleton
    // abstract fun bindSensorRepository(
    //     impl: SensorRepositoryImpl
    // ): SensorRepository
}
