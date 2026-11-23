package pl.cgconnector.app.widget

import kotlinx.coroutines.flow.first
import pl.cgconnector.app.data.repository.GoalRepository
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.domain.AppZone
import pl.cgconnector.app.ui.today.TodayViewModel
import java.time.Clock
import javax.inject.Inject

/** Dane widżetu z bazy w telefonie (bez sieci). */
class WidgetContentLoader
    @Inject
    constructor(
        private val queue: WorkoutQueueRepository,
        private val goals: GoalRepository,
        private val clock: Clock,
    ) {
        suspend fun load(): WidgetContent {
            val today = clock.instant().atZone(AppZone.WARSAW).toLocalDate()
            return WidgetContent(
                today = today,
                nextWorkout = TodayViewModel.nextWorkout(queue.observeQueue().first(), today),
                goal = goals.observeGoal().first(),
            )
        }
    }
