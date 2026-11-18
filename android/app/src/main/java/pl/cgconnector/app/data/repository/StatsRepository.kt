package pl.cgconnector.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.cgconnector.app.data.local.StatsDao
import pl.cgconnector.app.data.local.SyncStatusEntity
import pl.cgconnector.app.data.local.toDomain
import pl.cgconnector.app.domain.PeriodSummary
import pl.cgconnector.app.domain.SyncStatus
import pl.cgconnector.app.domain.Vo2MaxPoint
import javax.inject.Inject

/** Odczyt z Room danych zapisywanych przy odświeżaniu: VO2max, podsumowania, stan synchronizacji. */
interface StatsRepository {
    /** Od najstarszego pomiaru. */
    fun observeVo2Max(): Flow<List<Vo2MaxPoint>>

    /** Od najnowszego tygodnia. */
    fun observeWeeklySummaries(): Flow<List<PeriodSummary>>

    fun observeSyncStatus(): Flow<SyncStatus>
}

class RoomStatsRepository
    @Inject
    constructor(
        private val dao: StatsDao,
    ) : StatsRepository {
        override fun observeVo2Max(): Flow<List<Vo2MaxPoint>> = dao.observeVo2Max().map { list -> list.map { it.toDomain() } }

        override fun observeWeeklySummaries(): Flow<List<PeriodSummary>> =
            dao.observeSummaries(PERIOD_WEEK).map { list -> list.map { it.toDomain() } }

        override fun observeSyncStatus(): Flow<SyncStatus> =
            dao.observeSyncStatus().map { (it ?: SyncStatusEntity()).toDomain() }

        companion object {
            const val PERIOD_WEEK = "week"
        }
    }
