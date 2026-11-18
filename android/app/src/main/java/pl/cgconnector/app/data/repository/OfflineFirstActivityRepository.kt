package pl.cgconnector.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.cgconnector.app.data.local.ActivityDao
import pl.cgconnector.app.data.local.toDomain
import pl.cgconnector.app.data.remote.ActivityRemoteDataSource
import pl.cgconnector.app.data.remote.NotConfiguredException
import pl.cgconnector.app.data.remote.toEntity
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityType
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineFirstActivityRepository
    @Inject
    constructor(
        private val dao: ActivityDao,
        private val remote: ActivityRemoteDataSource,
        private val clock: Clock,
    ) : ActivityRepository {
        override fun observeActivities(type: ActivityType?): Flow<List<Activity>> =
            dao.observeActivities(type).map { list -> list.map { it.toDomain() } }

        override suspend fun refresh(): RefreshResult {
            val today = LocalDate.now(clock.withZone(ZONE))
            val latest = dao.latestStartTime()?.atZone(ZONE)?.toLocalDate()
            // Zakładka kilku dni, bo aktywności na serwerze mogą zostać poprawione po fakcie.
            val from = latest?.minusDays(OVERLAP_DAYS) ?: today.minusDays(INITIAL_HISTORY_DAYS)
            return try {
                val items = remote.fetchActivities(from = from, to = today)
                dao.upsertAll(items.map { it.toEntity() })
                RefreshResult.Success(items.size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: NotConfiguredException) {
                RefreshResult.NotConfigured
            } catch (_: Exception) {
                // Laptop-serwer bywa wyłączony; to normalny stan.
                RefreshResult.Unavailable
            }
        }

        private companion object {
            val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
            const val OVERLAP_DAYS = 3L
            const val INITIAL_HISTORY_DAYS = 365L
        }
    }
