package pl.cgconnector.app.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.domain.workout.QueuedWorkout
import javax.inject.Inject

data class QueueUiState(
    val isLoading: Boolean = true,
    val items: List<QueuedWorkout> = emptyList(),
    /** Wpis, dla którego pokazujemy potwierdzenie usunięcia. */
    val confirmDelete: QueuedWorkout? = null,
)

@HiltViewModel
class QueueViewModel
    @Inject
    constructor(
        private val repository: WorkoutQueueRepository,
    ) : ViewModel() {
        private val confirmDelete = MutableStateFlow<QueuedWorkout?>(null)

        val uiState: StateFlow<QueueUiState> =
            combine(repository.observeQueue(), confirmDelete) { items, toDelete ->
                QueueUiState(isLoading = false, items = items, confirmDelete = toDelete)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), QueueUiState())

        /** Wysyłka i statusy w tle (WorkManager); wynik pojawi się w liście z Room. */
        fun refresh() = repository.refresh()

        /** Usunąć można tylko aktywne wpisy outboxu CG-connector ([QueuedWorkout.canDelete]). */
        fun askDelete(item: QueuedWorkout) {
            if (item.canDelete) confirmDelete.value = item
        }

        fun dismissDelete() {
            confirmDelete.value = null
        }

        fun confirmDelete() {
            val item = confirmDelete.value ?: return
            confirmDelete.value = null
            viewModelScope.launch { repository.requestDelete(item.id) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
