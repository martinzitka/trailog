package io.github.martinzitka.trailog.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single location fix, persisted exactly as the OS delivered it. This is the durable,
 * on-arrival record required by CLAUDE.md ("Points are written to disk as they arrive, never
 * buffered in memory") and the immutable-and-sacred rule (raw points are never modified or
 * deleted; every statistic is a recomputable view over them).
 *
 * SI units throughout; no unit suffixes because there is only one unit. Maps to and from the
 * platform-free [io.github.martinzitka.trailog.core.model.RawPoint] via the recording mappers.
 *
 * The `activityId`/`time` index serves recovery (max fix time per activity) and export
 * (points of one activity in time order).
 *
 * @property time epoch millis of the location fix itself, not the device clock (clocks jump).
 * @property recordedAt epoch millis this row was written — lets ingest lag be measured.
 * @property segmentIndex which segment of the activity this fix belongs to; a gap (crash,
 *   kill, reboot, blackout, or a pause) ends one segment and begins the next.
 */
@Entity(
    tableName = "raw_points",
    indices = [Index(value = ["activityId", "time"])],
)
data class RawPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: String,
    val segmentIndex: Int,
    val time: Long,
    val recordedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Double?,
    val speed: Double?,
    val bearing: Double?,
    val pressure: Double?,
)
