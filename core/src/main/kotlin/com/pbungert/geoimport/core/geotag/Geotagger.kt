package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import java.time.Duration
import java.time.Instant

/**
 * Matches photo capture times against a track. Positions between two track
 * points are linearly interpolated; outside the track's time range the nearest
 * endpoint is used if it is within [tolerance].
 *
 * Note that [tolerance] only clamps extrapolation past the ends of the track.
 * Inside the range a photo is always interpolated between its bracketing
 * points, however far apart in time those are.
 */
class Geotagger(track: List<TrackPoint>, private val tolerance: Duration) {

    private val points = track.sortedBy { it.time }

    fun locate(time: Instant): TrackPoint? {
        if (points.isEmpty()) return null
        val idx = points.indexOfFirst { !it.time.isBefore(time) }
        return when (idx) {
            -1 -> points.last().takeIf { Duration.between(it.time, time) <= tolerance }
            0 -> points.first().takeIf { Duration.between(time, points.first().time) <= tolerance }
            else -> interpolate(points[idx - 1], points[idx], time)
        }
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
