package io.github.martinzitka.trailog.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.ui.format.UnitSystem

/**
 * Settings — and only settings something actually reads (IMPLEMENTATION_PLAN.md M1.5: "a setting
 * with no effect is a bug, not a placeholder").
 *
 * Four of those exist today, each named with its reader in [AppPreferences]: the unit system,
 * which the display-edge formatter reads; the theme mode and Material You toggle, which the app's
 * theme reads; whether to draw paths and trails, which every map reads through `LocalShowTrails`;
 * and whether to hold the screen on while recording, which the Record screen reads (ADR 0011).
 * Material You is hidden below Android 12 rather than shown inert, because a control that cannot
 * change anything fails the same rule.
 *
 * There is no save button. Every change applies immediately and app-wide — the theme repaints and
 * every figure re-renders while this screen is still open.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsGroup(stringResource(R.string.settings_group_units)) {
            ChoiceRow(
                label = stringResource(R.string.settings_units_metric),
                detail = stringResource(R.string.settings_units_metric_detail),
                selected = state.unitSystem == UnitSystem.METRIC,
                onSelect = { viewModel.setUnitSystem(UnitSystem.METRIC) },
            )
            ChoiceRow(
                label = stringResource(R.string.settings_units_imperial),
                detail = stringResource(R.string.settings_units_imperial_detail),
                selected = state.unitSystem == UnitSystem.IMPERIAL,
                onSelect = { viewModel.setUnitSystem(UnitSystem.IMPERIAL) },
            )
            Note(stringResource(R.string.settings_units_note))
        }

        SettingsGroup(stringResource(R.string.settings_group_appearance)) {
            // Appearance holds a radio group and a switch, so the choices get their own sub-heading
            // rather than sitting directly under the card title.
            SubHeading(stringResource(R.string.settings_theme_label))
            ThemeMode.entries.forEach { mode ->
                ChoiceRow(
                    label = stringResource(mode.labelRes),
                    selected = state.themeMode == mode,
                    onSelect = { viewModel.setThemeMode(mode) },
                )
            }
            if (state.dynamicColourSupported) {
                ToggleRow(
                    label = stringResource(R.string.settings_dynamic_colour_label),
                    body = stringResource(R.string.settings_dynamic_colour_body),
                    checked = state.dynamicColour,
                    onCheckedChange = viewModel::setDynamicColour,
                )
            }
        }

        SettingsGroup(stringResource(R.string.settings_group_map)) {
            ToggleRow(
                label = stringResource(R.string.settings_show_trails_label),
                body = stringResource(R.string.settings_show_trails_body),
                checked = state.showTrails,
                onCheckedChange = viewModel::setShowTrails,
            )
            // The zoom floor is the whole reason this note exists: paths are absent from the
            // archive below z14, so a user who flips the switch while looking at a fitted route
            // sees nothing change and concludes the setting is broken.
            Note(stringResource(R.string.settings_show_trails_note))
        }

        SettingsGroup(stringResource(R.string.settings_group_recording)) {
            ToggleRow(
                label = stringResource(R.string.settings_keep_screen_on_label),
                body = stringResource(R.string.settings_keep_screen_on_body),
                checked = state.keepScreenOnWhileRecording,
                onCheckedChange = viewModel::setKeepScreenOnWhileRecording,
            )
        }
    }
}

private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

// ---- pieces ------------------------------------------------------------------------------

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

/**
 * One option of a mutually exclusive set. The whole row is the target, not just the radio button,
 * and it carries [Role.RadioButton] with the radio itself left out of the accessibility tree, so a
 * screen reader announces one selectable item rather than a label and an orphaned control.
 */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    detail: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * An on/off setting. As with [ChoiceRow] the whole row toggles and owns the semantics, so the
 * switch is not separately focusable.
 */
@Composable
private fun ToggleRow(
    label: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
