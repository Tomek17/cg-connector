package pl.cgconnector.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import pl.cgconnector.app.ui.activities.ActivitiesRoute
import pl.cgconnector.app.ui.settings.SettingsRoute

@Serializable
data object SettingsDestination

@Serializable
data object ActivitiesDestination

@Composable
fun CgNavHost(isConfigured: Boolean) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (isConfigured) ActivitiesDestination else SettingsDestination,
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
                onOpenSettings = {
                    navController.navigate(SettingsDestination) { launchSingleTop = true }
                },
            )
        }
    }
}
