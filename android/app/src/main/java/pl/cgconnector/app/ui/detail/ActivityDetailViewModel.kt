package pl.cgconnector.app.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.ActivityRepository
import pl.cgconnector.app.data.repository.RefreshResult
import pl.cgconnector.app.domain.ActivityDetail
import javax.inject.Inject

/** Dlaczego nie udało się pobrać szczegółów. Dane z Room (jeśli są) zostają na ekranie. */
enum class DetailError { OFFLINE, UNAUTHORIZED, NOT_CONFIGURED, INVALID_RESPONSE }

data class ActivityDetailUiState(
    val isLoading: Boolean = true,
    val detail: ActivityDetail? = null,
    val isFetching: Boolean = false,
    val error: DetailError? = null,
)

@HiltViewModel
class ActivityDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: ActivityRepository,
    ) : ViewModel() {
        /** Argument trasy `ActivityDetailDestination.activityId`. */
        private val activityId: Long = checkNotNull(savedStateHandle[ARG_ACTIVITY_ID])

        private val isFetching = MutableStateFlow(false)
        private val error = MutableStateFlow<DetailError?>(null)

        val uiState: StateFlow<ActivityDetailUiState> =
            combine(repository.observeActivityDetail(activityId), isFetching, error) { detail, fetching, err ->
                ActivityDetailUiState(isLoading = false, detail = detail, isFetching = fetching, error = err)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ActivityDetailUiState())

        init {
            // Na żądanie: z serwera tylko wtedy, gdy w Room nie ma jeszcze szczegółów.
            viewModelScope.launch {
                if (repository.observeActivityDetail(activityId).first()?.hasDetails != true) fetch()
            }
        }

        fun refresh() {
            viewModelScope.launch { fetch() }
        }

        private suspend fun fetch() {
            if (isFetching.value) return
            isFetching.value = true
            error.value =
                when (repository.refreshActivityDetail(activityId)) {
                    is RefreshResult.Success -> null
                    RefreshResult.Unavailable -> DetailError.OFFLINE
                    RefreshResult.Unauthorized -> DetailError.UNAUTHORIZED
                    RefreshResult.NotConfigured -> DetailError.NOT_CONFIGURED
                    RefreshResult.InvalidResponse -> DetailError.INVALID_RESPONSE
                }
            isFetching.value = false
        }

        companion object {
            const val ARG_ACTIVITY_ID = "activityId"
            private const val STOP_TIMEOUT_MS = 5_000L
        }
    }
