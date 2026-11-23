package pl.cgconnector.app.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SyncNowLimitTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")

    @Test
    fun `free when nothing synced in the last hour`() {
        assertNull(SyncNowLimit.nextAllowedAt(null, null, now))
        assertNull(SyncNowLimit.nextAllowedAt(now.minus(Duration.ofMinutes(61)), null, now))
    }

    @Test
    fun `blocked for an hour after the server's last sync`() {
        val last = now.minus(Duration.ofMinutes(20))
        assertEquals(last.plus(Duration.ofHours(1)), SyncNowLimit.nextAllowedAt(last, null, now))
    }

    @Test
    fun `later of server sync and own attempt wins`() {
        val own = now.plus(Duration.ofMinutes(55))
        assertEquals(own, SyncNowLimit.nextAllowedAt(now.minus(Duration.ofMinutes(30)), own, now))
    }
}
