package io.github.martinzitka.trailog.ui.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.ui.format.LocalFormatter

/**
 * Map data: the region packs installed on this device.
 *
 * This screen exists because the archive is not in the APK and never can be — the Czech build is
 * 1.3 GB — so until now it reached the phone only by `adb push`, and a map with no tiles was
 * indistinguishable from a map that had failed. Here the user can see what is installed, how big
 * it is, what ground it covers, and bring a new one in from a file they downloaded.
 *
 * **Import is a copy, not a reference.** The picked document is read once and written into the
 * app's own directory: a `content://` uri from the Downloads provider is not a path MapLibre can
 * open, its permission grant does not survive a reboot, and the user is free to delete the file
 * they imported from. The archive being installed means it is *here*, offline, forever.
 *
 * There is no download button. A region pack comes from `infra/tiles/build-tiles.sh` or from the
 * user's own server, fetched with whatever they already use — the app itself makes no network
 * request, which is the shortest path to CLAUDE.md's rule about egress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapDataScreen(
    viewModel: MapDataViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    // "*/*" rather than a MIME type: .pmtiles has no registered type, and providers report it as
    // application/octet-stream at best, so a filter would hide the file the user came here for.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val document = documentInfo(context, uri)
            viewModel.import(
                displayName = document.name,
                declaredSize = document.size,
                open = { context.contentResolver.openInputStream(uri) },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.map_data_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.map_data_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !state.loaded -> LoadingContent()

                state.archives.isEmpty() -> EmptyContent()

                else -> {
                    if (state.archives.size > 1) {
                        Text(
                            stringResource(R.string.map_data_choose),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.archives.forEach { archive ->
                        ArchiveCard(
                            archive = archive,
                            selectable = state.archives.size > 1,
                            onSelect = { viewModel.select(archive.name) },
                            onDelete = { pendingDelete = archive.name },
                        )
                    }
                }
            }

            ImportSection(
                state = state.import,
                onImport = { importLauncher.launch(arrayOf(ANY_MIME_TYPE)) },
                onCancel = viewModel::cancelImport,
                onDismissProblem = viewModel::dismissProblem,
            )
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.map_data_delete_title)) },
            // Says how it is undone, because it is not: there is no re-download, and rebuilding a
            // country archive is a twenty-minute job on a desktop.
            text = { Text(stringResource(R.string.map_data_delete_body, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(name)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.map_data_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.map_data_delete_cancel))
                }
            },
        )
    }
}

// ---- pieces ----------------------------------------------------------------------------------

@Composable
private fun LoadingContent() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * No pack installed. Not a blank screen and not an error — it is the state of every fresh install,
 * so it says where a region pack comes from rather than merely reporting its absence.
 */
@Composable
private fun EmptyContent() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.map_data_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.map_data_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.map_data_empty_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArchiveCard(
    archive: ArchiveRow,
    selectable: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val format = LocalFormatter.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    // Only a choice when there is something to choose between. One archive is
                    // simply the one in use, and a radio button that cannot be unset is noise.
                    if (selectable) {
                        Modifier.selectable(
                            selected = archive.inUse,
                            role = Role.RadioButton,
                            onClick = onSelect,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectable) {
                RadioButton(selected = archive.inUse, onClick = null)
            }
            Column(Modifier.weight(1f)) {
                Text(archive.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    format.fileSize(archive.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val coverage = archive.coverage
                Text(
                    text = if (coverage != null) {
                        format.coverage(
                            minLatitude = coverage.minLatitude,
                            minLongitude = coverage.minLongitude,
                            maxLatitude = coverage.maxLatitude,
                            maxLongitude = coverage.maxLongitude,
                        )
                    } else {
                        // An archive whose header will not parse still renders; only its extent
                        // is unknown. Saying so beats an empty line the user cannot interpret.
                        stringResource(R.string.map_data_coverage_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.map_data_delete_cd, archive.name),
                )
            }
        }
    }
}

@Composable
private fun ImportSection(
    state: ImportState,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onDismissProblem: () -> Unit,
) {
    val format = LocalFormatter.current

    when (state) {
        is ImportState.Copying -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (state.total != null) {
                        stringResource(
                            R.string.map_data_copying,
                            format.fileSize(state.copied),
                            format.fileSize(state.total),
                        )
                    } else {
                        stringResource(R.string.map_data_copying_unknown, format.fileSize(state.copied))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.total != null && state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.copied.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                // Copying a gigabyte over USB-OTG or from a slow SD card takes minutes, and the
                // user must be able to change their mind without killing the app.
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.map_data_cancel))
                }
            }
        }

        else -> {
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.map_data_import))
            }
            Problem(state = state, onDismiss = onDismissProblem)
        }
    }
}

@Composable
private fun Problem(state: ImportState, onDismiss: () -> Unit) {
    val format = LocalFormatter.current
    val message = when (state) {
        is ImportState.NotAnArchive -> stringResource(R.string.map_data_not_an_archive)
        is ImportState.NotEnoughSpace -> stringResource(
            R.string.map_data_no_space,
            format.fileSize(state.required),
            format.fileSize(state.available),
        )
        is ImportState.Failed -> stringResource(R.string.map_data_import_failed)
        else -> return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.map_data_dismiss))
        }
    }
}

// ---- the Android edge ------------------------------------------------------------------------

/** What the picker knows about the chosen document: a name to install it under, and a size. */
private data class DocumentInfo(val name: String, val size: Long?)

/**
 * Reads the display name and size a document provider reports.
 *
 * Both are advisory — a provider may report neither — so the name falls back to a generic one and
 * a null size simply means the copy cannot show a proportion or refuse early for lack of space.
 */
private fun documentInfo(context: Context, uri: Uri): DocumentInfo = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
        DocumentInfo(
            name = if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                cursor.getString(nameColumn)
            } else {
                FALLBACK_DOCUMENT_NAME
            },
            size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                cursor.getLong(sizeColumn)
            } else {
                null
            },
        )
    }
}.getOrNull() ?: DocumentInfo(FALLBACK_DOCUMENT_NAME, null)

private const val FALLBACK_DOCUMENT_NAME = "region"

private const val ANY_MIME_TYPE = "*/*"
