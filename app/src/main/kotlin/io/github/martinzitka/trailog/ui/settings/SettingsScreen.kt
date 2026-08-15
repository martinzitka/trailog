package io.github.martinzitka.trailog.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.ui.common.ComingSoonScreen

/**
 * Settings — only settings something actually reads (CLAUDE.md: a setting with no effect is a
 * bug). Placeholder for now; the real screen lands later in M1.5, once there are effective
 * settings to expose (e.g. a dynamic-colour toggle, the future imperial-units preference).
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    ComingSoonScreen(screenName = stringResource(R.string.nav_settings), modifier = modifier)
}
