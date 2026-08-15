package io.github.martinzitka.trailog.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The metadata of a recorded activity. The raw points it is made of live in [RawPointEntity]
 * and are immutable and sacred (CLAUDE.md); everything derived — its statistics, its smoothed
 * track — is a recomputable view over them, cached in [ActivityStatsEntity].
 *
 * The id is the client-generated UUIDv7 string, matching the id on every one of the activity's
 * raw points, so an activity can be created entirely offline and the server never mints it.
 *
 * Only name, notes and type are mutable metadata (CLAUDE.md's sync model: last-write-wins on
 * [updatedAt]). SI units and no unit suffixes; all timestamps are epoch millis at rest.
 *
 * @property id UUIDv7 string, equal to the `activityId` on the activity's raw points.
 * @property type [io.github.martinzitka.trailog.core.model.ActivityType] name.
 * @property name user-facing title; empty until the user sets one (a display name is derived
 *   from type and date at the render edge, never stored formatted).
 * @property notes free-text notes, or null.
 * @property startTime epoch millis of the activity's first fix / when recording began.
 * @property createdAt epoch millis the row was created.
 * @property updatedAt epoch millis of the last metadata edit; the sync tie-breaker.
 */
@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val notes: String?,
    val startTime: Long,
    val createdAt: Long,
    val updatedAt: Long,
)
