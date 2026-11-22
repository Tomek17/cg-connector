package pl.cgconnector.app.data.repository

import kotlinx.coroutines.CancellationException
import pl.cgconnector.app.data.local.ActivityDao
import pl.cgconnector.app.data.remote.ProgressRemoteDataSource
import pl.cgconnector.app.data.remote.toDomain
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.LoadError
import pl.cgconnector.app.domain.TagCount
import pl.cgconnector.app.domain.TagProgress
import javax.inject.Inject

/** Wynik zapytania do serwera: wartość albo powód błędu. */
sealed interface Loaded<out T> {
    data class Ok<T>(val value: T) : Loaded<T>

    data class Failed(val error: LoadError) : Loaded<Nothing>
}

interface ProgressRepository {
    suspend fun tags(): Loaded<List<TagCount>>

    suspend fun tagProgress(tag: ActivityTag): Loaded<TagProgress>

    /** Zapisuje tag na serwerze, a po sukcesie także w Room (lista i szczegóły od razu go pokazują). */
    suspend fun setTag(
        activityId: Long,
        tag: ActivityTag?,
    ): Loaded<Unit>
}

class ServerProgressRepository
    @Inject
    constructor(
        private val remote: ProgressRemoteDataSource,
        private val activityDao: ActivityDao,
    ) : ProgressRepository {
        override suspend fun tags(): Loaded<List<TagCount>> = load { remote.fetchTags().toDomain() }

        override suspend fun tagProgress(tag: ActivityTag): Loaded<TagProgress> =
            load {
                // Odpowiedź z nieznanym tagiem nie może się zdarzyć dla tagu wysłanego przez aplikację.
                remote.fetchTagProgress(tag.key).toDomain() ?: error("Unexpected tag")
            }

        override suspend fun setTag(
            activityId: Long,
            tag: ActivityTag?,
        ): Loaded<Unit> =
            load {
                val saved = remote.setActivityTag(activityId, tag?.key)
                activityDao.updateTag(activityId, saved.tag)
            }

        private suspend fun <T> load(block: suspend () -> T): Loaded<T> =
            try {
                Loaded.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loaded.Failed(OfflineFirstActivityRepository.classify(e).toLoadError())
            }
    }

fun RefreshResult.toLoadError(): LoadError =
    when (this) {
        RefreshResult.NotConfigured -> LoadError.NOT_CONFIGURED
        RefreshResult.Unauthorized -> LoadError.UNAUTHORIZED
        RefreshResult.Unavailable -> LoadError.OFFLINE
        RefreshResult.InvalidResponse, is RefreshResult.Success -> LoadError.INVALID_RESPONSE
    }
