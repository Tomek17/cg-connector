package pl.cgconnector.app.widget

import pl.cgconnector.app.domain.RaceGoal
import pl.cgconnector.app.domain.workout.QueuedWorkout
import java.time.LocalDate

/** Co pokazuje widżet: najbliższy trening z Planu i cel. */
data class WidgetContent(
    val today: LocalDate,
    val nextWorkout: QueuedWorkout?,
    val goal: RaceGoal?,
) {
    /** Dni do startu; `null` bez celu albo po starcie. */
    val goalDaysLeft: Long?
        get() = goal?.daysLeft(today)?.takeIf { it >= 0 }
}
