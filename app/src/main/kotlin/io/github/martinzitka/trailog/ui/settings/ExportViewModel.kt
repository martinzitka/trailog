package io.github.martinzitka.trailog.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.data.ActivityRef
import io.github.martinzitka.trailog.data.ActivityRepository
import io.github.martinzitka.trailog.data.TrailogDatabase
import io.github.martinzitka.trailog.export.GpxArchive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Where the bulk export has got to. Four states, and the screen shows all of them — an export of
 * several hundred rides takes long enough that a button which silently does nothing for a minute
 * would read as broken.
 */
sealed interface ExportUiState {

    /** Nothing has been asked for yet. The row's resting state, and the only one it starts in. */
    data object Idle : ExportUiState

    /**
     * Underway. [total] is 0 until the worklist has been counted, which is one query and usually
     * invisible; the screen shows an indeterminate bar for that moment rather than "0 of 0".
     */
    data class Running(val done: Int, val total: Int) : ExportUiState

    /** Finished, with [count] activities written. Zero means the history was empty. */
    data class Done(val count: Int) : ExportUiState

    /**
     * Failed. Carries no detail deliberately: the only failures here are a stream that could not
     * be opened or written, the user's own storage told them so, and an exception message is not
     * something to put on screen — nor in a log, where coordinates must never end up.
     */
    data object Failed : ExportUiState
}

/**
 * "Export all activities": every ride as GPX, in one zip the user picks a destination for.
 *
 * Separate from [SettingsViewModel] even though both drive the Settings screen. That one is a thin
 * projection of stored preferences with no I/O at all; this one owns a long-running job over the
 * whole database. Folding them together would put a progress state machine inside the preferences
 * object for no gain.
 *
 * Holds no Android types — the database arrives as two suspend lambdas and the destination as a
 * plain [OutputStream] — so the interesting behaviour is unit-testable on the JVM (CLAUDE.md
 * testing policy). The [Factory] wires the real repository.
 *
 * Export is a read. It never writes to, modifies or deletes anything on the device, which is why a
 * failure needs no recovery path: the worst case is a partial file at a destination the user chose.
 */
class ExportViewModel(
    private val activityRefs: suspend () -> List<ActivityRef>,
    private val loadActivity: suspend (String) -> Activity?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val state = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val uiState: StateFlow<ExportUiState> = state.asStateFlow()

    /**
     * Write every activity to [openSink] as a zip of GPX files, naming each entry with [entryName].
     *
     * @param openSink opens the destination the user picked, or returns null if it could not be
     *   opened. Called once, after the worklist is known, and the archive writer closes it.
     * @param entryName builds one entry's file name from a slug and the ride's start time —
     *   supplied by the screen, because file naming belongs at the display edge with the rest of
     *   the formatting.
     *
     * Ignored while an export is already running: two concurrent passes over the whole history
     * would race for the same progress state and achieve nothing.
     */
    fun exportAll(
        openSink: suspend () -> OutputStream?,
        entryName: (slug: String, startTime: Long) -> String,
    ) {
        if (state.value is ExportUiState.Running) return
        state.value = ExportUiState.Running(done = 0, total = 0)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) {
                    val refs = activityRefs()
                    state.value = ExportUiState.Running(done = 0, total = refs.size)
                    val sink = openSink() ?: error("no destination")
                    GpxArchive.write(
                        sink = sink,
                        refs = refs,
                        load = loadActivity,
                        name = entryName,
                        onProgress = { done ->
                            state.value = ExportUiState.Running(done = done, total = refs.size)
                        },
                    )
                }
            }
            // A cancelled export is the ViewModel going away, not a failure worth reporting to a
            // screen that no longer exists — and swallowing it would break structured concurrency.
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            state.value = result.fold(
                onSuccess = { ExportUiState.Done(it) },
                onFailure = { ExportUiState.Failed },
            )
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = ActivityRepository(TrailogDatabase.get(appContext))
            return ExportViewModel(
                activityRefs = repository::activityRefs,
                loadActivity = repository::loadActivity,
            ) as T
        }
    }
}
