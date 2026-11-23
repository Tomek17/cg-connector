package pl.cgconnector.app.domain.alerts

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import pl.cgconnector.app.domain.SyncStatus
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.testing.FakeWorkoutQueueRepository.Companion.queued
import java.time.Duration
import java.time.Instant

class AlertRulesTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")
    private val fresh = SyncStatus(serverLastSyncAt = now.minus(Duration.ofHours(2)), lastRefreshSuccessAt = now)

    @Test
    fun `failed workout is announced once and again after it recovers and fails`() {
        val failed = listOf(queued(1, "Interwały", status = QueueStatus.FAILED), queued(2, "Siła"))

        val (first, state) = AlertRules.evaluate(failed, fresh, now, AlertState())
        assertThat(first).containsExactly(Alert.WorkoutFailed(1, "Interwały", failed[0].date))

        val (second, same) = AlertRules.evaluate(failed, fresh, now, state)
        assertThat(second).isEmpty()

        val (_, recovered) = AlertRules.evaluate(listOf(queued(1, "Interwały", status = QueueStatus.SENT)), fresh, now, same)
        assertThat(recovered.notifiedFailedIds).isEmpty()
        assertThat(AlertRules.evaluate(failed, fresh, now, recovered).first).hasSize(1)
    }

    @Test
    fun `stale data is announced once per episode`() {
        val stale = fresh.copy(serverLastSyncAt = now.minus(Duration.ofHours(30)))

        val (first, state) = AlertRules.evaluate(emptyList(), stale, now, AlertState())
        assertThat(first).containsExactly(Alert.DataStale)
        assertThat(AlertRules.evaluate(emptyList(), stale, now, state).first).isEmpty()

        val (_, freshState) = AlertRules.evaluate(emptyList(), fresh, now, state)
        assertThat(freshState.staleNotified).isFalse()
    }

    @Test
    fun `never fetched data and fresh data give no alerts`() {
        assertThat(AlertRules.evaluate(emptyList(), SyncStatus(), now, AlertState()).first).isEmpty()
        assertThat(AlertRules.evaluate(emptyList(), fresh, now, AlertState()).first).isEmpty()
    }

    @Test
    fun `garmin login problem is announced once`() {
        val status = fresh.copy(garminStatus = "needs_login")
        val (first, state) = AlertRules.evaluate(emptyList(), status, now, AlertState())
        assertThat(first).containsExactly(Alert.GarminLogin)
        assertThat(AlertRules.evaluate(emptyList(), status, now, state).first).isEmpty()
    }
}
