package pl.cgconnector.app.ui.progress

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.ExerciseLayoutStore
import pl.cgconnector.app.data.repository.Loaded
import pl.cgconnector.app.data.repository.ProgressRepository
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.ExerciseLayout
import pl.cgconnector.app.domain.ExerciseProgress
import pl.cgconnector.app.domain.LoadError
import pl.cgconnector.app.domain.TagCount
import pl.cgconnector.app.domain.TagProgress
import javax.inject.Inject

data class ProgressTagsUiState(
    val isLoading: Boolean = true,
    val error: LoadError? = null,
    /** `null` = ekran kategorii; inaczej lista tagów tej kategorii. */
    val category: ActivityType? = null,
    val counts: Map<ActivityTag, TagCount> = emptyMap(),
) {
    /** Liczba z grupy „wszystkie” (też bez tagu); bez niej (starszy serwer) suma tagów. */
    fun countFor(type: ActivityType): Int {
        val ofType = counts.values.filter { it.tag.type == type }
        return ofType.firstOrNull { it.tag.isGroup }?.count ?: ofType.sumOf { it.count }
    }
}

/** Zakładka Progress (kategorie) i lista tagów jednej kategorii (argument [ARG_TYPE]). */
@HiltViewModel
class ProgressTagsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: ProgressRepository,
    ) : ViewModel() {
        private val category = savedStateHandle.get<String>(ARG_TYPE)?.let { ActivityType.fromApi(it) }
        private val state = MutableStateFlow(ProgressTagsUiState(category = category))
        val uiState: StateFlow<ProgressTagsUiState> = state.asStateFlow()

        init {
            load()
        }

        fun load() {
            state.value = state.value.copy(isLoading = true, error = null)
            viewModelScope.launch {
                state.value =
                    when (val result = repository.tags()) {
                        is Loaded.Ok -> state.value.copy(isLoading = false, counts = result.value.associateBy { it.tag })
                        is Loaded.Failed -> state.value.copy(isLoading = false, error = result.error)
                    }
            }
        }

        companion object {
            /** Nazwa pola `ProgressCategoryDestination.type`. */
            const val ARG_TYPE = "type"
        }
    }

data class TagProgressUiState(
    val tag: ActivityTag?,
    /** Wybrane ćwiczenie (ekran wykresu serii); `null` = ekran tagu. */
    val exercise: String? = null,
    val isLoading: Boolean = true,
    val error: LoadError? = null,
    val progress: TagProgress? = null,
    val layout: ExerciseLayout = ExerciseLayout(),
    /** Tryb edycji listy ćwiczeń (usuwanie, przywracanie, przesuwanie). */
    val isEditing: Boolean = false,
) {
    val exercises: List<ExerciseProgress>
        get() = layout.visible(progress?.strength.orEmpty())

    val removedExercises: List<ExerciseProgress>
        get() = layout.removed(progress?.strength.orEmpty())

    val selectedExercise: ExerciseProgress?
        get() = exercise?.let { name -> progress?.strength?.firstOrNull { it.exercise == name } }
}

/** Wykresy tagu (argument [ARG_TAG]) i, z [ARG_EXERCISE], wykres serii jednego ćwiczenia. */
@HiltViewModel
class TagProgressViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: ProgressRepository,
        private val layoutStore: ExerciseLayoutStore,
    ) : ViewModel() {
        private val state =
            MutableStateFlow(
                TagProgressUiState(
                    tag = ActivityTag.fromKey(savedStateHandle[ARG_TAG]),
                    exercise = savedStateHandle[ARG_EXERCISE],
                ),
            )
        val uiState: StateFlow<TagProgressUiState> = state.asStateFlow()

        init {
            load()
            state.value.tag?.let { tag ->
                viewModelScope.launch {
                    layoutStore.layout(tag).collect { layout -> state.update { it.copy(layout = layout) } }
                }
            }
        }

        fun toggleEditing() = state.update { it.copy(isEditing = !it.isEditing) }

        fun moveExercise(
            name: String,
            delta: Int,
        ) = saveLayout { it.layout.move(it.progress?.strength.orEmpty(), name, delta) }

        fun removeExercise(name: String) = saveLayout { it.layout.hide(it.progress?.strength.orEmpty(), name) }

        fun restoreExercise(name: String) = saveLayout { it.layout.restore(name) }

        private fun saveLayout(change: (TagProgressUiState) -> ExerciseLayout) {
            val tag = state.value.tag ?: return
            val layout = change(state.value)
            state.update { it.copy(layout = layout) }
            viewModelScope.launch { layoutStore.save(tag, layout) }
        }

        fun load() {
            val tag =
                state.value.tag ?: run {
                    state.value = state.value.copy(isLoading = false)
                    return
                }
            state.value = state.value.copy(isLoading = true, error = null)
            viewModelScope.launch {
                state.value =
                    when (val result = repository.tagProgress(tag)) {
                        is Loaded.Ok -> state.value.copy(isLoading = false, progress = result.value)
                        is Loaded.Failed -> state.value.copy(isLoading = false, error = result.error)
                    }
            }
        }

        companion object {
            /** Nazwy pól `TagProgressDestination`. */
            const val ARG_TAG = "tag"
            const val ARG_EXERCISE = "exercise"
        }
    }
