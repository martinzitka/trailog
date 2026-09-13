package io.github.martinzitka.trailog.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One sensor reading at one instant — a heart rate, a cadence, a power figure, a temperature.
 * Maps to and from the platform-free [io.github.martinzitka.trailog.core.model.SensorSample].
 *
 * **Its own table, not columns on `raw_points`.** A column there could only hold a reading that
 * happened to coincide with a location fix, so nothing would be stored during a signal blackout or
 * a pause, while a chest strap emits at about 1 Hz regardless of what the GPS is doing. Sensor data
 * shares the track's clock and nothing else.
 *
 * **Generic [type] and [value], not a column per sensor**, so adding a sensor is a new enum
 * constant rather than a migration.
 *
 * Samples are raw data and are treated like raw points: written as they arrive, never smoothed or
 * edited in place, and deleted only when the user deletes the whole activity. Unlike `raw_points`,
 * this table is new enough to carry a foreign key, so that deletion cascades from `activities` the
 * way `activity_stats` already does.
 *
 * There is deliberately **no uniqueness constraint**. Readings are assumed to arrive out of order
 * and duplicated, exactly like fixes, and the domain de-duplicates on read
 * ([io.github.martinzitka.trailog.core.model.SensorStream.normalise]) rather than rejecting a write
 * that might be the honest record of what a sensor sent.
 *
 * @property time epoch millis of the reading itself, on the same clock as [RawPointEntity.time].
 * @property type the [io.github.martinzitka.trailog.core.model.SensorType] name. Stored by name, so
 *   a new sensor needs no migration; renaming one would orphan every row that used it.
 * @property value the reading, in the unit that type declares. One unit per type, always, converted
 *   only at the display edge.
 */
@Entity(
    tableName = "sensor_samples",
    indices = [Index(value = ["activityId", "time"])],
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SensorSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: String,
    val time: Long,
    val type: String,
    val value: Double,
)
