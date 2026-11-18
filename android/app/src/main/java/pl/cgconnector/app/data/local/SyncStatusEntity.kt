package pl.cgconnector.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import pl.cgconnector.app.domain.RefreshOutcome
import pl.cgconnector.app.domain.SyncStatus
import java.time.Instant

/** Jeden wiersz (id = [SINGLE_ROW_ID]) ze stanem synchronizacji, widoczny na ekranie Status także offline. */
@Entity(tableName = "sync_status")
data class SyncStatusEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    @ColumnInfo(name = "server_last_sync_at") val serverLastSyncAt: Instant? = null,
    @ColumnInfo(name = "server_last_sync_status") val serverLastSyncStatus: String? = null,
    @ColumnInfo(name = "garmin_status") val garminStatus: String? = null,
    @ColumnInfo(name = "last_refresh_attempt_at") val lastRefreshAttemptAt: Instant? = null,
    @ColumnInfo(name = "last_refresh_outcome") val lastRefreshOutcome: String? = null,
    @ColumnInfo(name = "last_refresh_success_at") val lastRefreshSuccessAt: Instant? = null,
) {
    companion object {
        const val SINGLE_ROW_ID = 0
    }
}

fun SyncStatusEntity.toDomain() =
    SyncStatus(
        serverLastSyncAt = serverLastSyncAt,
        serverLastSyncStatus = serverLastSyncStatus,
        garminStatus = garminStatus,
        lastRefreshAttemptAt = lastRefreshAttemptAt,
        lastRefreshOutcome = RefreshOutcome.entries.firstOrNull { it.name == lastRefreshOutcome },
        lastRefreshSuccessAt = lastRefreshSuccessAt,
    )
