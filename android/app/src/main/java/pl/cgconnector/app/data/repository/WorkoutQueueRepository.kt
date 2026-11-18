package pl.cgconnector.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import pl.cgconnector.app.data.local.QueuedWorkoutEntity
import pl.cgconnector.app.data.local.WorkoutQueueDao
import pl.cgconnector.app.data.local.toDomain
import pl.cgconnector.app.data.remote.CgApi
import pl.cgconnector.app.data.remote.NewWorkoutDto
import pl.cgconnector.app.data.remote.ScheduledOutDto
import pl.cgconnector.app.data.remote.WorkoutRemoteDataSource
import pl.cgconnector.app.domain.AppZone
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.domain.workout.QueuedWorkout
import pl.cgconnector.app.domain.workout.WorkoutDefinition
import pl.cgconnector.app.domain.workout.WorkoutJson
import retrofit2.HttpException
import java.time.Clock
import java.time.LocalDate
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

/** Uruchamia wysyłkę kolejki w tle (WorkManager); w testach podmieniane. */
fun interface WorkoutQueueTrigger {
    fun trigger()
}

/**
 * Kolejka treningów do kalendarza Garmin Connect (przez serwer CG), offline-first:
 * [enqueue] i [requestDelete] zmieniają tylko Room i budzą workera, a sieć obsługuje [sync].
 */
interface WorkoutQueueRepository {
    fun observeQueue(): Flow<List<QueuedWorkout>>

    /** Zapisuje szkic z nowym `client_id` (UUID) przed jakąkolwiek wysyłką; zwraca id wiersza. */
    suspend fun enqueue(
        definition: WorkoutDefinition,
        date: LocalDate,
    ): Long

    /** Tylko wpisy outboxu CG-connector ([QueuedWorkout.canDelete]). */
    suspend fun requestDelete(id: Long)

    /** Ponowne zaplanowanie wpisu z błędem ([QueuedWorkout.canRetry]). */
    suspend fun retry(id: Long)

    /** Ręczne „Odśwież”: wysyłka i statusy w tle. */
    fun refresh()

    /** Wywołuje worker: wysyłka, usuwanie i statusy z serwera. */
    suspend fun sync(): RefreshResult
}

