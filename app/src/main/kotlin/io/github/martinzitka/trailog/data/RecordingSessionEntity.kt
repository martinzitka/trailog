package io.github.martinzitka.trailog.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The durable recording-session row. CLAUDE.md requires session state to live in the database,
 * never in memory or SharedPreferences, so any process — the app after a cold start, the boot
 * receiver, the service after a START_STICKY restart — can read what was happening and recover.
 *
 * At most one session exists at a time: it is inserted when recording starts and deleted when
 * the activity is finalised. The raw points it produced survive in [RawPointEntity].
 *
 * @property activityId the client-generated UUIDv7 of the activity being recorded (primary key).
 * @property type [io.github.martinzitka.trailog.core.model.ActivityType] name.
 * @property state [io.github.martinzitka.trailog.core.recording.RecordingState] name.
 * @property startTime epoch millis recording began.
 * @property currentSegmentIndex the segment new fixes are written with; bumped on every resume.
 * @property heartbeat epoch millis the service last reported itself alive, or null. Persisted
 *   periodically so a later recovery can tell how long the service was actually dead.
 */
@Entity(tableName = "recording_sessions")
data class RecordingSessionEntity(
    @PrimaryKey val activityId: String,
    val type: String,
    val state: String,
    val startTime: Long,
    val currentSegmentIndex: Int,
    val heartbeat: Long?,
)
