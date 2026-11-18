package pl.cgconnector.app.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import pl.cgconnector.app.data.repository.RefreshResult
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.domain.workout.QueuedWorkout
import pl.cgconnector.app.domain.workout.WorkoutDefinition
import java.time.LocalDate

class FakeWorkoutQueueRepository(
    items: List<QueuedWorkout> = emptyList(),
) : WorkoutQueueRepository {
    val items = MutableStateFlow(items)
    val enqueued = mutableListOf<Pair<WorkoutDefinition, LocalDate>>()
    val deleteRequests = mutableListOf<Long>()
    var refreshCalls = 0

    override fun observeQueue(): Flow<List<QueuedWorkout>> = items

    override suspend fun enqueue(
        definition: WorkoutDefinition,
        date: LocalDate,
    ): Long {
        enqueued += definition to date
        val id = (items.value.maxOfOrNull { it.id } ?: 0) + 1
        items.value += queued(id, definition.name, date)
        return id
    }

    override suspend fun requestDelete(id: Long) {
        deleteRequests += id
        items.value = items.value.map { if (it.id == id) it.copy(deleteRequested = true) else it }
    }

    val retryRequests = mutableListOf<Long>()

    override suspend fun retry(id: Long) {
        retryRequests += id
    }

    override fun refresh() {
        refreshCalls++
    }

    override suspend fun sync(): RefreshResult = RefreshResult.Success(0)

    companion object {
        fun queued(
            id: Long,
            name: String,
            date: LocalDate = LocalDate.of(2030, 1, 12),
            status: QueueStatus = QueueStatus.PENDING,
            createdInApp: Boolean = true,
        ) = QueuedWorkout(id, name, null, date, status, lastError = null, createdInApp, deleteRequested = false)
    }
}
