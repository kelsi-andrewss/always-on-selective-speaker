package com.frontieraudio.app.di

import android.content.Context
import androidx.room.Room
import com.frontieraudio.app.data.local.AppDatabase
import com.frontieraudio.app.data.local.dao.RecordingDao
import com.frontieraudio.app.data.local.dao.SyncDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "frontier_audio.db",
        ).addMigrations(AppDatabase.MIGRATION_1_2).build()

    @Provides
    fun provideRecordingDao(database: AppDatabase): RecordingDao =
        database.recordingDao()

    @Provides
    fun provideSyncDao(database: AppDatabase): SyncDao =
        database.syncDao()
}
