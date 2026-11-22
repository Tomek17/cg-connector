package pl.cgconnector.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import pl.cgconnector.app.domain.workout.WorkoutDefinition
import pl.cgconnector.app.domain.workout.WorkoutJson
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Szablon zapisany przez użytkownika („Moje szablony”). */
data class UserTemplate(
    val id: String,
    val definition: WorkoutDefinition,
)

/** Szablony treningów zapisane w telefonie (nie trafiają na serwer). */
interface WorkoutTemplateRepository {
    /** Od najnowszego. */
    fun observeTemplates(): Flow<List<UserTemplate>>

    suspend fun save(definition: WorkoutDefinition)

    suspend fun delete(id: String)
}

/**
 * Osobny plik DataStore (`templates`): ustawienia serwera są czyszczone przy zmianie konfiguracji,
 * a szablony mają przetrwać. Lista jako tablica JSON w formacie definicji serwera ([WorkoutJson]).
 */
@Singleton
class DataStoreWorkoutTemplateRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : WorkoutTemplateRepository {
        private val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("templates") }

        override fun observeTemplates(): Flow<List<UserTemplate>> = dataStore.data.map { decode(it[KEY]) }

        override suspend fun save(definition: WorkoutDefinition) {
            dataStore.edit { prefs ->
                val current = decode(prefs[KEY])
                val template = UserTemplate(UUID.randomUUID().toString(), definition)
                prefs[KEY] = encode((listOf(template) + current).take(MAX_TEMPLATES))
            }
        }

        override suspend fun delete(id: String) {
            dataStore.edit { prefs -> prefs[KEY] = encode(decode(prefs[KEY]).filterNot { it.id == id }) }
        }

        companion object {
            const val MAX_TEMPLATES = 30
            private val KEY = stringPreferencesKey("user_templates")

            fun encode(templates: List<UserTemplate>): String =
                JsonArray(
                    templates.map {
                        buildJsonObject {
                            put("id", it.id)
                            put("definition", WorkoutJson.encode(it.definition))
                        }
                    },
                ).toString()

            /** Uszkodzone wpisy są pomijane, żeby jeden zły szablon nie blokował reszty. */
            fun decode(text: String?): List<UserTemplate> {
                if (text.isNullOrBlank()) return emptyList()
                val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
                return array.mapNotNull { element ->
                    runCatching {
                        val obj = element.jsonObject
                        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
                        val definition = (obj["definition"] as? JsonObject)?.let(WorkoutJson::decode) ?: return@runCatching null
                        UserTemplate(id, definition)
                    }.getOrNull()
                }
            }
        }
    }
