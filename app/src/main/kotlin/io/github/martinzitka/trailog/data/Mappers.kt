package io.github.martinzitka.trailog.data

import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.recording.RecordingSession
import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.core.stats.ActivityStats
import kotlinx.datetime.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * Mapping between the Room entities (`:app`) and the platform-free domain types (`:core`).
 * Kept in one place so the epoch-millis ↔ [Instant] and enum-name ↔ enum conversions live
 * together and nothing reinvents them.
 */

fun RawPointEntity.toDomain(): RawPoint =
    RawPoint(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy,
        time = Instant.fromEpochMilliseconds(time),
        speed = speed,
        bearing = bearing,
        pressure = pressure,
        segmentIndex = segmentIndex,
    )

fun RawPoint.toEntity(activityId: String, recordedAt: Long): RawPointEntity =
    RawPointEntity(
        activityId = activityId,
        segmentIndex = segmentIndex,
        time = time.toEpochMilliseconds(),
        recordedAt = recordedAt,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy,
        speed = speed,
        bearing = bearing,
        pressure = pressure,
    )

fun RecordingSessionEntity.toDomain(): RecordingSession =
    RecordingSession(
        activityId = UUID.fromString(activityId),
        type = ActivityType.valueOf(type),
        state = RecordingState.valueOf(state),
        startTime = Instant.fromEpochMilliseconds(startTime),
        currentSegmentIndex = currentSegmentIndex,
        // lastFixTime is derived from the raw points on demand, not stored on the row.
        lastFixTime = null,
        heartbeat = heartbeat?.let { Instant.fromEpochMilliseconds(it) },
    )

fun RecordingSession.toEntity(): RecordingSessionEntity =
    RecordingSessionEntity(
        activityId = activityId.toString(),
        type = type.name,
        state = state.name,
        startTime = startTime.toEpochMilliseconds(),
        currentSegmentIndex = currentSegmentIndex,
        heartbeat = heartbeat?.toEpochMilliseconds(),
    )

/**
 * Serialise computed [ActivityStats] into its cache row. Durations are stored as whole millis;
 * the sub-millisecond loss is irrelevant to any displayed figure and the row is a recomputable
 * cache regardless.
 */
fun ActivityStats.toEntity(activityId: String, computedAt: Long): ActivityStatsEntity =
    ActivityStatsEntity(
        activityId = activityId,
        distance = distance,
        elapsedTime = elapsedTime.toLong(DurationUnit.MILLISECONDS),
        movingTime = movingTime.toLong(DurationUnit.MILLISECONDS),
        averageSpeed = averageSpeed,
        maxSpeed = maxSpeed,
        elevationGain = elevationGain,
        elevationLoss = elevationLoss,
        segmentCount = segmentCount,
        pointCount = pointCount,
        computedAt = computedAt,
    )

fun ActivityStatsEntity.toDomain(): ActivityStats =
    ActivityStats(
        distance = distance,
        elapsedTime = elapsedTime.milliseconds,
        movingTime = movingTime.milliseconds,
        averageSpeed = averageSpeed,
        maxSpeed = maxSpeed,
        elevationGain = elevationGain,
        elevationLoss = elevationLoss,
        segmentCount = segmentCount,
        pointCount = pointCount,
    )
