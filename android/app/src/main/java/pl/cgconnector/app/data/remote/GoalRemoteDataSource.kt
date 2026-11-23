package pl.cgconnector.app.data.remote

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pl.cgconnector.app.domain.RaceGoal
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** `PUT /api/v1/goal` (schemat `GoalBody`) i cel w odpowiedzi (schemat `GoalOut`, pola wyliczane pomijamy). */
@Serializable
data class GoalDto(
    val name: String,
    /** `YYYY-MM-DD`. */
    val date: String,
    @SerialName("distance_km") val distanceKm: Double,
    @SerialName("target_seconds") val targetSeconds: Int? = null,
)

/** `GET/PUT/DELETE /api/v1/goal` (schemat `GoalState`). */
@Serializable
data class GoalStateDto(
    val goal: GoalDto? = null,
)

fun GoalDto.toDomain() = RaceGoal(name, LocalDate.parse(date), distanceKm, targetSeconds)

fun RaceGoal.toDto() = GoalDto(name, date.toString(), distanceKm, targetSeconds)

/** Cel trzyma serwer, żeby widział go też Claude; telefon ma kopię do pracy offline. */
interface GoalRemoteDataSource {
    suspend fun fetch(): GoalStateDto

    suspend fun save(goal: GoalDto): GoalStateDto

    suspend fun clear(): GoalStateDto
}

class ApiGoalRemoteDataSource
    @Inject
    constructor(
        private val apiProvider: CgApiProvider,
    ) : GoalRemoteDataSource {
        override suspend fun fetch() = api().goal()

        override suspend fun save(goal: GoalDto) = api().setGoal(goal)

        override suspend fun clear() = api().deleteGoal()

        private suspend fun api(): CgApi = apiProvider.get() ?: throw NotConfiguredException()
    }

/** Build z `USE_FAKE_API = true`: cel tylko w pamięci, na start przykładowy półmaraton. */
@Singleton
class FakeGoalRemoteDataSource
    @Inject
    constructor() : GoalRemoteDataSource {
        private var goal: GoalDto? = GoalDto("Półmaraton Warszawski", "2027-03-28", RaceGoal.HALF_MARATHON_KM, 6300)

        override suspend fun fetch(): GoalStateDto {
            delay(LATENCY_MS)
            return GoalStateDto(goal)
        }

        override suspend fun save(goal: GoalDto): GoalStateDto {
            delay(LATENCY_MS)
            this.goal = goal
            return GoalStateDto(goal)
        }

        override suspend fun clear(): GoalStateDto {
            delay(LATENCY_MS)
            goal = null
            return GoalStateDto(null)
        }

        private companion object {
            const val LATENCY_MS = 300L
        }
    }
