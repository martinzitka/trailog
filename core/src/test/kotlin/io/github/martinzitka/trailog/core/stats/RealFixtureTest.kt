package io.github.martinzitka.trailog.core.stats

import io.github.martinzitka.trailog.core.filter.Filters
import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.model.ActivityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit

/**
 * Validation against real reference tracks in `resources/fixtures/`, with figures published by
 * independent apps (CLAUDE.md acceptance criteria: a real M0 ride and an existing-app export
 * with known-correct statistics, distance within 1%, elevation within 5%).
 *
 * Both fixtures are the same ~38 km ride, **anonymized by a constant longitude shift** — a
 * translation preserves haversine distance and elevation exactly (latitude is unchanged, so the
 * per-hop Δlon and both latitudes are unchanged), so the published figures below still apply,
 * while the real location is hidden. This is why the fixtures are shifted rather than
 * end-truncated: truncation would break the distance reference and still leave the route middle
 * exposed.
 *
 * Reference figures for the route:
 * - Distance: Sports Tracker 38.03 km, mapy.cz 38.3 km.
 * - Elevation: mapy.cz DEM 466 m; Sports Tracker (GPS, lightly smoothed) 552 m. These disagree
 *   ~18%, which is normal for elevation and worse here because the test phone has no barometer.
 *   The assertion is therefore a plausibility band bracketing both references, not a tight bound;
 *   the elevation *algorithm* is pinned-down precisely by [ElevationTest]. See ADR 0006.
 */
class RealFixtureTest {

    private fun load(name: String): String =
        RealFixtureTest::class.java.getResourceAsStream("/fixtures/$name")
            ?.readBytes()?.decodeToString()
            ?: error("fixture $name not found on the test classpath")

    private fun within(actual: Double, reference: Double, fraction: Double): Boolean =
        kotlin.math.abs(actual - reference) / reference <= fraction

    @Test
    fun `real M0 ride distance is within 1 percent of both published references`() {
        val segments = Gpx.read(load("ride_reference.gpx")).single().segments()
        val distanceKm = Statistics.distance(segments) / 1000.0
        assertTrue(within(distanceKm, 38.03, 0.01), "distance $distanceKm km vs Sports Tracker 38.03")
        assertTrue(within(distanceKm, 38.3, 0.01), "distance $distanceKm km vs mapy.cz 38.3")
    }

    @Test
    fun `real M0 ride is one clean segment covering the full elapsed time`() {
        val segments = Gpx.read(load("ride_reference.gpx")).single().segments()
        assertEquals(1, segments.size, "session 15 was a gap-free ride")
        val elapsedMin = Statistics.elapsedTime(segments).toDouble(DurationUnit.MINUTES)
        assertTrue(elapsedMin in 100.0..110.0, "elapsed $elapsedMin min")
        // Almost all of it was moving — a short-stop ride, not a long café break.
        val movingMin = Statistics.movingTime(segments, ActivityType.CYCLING).toDouble(DurationUnit.MINUTES)
        assertTrue(movingMin / elapsedMin > 0.9, "moving/elapsed ${movingMin / elapsedMin}")
    }

    @Test
    fun `filtering a clean ride changes distance negligibly`() {
        // Sub-3 m accuracy, no spikes: the default filter should be nearly a no-op. This guards
        // against an over-aggressive filter silently eating good points on good data.
        val raw = Gpx.read(load("ride_reference.gpx")).single().segments()
        val filtered = Filters.default(ActivityType.CYCLING).apply(raw)
        val rawKept = raw.sumOf { it.points.size }
        val filteredKept = filtered.sumOf { it.points.size }
        assertTrue(filteredKept >= rawKept * 0.999, "filter dropped too many: $rawKept -> $filteredKept")
        assertTrue(
            within(Statistics.distance(filtered), Statistics.distance(raw), 0.005),
            "filtered distance drifted from raw by more than 0.5%",
        )
    }

    @Test
    fun `position smoothing barely changes distance on a clean ride`() {
        // Justifies keeping smoothing opt-in (ADR 0007): on sub-3 m-accuracy data it is nearly
        // a no-op, so it must not be enabled by default on the strength of noisy-track reasoning.
        val raw = Gpx.read(load("ride_reference.gpx")).single().segments()
        val smoothed = Filters.smoothed(ActivityType.CYCLING, window = 5).apply(raw)
        assertTrue(
            within(Statistics.distance(smoothed), Statistics.distance(raw), 0.01),
            "smoothing moved clean-ride distance by more than 1%",
        )
    }

    @Test
    fun `real M0 ride elevation is in the plausible reference band`() {
        val segments = Gpx.read(load("ride_reference.gpx")).single().segments()
        val e = Elevation.change(segments) // production default (window 7, threshold 6)
        // Brackets mapy DEM (466) and Sports Tracker GPS (552); well below the naive-sum ~920.
        assertTrue(e.gain in 430.0..560.0, "gain ${e.gain} outside the reference band")
        assertTrue(e.loss in 430.0..560.0, "loss ${e.loss} outside the reference band")
        assertTrue(kotlin.math.abs(e.gain - e.loss) < 30.0, "loop should be roughly symmetric")
    }

    @Test
    fun `sports tracker export parses and reproduces its own published distance`() {
        // A real third-party file our reader must tolerate: it *declares* the Garmin
        // TrackPointExtension namespace on its root element but uses no extension element
        // anywhere — no heart rate, no cadence. Worth stating precisely, because M2 turns on
        // whether the Sports Tracker history carries sensor data worth a FIT decoder, and this
        // fixture is the only evidence in the repo (ADR 0022).
        // Our parser + haversine should land on Sports Tracker's own 38.03 km.
        val track = Gpx.read(load("sportstracker_reference.gpx")).single()
        val segments = track.segments()
        assertTrue(track.points.size > 3000, "expected a dense ST track, got ${track.points.size}")
        val distanceKm = Statistics.distance(segments) / 1000.0
        assertTrue(within(distanceKm, 38.03, 0.01), "distance $distanceKm km vs Sports Tracker 38.03")
    }

    @Test
    fun `sports tracker export elevation is in the plausible reference band`() {
        val segments = Gpx.read(load("sportstracker_reference.gpx")).single().segments()
        val e = Elevation.change(segments)
        assertTrue(e.gain in 400.0..580.0, "gain ${e.gain} outside the reference band")
        assertTrue(e.loss in 400.0..580.0, "loss ${e.loss} outside the reference band")
    }
}
