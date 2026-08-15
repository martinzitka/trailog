package io.github.martinzitka.trailog.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.martinzitka.trailog.R

/**
 * A deliberately honest placeholder for a tab whose real screen has not been built yet. Used for
 * History, Sensors and Settings while Record is the first M1.5 screen delivered. It is not a
 * finished screen and is not held to the per-screen "Definition of done" — it exists so the
 * bottom-bar navigation is complete and reviewable, and each of these files is fleshed out in place
 * as its screen lands.
 *
 * @param screenName the human name of the screen being built, shown in the pending message.
 */
@Composable
fun ComingSoonScreen(
    screenName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.placeholder_coming_soon),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.placeholder_screen_pending, screenName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
