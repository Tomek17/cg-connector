package pl.cgconnector.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.cgconnector.app.MainActivity
import pl.cgconnector.app.R
import pl.cgconnector.app.ui.format.Formatters
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Widżet „Dziś” na ekran główny: następny trening i dni do startu. */
@AndroidEntryPoint
class TodayWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var loader: WidgetContentLoader

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                val views = render(context, loader.load())
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Formatters.POLISH)

        /** Odświeża wszystkie widżety (np. po zmianie Planu albo celu). */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, TodayWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }

        fun render(
            context: Context,
            content: WidgetContent,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            val workout = content.nextWorkout
            if (workout == null) {
                views.setTextViewText(R.id.widget_workout, context.getString(R.string.widget_no_workout))
                views.setViewVisibility(R.id.widget_workout_day, View.GONE)
            } else {
                views.setTextViewText(R.id.widget_workout, workout.name)
                val day =
                    when (workout.date) {
                        content.today -> context.getString(R.string.today_day_today)
                        content.today.plusDays(1) -> context.getString(R.string.today_day_tomorrow)
                        else -> DAY.format(workout.date)
                    }
                views.setTextViewText(R.id.widget_workout_day, day)
                views.setViewVisibility(R.id.widget_workout_day, View.VISIBLE)
            }
            val goal = content.goal
            val days = content.goalDaysLeft
            if (goal == null || days == null) {
                views.setViewVisibility(R.id.widget_goal, View.GONE)
            } else {
                val whenText =
                    if (days == 0L) {
                        context.getString(R.string.today_goal_today)
                    } else {
                        context.resources.getQuantityString(R.plurals.today_goal_days_left, days.toInt(), days.toInt())
                    }
                views.setTextViewText(R.id.widget_goal, "${goal.name}: $whenText")
                views.setViewVisibility(R.id.widget_goal, View.VISIBLE)
            }
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }
    }
}
