package io.github.martinzitka.trailog.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.martinzitka.trailog.ui.detail.ActivityDetailScreen
import io.github.martinzitka.trailog.ui.detail.ActivityDetailViewModel
import io.github.martinzitka.trailog.ui.history.HistoryScreen
import io.github.martinzitka.trailog.ui.history.HistoryViewModel
import io.github.martinzitka.trailog.ui.record.RecordScreen
import io.github.martinzitka.trailog.ui.record.RecordViewModel
import io.github.martinzitka.trailog.ui.sensors.SensorsScreen
import io.github.martinzitka.trailog.ui.sensors.SensorsViewModel
import io.github.martinzitka.trailog.ui.settings.SettingsScreen
import io.github.martinzitka.trailog.ui.theme.TrailogTheme

/**
 * The root of the app UI: the Material 3 theme wrapping a [Scaffold] with a bottom navigation bar
 * (Record, History, Sensors, Settings) over a [NavHost]. Activity detail is a non-tab route pushed
 * on top of History.
 *
 * Tab switching uses the standard single-top / restore-state pattern so re-selecting a tab returns
 * to its saved state rather than stacking duplicates.
 */
@Composable
fun TrailogApp() {
    TrailogTheme {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination

        // Standard single-top / restore-state tab switch, used by the bottom bar and by in-screen
        // shortcuts to a tab (History's empty state sends the user to Record).
        val switchTab: (TopLevelDestination) -> Unit = { dest ->
            navController.navigate(dest.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    TopLevelDestination.entries.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        val label = stringResource(dest.labelRes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { switchTab(dest) },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.RECORD.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(TopLevelDestination.RECORD.route) {
                    val context = LocalContext.current
                    val recordViewModel: RecordViewModel = viewModel(
                        factory = RecordViewModel.Factory(context),
                    )
                    RecordScreen(viewModel = recordViewModel, modifier = Modifier.fillMaxSize())
                }
                composable(TopLevelDestination.HISTORY.route) {
                    val context = LocalContext.current
                    val historyViewModel: HistoryViewModel = viewModel(
                        factory = HistoryViewModel.Factory(context),
                    )
                    HistoryScreen(
                        viewModel = historyViewModel,
                        onOpenActivity = { id -> navController.navigate(Routes.activityDetail(id)) },
                        onStartRecording = { switchTab(TopLevelDestination.RECORD) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable(TopLevelDestination.SENSORS.route) {
                    val context = LocalContext.current
                    val sensorsViewModel: SensorsViewModel = viewModel(
                        factory = SensorsViewModel.Factory(context),
                    )
                    SensorsScreen(viewModel = sensorsViewModel, modifier = Modifier.fillMaxSize())
                }
                composable(TopLevelDestination.SETTINGS.route) {
                    SettingsScreen(modifier = Modifier.fillMaxSize())
                }
                composable(
                    route = Routes.ACTIVITY_DETAIL,
                    arguments = listOf(navArgument(Routes.ARG_ACTIVITY_ID) { type = NavType.StringType }),
                ) { entry ->
                    val context = LocalContext.current
                    val activityId = entry.arguments?.getString(Routes.ARG_ACTIVITY_ID).orEmpty()
                    val detailViewModel: ActivityDetailViewModel = viewModel(
                        factory = ActivityDetailViewModel.Factory(context, activityId),
                    )
                    ActivityDetailScreen(
                        viewModel = detailViewModel,
                        onBack = { navController.popBackStack() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
