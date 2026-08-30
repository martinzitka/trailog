package io.github.martinzitka.trailog.ui.map

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream

/** One installed region pack, as the screen shows it. */
data class ArchiveRow(
    val name: String,
    val sizeBytes: Long,
    /** Null when the header would not parse; the row then shows the size alone. */
    val coverage: MapTiles.Coverage?,
    /** Whether this is the archive the maps render. Exactly one row carries it when any do. */
    val inUse: Boolean,
)

/** What an import is doing, or what went wrong with the last one. */
sealed interface ImportState {

    data object Idle : ImportState

    /**
     * Copying. [total] is null when the picker did not report a size, which is legal — the screen
     * then shows how much has been copied rather than a proportion.
     */
    data class Copying(val copied: Long, val total: Long?) : ImportState

    /** The picked file was not a PMTiles archive. Nothing was copied. */
    data object NotAnArchive : ImportState

    /** The archive does not fit. Both figures are bytes. */
    data class NotEnoughSpace(val required: Long, val available: Long) : ImportState

    /** The copy failed part-way and the partial file was removed. */
    data object Failed : ImportState
}

/** Everything Settings' Map data screen renders. */
data class MapDataUiState(
    val archives: List<ArchiveRow> = emptyList(),
    val import: ImportState = ImportState.Idle,
    /** False only until the first directory listing arrives, which is one stat per file. */
    val loaded: Boolean = false,
)

/**
 * Map data: which region packs are installed, which one the maps use, and importing a new one.
 *
 * The whole point of the screen is that a 1.3 GB archive used to reach the phone only by
 * `adb push`, with nothing in the app to say whether it had arrived. So this is deliberately a
 * thin layer over [MapArchiveStore] — the store owns the files and the selection, and every map
 * in the app resolves through the same store, which is what makes an import visible immediately
 * on the Record and Activity screens.
 *
 * The ViewModel holds no Android types; the store arrives as a constructor argument over a plain
 * directory, so this is unit-testable on the JVM against a temp folder (CLAUDE.md testing policy).
 *
 * No coordinates are logged. The bounds a pack declares are shown on screen, never written out.
 */
class MapDataViewModel(
    private val store: MapArchiveStore,
) : ViewModel() {

    private val importState = MutableStateFlow<ImportState>(ImportState.Idle)

    private var importJob: Job? = null

    val uiState: StateFlow<MapDataUiState> =
        combine(store.archives, store.active, importState) { archives, active, import ->
            MapDataUiState(
                archives = archives.map { archive ->
                    ArchiveRow(
                        name = archive.name,
                        sizeBytes = archive.sizeBytes,
                        coverage = archive.coverage,
                        inUse = archive.name == active?.name,
                    )
                },
                import = import,
                loaded = true,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = MapDataUiState(),
        )

    /**
     * Copy a region pack in from the file the user picked.
     *
     * @param open opens the picked document. Called on the store's IO dispatcher; the screen only
     *   supplies it, because a `ContentResolver` is an Android type and does not belong here.
     *
     * Ignored while an import is already running: two copies into the same directory would race
     * for the progress state and one of them would win the file name.
     */
    fun import(displayName: String, declaredSize: Long?, open: suspend () -> InputStream?) {
        if (importState.value is ImportState.Copying) return
        importState.value = ImportState.Copying(copied = 0, total = declaredSize)
        importJob = viewModelScope.launch {
            val outcome = runCatching {
                store.install(
                    displayName = displayName,
                    declaredSize = declaredSize,
                    open = open,
                    onProgress = { copied ->
                        importState.value = ImportState.Copying(copied, declaredSize)
                    },
                )
            }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            importState.value = when (val result = outcome.getOrNull()) {
                is InstallOutcome.Installed -> ImportState.Idle
                is InstallOutcome.NotAnArchive -> ImportState.NotAnArchive
                is InstallOutcome.NotEnoughSpace ->
                    ImportState.NotEnoughSpace(result.required, result.available)
                else -> ImportState.Failed
            }
        }
    }

    /**
     * Abandon a running import. The store removes the partial file, so a cancelled import leaves
     * the device exactly as it was.
     */
    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        importState.value = ImportState.Idle
    }

    /** Clear a failure message. Has no effect while a copy is running. */
    fun dismissProblem() {
        if (importState.value !is ImportState.Copying) importState.value = ImportState.Idle
    }

    /** Render this archive from now on. */
    fun select(name: String) = store.select(name)

    /** Delete an installed archive. The screen confirms first — there is no undo and no re-download. */
    fun delete(name: String) {
        viewModelScope.launch { store.delete(name) }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MapDataViewModel(MapArchiveStore.get(appContext)) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
