package io.github.martinzitka.trailog.ui.nav

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.github.martinzitka.trailog.ui.detail.ActivityMapScreen
import io.github.martinzitka.trailog.ui.format.Formatter
import io.github.martinzitka.trailog.ui.format.LocalFormatter
import io.github.martinzitka.trailog.ui.history.HistoryScreen
import io.github.martinzitka.trailog.ui.history.HistoryViewModel
import io.github.martinzitka.trailog.ui.map.LocalShowTrails
import io.github.martinzitka.trailog.ui.record.RecordScreen
import io.github.martinzitka.trailog.ui.record.RecordViewModel
import io.github.martinzitka.trailog.ui.sensors.SensorsScreen
import io.github.martinzitka.trailog.ui.sensors.SensorsViewModel
import io.github.martinzitka.trailog.ui.settings.PrefsAppSettings
import io.github.martinzitka.trailog.ui.settings.SettingsScreen
import io.github.martinzitka.trailog.ui.settings.SettingsViewModel
import io.github.martinzitka.trailog.ui.settings.ThemeMode
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
    val context = LocalContext.current
    // The app-wide preferences are read once, here, and pushed down: the theme as parameters, the
    // unit system as the ambient formatter, and trail visibility as an ambient flag every map
    // reads. Changing any of them in Settings repaints the tree immediately, which is why they are
    // read at the root rather than per screen.
    val preferences by PrefsAppSettings.get(context).preferences.collectAsStateWithLifecycle()

    // Remembered against the unit system, not rebuilt per recomposition: LocalFormatter is a static
    // local compared by identity, so handing it a fresh instance would invalidate the entire tree
    // every time anything up here recomposed.
    val formatter = remember(preferences.unitSystem) { Formatter(preferences.unitSystem) }

    TrailogTheme(
        darkTheme = when (preferences.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        },
        dynamicColor = preferences.dynamicColour,
    ) {
        CompositionLocalProvider(
            LocalFormatter provides formatter,
            LocalShowTrails provides preferences.showTrails,
        ) {
            TrailogNavigation()
        }
    }
}

/**
 * The navigation scaffold itself, split from [TrailogApp] so the theme and formatter providers wrap
 * it rather than sitting inside the same function as the [NavHost].
 */
@Composable
private fun TrailogNavigation() {
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

    // The fullscreen map hides the bar rather than dimming it: it is a map you pan and zoom, and
    // a navigation bar across the bottom of it is both a smaller map and a row of targets sitting
    // exactly where a thumb drags.
    val fullscreen = currentDestination?.route in Routes.fullscreen

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (fullscreen) return@Scaffold
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
                val context = LocalContext.current
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(context),
                )
                SettingsScreen(viewModel = settingsViewModel, modifier = Modifier.fillMaxSize())
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
                    onOpenMap = { navController.navigate(Routes.activityMap(activityId)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(
                route = Routes.ACTIVITY_MAP,
                arguments = listOf(navArgument(Routes.ARG_ACTIVITY_ID) { type = NavType.StringType }),
            ) { entry ->
                val context = LocalContext.current
                val activityId = entry.arguments?.getString(Routes.ARG_ACTIVITY_ID).orEmpty()
                // Its own ViewModel instance, scoped to this back-stack entry. The detail screen's
                // one belongs to the entry underneath and would outlive nothing useful here; the
                // cost is one recompute of the profiles, off the main thread.
                val mapViewModel: ActivityDetailViewModel = viewModel(
                    factory = ActivityDetailViewModel.Factory(context, activityId),
                )
                ActivityMapScreen(
                    viewModel = mapViewModel,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
