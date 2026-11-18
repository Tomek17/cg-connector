package pl.cgconnector.app.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serwer w pamięci dla buildu debug (`USE_FAKE_API`). Zachowuje się jak `/api/v1/workouts` i
 * `/api/v1/scheduled` z PR #10: idempotencja po `client_id`, jeden wpis na trening i dzień,
 * „wysłanie do Garmina” przy następnym odczycie listy. Nazwa treningu zawierająca [REJECT_MARKER]
 * daje 422, żeby dało się zobaczyć status „błąd” bez serwera.
 */
@Singleton
class FakeWorkoutRemoteDataSource
    @Inject
    constructor() : WorkoutRemoteDataSource {
        private val mutex = Mutex()
        private val workouts = mutableMapOf<String, WorkoutOutDto>()
        private val scheduled = linkedMapOf<Long, ScheduledOutDto>()
        private var nextId = 1L

        override suspend fun createWorkout(body: NewWorkoutDto): WorkoutOutDto =
            simulate {
                val name = body.definition["name"]?.jsonPrimitive?.content.orEmpty()
                if (REJECT_MARKER in name) throw httpError(422, "Invalid workout definition (fake)")
                workouts.getOrPut(body.clientId) {
                    val sport = body.definition["sport"]?.jsonPrimitive?.content.orEmpty()
                    WorkoutOutDto(nextId++, body.clientId, name, sport, createdBy = "app")
                }
            }

        override suspend fun schedule(
            workoutId: Long,
            date: LocalDate,
        ): ScheduledOutDto =
            simulate {
                val workout = workouts.values.firstOrNull { it.id == workoutId } ?: throw httpError(404, "Nie znaleziono")
                scheduled.values.firstOrNull {
                    it.workoutId == workoutId && it.date == date.toString() && it.status != "deleting"
                } ?: ScheduledOutDto(nextId++, workoutId, workout.name, workout.sport, date.toString(), "pending")
                    .also { scheduled[it.id] = it }
            }

        override suspend fun listScheduled(
            from: LocalDate,
            to: LocalDate,
        ): List<ScheduledOutDto> =
            simulate {
                scheduled.values.toList().forEach { item ->
                    when (item.status) {
                        "pending" -> scheduled[item.id] = item.copy(status = "sent")
                        "deleting" -> scheduled.remove(item.id)
                    }
                }
                scheduled.values.filter { LocalDate.parse(it.date) in from..to }
            }

        override suspend fun deleteScheduled(scheduledId: Long): ScheduledOutDto =
            simulate {
                val item = scheduled[scheduledId] ?: throw httpError(404, "Nie znaleziono")
                if (item.status == "sent" || item.status == "deleting") {
                    item.copy(status = "deleting").also { scheduled[scheduledId] = it }
                } else {
                    scheduled.remove(scheduledId)
                    item.copy(status = "deleted")
                }
            }

        private suspend fun <T> simulate(block: () -> T): T {
            delay(SIMULATED_LATENCY_MS)
            return mutex.withLock { block() }
        }

        companion object {
            const val REJECT_MARKER = "[422]"
            private const val SIMULATED_LATENCY_MS = 300L

            private fun httpError(
                code: Int,
                detail: String,
            ) = HttpException(
                Response.error<Any>(code, """{"detail":"$detail"}""".toResponseBody("application/json".toMediaType())),
            )
        }
    }
