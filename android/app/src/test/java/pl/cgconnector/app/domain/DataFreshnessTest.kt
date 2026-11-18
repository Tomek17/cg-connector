package pl.cgconnector.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DataFreshnessTest {
    private val now = Instant.parse("2026-11-18T13:10:00Z") // 14:10 czasu polskiego

    private fun ago(hours: Long) = now.minus(Duration.ofHours(hours))

    private fun evaluate(
        app: Instant?,
        server: Instant?,
    ) = DataFreshness.evaluate(now, app, server)

    @Test
    fun `daily cycle - server synced yesterday 14_03, now 14_10 before today's sync is still fresh`() {
        val serverYesterday = Instant.parse("2026-11-17T13:03:00Z") // wczoraj 14:03
        val appYesterday = Instant.parse("2026-11-17T13:20:00Z") // wczoraj 14:20

        assertThat(evaluate(appYesterday, serverYesterday)).isEqualTo(Freshness.FRESH)
    }

    @Test
    fun `fresh when both are recent`() {
        assertThat(evaluate(ago(1), ago(2))).isEqualTo(Freshness.FRESH)
    }

    @Test
    fun `server sync threshold is 26 h`() {
        assertThat(evaluate(ago(1), ago(26))).isEqualTo(Freshness.FRESH)
        assertThat(evaluate(ago(1), ago(26).minusSeconds(1))).isEqualTo(Freshness.STALE)
    }

    @Test
    fun `app refresh threshold is 30 h`() {
        assertThat(evaluate(ago(30), ago(1))).isEqualTo(Freshness.FRESH)
        assertThat(evaluate(ago(30).minusSeconds(1), ago(1))).isEqualTo(Freshness.STALE)
    }

    @Test
    fun `server not synced for days is stale even after app refresh`() {
        assertThat(evaluate(ago(0), ago(50))).isEqualTo(Freshness.STALE)
    }

    @Test
    fun `fresh install is reported separately`() {
        assertThat(evaluate(null, null)).isEqualTo(Freshness.NEVER_FETCHED)
        assertThat(evaluate(null, ago(1))).isEqualTo(Freshness.NEVER_FETCHED)
    }

    @Test
    fun `server never synced with Garmin is stale`() {
        assertThat(evaluate(ago(1), null)).isEqualTo(Freshness.STALE)
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
