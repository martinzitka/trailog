package io.github.martinzitka.trailog.ui.record

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.recording.RecordingEngine
import io.github.martinzitka.trailog.core.recording.RecordingSession
import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.core.stats.Statistics
import io.github.martinzitka.trailog.data.ActivityRepository
import io.github.martinzitka.trailog.data.TrailogDatabase
import io.github.martinzitka.trailog.recording.AndroidRecordingEngine
import io.github.martinzitka.trailog.ui.map.TracePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.DurationUnit

/**
 * The Record screen's state holder. It derives the seven-case [RecordUiState] from three inputs —
 * the durable recording [RecordingSession] (from the engine), the permission/battery
 * [RecordEnvironment] (pushed in by the screen), and live statistics recomputed each second from
 * the raw points — and forwards user intent to the [RecordingEngine].
 *
 * It holds no Android types: permissions arrive as booleans via [updateEnvironment], and live
 * stats are loaded through an injected [loadActivity] lambda. That keeps the interesting logic
 * ([reduce]) a pure function and unit-testable without a device (CLAUDE.md testing policy).
 *
 * No coordinates are logged — nothing is logged here at all.
 */
class RecordViewModel(
    private val engine: RecordingEngine,
    private val loadActivity: suspend (String) -> Activity?,
    private val saveMetadata: suspend (String, String, String?, ActivityType) -> Unit,
    private val settings: RecordSettings,
    private val now: () -> Instant = { Clock.System.now() },
    ticker: Flow<Unit> = secondTicker(),
) : ViewModel() {

    private val environment = MutableStateFlow(RecordEnvironment())
    private val selectedType = MutableStateFlow(settings.lastActivityType())
    private val recentTypes = MutableStateFlow(settings.recentActivityTypes())
    private val livePayload = MutableStateFlow<LivePayload?>(null)
    private val _namingPrompt = MutableStateFlow<NamingPrompt?>(null)

    init {
        // Refresh live stats on every session change and every tick, cancelling an in-flight
        // recompute if a newer trigger arrives. Recomputing over the raw points once a second is
        // cheap — a four-hour ride is ~14k points and "data volume is not a problem" (CLAUDE.md).
        viewModelScope.launch {
            combine(engine.session, ticker.onStart { emit(Unit) }) { session, _ -> session }
                .collectLatest { session ->
                    livePayload.value = session?.let { loadLive(it) }
                }
        }
    }

    val uiState: StateFlow<RecordUiState> =
        combine(
            engine.session, environment, selectedType, livePayload, recentTypes,
        ) { session, env, type, live, recent ->
            reduce(session, env, type, live, recent)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = reduce(
                engine.session.value, environment.value, selectedType.value, livePayload.value,
                recentTypes.value,
            ),
        )

    /**
     * The just-finished activity waiting to be named, or null when there is nothing to name
     * (M1.8). Held separately from [uiState] because it outlives the recording: by the time it is
     * non-null the session is gone and [uiState] has already fallen back to
     * [RecordUiState.Ready]. Living in the ViewModel also means a rotation mid-typing does not
     * lose the prompt.
     */
    val namingPrompt: StateFlow<NamingPrompt?> = _namingPrompt.asStateFlow()

    // ---- intent ---------------------------------------------------------------------------

    /** Push the latest permission / battery-optimisation state, read by the screen from the OS. */
    fun updateEnvironment(env: RecordEnvironment) {
        environment.value = env
    }

    /** Pre-select an activity type while idle. The choice is persisted only once recording starts. */
    fun selectType(type: ActivityType) {
        selectedType.value = type
    }

    fun start() {
        val type = selectedType.value
        settings.noteActivityTypeUsed(type)
        // Re-read rather than reordering locally, so the chips reflect exactly what was stored.
        recentTypes.value = settings.recentActivityTypes()
        engine.start(type)
    }

    fun pause() = engine.pause()
    fun resume() = engine.resume()

    /** Confirmed stop: finalise and save the activity, then offer to name it. */
    fun stop() {
        val finished = engine.session.value
        engine.requestStop()
        engine.finalize()
        finished?.let { raiseNamingPrompt(it) }
    }

    /** Resume an interrupted session into a new segment. */
    fun recoverResume() = engine.recoverResume()

    /**
     * Finish an interrupted session without resuming, saving what was recorded. Also the end of a
     * recording, so it offers naming on the same terms — the ride may be hours old by now, but the
     * prompt costs a dismissal and the alternative is the ride quietly landing in History unnamed.
     */
    fun recoverFinish() {
        val finished = engine.session.value
        engine.finalize()
        finished?.let { raiseNamingPrompt(it) }
    }

    /**
     * Write the name and notes onto the activity that just finished, and close the prompt.
     *
     * The activity was saved before the prompt ever appeared, so nothing here can cost a ride —
     * a failed metadata write loses a title, and the user can set one from Activity detail at any
     * time. Both fields stay optional: with nothing typed this writes nothing at all rather than
     * stamping a pointless `updatedAt` on the row.
     */
    fun saveName(name: String, notes: String?) {
        val prompt = _namingPrompt.value ?: return
        _namingPrompt.value = null
        val trimmedName = name.trim()
        val trimmedNotes = notes?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedName.isEmpty() && trimmedNotes == null) return
        viewModelScope.launch {
            saveMetadata(prompt.activityId, trimmedName, trimmedNotes, prompt.activityType)
        }
    }

    /**
     * Dismiss the prompt without naming. The ride keeps the title History derives from its type
     * and date, and remains editable from Activity detail forever (CLAUDE.md: a ride is never
     * held hostage to its metadata).
     */
    fun skipNaming() {
        _namingPrompt.value = null
    }

    private fun raiseNamingPrompt(session: RecordingSession) {
        _namingPrompt.value = NamingPrompt(session.activityId.toString(), session.type)
    }

    // ---- derivation -----------------------------------------------------------------------

    /**
     * The chips the picker offers: the [OFFERED_TYPE_COUNT] most recently used, with [selected]
     * guaranteed present.
     *
     * When the selection is not already among the recent ones — it was picked out of the overflow
     * — it displaces the *least* recent rather than being prepended. Prepending would reshuffle
     * the row under the user's finger every time they chose a type, which is a worse trade than
     * one chip changing at the far end.
     */
    private fun offeredTypes(
        selected: ActivityType,
        recent: List<ActivityType>,
    ): List<ActivityType> {
        val top = recent.take(OFFERED_TYPE_COUNT)
        return if (selected in top || top.size < OFFERED_TYPE_COUNT) {
            (top + selected).distinct()
        } else {
            top.dropLast(1) + selected
        }
    }

    /**
     * Pure mapping from inputs to screen state — the whole phase logic in one testable place.
     * Live data is only trusted when it belongs to the current session, so a stale payload from
     * a just-finished activity never leaks into the next one.
     */
    internal fun reduce(
        session: RecordingSession?,
        env: RecordEnvironment,
        selected: ActivityType,
        live: LivePayload?,
        recent: List<ActivityType> = ActivityType.entries,
    ): RecordUiState {
        val validLive = live?.takeIf { session != null && it.activityId == session.activityId.toString() }
        return when (session?.state) {
            null, RecordingState.IDLE ->
                if (!env.canRecord) RecordUiState.PermissionsMissing(env)
                else RecordUiState.Ready(selected, offeredTypes(selected, recent), env)

            RecordingState.RECORDING -> RecordUiState.Recording(
                activityType = session.type,
                live = validLive?.live ?: LiveStats.EMPTY,
                segments = validLive?.segments ?: emptyList(),
                environment = env,
            )

            RecordingState.PAUSED -> RecordUiState.Paused(
                activityType = session.type,
                live = validLive?.live ?: LiveStats.EMPTY,
                segments = validLive?.segments ?: emptyList(),
                environment = env,
            )

            RecordingState.STOPPING -> RecordUiState.Saving(env)

            RecordingState.RECOVERING -> RecordUiState.InterruptedSessionFound(
                gapSeconds = validLive?.gapSeconds ?: 0,
                environment = env,
            )
        }
    }

    private suspend fun loadLive(session: RecordingSession): LivePayload {
        val id = session.activityId.toString()
        val activity = loadActivity(id)
            ?: return LivePayload(id, LiveStats.EMPTY, emptyList(), 0)

        val segments = activity.segments()
        val stats = Statistics.compute(segments, activity.type)
        val nowInstant = now()

        val elapsedSeconds = (nowInstant - session.startTime)
            .toLong(DurationUnit.SECONDS).coerceAtLeast(0)
        val lastPoint = activity.points.maxByOrNull { it.time }
        val gapSeconds = lastPoint?.let { (nowInstant - it.time).toLong(DurationUnit.SECONDS) }
            ?.coerceAtLeast(0) ?: 0

        return LivePayload(
            activityId = id,
            live = LiveStats(
                elapsedSeconds = elapsedSeconds,
                movingSeconds = stats.movingTime.toLong(DurationUnit.SECONDS),
                distance = stats.distance,
                currentSpeed = lastPoint?.speed ?: 0.0,
                elevationGain = stats.elevationGain,
            ),
            segments = segments.map { seg ->
                seg.points.map { TracePoint(it.latitude, it.longitude) }
            },
            gapSeconds = gapSeconds,
        )
    }

    /** Live figures plus the id they belong to, so a stale load is never shown for a new session. */
    internal data class LivePayload(
        val activityId: String,
        val live: LiveStats,
        val segments: List<List<TracePoint>>,
        val gapSeconds: Long,
    )

    /**
     * Assembles a [RecordViewModel] wired to the real engine, repository and preferences. Held as
     * a [ViewModelProvider.Factory] so the screen gets a lifecycle-scoped instance.
     */
    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = ActivityRepository(TrailogDatabase.get(appContext))
            return RecordViewModel(
                engine = AndroidRecordingEngine.get(appContext),
                loadActivity = repository::loadActivity,
                saveMetadata = repository::updateMetadata,
                settings = PrefsRecordSettings(appContext),
            ) as T
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /**
         * How many activity types the picker shows as chips before the rest go to the overflow.
         *
         * Five fits two rows on a narrow screen and leaves the start button above the fold, which
         * is the constraint that produced this whole arrangement — fifteen chips buried it.
         */
        const val OFFERED_TYPE_COUNT = 5
    }
}
