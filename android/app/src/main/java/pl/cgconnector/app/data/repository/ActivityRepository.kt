package pl.cgconnector.app.data.repository

import kotlinx.coroutines.flow.Flow
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityDetail
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.RefreshOutcome

interface ActivityRepository {
    /** Dane zawsze z lokalnej bazy (offline-first). `type == null` = wszystkie typy. */
    fun observeActivities(type: ActivityType?): Flow<List<Activity>>

    /** Trening ze szczegółami z Room; `null`, gdy treningu nie ma w bazie. */
    fun observeActivityDetail(id: Long): Flow<ActivityDetail?>

    /** Pobiera szczegóły jednego treningu z serwera do Room (na żądanie, przy otwarciu ekranu). */
    suspend fun refreshActivityDetail(id: Long): RefreshResult

    /**
     * Pobiera nowe dane z serwera do bazy: treningi, VO2max, stan serwera (`/me`) i podsumowania tygodni.
     * Brak serwera to wynik [RefreshResult.Unavailable], nie wyjątek. Wynik trafia do stanu synchronizacji.
     */
    suspend fun refresh(): RefreshResult
}

sealed interface RefreshResult {
    data class Success(val fetched: Int) : RefreshResult

    data object NotConfigured : RefreshResult

    /** Brak sieci, timeout, 5xx: laptop-serwer jest wyłączony albo uśpiony. Warto ponowić. */
    data object Unavailable : RefreshResult

    /** 401: token odrzucony. Ponawianie nic nie da, użytkownik musi wkleić nowy token. */
    data object Unauthorized : RefreshResult

    /** Odpowiedź niezgodna z kontraktem (JSON, inne 4xx). Ponawianie nic nie da. */
    data object InvalidResponse : RefreshResult
}

fun RefreshResult.toOutcome(): RefreshOutcome =
    when (this) {
        is RefreshResult.Success -> RefreshOutcome.SUCCESS
        RefreshResult.NotConfigured -> RefreshOutcome.NOT_CONFIGURED
        RefreshResult.Unavailable -> RefreshOutcome.UNAVAILABLE
        RefreshResult.Unauthorized -> RefreshOutcome.UNAUTHORIZED
        RefreshResult.InvalidResponse -> RefreshOutcome.INVALID_RESPONSE
    }
