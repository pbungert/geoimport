package com.pbungert.geoimport.core.track

import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.core.model.distanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The spikes these tests describe are taken from two real recordings, so the
 * shapes asserted here are the ones the recorder actually produces rather than
 * ones invented to suit the algorithm.
 */
class TrackCleanupTest {

    private val start = Instant.parse("2026-09-20T14:00:00Z")

    /** A point [seconds] in, [northMeters]/[eastMeters] from a fixed origin. */
    private fun at(
        seconds: Long,
        northMeters: Double = 0.0,
        eastMeters: Double = 0.0,
        ele: Double? = 550.0,
        accuracy: Double? = null,
        eleAccuracy: Double? = null,
        speed: Double? = null,
    ) = TrackPoint(
        time = start.plusSeconds(seconds),
        lat = 46.95 + northMeters / 111_320.0,
        lon = 7.44 + eastMeters / (111_320.0 * 0.6820), // cos(46.95 degrees)
        ele = ele,
        accuracy = accuracy,
        eleAccuracy = eleAccuracy,
        speed = speed,
    )

    private fun clean(vararg points: TrackPoint) = TrackCleanup.clean(Track.of(points.toList()))

    @Test
    fun judgesAFileFromElsewhereInTimeOrder() {
        // A foreign GPX may list its points in any order. Read as written this
        // straight stretch looks like a spike; read in time order it is a walk.
        val result = TrackCleanup.clean(
            Track(
                listOf(
                    listOf(
                        at(0), at(90, eastMeters = 300.0),
                        at(30, eastMeters = 100.0), at(60, eastMeters = 200.0),
                    )
                )
            )
        )
        assertEquals(0, result.removedCount)
        assertEquals(
            listOf(0L, 30L, 60L, 90L),
            result.track.segments.single().map { it.time.epochSecond - start.epochSecond },
        )
    }

    @Test
    fun removesAPositionSpikeAndKeepsItsNeighbours() {
        // The shape of the 15:44:59 fix: 400 m out, 400 m back, while the
        // points either side of it are a few metres apart.
        val result = clean(
            at(0), at(30, eastMeters = 20.0),
            at(60, eastMeters = 420.0),
            at(90, eastMeters = 37.0), at(120, eastMeters = 55.0),
        )
        assertEquals(1, result.removedCount)
        assertEquals(4, result.track.size)
        assertEquals(
            start.plusSeconds(60),
            result.notes.single { it.finding == TrackCleanup.Finding.POSITION_EXCURSION }.time,
        )
    }

    @Test
    fun keepsAGenuineLongStep() {
        // Moving steadily east: every step is long, but nothing comes back.
        val result = clean(
            at(0), at(30, eastMeters = 300.0), at(60, eastMeters = 600.0),
            at(90, eastMeters = 900.0), at(120, eastMeters = 1200.0),
        )
        assertEquals(0, result.removedCount)
        assertEquals(5, result.track.size)
    }

    @Test
    fun keepsOrdinaryScatterWhileStandingStill() {
        val result = clean(
            at(0), at(30, northMeters = 12.0), at(60, eastMeters = -9.0),
            at(90, northMeters = -7.0), at(120, eastMeters = 11.0),
        )
        assertEquals(0, result.removedCount)
        assertEquals(5, result.track.size)
    }

    /**
     * The neighbours of a spike have to be judged against each other, not
     * against the spike: a corner is two long steps too, but its ends are far
     * apart and it is a route somebody walked.
     */
    @Test
    fun keepsASharpCorner() {
        val result = clean(
            at(0), at(30, eastMeters = 200.0),
            at(60, eastMeters = 400.0),
            at(90, eastMeters = 400.0, northMeters = 200.0),
            at(120, eastMeters = 400.0, northMeters = 400.0),
        )
        assertEquals(0, result.removedCount)
    }

    @Test
    fun judgesEachSegmentOnItsOwn() {
        // Two recordings of the same place, hours apart. The jump between them
        // is not a spike, because nothing was recorded in between.
        val a = listOf(at(0), at(30, eastMeters = 10.0), at(60, eastMeters = 20.0))
        val b = listOf(
            at(20_000, eastMeters = 5000.0),
            at(20_030, eastMeters = 5010.0),
            at(20_060, eastMeters = 5020.0),
        )
        val result = TrackCleanup.clean(Track(listOf(a, b)))
        assertEquals(0, result.removedCount)
        assertEquals(2, result.track.segments.size)
    }

    @Test
    fun dropsAWildElevationButKeepsThePosition() {
        // The 888 m fix from 2026-09-18: the position was only slightly off,
        // the altitude was three hundred metres out.
        val result = clean(
            at(0, ele = 550.0), at(30, ele = 555.0),
            at(60, eastMeters = 20.0, ele = 888.0),
            at(90, ele = 558.0), at(120, ele = 556.0),
        )
        assertEquals(0, result.removedCount)
        assertEquals(5, result.track.size)
        assertNull(result.track.points[2].ele)
        assertNotNull(result.track.points[1].ele)
        assertEquals(1, result.count(TrackCleanup.Finding.ELEVATION_EXCURSION))
    }

