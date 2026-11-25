package pl.cgconnector.app.ui.progress

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.cgconnector.app.data.repository.Loaded
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.ExerciseProgress
import pl.cgconnector.app.domain.LoadError
import pl.cgconnector.app.domain.SetPoint
import pl.cgconnector.app.domain.TagCount
import pl.cgconnector.app.domain.TagProgress
import pl.cgconnector.app.testing.FakeProgressRepository
import pl.cgconnector.app.testing.MainDispatcherRule
import java.time.LocalDate

/** Robolectric, bo SavedStateHandle należy do warstwy Androida. */
@RunWith(AndroidJUnit4::class)
class ProgressViewModelsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val day = LocalDate.of(2026, 11, 20)

    @Test
    fun `tag counts are summed per category`() {
        val repo =
            FakeProgressRepository(
                tagsResult =
                    Loaded.Ok(
                        listOf(
                            TagCount(ActivityTag.INTERVALS, 3, day),
                            TagCount(ActivityTag.BASE, 2, day),
                            TagCount(ActivityTag.LEGS, 4, day),
                        ),
                    ),
            )
        val viewModel by lazy { ProgressTagsViewModel(SavedStateHandle(), repo) }

        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.category).isNull()
        assertThat(state.countFor(ActivityType.RUNNING)).isEqualTo(5)
        assertThat(state.countFor(ActivityType.STRENGTH)).isEqualTo(4)
        assertThat(state.countFor(ActivityType.CLIMBING)).isEqualTo(0)
    }

    @Test
    fun `group count includes untagged activities and is not summed twice`() {
        val repo =
            FakeProgressRepository(
                tagsResult =
                    Loaded.Ok(
                        listOf(
                            TagCount(ActivityTag.ALL_RUNNING, 7, day),
                            TagCount(ActivityTag.INTERVALS, 3, day),
                            TagCount(ActivityTag.LEGS, 4, day),
                        ),
                    ),
            )
        val viewModel by lazy { ProgressTagsViewModel(SavedStateHandle(), repo) }

        val state = viewModel.uiState.value
        assertThat(state.countFor(ActivityType.RUNNING)).isEqualTo(7)
        assertThat(state.countFor(ActivityType.STRENGTH)).isEqualTo(4)
    }

    @Test
    fun `groups are listed first in progress but cannot be set on an activity`() {
        assertThat(ActivityTag.forType(ActivityType.RUNNING).first()).isEqualTo(ActivityTag.ALL_RUNNING)
        assertThat(ActivityTag.forType(ActivityType.STRENGTH).first()).isEqualTo(ActivityTag.ALL_STRENGTH)
        assertThat(ActivityTag.settableFor(ActivityType.RUNNING)).doesNotContain(ActivityTag.ALL_RUNNING)
        assertThat(ActivityTag.settableFor(ActivityType.STRENGTH)).doesNotContain(ActivityTag.ALL_STRENGTH)
    }

    @Test
    fun `category screen keeps its type and retry reloads after an error`() {
        val repo = FakeProgressRepository(tagsResult = Loaded.Failed(LoadError.OFFLINE))
        val viewModel by lazy {
            ProgressTagsViewModel(SavedStateHandle(mapOf(ProgressTagsViewModel.ARG_TYPE to "strength")), repo)
        }

        assertThat(viewModel.uiState.value.category).isEqualTo(ActivityType.STRENGTH)
        assertThat(viewModel.uiState.value.error).isEqualTo(LoadError.OFFLINE)

        repo.tagsResult = Loaded.Ok(listOf(TagCount(ActivityTag.PUSH, 1, day)))
        viewModel.load()
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.counts.keys).containsExactly(ActivityTag.PUSH)
        assertThat(repo.tagsCalls).isEqualTo(2)
    }

    @Test
    fun `exercise screen picks the exercise from the tag data`() {
        val squat =
            ExerciseProgress("SQUAT", "BARBELL_BACK_SQUAT", 1, listOf(SetPoint(1, day, 1, 80.0, 5)))
        val repo =
            FakeProgressRepository(
                progressResult = { Loaded.Ok(TagProgress(it, emptyList(), listOf(squat))) },
            )
        val handle =
            SavedStateHandle(
                mapOf(TagProgressViewModel.ARG_TAG to "legs", TagProgressViewModel.ARG_EXERCISE to "BARBELL_BACK_SQUAT"),
            )
        val viewModel by lazy { TagProgressViewModel(handle, repo) }

        val state = viewModel.uiState.value
        assertThat(state.tag).isEqualTo(ActivityTag.LEGS)
        assertThat(state.selectedExercise).isEqualTo(squat)
    }

    @Test
    fun `unknown tag shows nothing instead of crashing`() {
        val viewModel by lazy {
            TagProgressViewModel(SavedStateHandle(mapOf(TagProgressViewModel.ARG_TAG to "sprint")), FakeProgressRepository())
        }

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.progress).isNull()
    }
}
