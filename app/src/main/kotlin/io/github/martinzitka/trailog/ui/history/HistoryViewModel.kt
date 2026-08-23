package io.github.martinzitka.trailog.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.data.ActivityRepository
import io.github.martinzitka.trailog.data.ActivityWithStats
import io.github.martinzitka.trailog.data.TrailogDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * The History screen's state holder: the activity list, most recent first, mapped from Room rows
 * into [HistoryUiState].
 *
 * It takes the row flow as a constructor argument rather than reaching for a database, so the
 * mapping — the only interesting logic here — is unit-testable without a device, and so the
 * loading, empty, populated and error paths can each be driven directly (CLAUDE.md testing policy).
 *
 * Ordering is the database's (`ORDER BY startTime DESC`) and is preserved as-is; re-sorting here
 * would be a second source of truth for what "most recent" means.
 *
 * No coordinates pass through this class — a History row carries statistics and metadata only.
 */
class HistoryViewModel(
    rows: Flow<List<ActivityWithStats>>,
) : ViewModel() {

    /** Bumped by [retry] to re-subscribe to the row flow after a read failure. */
    private val attempts = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HistoryUiState> = attempts
        .flatMapLatest {
            rows.map(::toUiState)
                .onStart { emit(HistoryUiState.Loading) }
                .catch { emit(HistoryUiState.Error) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = HistoryUiState.Loading,
        )

    /** Re-read the list after an error. Cheap: it just re-subscribes to the query. */
    fun retry() {
        attempts.value += 1
    }

    private fun toUiState(rows: List<ActivityWithStats>): HistoryUiState =
        if (rows.isEmpty()) {
            HistoryUiState.Empty
        } else {
            HistoryUiState.Populated(rows.map(::toItem))
        }

    private fun toItem(row: ActivityWithStats): HistoryItem = HistoryItem(
        id = row.activity.id,
        type = ActivityType.valueOf(row.activity.type),
        name = row.activity.name,
        startTime = row.activity.startTime,
        distance = row.stats?.distance,
        // Stats durations are stored in millis; the display edge works in whole seconds.
        elapsedSeconds = row.stats?.elapsedTime?.div(MILLIS_PER_SECOND),
        elevationGain = row.stats?.elevationGain,
    )

    /** Builds a [HistoryViewModel] over the real database. */
    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = ActivityRepository(TrailogDatabase.get(appContext))
            return HistoryViewModel(repository.activitiesWithStatsByRecency()) as T
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
