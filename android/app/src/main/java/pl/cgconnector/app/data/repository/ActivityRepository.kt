package pl.cgconnector.app.data.repository

import kotlinx.coroutines.flow.Flow
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityType

interface ActivityRepository {
    /** Dane zawsze z lokalnej bazy (offline-first). `type == null` = wszystkie typy. */
    fun observeActivities(type: ActivityType?): Flow<List<Activity>>

    /** Pobiera nowe dane z serwera do bazy. Brak serwera to wynik [RefreshResult.Unavailable], nie wyjątek. */
    suspend fun refresh(): RefreshResult
}

sealed interface RefreshResult {
    data class Success(val fetched: Int) : RefreshResult

    data object NotConfigured : RefreshResult

    data object Unavailable : RefreshResult
}