    @Test
    fun dropsAnElevationTheReceiverRatedAsWorthless() {
        val result = clean(
            at(0, eleAccuracy = 12.0),
            at(30, ele = 560.0, eleAccuracy = 122.0),
            at(60, eleAccuracy = 15.0),
        )
        assertEquals(0, result.removedCount)
        assertNull(result.track.points[1].ele)
        assertNotNull(result.track.points[0].ele)
        assertEquals(1, result.count(TrackCleanup.Finding.ELEVATION_UNTRUSTED))
    }

    @Test
    fun keepsARealClimb() {
        // Gaining a hundred metres a minute and staying there is a funicular,
        // not a spike: the altitude never comes back down.
        val result = clean(
            at(0, ele = 550.0), at(60, ele = 660.0), at(120, ele = 770.0),
            at(180, ele = 880.0), at(240, ele = 990.0),
        )
        assertEquals(0, result.count(TrackCleanup.Finding.ELEVATION_EXCURSION))
        assertTrue(result.track.points.all { it.ele != null })
    }

    @Test
    fun notesWhenTheReceiverSpeedContradictsTheGround() {
        // 20 m/s claimed while the positions show a walking pace.
        val result = clean(
            at(0, speed = 1.2), at(30, eastMeters = 30.0, speed = 1.1),
            at(60, eastMeters = 60.0, speed = 20.6),
        )
        val note = result.notes.single {
            it.finding == TrackCleanup.Finding.SPEED_CONTRADICTION
        }
        assertEquals(start.plusSeconds(60), note.time)
        assertTrue(note.detail.contains("20.6"))
    }

    /** A contradiction is evidence, not a verdict; the fix stays. */
    @Test
    fun doesNotRemoveOnASpeedContradictionAlone() {
        val result = clean(
            at(0, speed = 1.2), at(30, eastMeters = 30.0, speed = 25.0),
            at(60, eastMeters = 60.0, speed = 1.1),
        )
        assertEquals(0, result.removedCount)
        assertEquals(3, result.track.size)
    }

    /**
     * The geometry path has to carry a file that brings no evidence at all,
     * which is every GPX and KML exported by anything else.
     */
    @Test
    fun worksOnPointsThatCarryNoEvidenceFields() {
        val bare = listOf(
            TrackPoint(start, 46.95, 7.44, null),
            TrackPoint(start.plusSeconds(30), 46.95, 7.4405, null),
            TrackPoint(start.plusSeconds(60), 46.9530, 7.4480, null),
            TrackPoint(start.plusSeconds(90), 46.95, 7.4406, null),
            TrackPoint(start.plusSeconds(120), 46.95, 7.4407, null),
        )
        val result = TrackCleanup.clean(Track.of(bare))
        assertEquals(1, result.removedCount)
        assertEquals(4, result.track.size)
    }

    @Test
    fun leavesAShortTrackAlone() {
        assertEquals(2, TrackCleanup.clean(Track.of(listOf(at(0), at(30)))).track.size)
        assertEquals(0, TrackCleanup.clean(Track.EMPTY).notes.size)
    }

    /**
     * A known limitation, pinned so that changing it has to be deliberate.
     *
     * Two bad fixes in a row shield each other: the test asks whether a point
     * is far from both neighbours, and each of these has the other sitting
     * right beside it. Catching them means judging runs of points rather than
     * single ones, and a run of two is no longer obviously impossible - a real
     * detour down a dead end and back looks the same - so it needs a
     * plausibility guard that this pass does not yet have.
     *
     * Neither recorded track contains one: every spike seen so far is a single
     * fix, which is what makes the single-point rule safe.
     */
    @Test
    fun cannotYetSeeTwoSpikesStandingTogether() {
        val result = clean(
            at(0), at(30, eastMeters = 15.0),
            at(60, eastMeters = 400.0),
            at(90, eastMeters = 420.0),
            at(120, eastMeters = 30.0), at(150, eastMeters = 45.0),
        )
        assertEquals(0, result.removedCount)
        assertEquals(6, result.track.size)
    }

    @Test
    fun findsASecondSpikeOncePointsBetweenThemAreSound() {
        // Separated by a good fix, each spike is judged on its own.
        val result = clean(
            at(0), at(30, eastMeters = 15.0),
            at(60, eastMeters = 400.0),
            at(90, eastMeters = 25.0),
            at(120, eastMeters = 430.0),
            at(150, eastMeters = 35.0), at(180, eastMeters = 45.0),
        )
        assertEquals(2, result.removedCount)
        assertEquals(5, result.track.size)
    }

    @Test
    fun shortensTheTrackItCleans() {
        val dirty = Track.of(
            listOf(
                at(0), at(30, eastMeters = 20.0),
                at(60, eastMeters = 420.0),
                at(90, eastMeters = 37.0), at(120, eastMeters = 55.0),
            )
        )
        val before = dirty.segments.single().zipWithNext { a, b -> distanceMeters(a, b) }.sum()
        val after = TrackCleanup.clean(dirty).track.segments.single()
            .zipWithNext { a, b -> distanceMeters(a, b) }.sum()
        assertTrue("cleaning should remove the detour", after < before / 2)
    }
}
