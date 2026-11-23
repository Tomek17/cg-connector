package pl.cgconnector.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import pl.cgconnector.app.data.remote.GarminSyncRemoteDataSource
import pl.cgconnector.app.domain.LoadError
import java.time.OffsetDateTime
import javax.inject.Inject

/** Wynik „Synchronizuj teraz”. */
sealed interface SyncNowOutcome {
    /** Serwer skończył; [ok] = bez błędu, dane są już w telefonie. */
    data class Finished(val ok: Boolean) : SyncNowOutcome

    /** Serwer nie zaczął synchronizacji; [retryAt] = kiedy można spróbować ponownie. */
    data class Refused(val reason: Reason, val retryAt: OffsetDateTime? = null) : SyncNowOutcome

    /** Synchronizacja trwa dłużej niż czekamy; skończy się sama, dane dojdą przy odświeżeniu. */
    data object StillRunning : SyncNowOutcome

    data class Failed(val error: LoadError) : SyncNowOutcome

    enum class Reason { NO_GARMIN, NEEDS_LOGIN, RATE_LIMITED, RUNNING, TOO_SOON, UNKNOWN }
}

interface GarminSyncRepository {
    /** Zleca serwerowi synchronizację z Garminem, czeka na nią i odświeża dane w telefonie. */
    suspend fun syncNow(): SyncNowOutcome
}

class ServerGarminSyncRepository(
    private val remote: GarminSyncRemoteDataSource,
    private val activityRepository: ActivityRepository,
    private val pollDelayMs: Long,
    private val maxPolls: Int,
) : GarminSyncRepository {
    @Inject
    constructor(
        remote: GarminSyncRemoteDataSource,
        activityRepository: ActivityRepository,
    ) : this(remote, activityRepository, POLL_DELAY_MS, MAX_POLLS)

    override suspend fun syncNow(): SyncNowOutcome =
        try {
            val before = runCatching { remote.me().lastSyncAttemptAt }.getOrNull()
            val answer = remote.syncNow()
            if (!answer.started) {
                val retryAt = answer.retryAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                SyncNowOutcome.Refused(reason(answer.reason), retryAt)
            } else {
                waitForServer(before)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncNowOutcome.Failed(OfflineFirstActivityRepository.classify(e).toLoadError())
        }

    private suspend fun waitForServer(before: String?): SyncNowOutcome {
        repeat(maxPolls) {
            delay(pollDelayMs)
            val me = runCatching { remote.me() }.getOrNull() ?: return@repeat
            if (me.lastSyncStatus != RUNNING && me.lastSyncAttemptAt != null && me.lastSyncAttemptAt != before) {
                activityRepository.refresh()
                return SyncNowOutcome.Finished(ok = me.lastSyncStatus == OK)
            }
        }
        return SyncNowOutcome.StillRunning
    }

    private fun reason(value: String?): SyncNowOutcome.Reason =
        when (value) {
            "no_garmin" -> SyncNowOutcome.Reason.NO_GARMIN
            "needs_login" -> SyncNowOutcome.Reason.NEEDS_LOGIN
            "rate_limited" -> SyncNowOutcome.Reason.RATE_LIMITED
            RUNNING -> SyncNowOutcome.Reason.RUNNING
            "too_soon" -> SyncNowOutcome.Reason.TOO_SOON
            else -> SyncNowOutcome.Reason.UNKNOWN
        }

    private companion object {
        const val RUNNING = "running"
        const val OK = "ok"
        const val POLL_DELAY_MS = 4_000L

        /** Ok. 3 minuty; dłuższa synchronizacja (np. pierwsza, z historią) skończy się w tle. */
        const val MAX_POLLS = 45
    }
}
