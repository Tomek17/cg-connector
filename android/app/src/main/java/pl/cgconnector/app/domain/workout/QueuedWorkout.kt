package pl.cgconnector.app.domain.workout

import java.time.LocalDate

/**
 * Status wpisu kolejki. Wartości API to statusy outboxu serwera (`ScheduledOut.status`);
 * [PENDING] obejmuje też szkic, który jeszcze nie dotarł do serwera.
 */
enum class QueueStatus(
    val apiValue: String,
) {
    PENDING("pending"),
    SENT("sent"),
    FAILED("failed"),
    DELETING("deleting"),
    DELETED("deleted"),
    ;

    companion object {
        /** Nieznany status z nowszego serwera traktujemy jak oczekujący, żeby nie zgubić wpisu. */
        fun fromApi(value: String): QueueStatus = entries.firstOrNull { it.apiValue == value } ?: PENDING
    }
}

/** Trening w kolejce do kalendarza Garmin Connect, tak jak widzi go ekran kolejki. */
data class QueuedWorkout(
    val id: Long,
    val name: String,
    val sport: WorkoutSport?,
    val date: LocalDate,
    val status: QueueStatus,
    val lastError: String?,
    /** Szkic z tej aplikacji (ma `client_id`); inaczej wpis utworzony przez CG-connector z innego miejsca (MCP). */
    val createdInApp: Boolean,
    /** Usunięcie zlecone w aplikacji, jeszcze niepotwierdzone przez serwer. */
    val deleteRequested: Boolean,
    /** Telefon ma definicję treningu (szkic z tej aplikacji), więc można go skopiować do kreatora. */
    val canCopy: Boolean = false,
) {
    /**
     * Kolejka zawiera wyłącznie wpisy outboxu CG-connector (serwer nie pokazuje innych pozycji kalendarza
     * Garmin), więc każdy aktywny wpis można usunąć (MASTER_PROMPT 7.11).
     */
    val canDelete: Boolean
        get() = !deleteRequested && status in setOf(QueueStatus.PENDING, QueueStatus.SENT, QueueStatus.FAILED)

    val canRetry: Boolean
        get() = !deleteRequested && status == QueueStatus.FAILED
}
