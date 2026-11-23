package pl.cgconnector.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppVersionTest {
    @Test
    fun `compares numerically`() {
        assertThat(AppVersion.isNewer("v0.11.0", "0.9.0")).isTrue()
        assertThat(AppVersion.isNewer("v0.10.1", "0.10.0")).isTrue()
        assertThat(AppVersion.isNewer("v1.0.0", "0.99.9")).isTrue()
        assertThat(AppVersion.isNewer("v0.9.0", "0.11.0")).isFalse()
        assertThat(AppVersion.isNewer("v0.11.0", "0.11.0")).isFalse()
    }

    @Test
    fun `release is newer than its release candidate`() {
        assertThat(AppVersion.isNewer("v1.0.0", "1.0.0-rc1")).isTrue()
        assertThat(AppVersion.isNewer("v1.0.0-rc1", "1.0.0")).isFalse()
    }

    @Test
    fun `garbage is never newer`() {
        assertThat(AppVersion.isNewer("latest", "0.1.0")).isFalse()
        assertThat(AppVersion.isNewer("v2.0.0", "dev")).isFalse()
    }
}
