package com.pbungert.geoimport.core.track

import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.core.model.distanceMeters
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
 * Everything here works from positions and their timestamps alone, so it
 * applies to a GPX or KML file from any source, recorded at any interval.
 * Where a track carries what the receiver said about its own fixes - accuracy,
 * vertical accuracy, measured speed - that evidence sharpens the result, but
 * nothing depends on it being there.
 *
 * Judging happens per segment. A gap between segments is a stretch that was
 * never recorded, so the points either side of it are not neighbours and
 * nothing may be concluded from the distance between them.
 */
object TrackCleanup {

    /** Turns a median absolute deviation into something that reads as a sigma. */
    private const val MAD_TO_SIGMA = 1.4826

    /**
     * Defaults are set from two real recordings - one urban day-long track and
     * one hour-long walk through a degraded stretch - where they find every
     * spike present and nothing else.
     *
     * The pace thresholds are held to the same recordings: on both, they
     * convict exactly the fixes that the geometry alone convicted, and nothing
     * further. On older tracks sampled a minute apart they are the more
     * cautious of the two, keeping the sixty-to-ninety-metre excursions that
     * somebody on foot could have made and removing the ones nobody could.
     */
    data class Options(
        /**
         * How far a point must sit from *both* neighbours before its position
         * is doubted, in a file that says nothing about its own accuracy.
         * Below this, a disagreement is ordinary scatter.
         */
        val minExcursionMeters: Double = 60.0,
        /**
         * The same floor for a file that does carry accuracies, in multiples
         * of the worst one the three fixes reported. A receiver claiming five
         * metres and landing four hundred away has contradicted itself; one
         * claiming a hundred metres has not.
         */
        val accuracyFloorFactor: Double = 4.0,
        /**
         * Lower bound on that floor. A fix accurate to a metre still wanders
         * by a few, and there is nothing to gain from prosecuting a wobble
         * smaller than the photo placement it would change.
         */
        val minAccuracyFloorMeters: Double = 15.0,
        /**
         * How close the two neighbours must be *to each other*, as a fraction
         * of the excursion, for the point between them to be a detour nobody
         * took. This is what separates a spike from a corner.
         */
        val returnRatio: Double = 0.4,
        /**
         * How far above the ordinary pace both steps must be, in robust
         * standard deviations - so it reads on the same scale as a sigma.
         */
        val paceMultiple: Double = 5.0,
        /** How many steps either side the pace is read from. */
        val paceWindowSteps: Int = 10,
        /**
         * How far either side in time, which is the bound that bites when a
         * file is sampled coarsely. Ten steps of a five-minute recording reach
         * across an hour, and an hour of a photography day is a walk, a drive
         * and a stop - none of which says anything about the others.
         */
        val paceWindow: Duration = Duration.ofMinutes(10),
        /**
         * Floor on the spread of that window. A recorder left standing still
         * produces steps that barely differ at all, and without a floor every
         * twitch is infinitely many deviations away from nothing.
         *
         * With [paceMultiple] it also sets the slowest excursion that can ever
         * be convicted - 1.5 m/s, which is about as fast as an out-and-back on
         * foot gets. Below that, a fix that wandered off and came back while
         * the device sat still is indistinguishable from its owner doing the
         * same, and the fix keeps its place.
         */
        val minPaceSpreadMetersPerSecond: Double = 0.3,
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

    /**
     * One judgement about one fix. Carries the fix itself, not just when it
     * happened, so a caller can draw what was removed rather than having to
     * look it back up by timestamp.
     */
    data class Note(val point: TrackPoint, val finding: Finding, val detail: String) {
        val time: Instant get() = point.time
    }

    data class Result(val track: Track, val notes: List<Note>) {
        fun count(finding: Finding) = notes.count { it.finding == finding }

        /** Fixes dropped outright, as opposed to ones that lost an altitude. */
        val removedCount get() = count(Finding.POSITION_EXCURSION)

        /** The dropped fixes themselves, in time order. */
        val removedPoints: List<TrackPoint>
            get() = notes.filter { it.finding == Finding.POSITION_EXCURSION }.map { it.point }
    }

    fun clean(track: Track, options: Options = Options()): Result {
        val notes = mutableListOf<Note>()
        // A file from elsewhere does not promise time order, and every
        // judgement below rests on a point's neighbours being its neighbours.
        val segments = track.segments.map {
            cleanSegment(it.sortedBy { point -> point.time }, options, notes)
        }
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
     * two ends agreeing about where the device was, reached faster than this
     * stretch of track was being covered either side of it.
     *
     * That last clause is what makes the rule portable. A distance on its own
     * only means something at the interval it was chosen for: four hundred
     * metres between fixes is absurd at thirty seconds and unremarkable at
     * five, and a file from elsewhere may be sampled at either. Dividing by
     * the time between the fixes takes the interval out of it, and reading
     * what counts as fast from the surrounding track takes out the difference
     * between walking and driving - including on a track that does both,
     * because the window follows the point being judged.
     *
     * The pace is read from the steps around the fix that nothing is suspected
     * of, which keeps a run of bad ones from vouching for each other and keeps
     * a genuine detour from being judged against the walk that preceded the
     * drive.
     *
     * Only single fixes are caught. Two bad ones in a row shield each other,
     * because each has the other for a neighbour, and seeing through that
     * means judging runs of points - at which point a real detour down a dead
     * end and back has the same shape, and telling them apart needs more than
     * one track.
     *
     * Sampled coarsely enough, a spike and a detour stop being distinguishable
     * at all: four hundred metres out and back is a walk somebody took if the
     * fixes are five minutes apart. The pace test declines to convict there,
     * which is the honest answer - the evidence for the verdict is not in the
     * file.
     */
    private fun dropPositionExcursions(
        points: List<TrackPoint>,
        options: Options,
        notes: MutableList<Note>,
    ): List<TrackPoint> {
        if (points.size < 3) return points
        val steps = DoubleArray(points.size - 1) { stepSpeed(points[it], points[it + 1]) }

        // Geometry proposes. Whether a fix far from both neighbours is a spike
        // or a place somebody went is not a question its shape can answer.
        val suspect = BooleanArray(points.size)
        for (i in 1 until points.size - 1) {
            if (!steps[i - 1].isFinite() || !steps[i].isFinite()) continue
            val out = distanceMeters(points[i - 1], points[i])
            val back = distanceMeters(points[i], points[i + 1])
            val floor = excursionFloor(points, i, options)
            if (out < floor || back < floor) continue
            val across = distanceMeters(points[i - 1], points[i + 1])
            if (across > options.returnRatio * max(out, back)) continue
            suspect[i] = true
        }
        if (suspect.none { it }) return points

        // The pace disposes.
        val drop = BooleanArray(points.size)
        for (i in points.indices) {
            if (!suspect[i]) continue
            val limit = localPace(points, steps, suspect, i, options).limit
            // Either leg is enough. Getting there was impossible or it was
            // not, and however long the way back took cannot make the way out
            // possible - which matters, because a dropout on one side of a
            // spike leaves that side looking like an ordinary stroll.
            if (max(steps[i - 1], steps[i]) >= limit) drop[i] = true
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
                points[i],
                Finding.POSITION_EXCURSION,
                "${out.toInt()} m out, ${back.toInt()} m back, " +
                    "neighbours ${across.toInt()} m apart, " +
                    String.format(
                        Locale.US,
                        "at %.1f m/s where %.1f m/s was ordinary",
                        max(steps[i - 1], steps[i]),
                        localPace(points, steps, suspect, i, options).ordinary,
                    ),
            )
        }
        return points.filterIndexed { i, _ -> !drop[i] }
    }

    /** Metres per second between two fixes, or NaN when time did not pass. */
    private fun stepSpeed(from: TrackPoint, to: TrackPoint): Double {
        val seconds = Duration.between(from.time, to.time).toMillis() / 1000.0
        return if (seconds <= 0) Double.NaN else distanceMeters(from, to) / seconds
    }

    /**
     * How far a fix has to be from both neighbours before the distance alone
     * is worth anything. Where the receiver rated its own fixes, its rating
     * sets the bar; where it said nothing, one flat number has to stand in.
     */
    private fun excursionFloor(points: List<TrackPoint>, i: Int, options: Options): Double {
        val worst = listOfNotNull(
            points[i - 1].accuracy, points[i].accuracy, points[i + 1].accuracy,
        ).maxOrNull() ?: return options.minExcursionMeters
        return max(worst * options.accuracyFloorFactor, options.minAccuracyFloorMeters)
    }

    /** What this stretch of track was doing, and what would be out of character. */
    private data class Pace(val ordinary: Double, val limit: Double)

    /**
     * The ordinary pace around a fix, read from the steps near it that no
     * suspicion attaches to.
     *
     * Near in both senses: within a few steps and within a few minutes. The
     * second matters because the first stops meaning anything once a file is
     * sampled in minutes rather than seconds.
     *
     * Leaving the suspects out is what makes the estimate hold up. A bad fix
     * makes both of its own steps look fast, so a run of them can drag an
     * average clean past the very speeds it is meant to catch - and on a short
     * segment, two of them are most of the evidence there is. Excluding every
     * step that touches a suspected fix leaves only the track's own testimony,
     * and a median and median absolute deviation over that shrug off whatever
     * is left.
     */
    private fun localPace(
        points: List<TrackPoint>,
        steps: DoubleArray,
        suspect: BooleanArray,
        i: Int,
        options: Options,
    ): Pace {
        val centre = points[i].time.toEpochMilli()
        val reach = options.paceWindow.toMillis()
        fun near(at: Int) = abs(points[at].time.toEpochMilli() - centre) <= reach
        val window = (max(0, i - 1 - options.paceWindowSteps)..
            min(steps.size - 1, i + options.paceWindowSteps))
            .filter { steps[it].isFinite() && !suspect[it] && !suspect[it + 1] }
            .filter { near(it) || near(it + 1) }
            .map { steps[it] }
            .sorted()
        // Nothing unsuspected to compare against, which a three-point segment
        // guarantees. The spread floor then stands in for a pace, and says
        // something about physics rather than about this track: out and back
        // at a brisk walk between two fixes is a trip somebody could make.
        if (window.isEmpty()) {
            return Pace(0.0, options.paceMultiple * options.minPaceSpreadMetersPerSecond)
        }
        val ordinary = median(window)
        val spread = max(
            MAD_TO_SIGMA * median(window.map { abs(it - ordinary) }.sorted()),
            options.minPaceSpreadMetersPerSecond,
        )
        return Pace(ordinary, ordinary + options.paceMultiple * spread)
    }

    private fun median(sorted: List<Double>): Double =
        if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

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
                point,
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
                    point,
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
                points[i],
                Finding.SPEED_CONTRADICTION,
                String.format(
                    Locale.US,
                    "receiver says %.1f m/s, positions say %.1f m/s", reported, geometric,
                ),
            )
        }
    }
}
