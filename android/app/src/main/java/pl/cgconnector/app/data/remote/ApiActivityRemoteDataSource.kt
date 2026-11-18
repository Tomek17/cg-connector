package pl.cgconnector.app.data.remote

import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject

class ApiActivityRemoteDataSource
    @Inject
    constructor(
        private val apiProvider: CgApiProvider,
    ) : ActivityRemoteDataSource {
        override suspend fun fetchActivities(
            from: LocalDate,
            to: LocalDate,
        ): List<ActivityDto> {
            return fetchAllPages(api(), from, to)
        }

        override suspend fun fetchActivityDetail(id: Long): ActivityDetailDto = api().activity(id)

        override suspend fun fetchVo2Max(
            from: LocalDate,
            to: LocalDate,
        ): List<Vo2MaxPointDto> = api().vo2max(from = from.toString(), to = to.toString()).items

        override suspend fun fetchMe(): MeDto = api().me()

        override suspend fun fetchWeeklySummary(): SummaryDto = api().summary(period = "week")

        private suspend fun api(): CgApi = apiProvider.get() ?: throw NotConfiguredException()

        companion object {
            /** Bezpiecznik na wypadek błędu serwera (has_more bez postępu). */
            private const val MAX_PAGES = 20

            /**
             * Pobiera wszystkie strony: przy `has_more` kolejne zapytanie ma `to` = dzień najstarszej
             * otrzymanej aktywności; duplikaty z tego dnia rozwiązuje upsert po `id`.
             */
            suspend fun fetchAllPages(
                api: CgApi,
                from: LocalDate,
                to: LocalDate,
                limit: Int = CgApi.MAX_LIMIT,
            ): List<ActivityDto> {
                val result = LinkedHashMap<Long, ActivityDto>()
                var pageTo = to
                repeat(MAX_PAGES) {
                    val page = api.activities(from = from.toString(), to = pageTo.toString(), limit = limit)
                    page.items.forEach { result[it.id] = it }
                    if (!page.hasMore || page.items.isEmpty()) return result.values.toList()
                    val oldestDay = page.items.minOf { OffsetDateTime.parse(it.startTime).toLocalDate() }
                    // Brak postępu (cała strona z jednego dnia): dalsze zapytania zwróciłyby to samo.
                    if (!oldestDay.isBefore(pageTo)) return result.values.toList()
                    pageTo = oldestDay
                }
                return result.values.toList()
            }
        }
    }
