package com.pbungert.geoimport.core.track

import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.core.model.distanceMeters
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Removes fixes that are *misleading* from a recorded track, and leaves the
 * ones that are merely imprecise.
 *
 * The distinction is the whole point. A receiver under a cliff or indoors
 * reports positions that are vague but honest, and throwing those away opens a
 * hole in the track that [com.pbungert.geoimport.core.geotag.Geotagger] then
 * draws a straight line across - which is worse than the vague point it
 * replaced. What has to go is the fix that is confidently wrong: the one that
 * puts the device a quarter-kilometre away and brings it straight back.
 *
 * Everything here works on geometry alone, so it applies to a GPX or KML file
 * from any source. Where a track carries what the receiver said about its own
 * fixes - accuracy, vertical accuracy, measured speed - that evidence sharpens
 * the result, but nothing depends on it being there.
 *
 * Judging happens per segment. A gap between segments is a stretch that was
 * never recorded, so the points either side of it are not neighbours and
 * nothing may be concluded from the distance between them.
 */
object TrackCleanup {

    /**
     * Defaults are set from two real recordings - one urban day-long track and
     * one hour-long walk through a degraded stretch - where they find every
     * spike present and nothing else.
     */
    data class Options(
        /**
         * How far a point must sit from *both* neighbours before its position
         * is doubted. Below this, a disagreement is ordinary scatter.
         */
        val minExcursionMeters: Double = 60.0,
        /**
         * How close the two neighbours must be *to each other*, as a fraction
         * of the excursion, for the point between them to be a detour nobody
         * took. This is what separates a spike from a corner.
         */
        val returnRatio: Double = 0.4,
        /** [minExcursionMeters] for altitude, which is noisier than position. */
        val minEleExcursionMeters: Double = 100.0,
        /** [returnRatio] for altitude. */
        val eleReturnRatio: Double = 0.4,
        /**
         * Vertical accuracy above which an altitude is not worth keeping. GNSS
         * altitude is poor even when the position is good, so this discards the
         * altitude and keeps the fix.
         */
        val untrustedEleAccuracyMeters: Double = 50.0,
        /**
         * How far the receiver's own speed may differ from the speed implied by
         * the distance covered before the fix is called into question.
         */
        val speedDisagreementMetersPerSecond: Double = 5.0,
        /**
         * Removing a point makes its neighbours adjacent, which can reveal a
         * second spike behind the first. Passes are repeated until nothing more
         * is found, up to this many, so a pathological run cannot eat a track.
         */
        val maxPasses: Int = 3,
    )

    enum class Finding {
        /** Far from both neighbours, which are close to each other. Removed. */
        POSITION_EXCURSION,

        /** The same shape in altitude. The altitude is dropped, the fix kept. */
        ELEVATION_EXCURSION,

        /** The receiver rated its own altitude as worthless. Altitude dropped. */
        ELEVATION_UNTRUSTED,

        /**
         * The receiver's measured speed contradicts the ground it says it
         * covered. Recorded, never acted on: a chord across a curve
         * understates real distance, so this alone does not convict a fix.
         */
        SPEED_CONTRADICTION,
    }

    /** One judgement about one fix, for logging and for tests. */
    data class Note(val time: Instant, val finding: Finding, val detail: String)

    data class Result(val track: Track, val notes: List<Note>) {
        fun count(finding: Finding) = notes.count { it.finding == finding }

        /** Fixes dropped outright, as opposed to ones that lost an altitude. */
        val removedCount get() = count(Finding.POSITION_EXCURSION)
    }

    fun clean(track: Track, options: Options = Options()): Result {
        val notes = mutableListOf<Note>()
        val segments = track.segments.map { cleanSegment(it, options, notes) }
        return Result(Track(segments), notes.sortedBy { it.time })
    }

    private fun cleanSegment(
        points: List<TrackPoint>,
        options: Options,
        notes: MutableList<Note>,
    ): List<TrackPoint> {
        // Judged on the points as they came in, so that it corroborates what
        // the geometry goes on to remove instead of only describing what is
        // left. It also reaches fixes the excursion test cannot see, such as
        // one at the very start of a segment with nothing before it.
        flagSpeedContradictions(points, options, notes)

        var current = points
        var pass = 0
        while (pass < options.maxPasses) {
            val next = dropPositionExcursions(current, options, notes)
            if (next.size == current.size) break
            current = next
            pass++
        }
        return cleanElevations(current, options, notes)
    }

