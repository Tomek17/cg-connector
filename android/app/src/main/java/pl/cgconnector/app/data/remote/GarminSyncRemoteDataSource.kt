package pl.cgconnector.app.data.remote

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** `POST /api/v1/sync` (schemat `SyncNow`). */
@Serializable
data class SyncNowDto(
    val started: Boolean,
    /** `no_garmin` | `needs_login` | `rate_limited` | `running` | `too_soon` | null. */
    val reason: String? = null,
    /** ISO-8601 z offsetem: kiedy można spróbować ponownie. */
    @SerialName("retry_at") val retryAt: String? = null,
)

/** „Synchronizuj teraz”: serwer pobiera nowe dane z Garmina poza codzienną synchronizacją. */
interface GarminSyncRemoteDataSource {
    suspend fun syncNow(): SyncNowDto

    /** Stan synchronizacji serwera (`/me`), do sprawdzania, czy już się skończyła. */
    suspend fun me(): MeDto
}

class ApiGarminSyncRemoteDataSource
    @Inject
    constructor(
        private val apiProvider: CgApiProvider,
    ) : GarminSyncRemoteDataSource {
        override suspend fun syncNow() = api().syncNow()

        override suspend fun me() = api().me()

        private suspend fun api(): CgApi = apiProvider.get() ?: throw NotConfiguredException()
    }

/** Build z `USE_FAKE_API = true`: synchronizacja „trwa” chwilę i kończy się sukcesem. */
@Singleton
class FakeGarminSyncRemoteDataSource
    @Inject
    constructor() : GarminSyncRemoteDataSource {
        private var lastAttempt: OffsetDateTime? = null
        private var polls = 0

        override suspend fun syncNow(): SyncNowDto {
            delay(LATENCY_MS)
            lastAttempt = OffsetDateTime.now()
            polls = 0
            return SyncNowDto(started = true)
        }

        override suspend fun me(): MeDto {
            delay(LATENCY_MS)
            polls++
            val running = lastAttempt != null && polls < 2
            return MeDto(
                name = "tomek",
                displayName = "tomek",
                garminStatus = "ok",
                lastSyncAt = if (running) null else lastAttempt?.toString(),
                lastSyncAttemptAt = lastAttempt?.toString(),
                lastSyncStatus = if (running) "running" else "ok",
            )
        }

        private companion object {
            const val LATENCY_MS = 300L
        }
    }
