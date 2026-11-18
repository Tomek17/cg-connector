package pl.cgconnector.app.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.ActivityRepository
import pl.cgconnector.app.data.repository.RefreshResult
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityType
import javax.inject.Inject

data class ActivitiesUiState(
    val activities: List<Activity> = emptyList(),
    val selectedType: ActivityType? = null,
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = true,
)

sealed interface ActivitiesEvent {
    data object Refreshed : ActivitiesEvent

    data object ServerUnavailable : ActivitiesEvent

    data object NotConfigured : ActivitiesEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActivitiesViewModel
    @Inject
    constructor(
        private val repository: ActivityRepository,
    ) : ViewModel() {
        private val selectedType = MutableStateFlow<ActivityType?>(null)
        private val isRefreshing = MutableStateFlow(false)

        private val _events = Channel<ActivitiesEvent>(Channel.BUFFERED)
        val events: Flow<ActivitiesEvent> = _events.receiveAsFlow()

        val uiState: StateFlow<ActivitiesUiState> =
            combine(
                selectedType.flatMapLatest { type -> repository.observeActivities(type) },
                selectedType,
                isRefreshing,
            ) { activities, type, refreshing ->
                ActivitiesUiState(
                    activities = activities,
                    selectedType = type,
                    isRefreshing = refreshing,
                    isLoading = false,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ActivitiesUiState())

        fun onTypeSelected(type: ActivityType?) {
            selectedType.value = type
        }

        fun refresh() {
            if (isRefreshing.value) return
            isRefreshing.value = true
            viewModelScope.launch {
                val event =
                    when (repository.refresh()) {
                        is RefreshResult.Success -> ActivitiesEvent.Refreshed
                        RefreshResult.Unavailable -> ActivitiesEvent.ServerUnavailable
                        RefreshResult.NotConfigured -> ActivitiesEvent.NotConfigured
                    }
                isRefreshing.value = false
                _events.send(event)
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
