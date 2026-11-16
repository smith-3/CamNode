package com.fragmind.camnode.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.fragmind.camnode.core.data.AlertsRepository
import com.fragmind.camnode.core.data.AppLockRepository
import com.fragmind.camnode.core.data.CameraSettingsRepository
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.data.LocalStateRepository
import com.fragmind.camnode.core.data.device.AndroidDeviceClass
import com.fragmind.camnode.core.data.device.DeviceClass
import com.fragmind.camnode.core.data.store.StoredState
import com.fragmind.camnode.core.data.store.StoredStateSerializer
import com.fragmind.camnode.core.model.WallClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindHouseholdRepository(repository: LocalStateRepository): HouseholdRepository

    @Binds
    abstract fun bindCameraSettingsRepository(repository: LocalStateRepository): CameraSettingsRepository

    @Binds
    abstract fun bindAlertsRepository(repository: LocalStateRepository): AlertsRepository

    @Binds
    abstract fun bindAppLockRepository(repository: LocalStateRepository): AppLockRepository

    @Binds
    abstract fun bindDeviceClass(deviceClass: AndroidDeviceClass): DeviceClass

    companion object {
        @Provides
        @Singleton
        fun provideStateStore(@ApplicationContext context: Context): DataStore<StoredState> = DataStoreFactory.create(
            serializer = StoredStateSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { StoredStateSerializer.defaultValue },
            produceFile = { context.dataStoreFile("camnode_state.json") },
        )

        @Provides
        fun provideWallClock(): WallClock = WallClock.System

        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
