package io.github.martinzitka.trailog.core.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geodesy primitives. Pure functions over SI units (degrees in, metres out). */
object Geo {

    /**
     * Mean Earth radius in metres (IUGG). The haversine formula assumes a sphere; over the
     * short segments between GPS fixes the error from ignoring the ellipsoid is far below
     * the 1% distance tolerance in CLAUDE.md.
     */
    const val EARTH_RADIUS_METERS: Double = 6_371_008.8

    /**
     * Great-circle distance in metres between two WGS84 coordinates, via the haversine
     * formula. This is the sanctioned distance primitive; total distance is the sum of
     * haversine hops over filtered points, never across a segment boundary.
     */
    fun haversine(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a).coerceAtMost(1.0))
    }
}
