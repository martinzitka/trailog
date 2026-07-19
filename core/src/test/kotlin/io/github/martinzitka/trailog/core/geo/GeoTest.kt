package io.github.martinzitka.trailog.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {

    @Test
    fun `identical points are zero distance`() {
        assertEquals(0.0, Geo.haversine(50.0, 14.0, 50.0, 14.0), 1e-9)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        // A degree of latitude is ~111.2 km everywhere. Assert within 0.1%.
        val d = Geo.haversine(50.0, 14.0, 51.0, 14.0)
        assertEquals(111_195.0, d, 111.0)
    }

    @Test
    fun `known city pair matches published great-circle distance`() {
        // Prague (Old Town Square) to Brno (Freedom Square). Published great-circle
        // distance is ~184.6 km; assert within 1% (the CLAUDE.md distance tolerance).
        val prague = doubleArrayOf(50.0870, 14.4207)
        val brno = doubleArrayOf(49.1951, 16.6068)
        val d = Geo.haversine(prague[0], prague[1], brno[0], brno[1])
        val expected = 184_600.0
        assertTrue(
            kotlin.math.abs(d - expected) / expected < 0.01,
            "expected ~${expected}m within 1%, got ${d}m",
        )
    }

    @Test
    fun `distance is symmetric`() {
        val ab = Geo.haversine(50.0, 14.0, 49.0, 16.0)
        val ba = Geo.haversine(49.0, 16.0, 50.0, 14.0)
        assertEquals(ab, ba, 1e-6)
    }
}
