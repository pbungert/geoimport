package com.pbungert.geoimport.core.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * Great-circle distance between two positions, in metres.
 *
 * Haversine on a sphere: at the scale of a day's walking the difference from a
 * proper ellipsoid is a few metres, which is well inside what the fixes
 * themselves are worth.
 */
fun distanceMeters(a: TrackPoint, b: TrackPoint): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    // min() guards the tiny floating-point overshoot past 1 that would make
    // asin return NaN for two points at the same place.
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
}

/**
 * How far the track ran, in metres. Summed per segment, so a pause or a gap
 * between two merged recordings does not add the straight line across it.
 */
val Track.distanceMeters: Double
    get() = segments.sumOf { segment ->
        segment.zipWithNext { a, b -> distanceMeters(a, b) }.sum()
    }
