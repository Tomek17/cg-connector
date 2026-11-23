package pl.cgconnector.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.cgconnector.app.data.remote.GarminSyncRemoteDataSource
import pl.cgconnector.app.data.remote.MeDto
import pl.cgconnector.app.data.remote.SyncNowDto
import pl.cgconnector.app.domain.LoadError
import pl.cgconnector.app.testing.FakeActivityRepository
import java.io.IOException

class ServerGarminSyncRepositoryTest {
    private class Remote(
        var answer: SyncNowDto = SyncNowDto(started = true),
        /** Kolejne odpowiedzi `/me`; ostatnia się powtarza. */
        val states: MutableList<MeDto> = mutableListOf(),
        var error: Exception? = null,
    ) : GarminSyncRemoteDataSource {
        var syncCalls = 0

        override suspend fun syncNow(): SyncNowDto {
            error?.let { throw it }
            syncCalls++
            return answer
        }

        override suspend fun me(): MeDto = if (states.size > 1) states.removeAt(0) else states.first()
    }

    private fun me(
        attempt: String?,
        status: String?,
    ) = MeDto("tomek", "tomek", "ok", lastSyncAttemptAt = attempt, lastSyncStatus = status)

    private fun repo(
        remote: Remote,
        activities: FakeActivityRepository = FakeActivityRepository(),
    ) = ServerGarminSyncRepository(remote, activities, pollDelayMs = 10, maxPolls = 5)

    @Test
    fun `waits for the new run and refreshes local data`() =
        runTest {
            val old = "2026-11-23T14:00:00+01:00"
            val new = "2026-11-23T15:00:00+01:00"
            val remote =
                Remote(states = mutableListOf(me(old, "ok"), me(old, "ok"), me(new, "running"), me(new, "ok")))
            val activities = FakeActivityRepository()

            assertThat(repo(remote, activities).syncNow()).isEqualTo(SyncNowOutcome.Finished(ok = true))
            assertThat(activities.refreshCalls).isEqualTo(1)
        }

    @Test
    fun `failed run is reported`() =
        runTest {
            val remote = Remote(states = mutableListOf(me(null, null), me("2026-11-23T15:00:00+01:00", "failed")))
            assertThat(repo(remote).syncNow()).isEqualTo(SyncNowOutcome.Finished(ok = false))
        }

    @Test
    fun `refusal keeps reason and retry time`() =
        runTest {
            val remote =
                Remote(
                    answer = SyncNowDto(started = false, reason = "too_soon", retryAt = "2026-11-23T15:05:00+01:00"),
                    states = mutableListOf(me(null, null)),
                )
            val outcome = repo(remote).syncNow() as SyncNowOutcome.Refused
            assertThat(outcome.reason).isEqualTo(SyncNowOutcome.Reason.TOO_SOON)
            assertThat(outcome.retryAt?.hour).isEqualTo(15)
        }

    @Test
    fun `long sync is still running after polling`() =
        runTest {
            val remote = Remote(states = mutableListOf(me(null, null), me("2026-11-23T15:00:00+01:00", "running")))
            assertThat(repo(remote).syncNow()).isEqualTo(SyncNowOutcome.StillRunning)
        }

    @Test
    fun `offline server is a failure`() =
        runTest {
            val remote = Remote(states = mutableListOf(me(null, null)), error = IOException("offline"))
            assertThat(repo(remote).syncNow()).isEqualTo(SyncNowOutcome.Failed(LoadError.OFFLINE))
        }
}
