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
 */
data class Activity(
    val id: UUID,
    val type: ActivityType,
    val name: String,
    val points: List<RawPoint>,
) {
    /** The activity's raw points read as ordered, de-duplicated segments. */
    fun segments(): List<Segment> = Segment.segmentsOf(points)
}
