package io.github.martinzitka.trailog.data

import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.recording.RecordingSession
import io.github.martinzitka.trailog.core.recording.RecordingState
import kotlinx.datetime.Instant
import java.util.UUID

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
