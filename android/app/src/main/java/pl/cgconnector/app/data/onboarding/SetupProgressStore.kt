package pl.cgconnector.app.data.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

/** Kroki kreatora pierwszego uruchomienia. */
enum class SetupStep { WELCOME, SERVER, GARMIN, CLAUDE, DONE }

/**
 * Krok kreatora w toku. Zapisywany, bo w trakcie użytkownik wychodzi z aplikacji (poczta po kod
 * z Garmina, przeglądarka z claude.ai) i system może ją zamknąć: po powrocie kreator wraca do tego kroku.
 */
interface SetupProgressStore {
    /** Krok w toku albo `null`, gdy kreator się skończył (albo nigdy nie zaczął). */
    val step: Flow<SetupStep?>

    suspend fun save(step: SetupStep)

    suspend fun finish()
}

class DataStoreSetupProgressStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : SetupProgressStore {
        override val step: Flow<SetupStep?> =
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .map { prefs -> prefs[KEY]?.let { name -> SetupStep.entries.firstOrNull { it.name == name } } }

        override suspend fun save(step: SetupStep) {
            dataStore.edit { it[KEY] = step.name }
        }

        override suspend fun finish() {
            dataStore.edit { it.remove(KEY) }
        }

        private companion object {
            val KEY = stringPreferencesKey("setup_step")
        }
    }
