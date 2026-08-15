package io.github.martinzitka.trailog.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.ui.common.ComingSoonScreen

/**
 * Activity detail — map zoomed to the route, elevation and speed charts, summary, splits, edit,
 * delete and export. Pushed from History. Placeholder for now; the real screen is an M1.5 screen
 * that follows History.
 *
 * @param activityId the activity to show; already threaded through navigation so the detail
 *   screen can load it once built.
 */
@Composable
fun ActivityDetailScreen(
    activityId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ComingSoonScreen(screenName = stringResource(R.string.nav_activity_detail), modifier = modifier)
}
