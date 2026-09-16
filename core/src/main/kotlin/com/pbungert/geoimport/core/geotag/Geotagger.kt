package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Matches photo capture times against a track. Positions between two track
 * points are linearly interpolated; outside the track's time range the nearest
 * endpoint is used if it is within [tolerance].
 *
 * Note that [tolerance] only clamps extrapolation past the ends of the track.
 * Inside the range a photo is always interpolated between its bracketing
 * points, however far apart those are. [Fix.gapMeters] reports how far, so a
 * caller can tell a position that was measured from one that was inferred
 * across a recording dropout.
 */
class Geotagger(track: List<TrackPoint>, private val tolerance: Duration) {

    private val points = track.sortedBy { it.time }

    /**
     * [gapMeters] is the distance between the two points the fix was
     * interpolated between, which bounds how far off it can be. Null when the
     * fix came from a single point rather than a pair.
     */
    data class Fix(val point: TrackPoint, val gapMeters: Double?)

    fun locate(time: Instant): TrackPoint? = resolve(time)?.point

    fun resolve(time: Instant): Fix? {
        if (points.isEmpty()) return null
        val idx = points.indexOfFirst { !it.time.isBefore(time) }
        return when (idx) {
            -1 -> points.last()
                .takeIf { Duration.between(it.time, time) <= tolerance }
                ?.let { Fix(it, null) }
            0 -> points.first()
                .takeIf { Duration.between(time, points.first().time) <= tolerance }
                ?.let { Fix(it, null) }
            else -> {
                val a = points[idx - 1]
                val b = points[idx]
                Fix(interpolate(a, b, time), distanceMeters(a, b))
            }
        }
    }

    private fun distanceMeters(a: TrackPoint, b: TrackPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    private fun interpolate(a: TrackPoint, b: TrackPoint, time: Instant): TrackPoint {
        val span = Duration.between(a.time, b.time).toMillis()
        if (span == 0L) return a
        val f = Duration.between(a.time, time).toMillis().toDouble() / span
        return TrackPoint(
            time = time,
            lat = a.lat + (b.lat - a.lat) * f,
            lon = a.lon + (b.lon - a.lon) * f,
            ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * f else a.ele ?: b.ele,
        )
    }
}
