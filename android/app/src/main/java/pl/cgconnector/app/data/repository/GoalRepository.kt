package pl.cgconnector.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import pl.cgconnector.app.data.remote.GoalDto
import pl.cgconnector.app.data.remote.GoalRemoteDataSource
import pl.cgconnector.app.data.remote.GoalStateDto
import pl.cgconnector.app.data.remote.toDomain
import pl.cgconnector.app.data.remote.toDto
import pl.cgconnector.app.domain.RaceGoal
import javax.inject.Inject

interface GoalRepository {
    /** Ostatnio znany cel (kopia w telefonie, działa bez serwera). */
    fun observeGoal(): Flow<RaceGoal?>

    /** Pobiera cel z serwera (mógł go zmienić Claude). */
    suspend fun refresh(): Loaded<Unit>

    suspend fun save(goal: RaceGoal): Loaded<Unit>

    suspend fun clear(): Loaded<Unit>
}

class DataStoreGoalRepository
    @Inject
    constructor(
        private val remote: GoalRemoteDataSource,
        private val dataStore: DataStore<Preferences>,
        private val json: Json,
    ) : GoalRepository {
        override fun observeGoal(): Flow<RaceGoal?> =
            dataStore.data.map { prefs ->
                prefs[KEY]?.let { text -> runCatching { json.decodeFromString<GoalDto>(text).toDomain() }.getOrNull() }
            }

        override suspend fun refresh() = store { remote.fetch() }

        override suspend fun save(goal: RaceGoal) = store { remote.save(goal.toDto()) }

        override suspend fun clear() = store { remote.clear() }

        private suspend fun store(call: suspend () -> GoalStateDto): Loaded<Unit> =
            try {
                val goal = call().goal
                // Sprawdza datę przed zapisem: zła odpowiedź nie może nadpisać dobrej kopii.
                goal?.toDomain()
                dataStore.edit { prefs ->
                    if (goal == null) prefs.remove(KEY) else prefs[KEY] = json.encodeToString(GoalDto.serializer(), goal)
                }
                Loaded.Ok(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loaded.Failed(OfflineFirstActivityRepository.classify(e).toLoadError())
            }

        private companion object {
            val KEY = stringPreferencesKey("race_goal")
        }
    }