    /**
     * A spike is not simply a long step - a long step is how a track records
     * moving. It is a long step *out* followed by a long step *back*, with the
     * two ends agreeing about where the device was. Nobody made that trip.
     *
     * Only single fixes are caught. Two bad ones in a row shield each other,
     * because each has the other for a neighbour, and seeing through that means
     * judging runs of points - at which point a real detour down a dead end and
     * back has the same shape, and telling them apart needs more than geometry.
     * Every spike in the recordings this was built from is a single fix, which
     * is exactly what makes the one-point rule safe: nobody travels four
     * hundred metres out and back between two consecutive samples.
     */
    private fun dropPositionExcursions(
        points: List<TrackPoint>,
        options: Options,
        notes: MutableList<Note>,
    ): List<TrackPoint> {
        if (points.size < 3) return points
        val drop = BooleanArray(points.size)
        for (i in 1 until points.size - 1) {
            val out = distanceMeters(points[i - 1], points[i])
            val back = distanceMeters(points[i], points[i + 1])
            if (out < options.minExcursionMeters || back < options.minExcursionMeters) continue
            val across = distanceMeters(points[i - 1], points[i + 1])
            if (across > options.returnRatio * max(out, back)) continue
            drop[i] = true
        }

        // A sound fix sitting between two spikes looks like a spike itself:
        // its neighbours are the two bad ones, and they agree with each other.
        // Nothing is convicted on the testimony of two suspects, so a point
        // whose evidence is entirely other candidates keeps its place.
        for (i in 1 until points.size - 1) {
            if (drop[i] && drop[i - 1] && drop[i + 1]) drop[i] = false
        }

        if (drop.none { it }) return points
        for (i in points.indices) {
            if (!drop[i]) continue
            val out = distanceMeters(points[i - 1], points[i])
            val back = distanceMeters(points[i], points[i + 1])
            val across = distanceMeters(points[i - 1], points[i + 1])
            notes += Note(
                points[i].time,
                Finding.POSITION_EXCURSION,
                "${out.toInt()} m out, ${back.toInt()} m back, " +
                    "neighbours ${across.toInt()} m apart",
            )
        }
        return points.filterIndexed { i, _ -> !drop[i] }
    }

    /**
     * Altitude is judged separately from position because the two fail
     * separately: a fix can be where it says it is and still claim to be three
     * hundred metres up. Only the altitude is dropped - [TrackPoint.ele] is
     * nullable and the geotagger already writes a fix without one - so the
     * position survives to hold the track together.
     */
    private fun cleanElevations(
        points: List<TrackPoint>,
        options: Options,
        notes: MutableList<Note>,
    ): List<TrackPoint> = points.mapIndexed { i, point ->
        val ele = point.ele ?: return@mapIndexed point

        val eleAccuracy = point.eleAccuracy
        if (eleAccuracy != null && eleAccuracy > options.untrustedEleAccuracyMeters) {
            notes += Note(
                point.time,
                Finding.ELEVATION_UNTRUSTED,
                "vertical accuracy ${eleAccuracy.toInt()} m",
            )
            return@mapIndexed point.copy(ele = null)
        }

        // Neighbours are read from the segment as it came in, so one bad
        // altitude cannot drag the verdict on the next.
        val before = points.getOrNull(i - 1)?.ele
        val after = points.getOrNull(i + 1)?.ele
        if (before != null && after != null) {
            val up = abs(ele - before)
            val down = abs(ele - after)
            val across = abs(after - before)
            if (up >= options.minEleExcursionMeters &&
                down >= options.minEleExcursionMeters &&
                across <= options.eleReturnRatio * max(up, down)
            ) {
                notes += Note(
                    point.time,
                    Finding.ELEVATION_EXCURSION,
                    "${ele.toInt()} m between ${before.toInt()} m and ${after.toInt()} m",
                )
                return@mapIndexed point.copy(ele = null)
            }
        }
        point
    }

    /**
     * Two independent readings of the same quantity: what the receiver measured
     * from Doppler shift, and what the distance to the previous fix implies.
     * They agree on a healthy fix and part company on a broken one, which is a
     * tell that needs no assumption about how fast the device was travelling -
     * the thing no fixed speed limit can survive on a track that both walks and
     * rides.
     */
    private fun flagSpeedContradictions(
        points: List<TrackPoint>,
        options: Options,
        notes: MutableList<Note>,
    ) {
        for (i in 1 until points.size) {
            val reported = points[i].speed ?: continue
            val seconds =
                Duration.between(points[i - 1].time, points[i].time).toMillis() / 1000.0
            if (seconds <= 0) continue
            val geometric = distanceMeters(points[i - 1], points[i]) / seconds
            if (abs(reported - geometric) <= options.speedDisagreementMetersPerSecond) continue
            notes += Note(
                points[i].time,
                Finding.SPEED_CONTRADICTION,
                String.format(
                    Locale.US,
                    "receiver says %.1f m/s, positions say %.1f m/s", reported, geometric,
                ),
            )
        }
    }
}
