package pl.cgconnector.app.data.remote

import java.time.LocalDate
import javax.inject.Inject

/** Treningi po stronie serwera. Błędy jak w [ActivityRemoteDataSource]: wyjątek, klasyfikuje wywołujący. */
interface WorkoutRemoteDataSource {
    suspend fun createWorkout(body: NewWorkoutDto): WorkoutOutDto

    suspend fun schedule(
        workoutId: Long,
        date: LocalDate,
    ): ScheduledOutDto

    suspend fun listScheduled(
        from: LocalDate,
        to: LocalDate,
    ): List<ScheduledOutDto>

    suspend fun deleteScheduled(scheduledId: Long): ScheduledOutDto
}

class ApiWorkoutRemoteDataSource
    @Inject
    constructor(
        private val apiProvider: CgApiProvider,
    ) : WorkoutRemoteDataSource {
        override suspend fun createWorkout(body: NewWorkoutDto): WorkoutOutDto = api().createWorkout(body)

        override suspend fun schedule(
            workoutId: Long,
            date: LocalDate,
        ): ScheduledOutDto = api().scheduleWorkout(workoutId, ScheduleRequestDto(date.toString()))

        override suspend fun listScheduled(
            from: LocalDate,
            to: LocalDate,
        ): List<ScheduledOutDto> = api().scheduled(from = from.toString(), to = to.toString()).items

        override suspend fun deleteScheduled(scheduledId: Long): ScheduledOutDto = api().deleteScheduled(scheduledId)

        private suspend fun api(): CgApi = apiProvider.get() ?: throw NotConfiguredException()
    }
