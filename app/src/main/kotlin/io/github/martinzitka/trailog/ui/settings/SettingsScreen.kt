package io.github.martinzitka.trailog.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.ui.format.LocalFormatter
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
 *
 * Two rows here are actions rather than settings: Map data, which opens the region packs screen,
 * and the export below.
 *
 * The Data group is the exception to all of that: it holds an action, not a setting. "Export all
 * activities" lives here because this is where the rest of the data rights will land — full export
 * and full deletion are product features, not afterthoughts (CLAUDE.md) — and because History is
 * better left as a list. The work belongs to [ExportViewModel]; this screen only picks the
 * destination and names the file, which is display-edge work like every other string here.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    exportViewModel: ExportViewModel,
    importViewModel: ImportViewModel,
    onOpenMapData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportState by exportViewModel.uiState.collectAsStateWithLifecycle()
    val importState by importViewModel.uiState.collectAsStateWithLifecycle()

    // Hoisted out of the composable scope so the launcher callback below can capture them: a
    // CompositionLocal cannot be read from inside a plain lambda.
    val format = LocalFormatter.current
    val context = LocalContext.current

    // The user picks the destination; the app never writes to storage it chose itself. A null uri
    // means they backed out of the picker, which is not a failure and leaves the row untouched.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ZIP_MIME_TYPE),
    ) { uri ->
        if (uri != null) {
            exportViewModel.exportAll(
                openSink = { context.contentResolver.openOutputStream(uri) },
                entryName = { slug, startTime -> format.exportFileName(slug, startTime, "gpx") },
            )
        }
    }

    // The user picks the archive; the app never goes looking for one. Backing out of the picker
    // returns null, which is not a failure and leaves the row untouched.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            importViewModel.importArchive { context.contentResolver.openInputStream(uri) }
        }
    }

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

            ActionRow(
                label = stringResource(R.string.map_data_entry_label),
                body = stringResource(R.string.map_data_entry_body),
                icon = Icons.Filled.Layers,
                enabled = true,
                onClick = onOpenMapData,
            )
        }

        SettingsGroup(stringResource(R.string.settings_group_recording)) {
            ToggleRow(
                label = stringResource(R.string.settings_keep_screen_on_label),
                body = stringResource(R.string.settings_keep_screen_on_body),
                checked = state.keepScreenOnWhileRecording,
                onCheckedChange = viewModel::setKeepScreenOnWhileRecording,
            )
        }

        SettingsGroup(stringResource(R.string.settings_group_data)) {
            ActionRow(
                label = stringResource(R.string.settings_export_all_label),
                body = stringResource(R.string.settings_export_all_body),
                icon = Icons.Filled.FileDownload,
                enabled = exportState !is ExportUiState.Running,
                onClick = {
                    exportLauncher.launch(
                        format.exportFileName(
                            slug = EXPORT_ARCHIVE_SLUG,
                            epochMillis = System.currentTimeMillis(),
                            extension = "zip",
                        ),
                    )
                },
            )
            ExportStatus(exportState)
            Note(stringResource(R.string.settings_export_all_note))

            ActionRow(
                label = stringResource(R.string.settings_import_label),
                body = stringResource(R.string.settings_import_body),
                icon = Icons.Filled.FileUpload,
                enabled = importState !is ImportUiState.Running,
                // Zip MIME types are inconsistent across providers and some report a zip as
                // octet-stream, so the filter is deliberately broad: a picker that hides the user's
                // own archive is worse than one that shows a file they will not choose.
                onClick = { importLauncher.launch(arrayOf(ZIP_MIME_TYPE, ANY_MIME_TYPE)) },
            )
            ImportStatus(importState)
            Note(stringResource(R.string.settings_import_note))
        }
    }
}

/**
 * What the export is doing, or what it did. Rendered in the group rather than as a snackbar: an
 * export of several hundred rides runs long enough that its progress has to stay on screen, and a
 * transient message would take the outcome away with it.
 */
@Composable
private fun ExportStatus(state: ExportUiState) {
    when (state) {
        is ExportUiState.Idle -> Unit

        is ExportUiState.Running -> Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (state.total > 0) {
                    stringResource(R.string.settings_export_running, state.done, state.total)
                } else {
                    stringResource(R.string.settings_export_starting)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Indeterminate until the worklist has been counted, which is one query: a bar sitting
            // at zero would suggest nothing is happening.
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is ExportUiState.Done -> Note(
            if (state.count == 0) {
                stringResource(R.string.settings_export_empty)
            } else {
                pluralStringResource(R.plurals.settings_export_done, state.count, state.count)
            },
        )

        is ExportUiState.Failed -> Note(
            text = stringResource(R.string.settings_export_failed),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * What the import is doing, or what it did.
 *
 * The finished state reports all three outcomes rather than just the additions, because "already
 * here" is the *expected* result of running an import twice — showing only "added 0" would read as
 * a failure when it is the feature working.
 */
@Composable
private fun ImportStatus(state: ImportUiState) {
    when (state) {
        is ImportUiState.Idle -> Unit

        is ImportUiState.Running -> Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_import_running, state.done),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Always indeterminate: a streamed zip cannot say how many entries it holds without
            // being read to the end first, so there is no honest denominator to draw.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is ImportUiState.Done -> {
            val report = state.report
            if (report.total == 0) {
                Note(stringResource(R.string.settings_import_empty))
            } else {
                Note(
                    listOfNotNull(
                        pluralStringResource(
                            R.plurals.settings_import_done, report.imported, report.imported,
                        ),
                        report.skipped.takeIf { it > 0 }?.let {
                            pluralStringResource(R.plurals.settings_import_skipped, it, it)
                        },
                        report.failed.takeIf { it > 0 }?.let {
                            pluralStringResource(R.plurals.settings_import_failed_entries, it, it)
                        },
                    ).joinToString(" "),
                )
            }
        }

        is ImportUiState.Failed -> Note(
            text = stringResource(R.string.settings_import_failed),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** The proposed name of the archive, before the date stamp the formatter appends. */
private const val EXPORT_ARCHIVE_SLUG = "activities"

private const val ZIP_MIME_TYPE = "application/zip"

/** See the import row: some providers report a zip as an unhelpfully generic type. */
private const val ANY_MIME_TYPE = "*/*"

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

/**
 * Something the row *does*, rather than a value it holds — the one shape in this screen that is
 * not a setting. As with the others the whole row is the target and owns the semantics, and the
 * trailing icon is decorative because the label already says what will happen.
 */
@Composable
private fun ActionRow(
    label: String,
    body: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColour = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = contentColour)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(icon, contentDescription = null, tint = contentColour)
    }
}

/** Material's disabled-content opacity, applied to the row's own colours. */
private const val DISABLED_ALPHA = 0.38f

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
private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
