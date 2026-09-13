package io.github.martinzitka.trailog.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.martinzitka.trailog.data.ActivityRepository
import io.github.martinzitka.trailog.data.TrailogDatabase
import io.github.martinzitka.trailog.importer.GpxArchiveImport
import io.github.martinzitka.trailog.importer.ImportReport
import io.github.martinzitka.trailog.importer.ImportedActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID

/**
 * Where the bulk import has got to.
 *
 * **[Running] carries a count, not a fraction**, unlike the export's. A zip read as a stream cannot
 * say how many entries it holds without decompressing to the end first, and doing that just to draw
 * a percentage would double the work on a large archive. A running count is honest; a progress bar
 * would have to invent a denominator.
 */
sealed interface ImportUiState {

    data object Idle : ImportUiState

    /** Underway, with [done] entries dealt with so far. */
    data class Running(val done: Int) : ImportUiState

    /** Finished. Carries the whole breakdown, because "skipped" is a normal, expected outcome. */
    data class Done(val report: ImportReport) : ImportUiState

    /**
     * The archive could not be read at all — not a file, not a zip, or the stream failed. Carries
     * no detail: an exception message is not something to put on screen, nor in a log where
     * coordinates must never end up.
     */
    data object Failed : ImportUiState
}

/**
 * "Import activities": read a zip of GPX back into the database.
 *
 * The mirror of [ExportViewModel], and deliberately so — the format it reads is exactly the one the
 * export writes, so restoring a backup and migrating a history from another app are the same
 * operation.
 *
 * Holds no Android types: the archive arrives as a suspend lambda returning an [InputStream], and
 * the database as two suspend lambdas, so the interesting behaviour is unit-testable on the JVM
 * (CLAUDE.md testing policy). The [Factory] wires the real repository.
 *
 * **Import only ever adds.** It never modifies or deletes an activity that is already stored, so
 * there is no destructive path here and a failure needs no recovery: the worst case is that fewer
 * activities arrived than the archive held, and running it again picks up the rest.
 */
class ImportViewModel(
    private val exists: suspend (UUID) -> Boolean,
    private val write: suspend (ImportedActivity) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val uiState: StateFlow<ImportUiState> = state.asStateFlow()

    /**
     * Read the archive [openSource] opens, writing every activity not already stored.
     *
     * Ignored while an import is already running: two concurrent passes would race on the same
     * de-duplication check and could write the same activity twice.
     */
    fun importArchive(openSource: suspend () -> InputStream?) {
        if (state.value is ImportUiState.Running) return
        state.value = ImportUiState.Running(done = 0)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) {
                    val source = openSource() ?: error("no source")
                    GpxArchiveImport.read(
                        source = source,
                        exists = exists,
                        write = write,
                        onProgress = { done -> state.value = ImportUiState.Running(done) },
                    )
                }
            }
            // A cancelled import is the ViewModel going away, not a failure worth reporting to a
            // screen that no longer exists — and swallowing it would break structured concurrency.
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            state.value = result.fold(
                onSuccess = { ImportUiState.Done(it) },
                onFailure = { ImportUiState.Failed },
            )
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = ActivityRepository(TrailogDatabase.get(appContext))
            return ImportViewModel(
                exists = { repository.exists(it.toString()) },
                write = {
                    repository.importActivity(
                        id = it.id.toString(),
                        type = it.type,
                        name = it.name,
                        notes = it.notes,
                        startTime = it.startTime.toEpochMilliseconds(),
                        points = it.points,
                        incoming = it.samples,
                    )
                },
            ) as T
        }
    }
}
