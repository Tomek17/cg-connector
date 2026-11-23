package pl.cgconnector.app.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import pl.cgconnector.app.BuildConfig
import pl.cgconnector.app.data.remote.ActivityRemoteDataSource
import pl.cgconnector.app.data.remote.ApiActivityRemoteDataSource
import pl.cgconnector.app.data.remote.ApiGarminSyncRemoteDataSource
import pl.cgconnector.app.data.remote.ApiGoalRemoteDataSource
import pl.cgconnector.app.data.remote.ApiProgressRemoteDataSource
import pl.cgconnector.app.data.remote.ApiWorkoutRemoteDataSource
import pl.cgconnector.app.data.remote.FakeActivityRemoteDataSource
import pl.cgconnector.app.data.remote.FakeGarminSyncRemoteDataSource
import pl.cgconnector.app.data.remote.FakeGoalRemoteDataSource
import pl.cgconnector.app.data.remote.FakeProgressRemoteDataSource
import pl.cgconnector.app.data.remote.FakeWorkoutRemoteDataSource
import pl.cgconnector.app.data.remote.GarminSyncRemoteDataSource
import pl.cgconnector.app.data.remote.GoalRemoteDataSource
import pl.cgconnector.app.data.remote.ProgressRemoteDataSource
import pl.cgconnector.app.data.remote.WorkoutRemoteDataSource
import pl.cgconnector.app.data.repository.ActivityRepository
import pl.cgconnector.app.data.repository.DataStoreGoalRepository
import pl.cgconnector.app.data.repository.DataStoreQueuePauseStore
import pl.cgconnector.app.data.repository.DataStoreWorkoutTemplateRepository
import pl.cgconnector.app.data.repository.GarminSyncRepository
import pl.cgconnector.app.data.repository.GoalRepository
import pl.cgconnector.app.data.repository.OfflineFirstActivityRepository
import pl.cgconnector.app.data.repository.OfflineFirstWorkoutQueueRepository
import pl.cgconnector.app.data.repository.ProgressRepository
import pl.cgconnector.app.data.repository.QueuePauseStore
import pl.cgconnector.app.data.repository.RoomStatsRepository
import pl.cgconnector.app.data.repository.ServerGarminSyncRepository
import pl.cgconnector.app.data.repository.ServerProgressRepository
import pl.cgconnector.app.data.repository.StatsRepository
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.data.repository.WorkoutQueueTrigger
import pl.cgconnector.app.data.repository.WorkoutTemplateRepository
import pl.cgconnector.app.data.onboarding.OnboardingRepository
import pl.cgconnector.app.data.onboarding.ServerOnboardingRepository
import pl.cgconnector.app.data.update.GitHubUpdateChecker
import pl.cgconnector.app.data.update.UpdateChecker
import pl.cgconnector.app.data.settings.DataStoreServerConfigRepository
import pl.cgconnector.app.data.settings.KeystoreTokenCipher
import pl.cgconnector.app.data.settings.ServerConfigRepository
import pl.cgconnector.app.data.settings.TokenCipher
import pl.cgconnector.app.work.WorkManagerWorkoutQueueTrigger

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindTokenCipher(impl: KeystoreTokenCipher): TokenCipher

    @Binds
    abstract fun bindServerConfigRepository(impl: DataStoreServerConfigRepository): ServerConfigRepository

    @Binds
    abstract fun bindOnboardingRepository(impl: ServerOnboardingRepository): OnboardingRepository

    @Binds
    abstract fun bindUpdateChecker(impl: GitHubUpdateChecker): UpdateChecker

    @Binds
    abstract fun bindActivityRepository(impl: OfflineFirstActivityRepository): ActivityRepository

    @Binds
    abstract fun bindGarminSyncRepository(impl: ServerGarminSyncRepository): GarminSyncRepository

    @Binds
    abstract fun bindGoalRepository(impl: DataStoreGoalRepository): GoalRepository

    @Binds
    abstract fun bindProgressRepository(impl: ServerProgressRepository): ProgressRepository

    @Binds
    abstract fun bindStatsRepository(impl: RoomStatsRepository): StatsRepository

    @Binds
    abstract fun bindWorkoutQueueRepository(impl: OfflineFirstWorkoutQueueRepository): WorkoutQueueRepository

    @Binds
    abstract fun bindWorkoutTemplateRepository(impl: DataStoreWorkoutTemplateRepository): WorkoutTemplateRepository

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

        @Provides
        fun provideProgressRemoteDataSource(
            fake: Provider<FakeProgressRemoteDataSource>,
            api: Provider<ApiProgressRemoteDataSource>,
        ): ProgressRemoteDataSource = if (BuildConfig.USE_FAKE_API) fake.get() else api.get()

        @Provides
        fun provideGarminSyncRemoteDataSource(
            fake: Provider<FakeGarminSyncRemoteDataSource>,
            api: Provider<ApiGarminSyncRemoteDataSource>,
        ): GarminSyncRemoteDataSource = if (BuildConfig.USE_FAKE_API) fake.get() else api.get()

        @Provides
        fun provideGoalRemoteDataSource(
            fake: Provider<FakeGoalRemoteDataSource>,
            api: Provider<ApiGoalRemoteDataSource>,
        ): GoalRemoteDataSource = if (BuildConfig.USE_FAKE_API) fake.get() else api.get()
    }
}
