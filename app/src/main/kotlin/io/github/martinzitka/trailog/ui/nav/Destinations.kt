package io.github.martinzitka.trailog.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.martinzitka.trailog.R

/**
 * The app's navigation destinations. The four top-level tabs live in the bottom bar (Record,
 * History, Sensors, Settings); Activity detail is pushed on top of History and is not a tab.
 *
 * Routes are plain strings for `navigation-compose`. The detail route is parameterised by the
 * activity id.
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    RECORD("record", R.string.nav_record, Icons.Filled.FiberManualRecord),
    HISTORY("history", R.string.nav_history, Icons.Filled.History),
    SENSORS("sensors", R.string.nav_sensors, Icons.Filled.Sensors),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
}

/** Routes that are not tabs. */
object Routes {
    const val ACTIVITY_DETAIL = "activity/{activityId}"
    fun activityDetail(activityId: String) = "activity/$activityId"
    const val ARG_ACTIVITY_ID = "activityId"
}
