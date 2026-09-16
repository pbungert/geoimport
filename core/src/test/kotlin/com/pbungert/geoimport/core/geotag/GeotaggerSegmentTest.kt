package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The seam between two segments is not a route. Merging several recordings -
 * or resuming a paused one - must not turn the stretch nobody recorded into a
 * straight line photos get placed along.
 */
class GeotaggerSegmentTest {

    private val t0 = Instant.parse("2026-09-12T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    /** Munich in the morning, Salzburg in the evening: nothing in between. */
    private val twoOutings = Track(
        listOf(
            listOf(
                TrackPoint(at(0), 48.1372, 11.5756, 519.0),
                TrackPoint(at(60), 48.1400, 11.5800, 521.0),
            ),
            listOf(
                TrackPoint(at(600), 47.8095, 13.0550, 424.0),
                TrackPoint(at(660), 47.8120, 13.0600, 430.0),
            ),
        )
    )

    @Test
    fun doesNotInterpolateAcrossTheSeamBetweenTwoTracks() {
        val g = Geotagger(twoOutings, Duration.ofMinutes(30))
        assertNull("a photo taken between two outings has no recorded position", g.resolve(at(300)))
    }

    @Test
    fun aFlatListStillBridgesTheSameGap() {
        // The old behaviour, kept for a single continuous recording: without
        // segment information the two halves are one stretch of track.
        val g = Geotagger(twoOutings.points, Duration.ofMinutes(30))
        assertNotNull(g.resolve(at(300)))
    }

    @Test
    fun toleranceStillReachesPastASegmentEnd() {
        val g = Geotagger(twoOutings, Duration.ofMinutes(30))
        val fix = g.resolve(at(80))
        assertNotNull("20 min after the first segment ends is within tolerance", fix)
        assertEquals(48.1400, fix!!.point.lat, 1e-9)
        assertNull("an endpoint is not interpolated, so there is no gap to report", fix.gapMeters)
    }

    @Test
    fun picksTheNearerSegmentEndWhenBothAreInReach() {
        val tight = Track(
            listOf(
                listOf(TrackPoint(at(0), 1.0, 1.0, null)),
                listOf(TrackPoint(at(100), 9.0, 9.0, null)),
            )
        )
        val g = Geotagger(tight, Duration.ofHours(4))
        assertEquals(9.0, g.locate(at(70))!!.lat, 1e-9)
        assertEquals(1.0, g.locate(at(30))!!.lat, 1e-9)
    }

    @Test
    fun overlappingRecordingsPreferTheOneThatSampledMoreClosely() {
        val coarse = listOf(
            TrackPoint(at(0), 0.0, 0.0, null),
            TrackPoint(at(60), 6.0, 0.0, null),
        )
        val fine = listOf(
            TrackPoint(at(29), 1.0, 0.0, null),
            TrackPoint(at(31), 1.0, 0.0, null),
        )
        val g = Geotagger(Track(listOf(coarse, fine)), Duration.ofMinutes(30))
        assertEquals("the two-minute bracket wins over the hour-long one", 1.0, g.locate(at(30))!!.lat, 1e-9)
    }

    @Test
    fun interpolatesNormallyInsideASegment() {
        val g = Geotagger(twoOutings, Duration.ofMinutes(30))
        val fix = g.resolve(at(30))!!
        assertEquals(48.1386, fix.point.lat, 1e-4)
        assertNotNull("a within-segment fix still reports how wide the gap was", fix.gapMeters)
    }
}
