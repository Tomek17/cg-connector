package pl.cgconnector.app.data.remote

import java.time.LocalDate
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
            val api = apiProvider.get() ?: throw NotConfiguredException()
            return api.activities(from = from.toString(), to = to.toString()).items
        }
    }
