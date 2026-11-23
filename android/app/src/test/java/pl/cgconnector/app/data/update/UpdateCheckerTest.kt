package pl.cgconnector.app.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateCheckerTest {
    private fun release(
        tag: String,
        assets: List<AssetDto> = listOf(AssetDto("cg-connector-$tag.apk", "https://github.com/Tomek17/cg-connector/releases/download/$tag/cg-connector-$tag.apk")),
        prerelease: Boolean = false,
    ) = ReleaseDto(tag, "https://github.com/Tomek17/cg-connector/releases/tag/$tag", prerelease = prerelease, assets = assets)

    @Test
    fun `newer release links straight to the apk`() {
        val update = GitHubUpdateChecker.latestNewer(release("v0.12.0"), "0.11.0")

        assertThat(update?.version).isEqualTo("0.12.0")
        assertThat(update?.downloadUrl).endsWith("/cg-connector-v0.12.0.apk")
    }

    @Test
    fun `same or older release and prereleases are ignored`() {
        assertThat(GitHubUpdateChecker.latestNewer(release("v0.11.0"), "0.11.0")).isNull()
        assertThat(GitHubUpdateChecker.latestNewer(release("v0.12.0", prerelease = true), "0.11.0")).isNull()
    }

    @Test
    fun `without apk asset falls back to release page`() {
        val update = GitHubUpdateChecker.latestNewer(release("v0.12.0", assets = emptyList()), "0.11.0")

        assertThat(update?.downloadUrl).isEqualTo("https://github.com/Tomek17/cg-connector/releases/tag/v0.12.0")
    }
}
