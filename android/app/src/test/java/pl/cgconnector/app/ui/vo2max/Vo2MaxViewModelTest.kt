package pl.cgconnector.app.ui.vo2max

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.domain.Vo2MaxPoint
import pl.cgconnector.app.testing.FakeStatsRepository
import pl.cgconnector.app.testing.MainDispatcherRule
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class Vo2MaxViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun point(
        date: String,
        value: Double,
        sport: String = "running",
    ) = Vo2MaxPoint(LocalDate.parse(date), value, sport)

    @Test
    fun `running is default sport and points are sorted by date`() =
        runTest {
            val repo =
                FakeStatsRepository(
                    vo2max =
                        listOf(
                            point("2026-11-10", 52.0),
                            point("2026-11-01", 50.0, "cycling"),
                            point("2026-09-01", 50.0),
                        ),
                )
            val viewModel = Vo2MaxViewModel(repo)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            val state = viewModel.uiState.value
            assertThat(state.sports).containsExactly("running", "cycling").inOrder()
            assertThat(state.selectedSport).isEqualTo("running")
            assertThat(state.points.map { it.value }).containsExactly(50.0, 52.0).inOrder()
            assertThat(state.latest?.value).isEqualTo(52.0)

            viewModel.onSportSelected("cycling")
            assertThat(viewModel.uiState.value.points.single().sport).isEqualTo("cycling")
        }

    @Test
    fun `empty data`() =
        runTest {
            val viewModel = Vo2MaxViewModel(FakeStatsRepository())
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value.isLoading).isFalse()
            assertThat(viewModel.uiState.value.latest).isNull()
            assertThat(viewModel.uiState.value.change).isNull()
        }

    @Test
    fun `change is measured within 90 day window`() {
        val points =
            listOf(
                point("2026-01-01", 45.0),
                point("2026-08-25", 49.0),
                point("2026-10-01", 50.0),
                point("2026-11-18", 51.5),
            )

        // Okno: od 2026-08-20; najstarszy pomiar w oknie to 49.0.
        assertThat(Vo2MaxViewModel.change(points)).isWithin(1e-9).of(2.5)
        assertThat(Vo2MaxViewModel.change(points.takeLast(1))).isNull()
        assertThat(Vo2MaxViewModel.change(listOf(point("2026-01-01", 45.0), point("2026-11-18", 51.0)))).isNull()
    }

    @Test
    fun `chart normalizes dates and values`() {
        val data =
            LineChartMath.normalize(
                listOf(point("2026-11-01", 50.0), point("2026-11-03", 51.0), point("2026-11-11", 52.0)),
            )!!

        assertThat(data.points.map { it.x }).containsExactly(0f, 0.2f, 1f).inOrder()
        assertThat(data.points.first().y).isWithin(1e-6f).of(1f / 12)
        assertThat(data.points.last().y).isWithin(1e-6f).of(11f / 12)
        // Etykiety i opis: rzeczywiste skrajne pomiary, nie granice osi z marginesem.
        assertThat(data.minValue).isEqualTo(50.0)
        assertThat(data.maxValue).isEqualTo(52.0)
        assertThat(data.minY).isWithin(1e-6f).of(1f / 12)
        assertThat(data.maxY).isWithin(1e-6f).of(11f / 12)
    }

    @Test
    fun `chart handles single point and flat series`() {
        assertThat(LineChartMath.normalize(emptyList())).isNull()
        val single = LineChartMath.normalize(listOf(point("2026-11-01", 50.0)))!!
        assertThat(single.points.single()).isEqualTo(ChartPoint(0.5f, 0.5f))
        assertThat(single.minValue).isEqualTo(50.0)
        assertThat(single.maxValue).isEqualTo(50.0)
        assertThat(single.minY).isEqualTo(0.5f)
    }
}
