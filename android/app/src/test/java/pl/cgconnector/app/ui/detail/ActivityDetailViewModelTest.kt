package pl.cgconnector.app.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.cgconnector.app.data.repository.Loaded
import pl.cgconnector.app.data.repository.RefreshResult
import pl.cgconnector.app.domain.ActivityDetail
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.LoadError
import pl.cgconnector.app.domain.Lap
import pl.cgconnector.app.testing.FakeActivityRepository
import pl.cgconnector.app.testing.FakeProgressRepository
import pl.cgconnector.app.testing.MainDispatcherRule
import pl.cgconnector.app.testing.testActivity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Robolectric, bo SavedStateHandle należy do warstwy Androida. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ActivityDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-11-18T13:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val handle = SavedStateHandle(mapOf(ActivityDetailViewModel.ARG_ACTIVITY_ID to 1L))

    private fun detail(fetched: Boolean) =
        ActivityDetail(
            activity = testActivity(1),
            detailsFetchedAt = if (fetched) Instant.parse("2026-11-18T10:00:00Z") else null,
            detailsAvailable = fetched,
            laps = if (fetched) listOf(Lap(1, 1000.0, 330.0, 150, 160, null, 5.5, null)) else emptyList(),
            hrZones = emptyList(),
            exerciseSets = emptyList(),
        )

    @Test
    fun `fetches details on open when not cached`() =
        runTest {
            val repo = FakeActivityRepository()
            repo.details.value = mapOf(1L to detail(fetched = false))
            repo.onDetailFetched = { id -> repo.details.value = mapOf(id to detail(fetched = true)) }

            val viewModel = ActivityDetailViewModel(handle, repo, FakeProgressRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(repo.detailRequests).containsExactly(1L)
            val state = viewModel.uiState.value
            assertThat(state.isLoading).isFalse()
            assertThat(state.isFetching).isFalse()
            assertThat(state.error).isNull()
            assertThat(state.detail?.laps).hasSize(1)
        }

    @Test
    fun `cached details are shown without network`() =
        runTest {
            val repo = FakeActivityRepository()
            repo.details.value = mapOf(1L to detail(fetched = true))

            val viewModel = ActivityDetailViewModel(handle, repo, FakeProgressRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(repo.detailRequests).isEmpty()
            assertThat(viewModel.uiState.value.detail?.hasDetails).isTrue()
        }

    @Test
    fun `offline fetch keeps summary and reports error, retry works`() =
        runTest {
            val repo = FakeActivityRepository(detailResult = RefreshResult.Unavailable)
            repo.details.value = mapOf(1L to detail(fetched = false))

            val viewModel = ActivityDetailViewModel(handle, repo, FakeProgressRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value.error).isEqualTo(DetailError.OFFLINE)
            assertThat(viewModel.uiState.value.detail?.activity?.id).isEqualTo(1L)

            repo.detailResult = RefreshResult.Success(1)
            viewModel.refresh()

            assertThat(viewModel.uiState.value.error).isNull()
            assertThat(repo.detailRequests).hasSize(2)
        }

    @Test
    fun `unauthorized is mapped`() =
        runTest {
            val repo = FakeActivityRepository(detailResult = RefreshResult.Unauthorized)
            repo.details.value = mapOf(1L to detail(fetched = false))

            val viewModel = ActivityDetailViewModel(handle, repo, FakeProgressRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value.error).isEqualTo(DetailError.UNAUTHORIZED)
        }

    @Test
    fun `missing activity yields null detail`() =
        runTest {
            val repo = FakeActivityRepository(detailResult = RefreshResult.InvalidResponse)

            val viewModel = ActivityDetailViewModel(handle, repo, FakeProgressRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value.isLoading).isFalse()
            assertThat(viewModel.uiState.value.detail).isNull()
            assertThat(viewModel.uiState.value.error).isEqualTo(DetailError.INVALID_RESPONSE)
        }

    @Test
    fun `details unavailable on server are retried only after 24 h`() {
        val unavailable =
            detail(fetched = true).copy(detailsAvailable = false, detailsFetchedAt = now.minus(Duration.ofHours(23)))

        assertThat(ActivityDetailViewModel.shouldAutoFetch(unavailable, now)).isFalse()
        assertThat(
            ActivityDetailViewModel.shouldAutoFetch(unavailable.copy(detailsFetchedAt = now.minus(Duration.ofHours(25))), now),
        ).isTrue()
        assertThat(ActivityDetailViewModel.shouldAutoFetch(detail(fetched = true), now)).isFalse()
        assertThat(ActivityDetailViewModel.shouldAutoFetch(detail(fetched = false), now)).isTrue()
        assertThat(ActivityDetailViewModel.shouldAutoFetch(null, now)).isTrue()
    }

    @Test
    fun `old unavailable details are fetched again on open`() =
        runTest {
            val repo = FakeActivityRepository()
            repo.details.value =
                mapOf(1L to detail(fetched = true).copy(detailsAvailable = false, detailsFetchedAt = now.minus(Duration.ofHours(30))))

            val viewModel = ActivityDetailViewModel(handle, repo, FakeProgressRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(repo.detailRequests).containsExactly(1L)
        }

    @Test
    fun `fetching flag is cleared when repository throws`() =
        runTest {
            val repo = FakeActivityRepository()
            repo.details.value = mapOf(1L to detail(fetched = true))
            repo.onDetailFetched = { throw IllegalStateException("boom") }
            val viewModel = ActivityDetailViewModel(handle, repo, FakeProgressRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            val failure = runCatching { viewModel.fetch() }

            assertThat(failure.isFailure).isTrue()
            assertThat(viewModel.uiState.value.isFetching).isFalse()
        }

    @Test
    fun `tag change is saved and failure is shown`() =
        runTest {
            val repo = FakeActivityRepository()
            repo.details.value = mapOf(1L to detail(fetched = true))
            val progress = FakeProgressRepository()
            val viewModel = ActivityDetailViewModel(handle, repo, progress, clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.setTag(ActivityTag.INTERVALS)
            assertThat(progress.savedTags).containsExactly(1L to ActivityTag.INTERVALS)
            assertThat(viewModel.uiState.value.isSavingTag).isFalse()
            assertThat(viewModel.uiState.value.tagError).isNull()

            progress.setTagResult = Loaded.Failed(LoadError.OFFLINE)
            viewModel.setTag(null)
            assertThat(viewModel.uiState.value.tagError).isEqualTo(LoadError.OFFLINE)
        }
}
