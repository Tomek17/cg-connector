package pl.cgconnector.app.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import pl.cgconnector.app.R
import pl.cgconnector.app.ui.activities.ActivitiesRoute
import pl.cgconnector.app.ui.detail.ActivityDetailRoute
import pl.cgconnector.app.ui.queue.QueueRoute
import pl.cgconnector.app.ui.settings.SettingsRoute
import pl.cgconnector.app.ui.status.StatusRoute
import pl.cgconnector.app.ui.vo2max.Vo2MaxRoute
import pl.cgconnector.app.ui.workout.NewWorkoutButton
import pl.cgconnector.app.ui.workout.RunningWizardRoute
import kotlin.reflect.KClass

@Serializable
data object SettingsDestination

@Serializable
data object ActivitiesDestination

/** Nazwa pola musi zgadzać się z `ActivityDetailViewModel.ARG_ACTIVITY_ID`. */
@Serializable
data class ActivityDetailDestination(val activityId: Long)

@Serializable
data object Vo2MaxDestination

@Serializable
data object StatusDestination

@Serializable
data object QueueDestination

@Serializable
data object RunningWizardDestination

/** Zakładki dolnego paska. */
private enum class TopLevel(
    val destination: Any,
    val route: KClass<*>,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    ACTIVITIES(ActivitiesDestination, ActivitiesDestination::class, R.string.nav_activities, R.drawable.ic_nav_activities),
    QUEUE(QueueDestination, QueueDestination::class, R.string.nav_queue, R.drawable.ic_nav_queue),
    VO2MAX(Vo2MaxDestination, Vo2MaxDestination::class, R.string.nav_vo2max, R.drawable.ic_nav_vo2max),
    STATUS(StatusDestination, StatusDestination::class, R.string.nav_status, R.drawable.ic_nav_status),
    SETTINGS(SettingsDestination, SettingsDestination::class, R.string.nav_settings, R.drawable.ic_nav_settings),
}

@Composable
fun CgNavHost(isConfigured: Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = TopLevel.entries.any { tab -> currentDestination?.hasRoute(tab.route) == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevel.entries.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.hasRoute(tab.route) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.destination) },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (isConfigured) ActivitiesDestination else SettingsDestination,
            // Ekrany mają własne Scaffoldy; wcięcia zużyte tutaj nie mogą być dodane drugi raz.
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable<SettingsDestination> {
                SettingsRoute(
                    onSaved = {
                        navController.navigate(ActivitiesDestination) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable<ActivitiesDestination> {
                ActivitiesRoute(
                    onOpenSettings = { navController.navigateToTab(SettingsDestination) },
                    onOpenActivity = { id -> navController.navigate(ActivityDetailDestination(id)) },
                )
            }
            composable<ActivityDetailDestination> {
                ActivityDetailRoute(onBack = { navController.popBackStack() })
            }
            composable<Vo2MaxDestination> { Vo2MaxRoute() }
            composable<StatusDestination> { StatusRoute() }
            composable<QueueDestination> {
                QueueRoute(
                    floatingActionButton = {
                        NewWorkoutButton(onNewRunning = { navController.navigate(RunningWizardDestination) })
                    },
                )
            }
            composable<RunningWizardDestination> { RunningWizardRoute(onDone = { navController.popBackStack() }) }
        }
    }
}

/**
 * Standardowe przełączanie zakładek: jedna kopia ekranu, zachowany stan, „wstecz” wraca do Treningów.
 * Po pierwszej konfiguracji korzeniem stosu są Treningi, a nie startowy ekran grafu (Konfiguracja).
 */
private fun NavHostController.navigateToTab(destination: Any) {
    val rootId =
        runCatching { getBackStackEntry(ActivitiesDestination).destination.id }.getOrNull()
            ?: graph.findStartDestination().id
    navigate(destination) {
        popUpTo(rootId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
