package pl.cgconnector.app.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import pl.cgconnector.app.data.remote.FakeGoalRemoteDataSource
import pl.cgconnector.app.data.remote.GoalDto
import pl.cgconnector.app.data.remote.GoalRemoteDataSource
import pl.cgconnector.app.data.remote.GoalStateDto
import pl.cgconnector.app.domain.LoadError
import pl.cgconnector.app.domain.RaceGoal
import java.io.File
import java.io.IOException
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DataStoreGoalRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun repository(remote: GoalRemoteDataSource) =
        DataStoreGoalRepository(
            remote,
            PreferenceDataStoreFactory.create { File(tmp.newFolder(), "settings.preferences_pb") },
            json,
        )

    @Test
    fun refreshSaveAndClearKeepLocalCopy() =
        runTest {
            val repo = repository(FakeGoalRemoteDataSource())
            assertThat(repo.observeGoal().first()).isNull()

            assertThat(repo.refresh()).isEqualTo(Loaded.Ok(Unit))
            assertThat(repo.observeGoal().first()?.name).isEqualTo("Półmaraton Warszawski")

            val goal = RaceGoal("Maraton Warszawski", LocalDate.of(2027, 9, 26), RaceGoal.MARATHON_KM, 12600)
            repo.save(goal)
            assertThat(repo.observeGoal().first()).isEqualTo(goal)

            repo.clear()
            assertThat(repo.observeGoal().first()).isNull()
        }

    @Test
    fun failureKeepsPreviousGoal() =
        runTest {
            var fail = false
            val remote =
                object : GoalRemoteDataSource {
                    override suspend fun fetch(): GoalStateDto {
                        if (fail) throw IOException("offline")
                        return GoalStateDto(GoalDto("10 km", "2027-05-01", 10.0, 2700))
                    }

                    override suspend fun save(goal: GoalDto) = error("unused")

                    override suspend fun clear() = error("unused")
                }
            val repo = repository(remote)
            repo.refresh()
            fail = true

            assertThat(repo.refresh()).isEqualTo(Loaded.Failed(LoadError.OFFLINE))
            assertThat(repo.observeGoal().first()?.targetSeconds).isEqualTo(2700)
        }
}
