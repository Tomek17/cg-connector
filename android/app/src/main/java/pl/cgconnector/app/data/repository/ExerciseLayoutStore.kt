package pl.cgconnector.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.ExerciseLayout
import javax.inject.Inject

/** Własna kolejność i usunięte ćwiczenia listy jednego tagu w zakładce Progress (tylko na telefonie). */
interface ExerciseLayoutStore {
    fun layout(tag: ActivityTag): Flow<ExerciseLayout>

    suspend fun save(
        tag: ActivityTag,
        layout: ExerciseLayout,
    )
}

class DataStoreExerciseLayoutStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : ExerciseLayoutStore {
        override fun layout(tag: ActivityTag): Flow<ExerciseLayout> =
            dataStore.data.map { prefs ->
                ExerciseLayout(
                    order = prefs[orderKey(tag)]?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty(),
                    hidden = prefs[hiddenKey(tag)].orEmpty(),
                )
            }

        override suspend fun save(
            tag: ActivityTag,
            layout: ExerciseLayout,
        ) {
            dataStore.edit {
                it[orderKey(tag)] = layout.order.joinToString(SEPARATOR)
                it[hiddenKey(tag)] = layout.hidden
            }
        }

        private companion object {
            const val SEPARATOR = "\n"

            fun orderKey(tag: ActivityTag) = stringPreferencesKey("progress_exercise_order_${tag.key}")

            fun hiddenKey(tag: ActivityTag) = stringSetPreferencesKey("progress_exercise_hidden_${tag.key}")
        }
    }
