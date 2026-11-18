package pl.cgconnector.app.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import pl.cgconnector.app.BuildConfig
import pl.cgconnector.app.data.remote.ActivityRemoteDataSource
import pl.cgconnector.app.data.remote.ApiActivityRemoteDataSource
import pl.cgconnector.app.data.remote.ApiWorkoutRemoteDataSource
import pl.cgconnector.app.data.remote.FakeActivityRemoteDataSource
import pl.cgconnector.app.data.remote.FakeWorkoutRemoteDataSource
import pl.cgconnector.app.data.remote.WorkoutRemoteDataSource
import pl.cgconnector.app.data.repository.ActivityRepository
import pl.cgconnector.app.data.repository.DataStoreQueuePauseStore
import pl.cgconnector.app.data.repository.OfflineFirstActivityRepository
import pl.cgconnector.app.data.repository.OfflineFirstWorkoutQueueRepository
import pl.cgconnector.app.data.repository.QueuePauseStore
import pl.cgconnector.app.data.repository.RoomStatsRepository
import pl.cgconnector.app.data.repository.StatsRepository
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.data.repository.WorkoutQueueTrigger
import pl.cgconnector.app.data.settings.DataStoreServerConfigRepository
import pl.cgconnector.app.data.settings.KeystoreTokenCipher
import pl.cgconnector.app.data.settings.ServerConfigRepository
import pl.cgconnector.app.data.settings.TokenCipher
import pl.cgconnector.app.work.WorkManagerWorkoutQueueTrigger
import javax.inject.Provider

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindTokenCipher(impl: KeystoreTokenCipher): TokenCipher

    @Binds
    abstract fun bindServerConfigRepository(impl: DataStoreServerConfigRepository): ServerConfigRepository

    @Binds
    abstract fun bindActivityRepository(impl: OfflineFirstActivityRepository): ActivityRepository

    @Binds
    abstract fun bindStatsRepository(impl: RoomStatsRepository): StatsRepository

    @Binds
    abstract fun bindWorkoutQueueRepository(impl: OfflineFirstWorkoutQueueRepository): WorkoutQueueRepository

    @Binds
    abstract fun bindQueuePauseStore(impl: DataStoreQueuePauseStore): QueuePauseStore

    @Binds
    abstract fun bindWorkoutQueueTrigger(impl: WorkManagerWorkoutQueueTrigger): WorkoutQueueTrigger

    companion object {
        @Provides
        fun provideActivityRemoteDataSource(
            fake: Provider<FakeActivityRemoteDataSource>,
            api: Provider<ApiActivityRemoteDataSource>,
        ): ActivityRemoteDataSource = if (BuildConfig.USE_FAKE_API) fake.get() else api.get()

        @Provides
        fun provideWorkoutRemoteDataSource(
            fake: Provider<FakeWorkoutRemoteDataSource>,
            api: Provider<ApiWorkoutRemoteDataSource>,
        ): WorkoutRemoteDataSource = if (BuildConfig.USE_FAKE_API) fake.get() else api.get()
    }
}