class OfflineFirstWorkoutQueueRepository
    @Inject
    constructor(
        private val dao: WorkoutQueueDao,
        private val remote: WorkoutRemoteDataSource,
        private val trigger: WorkoutQueueTrigger,
        private val pauseStore: QueuePauseStore,
        private val json: Json,
        private val clock: Clock,
    ) : WorkoutQueueRepository {
        override fun observeQueue(): Flow<List<QueuedWorkout>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

        override suspend fun enqueue(
            definition: WorkoutDefinition,
            date: LocalDate,
        ): Long {
            val id =
                dao.insert(
                    QueuedWorkoutEntity(
                        clientId = UUID.randomUUID().toString(),
                        name = definition.name.trim(),
                        sport = definition.sport.apiValue,
                        definitionJson = WorkoutJson.encode(definition).toString(),
                        date = date,
                        status = QueueStatus.PENDING.apiValue,
                        createdAt = clock.instant(),
                    ),
                )
            trigger.trigger()
            return id
        }

        override suspend fun requestDelete(id: Long) {
            dao.markDeleteRequested(id)
            trigger.trigger()
        }

        override suspend fun retry(id: Long) {
            dao.resetFailed(id)
            trigger.trigger()
        }

        override fun refresh() = trigger.trigger()

        /**
         * Wysyła szkice, wykonuje zlecenia usunięcia i pobiera statusy z serwera. Odrzucenie jednego wpisu
         * (4xx) oznacza go jako „błąd” i nie przerywa reszty; brak serwera przerywa i daje
         * [RefreshResult.Unavailable] (worker ponowi). Każda operacja jest idempotentna po stronie serwera.
         */
        override suspend fun sync(): RefreshResult {
            // Serwer kazał poczekać (Retry-After): nie pytamy go wcześniej, worker spróbuje po backoffie.
            if (pauseStore.pausedUntil()?.isAfter(clock.instant()) == true) return RefreshResult.Unavailable
            return guard {
                for (item in dao.getAll()) {
                    when {
                        item.deleteRequested -> perItem(item) { delete(item) }
                        item.needsSending() -> perItem(item) { send(item) }
                    }
                }
                val count = refreshStatuses()
                dao.pruneBefore(today().minusDays(KEEP_PAST_DAYS))
                RefreshResult.Success(count)
            }
        }

        /** Szkic albo wpis do ponownego zaplanowania („Ponów”, także zaimportowany z serwera). */
        private fun QueuedWorkoutEntity.needsSending() =
            status == QueueStatus.PENDING.apiValue && remoteScheduledId == null &&
                (remoteWorkoutId != null || (clientId != null && definitionJson != null))

        private suspend fun send(item: QueuedWorkoutEntity) {
            val workoutId =
                item.remoteWorkoutId ?: remote
                    .createWorkout(
                        NewWorkoutDto(
                            clientId = requireNotNull(item.clientId),
                            definition = json.parseToJsonElement(requireNotNull(item.definitionJson)).jsonObject,
                        ),
                    ).id
                    .also { dao.setRemoteWorkoutId(item.id, it) }
            val scheduled = schedule(item, workoutId)
            dao.setScheduled(item.id, scheduled.id, scheduled.status, scheduled.lastError)
        }

        /** Idempotentne: dla tego treningu i dnia serwer zwraca istniejący wpis (wpis `failed` wraca do `pending`). */
        private suspend fun schedule(
            item: QueuedWorkoutEntity,
            workoutId: Long,
        ): ScheduledOutDto {
            val scheduled = remote.schedule(workoutId, item.date)
            // Wpis mógł już przyjść z GET /scheduled jako obcy (przerwana wcześniejsza próba): zostaje nasz.
            dao.getByRemoteScheduledId(scheduled.id)?.takeIf { it.id != item.id }?.let { dao.deleteById(it.id) }
            return scheduled
        }

        private suspend fun delete(item: QueuedWorkoutEntity) {
            val workoutId = item.remoteWorkoutId
            // Trening jest na serwerze, ale odpowiedź na /schedule mogła zginąć (przerwany worker): ustalamy id
            // wpisu tym samym idempotentnym zapytaniem, żeby nie zostawić go w kalendarzu Garmin.
            val scheduledId = item.remoteScheduledId ?: workoutId?.let { schedule(item, it).id }
            if (scheduledId == null) {
                // Nigdy nie trafił do outboxu serwera: wystarczy oznaczyć lokalnie.
                dao.finishDelete(item.id, QueueStatus.DELETED.apiValue, null)
                return
            }
            val result =
                try {
                    remote.deleteScheduled(scheduledId)
                } catch (e: HttpException) {
                    if (e.code() != HTTP_NOT_FOUND) throw e
                    null
                }
            dao.finishDelete(item.id, result?.status ?: QueueStatus.DELETED.apiValue, result?.lastError)
        }

        /** Odrzucenie (4xx poza 401/408/429) dotyczy tylko tego wpisu: bez ponowień, komunikat serwera w `last_error`. */
        private suspend fun perItem(
            item: QueuedWorkoutEntity,
            block: suspend () -> Unit,
        ) {
            try {
                block()
            } catch (e: HttpException) {
                if (!isRejection(e.code())) throw e
                val message = errorDetail(e)
                if (item.deleteRequested) {
                    dao.finishDelete(item.id, item.status, message)
                } else {
                    dao.setStatus(item.id, QueueStatus.FAILED.apiValue, message)
                }
            }
        }

        private suspend fun refreshStatuses(): Int {
            val from = today().minusDays(1)
            val to = from.plusDays(CgApi.MAX_SCHEDULED_DAYS)
            val items = remote.listScheduled(from, to)
            val local = dao.getAll()
            val known = local.mapNotNull { it.remoteScheduledId }.toSet()
            items.forEach { dto ->
                val date = LocalDate.parse(dto.date)
                if (dto.id in known) {
                    dao.applyServerState(dto.id, dto.name, date, dto.status, dto.lastError)
                } else {
                    dao.insert(
                        QueuedWorkoutEntity(
                            clientId = null,
                            name = dto.name,
                            sport = dto.sport,
                            definitionJson = null,
                            date = date,
                            status = dto.status,
                            remoteWorkoutId = dto.workoutId,
                            remoteScheduledId = dto.id,
                            lastError = dto.lastError,
                            createdAt = clock.instant(),
                        ),
                    )
                }
            }
            // Serwer nie zwraca wpisów `deleted`: brak w liście (w pobranym zakresie dat) = usunięty.
            val present = items.map { it.id }.toSet()
            local
                .filter { row ->
                    row.remoteScheduledId != null && row.remoteScheduledId !in present && !row.deleteRequested &&
                        row.status != QueueStatus.DELETED.apiValue && !row.date.isBefore(from) && !row.date.isAfter(to)
                }.forEach { dao.setStatus(it.id, QueueStatus.DELETED.apiValue, null) }
            return items.size
        }

        private fun today(): LocalDate = clock.instant().atZone(AppZone.WARSAW).toLocalDate()

        private suspend fun guard(block: suspend () -> RefreshResult): RefreshResult =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 408/429 to chwilowy stan serwera: ponawiamy jak przy braku sieci, nie wcześniej niż Retry-After.
                if (e is HttpException && e.code() in TRANSIENT_4XX) {
                    pauseStore.pauseUntil(clock.instant().plus(retryAfter(e)))
                    RefreshResult.Unavailable
                } else {
                    OfflineFirstActivityRepository.classify(e)
                }
            }

        /** `Retry-After` w sekundach albo jako data HTTP; brak lub zły = [DEFAULT_PAUSE], maks. [MAX_PAUSE]. */
        private fun retryAfter(e: HttpException): Duration {
            val header = e.response()?.headers()?.get("Retry-After")?.trim().orEmpty()
            val pause =
                header.toLongOrNull()?.let(Duration::ofSeconds)
                    ?: runCatching {
                        Duration.between(clock.instant(), ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME))
                    }.getOrNull()
                    ?: DEFAULT_PAUSE
            return pause.coerceIn(Duration.ZERO, MAX_PAUSE)
        }

        private fun errorDetail(e: HttpException): String {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val detail =
                body?.let { runCatching { json.parseToJsonElement(it).jsonObject["detail"] }.getOrNull() }
            val text =
                when (detail) {
                    is JsonPrimitive -> detail.content
                    // Walidacja FastAPI: lista obiektów z polem `msg`.
                    is JsonArray ->
                        detail
                            .mapNotNull { ((it as? JsonObject)?.get("msg") as? JsonPrimitive)?.content }
                            .joinToString("\n")
                    else -> null
                }
            return text?.takeIf { it.isNotBlank() }?.take(MAX_ERROR_LENGTH) ?: "HTTP ${e.code()}"
        }

        companion object {
            private val DEFAULT_PAUSE: Duration = Duration.ofSeconds(60)
            private val MAX_PAUSE: Duration = Duration.ofHours(6)
            private const val KEEP_PAST_DAYS = 30L
            private const val MAX_ERROR_LENGTH = 500
            private const val HTTP_UNAUTHORIZED = 401
            private const val HTTP_NOT_FOUND = 404
            private val TRANSIENT_4XX = setOf(408, 429)

            /** 4xx, którego ponawianie nie naprawi (zła definicja, data, brak obiektu). */
            fun isRejection(code: Int): Boolean = code in 400..499 && code != HTTP_UNAUTHORIZED && code !in TRANSIENT_4XX
        }
    }
