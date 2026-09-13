package io.github.martinzitka.trailog.export

import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.SensorSample
import io.github.martinzitka.trailog.core.model.SensorType
import io.github.martinzitka.trailog.data.ActivityRef
import io.github.martinzitka.trailog.ui.format.Formatter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Unit tests for [GpxArchive] — the bulk export's writer. Plain JVM: the sink is a byte array and
 * the database is a lambda, so nothing here needs a device.
 *
 * The load-bearing assertion is [segment gaps survive the archive], because a bulk export that
 * silently welded a recording gap into a straight line would corrupt the whole history at once.
 */
class GpxArchiveTest {

    @Test fun `writes one entry per activity, named after the ride`() = runTest {
        val out = ByteArrayOutputStream()
        val count = GpxArchive.write(
            sink = out,
            refs = listOf(ref("a", DAY), ref("b", DAY + HOUR)),
            load = { id -> activity(id, name = if (id == "a") "Morning loop" else "Evening spin") },
            name = ::nameOf,
        )

        assertEquals(2, count)
        assertEquals(
            listOf(
                "trailog-morning-loop-1970-01-02-0000.gpx",
                "trailog-evening-spin-1970-01-02-0100.gpx",
            ),
            entries(out).keys.toList(),
        )
    }

    @Test fun `segment gaps survive the archive as separate trkseg elements`() = runTest {
        val out = ByteArrayOutputStream()
        GpxArchive.write(
            sink = out,
            refs = listOf(ref("a", DAY)),
            load = { activity(it, points = ride(segment = 0) + ride(segment = 1, startSecond = 900)) },
            name = ::nameOf,
        )

        val gpx = entries(out).values.single()
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        // And it is real GPX, read back by the one reader in the project — with the gap intact.
        assertEquals(2, Gpx.read(gpx).single().segments().size)
    }

    @Test fun `sensor samples reach the archive and read back exactly`() = runTest {
        // The reading at 900 s falls between the two segments, where no track point can carry it.
        // It survives the round trip only because the exporter writes Trailog's own stream.
        val samples = listOf(
            SensorSample(Instant.fromEpochSeconds(0), SensorType.HEART_RATE, 142.0),
            SensorSample(Instant.fromEpochSeconds(900), SensorType.HEART_RATE, 138.0),
        )
        val out = ByteArrayOutputStream()
        GpxArchive.write(
            sink = out,
            refs = listOf(ref("a", DAY)),
            load = {
                activity(
                    it,
                    points = ride(segment = 0) + ride(segment = 1, startSecond = 1_800),
                    samples = samples,
                )
            },
            name = ::nameOf,
        )

        assertEquals(samples, Gpx.read(entries(out).values.single()).single().samples)
    }

    @Test fun `rides that would collide on name are kept apart`() = runTest {
        val out = ByteArrayOutputStream()
        GpxArchive.write(
            sink = out,
            // Same name, same start time: without disambiguation one would overwrite the other and
            // the export would silently lose a ride.
            refs = listOf(ref("a", DAY), ref("b", DAY), ref("c", DAY)),
            load = { activity(it, name = "Commute") },
            name = ::nameOf,
        )

        assertEquals(
            listOf(
                "trailog-commute-1970-01-02-0000.gpx",
                "trailog-commute-1970-01-02-0000-2.gpx",
                "trailog-commute-1970-01-02-0000-3.gpx",
            ),
            entries(out).keys.toList(),
        )
    }

    @Test fun `an activity deleted mid-export is skipped, not fatal`() = runTest {
        val out = ByteArrayOutputStream()
        val count = GpxArchive.write(
            sink = out,
            refs = listOf(ref("a", DAY), ref("gone", DAY), ref("b", DAY + HOUR)),
            load = { id -> if (id == "gone") null else activity(id) },
            name = ::nameOf,
        )

        assertEquals(2, count)
        assertEquals(2, entries(out).size)
    }

    @Test fun `progress counts the worklist, including what it skipped`() = runTest {
        val seen = mutableListOf<Int>()
        GpxArchive.write(
            sink = ByteArrayOutputStream(),
            refs = listOf(ref("a", DAY), ref("gone", DAY), ref("b", DAY)),
            load = { id -> if (id == "gone") null else activity(id) },
            name = ::nameOf,
            onProgress = { seen += it },
        )

        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test fun `an empty history produces a readable, empty archive`() = runTest {
        val out = ByteArrayOutputStream()
        val count = GpxArchive.write(out, refs = emptyList(), load = { null }, name = ::nameOf)

        assertEquals(0, count)
        assertTrue(entries(out).isEmpty())
    }

    @Test fun `a sink that fails mid-write reports it rather than truncating silently`() = runTest {
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("no space")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("no space")
        }

        assertThrows(IOException::class.java) {
            runBlocking {
                GpxArchive.write(
                    sink = failing,
                    // Enough points to overflow the buffer, so the failure lands during the write
                    // rather than only at close.
                    refs = List(20) { ref("a$it", DAY) },
                    load = { activity(it, points = ride(count = 500)) },
                    name = ::nameOf,
                )
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun ref(id: String, startTime: Long) = ActivityRef(id = id, startTime = startTime)

    /**
     * The real display-edge naming, as the Settings screen passes it in — pinned to UTC so the
     * stamp does not depend on where the test runs.
     */
    private fun nameOf(slug: String, startTime: Long) =
        Formatter().exportFileName(slug, startTime, "gpx", ZoneOffset.UTC)

    private fun activity(
        id: String,
        name: String = "Ride $id",
        points: List<RawPoint> = ride(),
        samples: List<SensorSample> = emptyList(),
    ) = Activity(
        id = UUID.nameUUIDFromBytes(id.toByteArray()),
        type = ActivityType.CYCLING,
        name = name,
        points = points,
        samples = samples,
    )

    private fun ride(count: Int = 10, segment: Int = 0, startSecond: Long = 0) =
        List(count) { i ->
            RawPoint(
                latitude = 50.0 + i * 0.0001,
                longitude = 14.0 + i * 0.0001,
                altitude = 200.0,
                accuracy = 5.0,
                time = Instant.fromEpochSeconds(startSecond + i),
                segmentIndex = segment,
            )
        }

    /** The archive read back: entry name to its GPX document, in the order they were written. */
    private fun entries(out: ByteArrayOutputStream): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }
}
