package pl.cgconnector.app.domain.workout

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import pl.cgconnector.app.data.repository.DataStoreWorkoutTemplateRepository
import pl.cgconnector.app.data.repository.UserTemplate

class TemplatesAndCatalogTest {
    @Test
    fun `json round trip keeps every built-in template`() {
        (BuiltInTemplates.RUNNING + BuiltInTemplates.STRENGTH).forEach { template ->
            assertThat(WorkoutJson.decode(WorkoutJson.encode(template))).isEqualTo(template)
        }
    }

    @Test
    fun `json round trip keeps targets and lap button`() {
        val workout =
            RunningWorkout(
                "Test",
                listOf(
                    RunStep(RunStepKind.WARMUP, StepEnd.LapButton, StepTarget.HrRange(120, 140)),
                    RepeatBlock(4, listOf(RunStep(RunStepKind.INTERVAL, StepEnd.Distance(0.8), StepTarget.Pace(240, 230)))),
                ),
            )

        assertThat(WorkoutJson.decode(WorkoutJson.encode(workout))).isEqualTo(workout)
    }

    @Test
    fun `decode rejects unknown sport and broken json`() {
        assertThat(WorkoutJson.decode(Json.parseToJsonElement("""{"sport": "swim", "name": "x"}""") as JsonObject)).isNull()
        assertThat(WorkoutJson.decode(Json.parseToJsonElement("""{"sport": "running", "name": "x", "steps": [{"type": "jog"}]}""") as JsonObject))
            .isNull()
    }

    @Test
    fun `built-in templates are valid and use catalog exercises`() {
        (BuiltInTemplates.RUNNING + BuiltInTemplates.STRENGTH).forEach { template ->
            assertThat(WorkoutValidator.validate(template)).isEmpty()
        }
        BuiltInTemplates.STRENGTH.flatMap { it.exercises }.forEach { exercise ->
            assertThat(ExerciseCatalog.polishName(exercise.name)).isNotNull()
        }
    }

    @Test
    fun `catalog names are unique`() {
        assertThat(ExerciseCatalog.ALL.map { it.garminName }).containsNoDuplicates()
        assertThat(ExerciseCatalog.ALL.map { it.polishName }).containsNoDuplicates()
    }

    @Test
    fun `search works in polish without diacritics and in english`() {
        val squat = ExerciseCatalog.search("przysiad sztanga").map { it.garminName }
        assertThat(squat).containsAtLeast("Barbell Back Squat", "Barbell Front Squat")
        assertThat(ExerciseCatalog.search("wioslowanie").map { it.garminName }).contains("Barbell Row")
        assertThat(ExerciseCatalog.search("WIOSŁOWANIE hantlem").map { it.garminName }).contains("Dumbbell Row")
        assertThat(ExerciseCatalog.search("pull-up").map { it.garminName }).contains("Pull-up")
        assertThat(ExerciseCatalog.search("", ExerciseGroup.CORE).all { it.group == ExerciseGroup.CORE }).isTrue()
        assertThat(ExerciseCatalog.search("xyz")).isEmpty()
    }

    @Test
    fun `polish names for garmin names and set keys`() {
        assertThat(ExerciseCatalog.polishName("barbell back squat")).isEqualTo("Przysiad ze sztangą")
        assertThat(ExerciseCatalog.polishNameForKeys("SQUAT", "BARBELL_BACK_SQUAT")).isEqualTo("Przysiad ze sztangą")
        assertThat(ExerciseCatalog.polishNameForKeys(null, "BARBELL_BACK_SQUAT")).isNull()
        assertThat(ExerciseCatalog.polishName("Unknown Thing")).isNull()
    }

    @Test
    fun `stored templates survive encode and skip broken entries`() {
        val templates = listOf(UserTemplate("a", BuiltInTemplates.STRENGTH[0]), UserTemplate("b", BuiltInTemplates.RUNNING[0]))
        val text = DataStoreWorkoutTemplateRepository.encode(templates)

        assertThat(DataStoreWorkoutTemplateRepository.decode(text)).isEqualTo(templates)
        val withBroken = text.dropLast(1) + """,{"id":"c","definition":{"sport":"swim"}}]"""
        assertThat(DataStoreWorkoutTemplateRepository.decode(withBroken)).isEqualTo(templates)
        assertThat(DataStoreWorkoutTemplateRepository.decode("not json")).isEmpty()
        assertThat(DataStoreWorkoutTemplateRepository.decode(null)).isEmpty()
    }
}
