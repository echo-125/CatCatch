package com.catcatch.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.catcatch.data.local.AppDatabase
import com.catcatch.data.local.BookmarkDao
import com.catcatch.data.local.TaskDao
import com.catcatch.data.remote.M3U8Parser
import com.catcatch.data.repository.DownloadRepository
import com.catcatch.data.repository.SettingsRepository
import com.catcatch.service.FFmpegConverter
import com.catcatch.service.SegmentDownloader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Hilt 依赖注入模块
 * 提供全局单例依赖
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 提供 OkHttpClient
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 提供 Room 数据库
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "catcatch.db"
        ).fallbackToDestructiveMigration().build()
    }

    /**
     * 提供 TaskDao
     */
    @Provides
    @Singleton
    fun provideTaskDao(database: AppDatabase): TaskDao {
        return database.taskDao()
    }

    /**
     * 提供 BookmarkDao
     */
    @Provides
    @Singleton
    fun provideBookmarkDao(database: AppDatabase): BookmarkDao {
        return database.bookmarkDao()
    }

    /**
     * 提供 M3U8Parser
     */
    @Provides
    @Singleton
    fun provideM3U8Parser(client: OkHttpClient): M3U8Parser {
        return M3U8Parser(client)
    }

    /**
     * 提供 SegmentDownloader
     */
    @Provides
    @Singleton
    fun provideSegmentDownloader(client: OkHttpClient): SegmentDownloader {
        return SegmentDownloader(client)
    }

    /**
     * 提供 FFmpegConverter
     */
    @Provides
    @Singleton
    fun provideFFmpegConverter(): FFmpegConverter {
        return FFmpegConverter()
    }

    /**
     * 提供 DownloadRepository
     */
    @Provides
    @Singleton
    fun provideDownloadRepository(
        taskDao: TaskDao,
        m3u8Parser: M3U8Parser
    ): DownloadRepository {
        return DownloadRepository(taskDao, m3u8Parser)
    }

    /**
     * 提供 DataStore
     */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    /**
     * 提供 SettingsRepository
     */
    @Provides
    @Singleton
    fun provideSettingsRepository(dataStore: DataStore<Preferences>): SettingsRepository {
        return SettingsRepository(dataStore)
    }
}
