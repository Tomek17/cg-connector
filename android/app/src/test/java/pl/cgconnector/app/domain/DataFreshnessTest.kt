package pl.cgconnector.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DataFreshnessTest {
    private val now = Instant.parse("2026-11-18T15:00:00Z")

    private fun ago(hours: Long) = now.minus(Duration.ofHours(hours))

    @Test
    fun `fresh when both app refresh and server sync are within 24 h`() {
        assertThat(DataFreshness.isStale(now, ago(1), ago(2))).isFalse()
    }

    @Test
    fun `exactly 24 h is still fresh, one second more is stale`() {
        assertThat(DataFreshness.isStale(now, ago(24), ago(1))).isFalse()
        assertThat(DataFreshness.isStale(now, ago(24).minusSeconds(1), ago(1))).isTrue()
    }

    @Test
    fun `stale when app has not refreshed for over 24 h`() {
        assertThat(DataFreshness.isStale(now, ago(25), ago(1))).isTrue()
    }

    @Test
    fun `stale when server has not synced with Garmin for over 24 h even after app refresh`() {
        assertThat(DataFreshness.isStale(now, ago(0), ago(30))).isTrue()
    }

    @Test
    fun `stale when never refreshed or never synced`() {
        assertThat(DataFreshness.isStale(now, null, ago(1))).isTrue()
        assertThat(DataFreshness.isStale(now, ago(1), null)).isTrue()
    }

    @Test
    fun `server state follows last refresh outcome`() {
        assertThat(SyncStatus().serverState()).isEqualTo(ServerState.UNKNOWN)
        assertThat(SyncStatus(lastRefreshOutcome = RefreshOutcome.SUCCESS).serverState()).isEqualTo(ServerState.ONLINE)
        assertThat(SyncStatus(lastRefreshOutcome = RefreshOutcome.UNAUTHORIZED).serverState()).isEqualTo(ServerState.ONLINE)
        assertThat(SyncStatus(lastRefreshOutcome = RefreshOutcome.UNAVAILABLE).serverState()).isEqualTo(ServerState.OFFLINE)
        assertThat(SyncStatus(lastRefreshOutcome = RefreshOutcome.NOT_CONFIGURED).serverState()).isEqualTo(ServerState.UNKNOWN)
    }
}
