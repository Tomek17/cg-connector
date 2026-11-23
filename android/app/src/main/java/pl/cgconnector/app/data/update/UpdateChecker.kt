package pl.cgconnector.app.data.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.cgconnector.app.BuildConfig
import pl.cgconnector.app.di.IoDispatcher
import pl.cgconnector.app.domain.AppVersion
import javax.inject.Inject
import javax.inject.Singleton

/** Nowsza wersja aplikacji na GitHub Releases. */
data class AppUpdate(
    val version: String,
    /** Bezpośredni link do pliku APK albo strona wydania, gdy APK nie ma w zasobach. */
    val downloadUrl: String,
)

@Serializable
internal data class ReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
internal data class AssetDto(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)

interface UpdateChecker {
    /** Nowsze wydanie albo `null` (brak nowszego, brak sieci, błąd GitHuba). */
    suspend fun check(): AppUpdate?
}

/** Pyta publiczne API GitHuba o najnowsze wydanie (bez tokenu, bez danych użytkownika). */
@Singleton
class GitHubUpdateChecker
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val json: Json,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : UpdateChecker {
        override suspend fun check(): AppUpdate? =
            withContext(ioDispatcher) {
                runCatching {
                    val request =
                        Request
                            .Builder()
                            .url(LATEST_URL)
                            .header("Accept", "application/vnd.github+json")
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val release = json.decodeFromString<ReleaseDto>(response.body.string())
                        latestNewer(release, BuildConfig.VERSION_NAME)
                    }
                }.getOrNull()
            }

        internal companion object {
            const val LATEST_URL = "https://api.github.com/repos/Tomek17/cg-connector/releases/latest"

            fun latestNewer(
                release: ReleaseDto,
                current: String,
            ): AppUpdate? {
                if (release.draft || release.prerelease || !AppVersion.isNewer(release.tagName, current)) return null
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk") && it.url.startsWith("https://github.com/") }
                return AppUpdate(release.tagName.removePrefix("v"), apk?.url ?: release.htmlUrl)
            }
        }
    }
