package pl.cgconnector.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import pl.cgconnector.app.data.local.ActivityDao
import pl.cgconnector.app.data.local.ActivityDetailDao
import pl.cgconnector.app.data.local.CgDatabase
import pl.cgconnector.app.data.local.StatsDao
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): CgDatabase = Room.databaseBuilder(context, CgDatabase::class.java, CgDatabase.NAME).build()

    @Provides
    fun provideActivityDao(db: CgDatabase): ActivityDao = db.activityDao()

    @Provides
    fun provideActivityDetailDao(db: CgDatabase): ActivityDetailDao = db.activityDetailDao()

    @Provides
    fun provideStatsDao(db: CgDatabase): StatsDao = db.statsDao()

    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Tylko HTTPS: network_security_config blokuje cleartext, a walidacja adresu wymaga https://.
            .followSslRedirects(false)
            .build()

    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)
}
