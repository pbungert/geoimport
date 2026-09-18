package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.core.model.distanceMeters
import java.time.Duration
import java.time.Instant

/**
 * Matches photo capture times against a track.
 *
 * Inside a segment a photo is interpolated between its bracketing points,
 * however far apart those are: the device was recording the whole time, so a
 * gap is a dropout and the two points really do bracket where it went.
 * [Fix.gapMeters] reports how far, so a caller can tell a position that was
 * measured from one that was inferred across a dropout.
 *
 * Between segments — a paused recorder, or two separate outings merged into
 * one track — nothing was recorded, and a straight line from where one segment
 * ended to where the next began is not a route anybody took. There a photo is
 * placed only if it falls within [tolerance] of a segment's end, which is the
 * same rule that has always applied past the ends of the track as a whole.
 */
class Geotagger(track: Track, private val tolerance: Duration) {

    /** Convenience for a single continuous recording, chiefly for tests. */
    constructor(points: List<TrackPoint>, tolerance: Duration) : this(Track.of(points), tolerance)

    private val segments = track.segments
        .filter { it.isNotEmpty() }
        .map { it.sortedBy { point -> point.time } }

    /**
     * [gapMeters] is the distance between the two points the fix was
     * interpolated between, which bounds how far off it can be. Null when the
     * fix came from a single point rather than a pair.
     */
    data class Fix(val point: TrackPoint, val gapMeters: Double?)

    fun locate(time: Instant): TrackPoint? = resolve(time)?.point

    fun resolve(time: Instant): Fix? {
        var best: Fix? = null
        var bestSpan: Duration? = null
        var nearestEnd: TrackPoint? = null
        var nearestDistance: Duration? = null

        for (points in segments) {
            val idx = points.firstAtOrAfter(time)
            if (idx == 0 || idx == points.size) {
                // Before or after this segment: a candidate only via tolerance.
                val end = if (idx == 0) points.first() else points.last()
                val away = Duration.between(time, end.time)
                    .let { if (it.isNegative) it.negated() else it }
                if (away <= tolerance && (nearestDistance == null || away < nearestDistance)) {
                    nearestEnd = end
                    nearestDistance = away
                }
                continue
            }
            // Inside this segment. Two overlapping recordings can both bracket
            // the same moment; the one that sampled it more closely is the
            // better evidence, so the shortest bracket wins.
            val a = points[idx - 1]
            val b = points[idx]
            val span = Duration.between(a.time, b.time)
            if (bestSpan == null || span < bestSpan) {
                bestSpan = span
                best = Fix(interpolate(a, b, time), distanceMeters(a, b))
            }
        }

        return best ?: nearestEnd?.let { Fix(it, null) }
    }

    /** Index of the first point at or after [time], or `size` when there is none. */
    private fun List<TrackPoint>.firstAtOrAfter(time: Instant): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (this[mid].time.isBefore(time)) low = mid + 1 else high = mid
        }
        return low
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
