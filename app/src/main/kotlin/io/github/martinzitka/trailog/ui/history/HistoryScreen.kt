package io.github.martinzitka.trailog.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.ui.common.ComingSoonScreen

/**
 * History — the list of recorded activities, most recent first. Placeholder for now; the real
 * list (empty/populated/loading states, tap-through to detail) is the next M1.5 screen.
 *
 * @param onOpenActivity called with an activity id when a row is tapped. Wired now so the
 *   navigation graph is complete before the list exists.
 */
@Composable
fun HistoryScreen(
    onOpenActivity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ComingSoonScreen(screenName = stringResource(R.string.nav_history), modifier = modifier)
}
