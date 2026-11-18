package pl.cgconnector.app.ui.detail

import androidx.annotation.VisibleForTesting
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
        private val clock: Clock,
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
            viewModelScope.launch {
                if (shouldAutoFetch(repository.observeActivityDetail(activityId).first(), clock.instant())) fetch()
            }
        }

        fun refresh() {
            viewModelScope.launch { fetch() }
        }

        @VisibleForTesting
        internal suspend fun fetch() {
            if (isFetching.value) return
            isFetching.value = true
            try {
                error.value =
                    when (repository.refreshActivityDetail(activityId)) {
                        is RefreshResult.Success -> null
                        RefreshResult.Unavailable -> DetailError.OFFLINE
                        RefreshResult.Unauthorized -> DetailError.UNAUTHORIZED
                        RefreshResult.NotConfigured -> DetailError.NOT_CONFIGURED
                        RefreshResult.InvalidResponse -> DetailError.INVALID_RESPONSE
                    }
            } finally {
                // Także przy anulowaniu (zamknięcie ekranu) albo nieoczekiwanym wyjątku.
                isFetching.value = false
            }
        }

        companion object {
            const val ARG_ACTIVITY_ID = "activityId"

            /** Po tylu godzinach ponawiamy pobranie, jeśli serwer wcześniej nie miał szczegółów z Garmina. */
            val UNAVAILABLE_RETRY_AFTER: Duration = Duration.ofHours(24)

            /**
             * Pobieranie na żądanie: gdy w Room nie ma jeszcze szczegółów albo serwer poprzednio ich nie miał
             * (`details_available = false`) i od tamtej próby minęło ponad [UNAVAILABLE_RETRY_AFTER].
             * Brak treningu w bazie też próbujemy pobrać (np. otwarty z powiadomienia przed odświeżeniem listy).
             */
            fun shouldAutoFetch(
                detail: ActivityDetail?,
                now: Instant,
            ): Boolean {
                val fetchedAt = detail?.detailsFetchedAt ?: return true
                if (detail.detailsAvailable) return false
                return Duration.between(fetchedAt, now) > UNAVAILABLE_RETRY_AFTER
            }

            private const val STOP_TIMEOUT_MS = 5_000L
        }
    }
