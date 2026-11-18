package pl.cgconnector.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** VO2max, podsumowania okresów i stan synchronizacji. */
@Dao
abstract class StatsDao {
    @Query("SELECT * FROM vo2max ORDER BY date ASC")
    abstract fun observeVo2Max(): Flow<List<Vo2MaxEntity>>

    @Query("SELECT MAX(date) FROM vo2max")
    abstract suspend fun latestVo2MaxDate(): LocalDate?

    @Upsert
    abstract suspend fun upsertVo2Max(points: List<Vo2MaxEntity>)

    @Query("SELECT * FROM summaries WHERE period = :period ORDER BY period_start DESC, type ASC")
    abstract fun observeSummaries(period: String): Flow<List<SummaryEntity>>

    /** Serwer zwraca komplet okresów, więc stare wiersze tego okresu zastępujemy w całości. */
    @Transaction
    open suspend fun replaceSummaries(
        period: String,
        items: List<SummaryEntity>,
    ) {
        deleteSummaries(period)
        upsertSummaries(items)
    }

    @Query("DELETE FROM summaries WHERE period = :period")
    protected abstract suspend fun deleteSummaries(period: String)

    @Upsert
    protected abstract suspend fun upsertSummaries(items: List<SummaryEntity>)

    @Query("SELECT * FROM sync_status WHERE id = ${SyncStatusEntity.SINGLE_ROW_ID}")
    abstract fun observeSyncStatus(): Flow<SyncStatusEntity?>

    @Query("SELECT * FROM sync_status WHERE id = ${SyncStatusEntity.SINGLE_ROW_ID}")
    abstract suspend fun getSyncStatus(): SyncStatusEntity?

    @Upsert
    abstract suspend fun upsertSyncStatus(status: SyncStatusEntity)

    /** Odczyt-zmiana-zapis jedynego wiersza w transakcji. */
    @Transaction
    open suspend fun updateSyncStatus(change: (SyncStatusEntity) -> SyncStatusEntity) {
        upsertSyncStatus(change(getSyncStatus() ?: SyncStatusEntity()))
    }
}
