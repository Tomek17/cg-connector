package pl.cgconnector.app.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import pl.cgconnector.app.data.repository.UserTemplate
import pl.cgconnector.app.data.repository.WorkoutTemplateRepository
import pl.cgconnector.app.domain.workout.WorkoutDefinition

class FakeWorkoutTemplateRepository : WorkoutTemplateRepository {
    val templates = MutableStateFlow<List<UserTemplate>>(emptyList())
    private var nextId = 0

    override fun observeTemplates(): Flow<List<UserTemplate>> = templates

    override suspend fun save(definition: WorkoutDefinition) {
        templates.value = listOf(UserTemplate("t${++nextId}", definition)) + templates.value
    }

    override suspend fun delete(id: String) {
        templates.value = templates.value.filterNot { it.id == id }
    }
}
