package pl.cgconnector.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.cgconnector.app.data.local.ActivityDao
import pl.cgconnector.app.data.local.ActivityDetailDao
import pl.cgconnector.app.data.local.StatsDao
import pl.cgconnector.app.data.local.toDomain
import pl.cgconnector.app.data.remote.ActivityRemoteDataSource
import pl.cgconnector.app.data.remote.NotConfiguredException
import pl.cgconnector.app.data.remote.applyTo
import pl.cgconnector.app.data.remote.toBundle
import pl.cgconnector.app.data.remote.toEntities
import pl.cgconnector.app.data.remote.toEntity
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityDetail
import pl.cgconnector.app.domain.ActivityType
import retrofit2.HttpException
import java.io.IOException
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
        private val detailDao: ActivityDetailDao,
        private val statsDao: StatsDao,
        private val remote: ActivityRemoteDataSource,
        private val clock: Clock,
    ) : ActivityRepository {
        override fun observeActivities(type: ActivityType?): Flow<List<Activity>> =
            dao.observeActivities(type).map { list -> list.map { it.toDomain() } }

        override fun observeActivityDetail(id: Long): Flow<ActivityDetail?> =
            detailDao.observe(id).map { it?.toDomain() }

        /** Wynik też trafia do stanu synchronizacji: 401 albo brak serwera przy szczegółach widać na ekranie Status. */
        override suspend fun refreshActivityDetail(id: Long): RefreshResult {
            val result =
                runCatchingRefresh {
                    val detail = remote.fetchActivityDetail(id)
                    detailDao.replace(detail.toBundle(fetchedAt = clock.instant()))
                    RefreshResult.Success(1)
                }
            recordAttempt(result, fullRefresh = false)
            return result
        }

        override suspend fun refresh(): RefreshResult {
            val result = runCatchingRefresh { refreshAll() }
            recordAttempt(result, fullRefresh = true)
            return result
        }

        /**
         * Zapisuje próbę połączenia. „Ostatnie odświeżenie aplikacji” przesuwa tylko udane pełne odświeżenie,
         * bo samo pobranie szczegółów jednego treningu nie aktualizuje listy, VO2max ani `/me`.
         */
        private suspend fun recordAttempt(
            result: RefreshResult,
            fullRefresh: Boolean,
        ) {
            val now = clock.instant()
            val success = fullRefresh && result is RefreshResult.Success
            statsDao.updateSyncStatus { status ->
                status.copy(
                    lastRefreshAttemptAt = now,
                    lastRefreshOutcome = result.toOutcome().name,
                    lastRefreshSuccessAt = if (success) now else status.lastRefreshSuccessAt,
                )
            }
        }

        private suspend fun refreshAll(): RefreshResult {
            val today = LocalDate.now(clock.withZone(ZONE))
            val latest = dao.latestStartTime()?.atZone(ZONE)?.toLocalDate()
            // Zakładka kilku dni, bo aktywności na serwerze mogą zostać poprawione po fakcie.
            val from = latest?.minusDays(OVERLAP_DAYS) ?: today.minusDays(INITIAL_HISTORY_DAYS)
            val items = remote.fetchActivities(from = from, to = today)
            dao.upsertAll(items.map { it.toEntity() })

            val vo2From = statsDao.latestVo2MaxDate()?.minusDays(OVERLAP_DAYS) ?: today.minusDays(INITIAL_HISTORY_DAYS)
            statsDao.upsertVo2Max(remote.fetchVo2Max(from = vo2From, to = today).map { it.toEntity() })

            val me = remote.fetchMe()
            statsDao.updateSyncStatus { me.applyTo(it) }

            val summary = remote.fetchWeeklySummary()
            statsDao.replaceSummaries(summary.period, summary.toEntities())
            return RefreshResult.Success(items.size)
        }

        private suspend fun runCatchingRefresh(block: suspend () -> RefreshResult): RefreshResult =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                classify(e)
            }

        companion object {
            private val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
            private const val OVERLAP_DAYS = 3L
            private const val INITIAL_HISTORY_DAYS = 365L

            /** Odróżnia brak serwera (normalny stan, ponawiamy) od błędów, których ponawianie nie naprawi. */
            fun classify(e: Exception): RefreshResult =
                when (e) {
                    is NotConfiguredException -> RefreshResult.NotConfigured
                    is HttpException ->
                        when {
                            e.code() == HTTP_UNAUTHORIZED -> RefreshResult.Unauthorized
                            e.code() >= HTTP_SERVER_ERROR -> RefreshResult.Unavailable
                            else -> RefreshResult.InvalidResponse
                        }
                    // Laptop-serwer bywa wyłączony; to normalny stan.
                    is IOException -> RefreshResult.Unavailable
                    // SerializationException (zły JSON), DateTimeParseException (zła data) i inne:
                    // odpowiedź niezgodna z kontraktem, a nie brak serwera.
                    else -> RefreshResult.InvalidResponse
                }

            private const val HTTP_UNAUTHORIZED = 401
            private const val HTTP_SERVER_ERROR = 500
        }
    }
