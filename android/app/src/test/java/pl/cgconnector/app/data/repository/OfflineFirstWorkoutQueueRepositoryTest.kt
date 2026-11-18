package pl.cgconnector.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.cgconnector.app.data.local.CgDatabase
import pl.cgconnector.app.data.remote.FakeWorkoutRemoteDataSource
import pl.cgconnector.app.data.remote.NewWorkoutDto
import pl.cgconnector.app.data.remote.WorkoutOutDto
import pl.cgconnector.app.data.remote.WorkoutRemoteDataSource
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.domain.workout.RunStep
import pl.cgconnector.app.domain.workout.RunStepKind
import pl.cgconnector.app.domain.workout.RunningWorkout
import pl.cgconnector.app.domain.workout.StepEnd
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class OfflineFirstWorkoutQueueRepositoryTest {
    private lateinit var db: CgDatabase
    private lateinit var repository: OfflineFirstWorkoutQueueRepository
    private val server = FakeWorkoutRemoteDataSource()
    private val remote = RecordingRemote(server)
    private var triggers = 0
    private val pauseStore =
        object : QueuePauseStore {
            var until: Instant? = null

            override suspend fun pausedUntil() = until

            override suspend fun pauseUntil(until: Instant) {
                this.until = until
            }
        }

    private val clock = Clock.fixed(Instant.parse("2030-01-10T08:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.of(2030, 1, 12)
    private val workout = RunningWorkout("Bieg testowy", listOf(RunStep(RunStepKind.INTERVAL, StepEnd.Time(1800))))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CgDatabase::class.java).allowMainThreadQueries().build()
        repository = OfflineFirstWorkoutQueueRepository(db.workoutQueueDao(), remote, { triggers++ }, pauseStore, Json, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun queue() = repository.observeQueue().first()

    @Test
    fun enqueue_savesDraftBeforeAnyNetworkCall() =
        runTest {
            repository.enqueue(workout, date)

            val row = db.workoutQueueDao().getAll().single()
            assertThat(row.clientId).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
            assertThat(row.definitionJson).contains("\"sport\":\"running\"")
            assertThat(row.status).isEqualTo("pending")
            assertThat(remote.created).isEmpty()
            assertThat(triggers).isEqualTo(1)
        }

    @Test
    fun sync_sendsDraftAndStoresServerIds() =
        runTest {
            repository.enqueue(workout, date)

            assertThat(repository.sync()).isEqualTo(RefreshResult.Success(1))

            val row = db.workoutQueueDao().getAll().single()
            assertThat(row.remoteWorkoutId).isNotNull()
            assertThat(row.remoteScheduledId).isNotNull()
            // Fake „wysyła do Garmina” przy odczycie listy, który następuje po wysłaniu.
            assertThat(queue().single().status).isEqualTo(QueueStatus.SENT)
        }

    @Test
    fun sync_withoutServer_keepsDraftAndRetriesWithSameClientId() =
        runTest {
            repository.enqueue(workout, date)
            remote.failure = IOException("offline")

            assertThat(repository.sync()).isEqualTo(RefreshResult.Unavailable)
            assertThat(queue().single().status).isEqualTo(QueueStatus.PENDING)

            remote.failure = null
            assertThat(repository.sync()).isInstanceOf(RefreshResult.Success::class.java)
            val clientId = db.workoutQueueDao().getAll().single().clientId
            assertThat(remote.created.map { it.clientId }).containsExactly(clientId)
        }

    @Test
    fun sync_rejectedDraftIsFailedWithServerMessageAndNotRetried() =
        runTest {
            repository.enqueue(workout, date)
            remote.failure = httpError(422, """{"detail":"Unknown exercise"}""")

            assertThat(repository.sync()).isInstanceOf(RefreshResult.Success::class.java)
            remote.failure = null
            repository.sync()

            val item = queue().single()
            assertThat(item.status).isEqualTo(QueueStatus.FAILED)
            assertThat(item.lastError).isEqualTo("Unknown exercise")
            assertThat(remote.created).isEmpty()
        }

    @Test
    fun delete_unsentDraftIsLocalOnly_sentGoesThroughServer() =
        runTest {
            val local = repository.enqueue(workout, date)
            remote.failure = IOException("offline")
            repository.sync()
            repository.requestDelete(local)
            assertThat(queue().single().deleteRequested).isTrue()
            remote.failure = null
            repository.sync()
            assertThat(db.workoutQueueDao().getById(local)?.status).isEqualTo("deleted")
            assertThat(queue()).isEmpty()
            assertThat(remote.created).isEmpty()

            val sent = repository.enqueue(workout.copy(name = "Drugi"), date)
            repository.sync()
            repository.requestDelete(sent)
            repository.sync()
            val item = requireNotNull(db.workoutQueueDao().getById(sent))
            assertThat(remote.deleted).hasSize(1)
            // `deleting` od serwera, a po kolejnym odczycie listy wpis znika = usunięty.
            assertThat(item.status).isEqualTo("deleted")
            assertThat(queue()).isEmpty()
        }

    @Test
    fun delete_afterLostScheduleResponse_findsServerEntryAndDeletesIt() =
        runTest {
            val id = repository.enqueue(workout, date)
            remote.loseScheduleResponse = true
            assertThat(repository.sync()).isEqualTo(RefreshResult.Unavailable)
            remote.loseScheduleResponse = false
            assertThat(db.workoutQueueDao().getById(id)?.remoteScheduledId).isNull()

            repository.requestDelete(id)
            repository.sync()

            assertThat(remote.deleted).hasSize(1)
            assertThat(server.listScheduled(date, date)).isEmpty()
            // Usunięte znikają z widoku kolejki.
            assertThat(queue()).isEmpty()
        }

    @Test
    fun tooManyRequests_pausesQueueUntilRetryAfter() =
        runTest {
            repository.enqueue(workout, date)
            remote.failure = httpError(429, "{}", retryAfter = "120")

            assertThat(repository.sync()).isEqualTo(RefreshResult.Unavailable)
            assertThat(pauseStore.until).isEqualTo(clock.instant().plusSeconds(120))

            remote.failure = null
            assertThat(repository.sync()).isEqualTo(RefreshResult.Unavailable)
            assertThat(remote.created).isEmpty()
        }

    @Test
    fun retry_reschedulesFailedEntry() =
        runTest {
            val id = repository.enqueue(workout.copy(name = "Bieg ${FakeWorkoutRemoteDataSource.FAIL_MARKER}"), date)
            repository.sync()
            assertThat(queue().single().status).isEqualTo(QueueStatus.FAILED)
            assertThat(queue().single().canRetry).isTrue()

            repository.retry(id)
            assertThat(queue().single().status).isEqualTo(QueueStatus.PENDING)
            repository.sync()

            // Ten sam wpis serwera (POST /schedule jest idempotentny), znów wysłany i znów „błąd” w fake.
            assertThat(server.listScheduled(date, date)).hasSize(1)
            assertThat(remote.created).hasSize(1)
        }

    @Test
    fun sync_importsEntriesCreatedOutsideTheApp() =
        runTest {
            val created = server.createWorkout(NewWorkoutDto("00000000-0000-4000-8000-00000000000a", WORKOUT_JSON))
            server.schedule(created.id, date)

            repository.sync()

            val item = queue().single()
            assertThat(item.name).isEqualTo("Z Claude")
            assertThat(item.createdInApp).isFalse()
            assertThat(item.canDelete).isTrue()
        }

    private class RecordingRemote(
        private val delegate: WorkoutRemoteDataSource,
    ) : WorkoutRemoteDataSource by delegate {
        var failure: Exception? = null
        var loseScheduleResponse = false
        val created = mutableListOf<NewWorkoutDto>()
        val deleted = mutableListOf<Long>()

        /** [failure] dotyczy tylko tworzenia treningu (brak serwera albo odrzucenie definicji). */
        override suspend fun createWorkout(body: NewWorkoutDto): WorkoutOutDto {
            failure?.let { throw it }
            return delegate.createWorkout(body).also { created += body }
        }

        override suspend fun schedule(
            workoutId: Long,
            date: LocalDate,
        ) = delegate.schedule(workoutId, date).also { if (loseScheduleResponse) throw IOException("response lost") }

        override suspend fun deleteScheduled(scheduledId: Long) =
            delegate.deleteScheduled(scheduledId).also { deleted += scheduledId }
    }

    private companion object {
        val WORKOUT_JSON = Json.parseToJsonElement("""{"sport":"running","name":"Z Claude"}""") as JsonObject

        fun httpError(
            code: Int,
            body: String,
            retryAfter: String? = null,
        ): HttpException {
            val raw =
                okhttp3.Response
                    .Builder()
                    .code(code)
                    .message("error")
                    .protocol(Protocol.HTTP_1_1)
                    .request(Request.Builder().url("https://example.invalid/").build())
                    .apply { retryAfter?.let { header("Retry-After", it) } }
                    .build()
            return HttpException(Response.error<Any>(body.toResponseBody("application/json".toMediaType()), raw))
        }
    }
}
