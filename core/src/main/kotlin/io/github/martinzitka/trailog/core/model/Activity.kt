package io.github.martinzitka.trailog.core.model

import java.util.UUID

/**
 * A recorded activity: an id, some mutable metadata, and the raw points it is made of. Raw
 * points are immutable and sacred (CLAUDE.md); everything else about an activity — its
 * statistics, its smoothed track — is a recomputable view over them.
 *
 * The id is a client-generated UUIDv7 so an activity can be created entirely offline; the
 * server never mints it.
 *
 * @property points every fix as delivered by the OS, unfiltered. May arrive out of order or
 *   duplicated; use [Segment.segmentsOf] to read them as ordered segments.
 * @property samples every sensor reading taken during the activity — heart rate and its kin. A
 *   stream of its own rather than fields on [points], because a strap keeps emitting when the GPS
 *   has nothing to say. Empty for an activity recorded with no sensor attached, which is most of
 *   them. Read them through [SensorStream].
 */
data class Activity(
    val id: UUID,
    val type: ActivityType,
    val name: String,
    val points: List<RawPoint>,
    val samples: List<SensorSample> = emptyList(),
) {
    /** The activity's raw points read as ordered, de-duplicated segments. */
    fun segments(): List<Segment> = Segment.segmentsOf(points)

    /**
     * The activity's sensor samples grouped onto the segments they were taken during. Samples in a
     * recording gap belong to no segment and are omitted — nothing is charted across a boundary.
     */
    fun samplesBySegment(): Map<Int, List<SensorSample>> =
        SensorStream.bySegment(samples, segments())
}
