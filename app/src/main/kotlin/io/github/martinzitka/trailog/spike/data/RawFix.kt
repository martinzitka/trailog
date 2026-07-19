package io.github.martinzitka.trailog.spike.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * M0 spike storage. One row per location fix, written the instant it arrives — no
 * in-memory buffering (CLAUDE.md: lose nothing). All values are stored exactly as the OS
 * delivered them. SI units throughout.
 *
 * This is throwaway; the real schema lands in M1.4.
 */
@Entity(tableName = "raw_fix")
data class RawFix(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Timestamp from the location fix (epoch millis), not the device clock. */
    val fixTime: Long,
    /** When this row was written (epoch millis) — lets us measure ingest lag. */
    val recordedAt: Long,
    val latitude: Double,
    val longitude: Double,
    /** Metres above the WGS84 ellipsoid, or null if unreported. */
    val altitude: Double?,
    /** Horizontal accuracy radius in metres, or null. */
    val accuracy: Double?,
    /** Ground speed in m/s, or null. */
    val speed: Double?,
    /** Degrees from true north, or null. */
    val bearing: Double?,
    /** Satellites used in the fix at the time, or null if unknown. */
    val satellites: Int?,
    /** Barometric pressure in Pascals, or null if no barometer. */
    val pressure: Double?,
    /** Recording session id; a new session starts a new segment on recovery. */
    val sessionId: Long,
)
