package io.github.martinzitka.trailog.core.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SensorStreamTest {

    private fun at(sec: Long) = Instant.fromEpochSeconds(sec)

    private fun hr(sec: Long, value: Double) = SensorSample(at(sec), SensorType.HEART_RATE, value)

    private fun pt(sec: Long, seg: Int) = RawPoint(
        latitude = 50.0,
        longitude = 14.0,
        altitude = null,
        accuracy = null,
        time = at(sec),
        segmentIndex = seg,
    )

    // ---- units --------------------------------------------------------------------------------

    @Test
    fun `every sensor type declares exactly one unit`() {
        // The whole point of the enum: one unit per type, fixed, converted only at the display
        // edge. A type with no unit would mean a value nobody can interpret.
        assertEquals(SensorUnit.BEATS_PER_MINUTE, SensorType.HEART_RATE.unit)
        assertEquals(SensorUnit.WATT, SensorType.POWER.unit)
        assertTrue(SensorType.entries.all { it.unit in SensorUnit.entries })
    }

    @Test
    fun `an unknown type name resolves to null rather than throwing`() {
        // A file written by a later Trailog may name a sensor this build has never heard of.
        assertEquals(SensorType.HEART_RATE, SensorType.byNameOrNull("HEART_RATE"))
        assertNull(SensorType.byNameOrNull("MUSCLE_OXYGEN"))
    }

    // ---- normalising --------------------------------------------------------------------------

    @Test
    fun `samples are sorted and duplicates of one type at one instant collapse`() {
        val normalised = SensorStream.normalise(
            listOf(hr(3, 140.0), hr(1, 130.0), hr(1, 999.0), hr(2, 135.0)),
        )
        assertEquals(listOf(130.0, 135.0, 140.0), normalised.map { it.value })
    }

    @Test
    fun `two different sensors at the same instant are both kept`() {
        val samples = listOf(
            hr(1, 140.0),
            SensorSample(at(1), SensorType.CADENCE, 85.0),
        )
        assertEquals(2, SensorStream.normalise(samples).size)
    }

    @Test
    fun `types present are reported in declaration order`() {
        val samples = listOf(
            SensorSample(at(1), SensorType.POWER, 220.0),
            hr(1, 140.0),
        )
        assertEquals(listOf(SensorType.HEART_RATE, SensorType.POWER), SensorStream.typesIn(samples))
        assertEquals(listOf(140.0), SensorStream.ofType(samples, SensorType.HEART_RATE).map { it.value })
    }

    // ---- segment alignment --------------------------------------------------------------------

    @Test
    fun `a sample taken in the gap between segments belongs to neither`() {
        // Segment 0 runs 0–10 s, segment 1 runs 600–610 s. The strap kept emitting throughout.
        val segments = Segment.segmentsOf(
            listOf(pt(0, 0), pt(10, 0), pt(600, 1), pt(610, 1)),
        )
        val grouped = SensorStream.bySegment(
            listOf(hr(5, 140.0), hr(300, 120.0), hr(605, 150.0)),
            segments,
        )

        assertEquals(setOf(0, 1), grouped.keys)
        assertEquals(listOf(140.0), grouped.getValue(0).map { it.value })
        assertEquals(listOf(150.0), grouped.getValue(1).map { it.value })
        // The 300 s reading is real, but there is no segment it could honestly be charted against.
        assertTrue(grouped.values.none { list -> list.any { it.value == 120.0 } })
    }

    @Test
    fun `samples outside the track's span are omitted`() {
        val segments = Segment.segmentsOf(listOf(pt(100, 0), pt(200, 0)))
        val grouped = SensorStream.bySegment(
            listOf(hr(50, 130.0), hr(150, 140.0), hr(250, 150.0)),
            segments,
        )
        assertEquals(listOf(140.0), grouped.getValue(0).map { it.value })
    }

    @Test
    fun `segment boundaries are inclusive at both ends`() {
        val segments = Segment.segmentsOf(listOf(pt(100, 0), pt(200, 0)))
        val grouped = SensorStream.bySegment(listOf(hr(100, 130.0), hr(200, 150.0)), segments)
        assertEquals(listOf(130.0, 150.0), grouped.getValue(0).map { it.value })
    }

    @Test
    fun `an activity groups its own samples by segment`() {
        val activity = Activity(
            id = java.util.UUID.randomUUID(),
            type = ActivityType.CYCLING,
            name = "",
            points = listOf(pt(0, 0), pt(10, 0), pt(600, 1)),
            samples = listOf(hr(5, 140.0), hr(300, 120.0)),
        )
        assertEquals(mapOf(0 to listOf(hr(5, 140.0))), activity.samplesBySegment())
    }

    @Test
    fun `empty inputs group to nothing`() {
        assertEquals(emptyMap(), SensorStream.bySegment(emptyList(), emptyList()))
        assertEquals(
            emptyMap(),
            SensorStream.bySegment(listOf(hr(1, 140.0)), emptyList()),
        )
    }

    // ---- nearest match ------------------------------------------------------------------------

    @Test
    fun `an exact timestamp match is returned`() {
        val samples = SensorStream.normalise(listOf(hr(10, 140.0), hr(20, 150.0)))
        assertEquals(140.0, SensorStream.nearestTo(samples, at(10))?.value)
    }

    @Test
    fun `the nearer of the two neighbours wins`() {
        val samples = SensorStream.normalise(listOf(hr(10, 140.0), hr(20, 150.0)))
        assertEquals(140.0, SensorStream.nearestTo(samples, at(12), tolerance = 60.seconds)?.value)
        assertEquals(150.0, SensorStream.nearestTo(samples, at(18), tolerance = 60.seconds)?.value)
    }

    @Test
    fun `a reading further away than the tolerance is not reported`() {
        val samples = SensorStream.normalise(listOf(hr(10, 140.0)))
        // Three seconds is the default: a fix in a sparse stretch must not be labelled with a
        // stale heart rate just because it is the only reading in the file.
        assertEquals(140.0, SensorStream.nearestTo(samples, at(13))?.value)
        assertNull(SensorStream.nearestTo(samples, at(14)))
    }

    @Test
    fun `an empty stream matches nothing`() {
        assertNull(SensorStream.nearestTo(emptyList(), at(10)))
    }
}
