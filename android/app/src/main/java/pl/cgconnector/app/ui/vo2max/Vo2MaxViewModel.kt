package pl.cgconnector.app.ui.vo2max

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import pl.cgconnector.app.data.repository.StatsRepository
import pl.cgconnector.app.domain.Vo2MaxPoint
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class Vo2MaxUiState(
    val isLoading: Boolean = true,
    /** Sporty, dla których są pomiary; bieg pierwszy. */
    val sports: List<String> = emptyList(),
    val selectedSport: String? = null,
    /** Pomiary wybranego sportu od najstarszego. */
    val points: List<Vo2MaxPoint> = emptyList(),
    /** Zmiana względem najstarszego pomiaru z ostatnich [Vo2MaxViewModel.CHANGE_WINDOW_DAYS] dni. */
    val change: Double? = null,
) {
    val latest: Vo2MaxPoint? get() = points.lastOrNull()
}

@HiltViewModel
class Vo2MaxViewModel
    @Inject
    constructor(
        repository: StatsRepository,
    ) : ViewModel() {
        private val chosenSport = MutableStateFlow<String?>(null)

        val uiState: StateFlow<Vo2MaxUiState> =
            combine(repository.observeVo2Max(), chosenSport) { all, chosen -> buildState(all, chosen) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Vo2MaxUiState())

        fun onSportSelected(sport: String) {
            chosenSport.value = sport
        }

        companion object {
            const val CHANGE_WINDOW_DAYS = 90
            private const val DEFAULT_SPORT = "running"
            private const val STOP_TIMEOUT_MS = 5_000L

            fun buildState(
                all: List<Vo2MaxPoint>,
                chosen: String?,
            ): Vo2MaxUiState {
                val sports = all.map { it.sport }.distinct().sortedWith(compareBy<String>({ it != DEFAULT_SPORT }, { it }))
                val sport = chosen?.takeIf { it in sports } ?: sports.firstOrNull()
                val points = all.filter { it.sport == sport }.sortedBy { it.date }
                return Vo2MaxUiState(
                    isLoading = false,
                    sports = sports,
                    selectedSport = sport,
                    points = points,
                    change = change(points),
                )
            }

            /** Ostatni pomiar minus najstarszy z okna [CHANGE_WINDOW_DAYS] dni; `null` przy < 2 pomiarach w oknie. */
            fun change(points: List<Vo2MaxPoint>): Double? {
                val latest = points.lastOrNull() ?: return null
                val windowStart = latest.date.minus(CHANGE_WINDOW_DAYS.toLong(), ChronoUnit.DAYS)
                val first = points.firstOrNull { !it.date.isBefore(windowStart) } ?: return null
                if (first == latest) return null
                return latest.value - first.value
            }
        }
    }
