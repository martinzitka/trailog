package io.github.martinzitka.trailog.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.gpx.GpxTrack
import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.Segment
import io.github.martinzitka.trailog.core.stats.Statistics
import io.github.martinzitka.trailog.core.stats.TrackProfile
import io.github.martinzitka.trailog.data.ActivityRepository
import io.github.martinzitka.trailog.data.ActivityWithStats
import io.github.martinzitka.trailog.data.TrailogDatabase
import io.github.martinzitka.trailog.data.toDomain
import io.github.martinzitka.trailog.ui.map.TracePoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.DurationUnit

/**
 * The Activity detail screen's state holder: one activity's metadata, figures, route, charts and
 * splits, plus the edit, delete and export intents.
 *
 * Like the other ViewModels here it holds no Android types — the database arrives as a row [Flow]
 * and three suspend lambdas — so the interesting work is unit-testable on the JVM (CLAUDE.md
 * testing policy). The [Factory] wires the real repository.
 *
 * Two things are worth knowing about how the figures are produced:
 *
 * **Statistics come from the cache when it exists and are computed on the fly when it does not.**
 * History can show a dash for a pending figure because a list row has other things to say; a
 * detail screen made of dashes would be useless. Either way the numbers come from the one `:core`
 * [Statistics] implementation over the same raw points, so they cannot disagree.
 *
 * **Splits and chart profiles are always computed from raw points**, because neither is cached —
 * they are views, cheap to rebuild, and rebuilding them is what lets an algorithm improvement
 * reach an old ride. That work happens on [computeDispatcher], never the main thread: a four-hour
 * ride is ~14,400 fixes and the screen must not hitch while it is measured.
 *
 * No coordinates are logged — nothing is logged here at all.
 */
class ActivityDetailViewModel(
    private val activityId: String,
    row: Flow<ActivityWithStats?>,
    private val loadActivity: suspend (String) -> Activity?,
    private val saveMetadata: suspend (String, String, String?, ActivityType) -> Unit,
    private val deleteActivity: suspend (String) -> Boolean,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    val uiState: StateFlow<ActivityDetailUiState> = row
        .map { current ->
            if (current == null) {
                // No row: deleted, here or elsewhere. The screen leaves rather than showing a
                // stale view of something that no longer exists.
                ActivityDetailUiState.Gone
            } else {
                ActivityDetailUiState.Loaded(detailOf(current))
            }
        }
        .flowOn(computeDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ActivityDetailUiState.Loading,
        )

    // ---- intent -----------------------------------------------------------------------------

    /**
     * Save edited metadata. The row flow re-emits with the new values, so nothing is mirrored in
     * local state here — there is one source of truth for what the activity is called.
     */
    fun saveEdits(name: String, notes: String?, type: ActivityType) {
        viewModelScope.launch { saveMetadata(activityId, name.trim(), notes?.trim(), type) }
    }

    /**
     * Delete the activity and everything derived from it, including its raw points. The screen
     * confirms first — this is the destructive path and there is no undo. Once the row is gone the
     * flow emits [ActivityDetailUiState.Gone] and the screen navigates back.
     */
    fun delete() {
        viewModelScope.launch { deleteActivity(activityId) }
    }

    /**
     * Serialise the activity to GPX, or null if it has vanished. Goes through `:core`'s [Gpx] —
     * the single GPX writer in the project — so segments become separate `<trkseg>` elements and a
     * recording gap is never welded into a straight line.
     *
     * Returns the document rather than writing it: choosing a destination and opening a stream is
     * the screen's job, at the Android edge. Export works entirely offline with no account
     * (CLAUDE.md), because it is nothing but this string and a file the user picked.
     */
    suspend fun buildGpx(): String? = withContext(computeDispatcher) {
        val activity = loadActivity(activityId) ?: return@withContext null
        Gpx.write(
            GpxTrack(
                name = activity.name.ifBlank { null },
                type = activity.type.name.lowercase(),
                points = activity.points,
            ),
        )
    }

    // ---- derivation -------------------------------------------------------------------------

    private suspend fun detailOf(row: ActivityWithStats): ActivityDetail {
        val type = ActivityType.valueOf(row.activity.type)
        val domain = loadActivity(activityId)
        val segments = domain?.segments() ?: emptyList()

        // The cache is authoritative when present; otherwise the same `:core` code computes the
        // figures now. Both paths read the same raw points, so they cannot produce two answers.
        val cached = row.stats?.toDomain()
        val stats = cached ?: Statistics.compute(segments, type)

        return ActivityDetail(
            id = row.activity.id,
            type = type,
            name = row.activity.name,
            notes = row.activity.notes,
            startTime = row.activity.startTime,
            distance = stats.distance,
            elapsedSeconds = stats.elapsedTime.toLong(DurationUnit.SECONDS),
            movingSeconds = stats.movingTime.toLong(DurationUnit.SECONDS),
            averageSpeed = stats.averageSpeed,
            maxSpeed = stats.maxSpeed,
            elevationGain = stats.elevationGain,
            elevationLoss = stats.elevationLoss,
            segmentCount = stats.segmentCount,
            pointCount = stats.pointCount,
            segments = segments.map { seg ->
                seg.points.map { TracePoint(it.latitude, it.longitude) }
            },
            elevationProfile = TrackProfile.elevation(segments),
            speedProfile = TrackProfile.speed(segments),
            splits = splitsOf(segments, type),
            statsPending = cached == null,
        )
    }

    private fun splitsOf(segments: List<Segment>, type: ActivityType): List<SplitRow> =
        Statistics.splits(segments, type, SPLIT_DISTANCE).map { split ->
            SplitRow(
                // Riders count from kilometre one, not zero.
                number = split.index + 1,
                distance = split.distance,
                movingSeconds = split.movingTime.toLong(DurationUnit.SECONDS),
                averageSpeed = split.averageSpeed,
                elevationGain = split.elevationGain,
                elevationLoss = split.elevationLoss,
                // A metre of slack: floating-point accumulation lands a "full" split a hair short.
                isPartial = split.distance < SPLIT_DISTANCE - 1.0,
            )
        }

    /**
     * Builds an [ActivityDetailViewModel] over the real database for one activity id.
     *
     * @param activityId the UUIDv7 string threaded through navigation from History.
     */
    class Factory(context: Context, private val activityId: String) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = ActivityRepository(TrailogDatabase.get(appContext))
            return ActivityDetailViewModel(
                activityId = activityId,
                row = repository.activityWithStatsFlow(activityId),
                loadActivity = repository::loadActivity,
                saveMetadata = repository::updateMetadata,
                deleteActivity = repository::delete,
            ) as T
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Splits are per kilometre. SI, and a parameter of the computation rather than a display
         * choice — an imperial *display* preference must not silently re-lap the ride at miles
         * (CLAUDE.md). Offering a mile option later means changing this, deliberately.
         */
        const val SPLIT_DISTANCE = 1_000.0
    }
}
