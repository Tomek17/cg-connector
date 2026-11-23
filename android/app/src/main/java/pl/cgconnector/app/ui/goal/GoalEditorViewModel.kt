package pl.cgconnector.app.ui.goal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.GoalRepository
import pl.cgconnector.app.data.repository.Loaded
import pl.cgconnector.app.domain.AppZone
import pl.cgconnector.app.domain.LoadError
import pl.cgconnector.app.domain.RaceGoal
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

enum class GoalProblem { NAME, DISTANCE, TIME }

data class GoalEditorUiState(
    val isLoading: Boolean = true,
    val today: LocalDate = LocalDate.MIN,
    /** Czy cel już istnieje (wtedy jest przycisk „Usuń cel”). */
    val hasGoal: Boolean = false,
    val name: String = "",
    /** Wybrany dystans z listy; `null` = „Inny” i liczy się [customKm]. */
    val presetKm: Double? = RaceGoal.HALF_MARATHON_KM,
    val customKm: String = "",
    val date: LocalDate = LocalDate.MIN,
    /** `h:mm:ss` albo `mm:ss`; puste = bez czasu docelowego. */
    val time: String = "",
    val problems: Set<GoalProblem> = emptySet(),
    val isSaving: Boolean = false,
    val error: LoadError? = null,
    /** Zapisano albo usunięto: ekran się zamyka. */
    val done: Boolean = false,
) {
    val distanceKm: Double?
        get() = presetKm ?: customKm.trim().replace(',', '.').toDoubleOrNull()

    /** Tempo dla wpisanego czasu, np. `4:59`; `null`, gdy czasu brak albo jest zły. */
    val paceSecPerKm: Double?
        get() {
            val km = distanceKm?.takeIf { it > 0 } ?: return null
            return RaceGoal.parseTime(time)?.let { it / km }
        }
}

/** Ustawienie, zmiana albo usunięcie celu (start + czas docelowy). */
@HiltViewModel
class GoalEditorViewModel
    @Inject
    constructor(
        private val repository: GoalRepository,
        clock: Clock,
    ) : ViewModel() {
        private val today = clock.instant().atZone(AppZone.WARSAW).toLocalDate()
        private val state = MutableStateFlow(GoalEditorUiState(today = today, date = today.plusMonths(DEFAULT_MONTHS_AHEAD)))
        val uiState: StateFlow<GoalEditorUiState> = state.asStateFlow()

        init {
            viewModelScope.launch {
                val goal = repository.observeGoal().first()
                state.update { s ->
                    if (goal == null) {
                        s.copy(isLoading = false)
                    } else {
                        val preset = RaceGoal.PRESETS.firstOrNull { abs(it - goal.distanceKm) < KM_TOLERANCE }
                        s.copy(
                            isLoading = false,
                            hasGoal = true,
                            name = goal.name,
                            presetKm = preset,
                            customKm = if (preset == null) formatKm(goal.distanceKm) else "",
                            date = goal.date,
                            time = goal.targetSeconds?.let(RaceGoal::formatTime).orEmpty(),
                        )
                    }
                }
            }
        }

        fun setName(value: String) = state.update { it.copy(name = value.take(MAX_NAME), problems = it.problems - GoalProblem.NAME) }

        fun choosePreset(km: Double?) = state.update { it.copy(presetKm = km, problems = it.problems - GoalProblem.DISTANCE) }

        fun setCustomKm(value: String) = state.update { it.copy(customKm = value, problems = it.problems - GoalProblem.DISTANCE) }

        fun setDate(value: LocalDate) = state.update { it.copy(date = value) }

        fun setTime(value: String) = state.update { it.copy(time = value, problems = it.problems - GoalProblem.TIME) }

        fun save() {
            val s = state.value
            if (s.isSaving) return
            val problems = validate(s)
            if (problems.isNotEmpty()) {
                state.update { it.copy(problems = problems) }
                return
            }
            val goal = RaceGoal(s.name.trim(), s.date, checkNotNull(s.distanceKm), RaceGoal.parseTime(s.time))
            submit { repository.save(goal) }
        }

        fun delete() = submit { repository.clear() }

        private fun submit(call: suspend () -> Loaded<Unit>) {
            if (state.value.isSaving) return
            state.update { it.copy(isSaving = true, error = null) }
            viewModelScope.launch {
                when (val result = call()) {
                    is Loaded.Ok -> state.update { it.copy(isSaving = false, done = true) }
                    is Loaded.Failed -> state.update { it.copy(isSaving = false, error = result.error) }
                }
            }
        }

        companion object {
            const val MAX_NAME = 80
            private const val DEFAULT_MONTHS_AHEAD = 3L
            private const val KM_TOLERANCE = 0.001
            private const val MIN_KM = 0.1
            private const val MAX_KM = 500.0
            private const val MIN_SECONDS = 60
            private const val MAX_SECONDS = 7 * 24 * 3600

            fun validate(s: GoalEditorUiState): Set<GoalProblem> =
                buildSet {
                    if (s.name.isBlank()) add(GoalProblem.NAME)
                    if (s.distanceKm?.let { it in MIN_KM..MAX_KM } != true) add(GoalProblem.DISTANCE)
                    if (s.time.isNotBlank() && RaceGoal.parseTime(s.time)?.let { it in MIN_SECONDS..MAX_SECONDS } != true) {
                        add(GoalProblem.TIME)
                    }
                }

            private fun formatKm(km: Double): String = String.format(Locale.ROOT, "%.3f", km).trimEnd('0').trimEnd('.')
        }
    }
