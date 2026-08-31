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
import io.github.martinzitka.trailog.core.stats.TrackIndex
import io.github.martinzitka.trailog.core.stats.TrackProfile
import io.github.martinzitka.trailog.data.ActivityRepository
import io.github.martinzitka.trailog.data.ActivityWithStats
import io.github.martinzitka.trailog.data.TrailogDatabase
import io.github.martinzitka.trailog.data.toDomain
import io.github.martinzitka.trailog.ui.format.UnitSystem
import io.github.martinzitka.trailog.ui.map.TracePoint
import io.github.martinzitka.trailog.ui.settings.PrefsAppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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
 * **The splits table is re-lapped, never relabelled.** Both inputs to its interval — the unit
 * preference and the lap count the reader picks — change *what `:core` is asked to compute*, so a
 * 5 km split is five real kilometres of the ride rather than five one-kilometre rows added up
 * (ADR 0014). That is why the interval is a `:core` argument here and not a display concern.
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
    lapDistance: Flow<Double> = flowOf(LAP_DISTANCE_METRIC),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /**
     * How many laps of the display unit one split covers. Held here rather than in the screen
     * because it feeds a `:core` computation, and kept out of `SavedStateHandle` deliberately: a
     * ViewModel already survives rotation, and the choice is a way of looking at one ride rather
     * than a preference that should follow the user to the next one.
     */
    private val splitLaps = MutableStateFlow(DEFAULT_SPLIT_LAPS)

    val uiState: StateFlow<ActivityDetailUiState> =
        combine(row, lapDistance, splitLaps) { current, lap, laps ->
            if (current == null) {
                // No row: deleted, here or elsewhere. The screen leaves rather than showing a
                // stale view of something that no longer exists.
                ActivityDetailUiState.Gone
            } else {
                ActivityDetailUiState.Loaded(detailOf(current, lap * laps, laps))
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
     * Choose the splits interval: [laps] laps of the display unit, one of [SPLIT_LAP_OPTIONS].
     *
     * The ride is re-lapped from raw points, so the new rows are real 5 km splits rather than
     * five kilometre rows summed — and the elevation and moving-time figures in them are the ones
     * `:core` computes for that distance.
     */
    fun setSplitLaps(laps: Int) {
        require(laps in SPLIT_LAP_OPTIONS) { "unsupported split interval: $laps" }
        splitLaps.value = laps
    }

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
                // Null for the same reason as in GpxArchive: the domain Activity has no notes
                // field, so there is nothing to write. Not a decision that notes should be
                // omitted — a gap, recorded in the plan rather than hidden here.
                description = null,
                type = activity.type.name.lowercase(),
                points = activity.points,
            ),
        )
    }

    // ---- derivation -------------------------------------------------------------------------

    private suspend fun detailOf(
        row: ActivityWithStats,
        splitInterval: Double,
        laps: Int,
    ): ActivityDetail {
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
            // Built here rather than in the map screen so it is produced on [computeDispatcher]
            // with the profiles it has to agree with, off the main thread.
            track = TrackIndex.of(segments),
            splits = splitsOf(segments, type, splitInterval),
            splitLaps = laps,
            statsPending = cached == null,
        )
    }

    private fun splitsOf(
        segments: List<Segment>,
        type: ActivityType,
        interval: Double,
    ): List<SplitRow> =
        Statistics.splits(segments, type, interval).map { split ->
            SplitRow(
                // Riders count from split one, not zero.
                number = split.index + 1,
                distance = split.distance,
                movingSeconds = split.movingTime.toLong(DurationUnit.SECONDS),
                averageSpeed = split.averageSpeed,
                elevationGain = split.elevationGain,
                elevationLoss = split.elevationLoss,
                // A metre of slack: floating-point accumulation lands a "full" split a hair short.
                isPartial = split.distance < interval - 1.0,
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
                // The single place the unit preference becomes a split distance. An imperial user
                // wants the ride lapped at miles, not kilometre laps relabelled — so the choice is
                // made here, in metres, and the ViewModel stays unit-agnostic.
                lapDistance = PrefsAppSettings.get(appContext).preferences.map { prefs ->
                    when (prefs.unitSystem) {
                        UnitSystem.METRIC -> LAP_DISTANCE_METRIC
                        UnitSystem.IMPERIAL -> LAP_DISTANCE_IMPERIAL
                    }
                }.distinctUntilChanged(),
            ) as T
        }
    }

    internal companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * One lap of the display unit, in metres — SI, like every other length here.
         *
         * It arrives as a constructor [Flow] rather than being read from the preference directly,
         * so this class never learns that a unit preference exists: it is handed a distance and
         * laps the ride at multiples of it. Choosing *which* distance is the [Factory]'s job.
         *
         * A mile rather than a kilometre is a deliberate re-lapping of the ride, not a conversion
         * of the same figures — which is why it changes what `:core` is asked to compute instead of
         * how the answer is rendered (ADR 0014).
         */
        const val LAP_DISTANCE_METRIC = 1_000.0
        const val LAP_DISTANCE_IMPERIAL = 1609.344

        /**
         * The intervals the reader can pick, in laps of the display unit.
         *
         * Round multiples rather than arbitrary distances: a rider thinks in kilometres or miles,
         * and the same list serves both because it is a count, not a length. Defined here, beside
         * the lap distances, so the screen cannot offer an option the ViewModel would reject.
         */
        val SPLIT_LAP_OPTIONS = listOf(1, 2, 5, 10)

        /** One kilometre, or one mile — the interval every other tracker shows by default. */
        const val DEFAULT_SPLIT_LAPS = 1
    }
}
