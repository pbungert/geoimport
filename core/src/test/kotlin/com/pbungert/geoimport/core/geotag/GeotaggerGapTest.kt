package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * A gap is only suspicious when the track moved across it. Sitting in one place
 * overnight and resuming in the morning is the common case and must stay
 * taggable, so the gap is measured in metres rather than in hours.
 */
class GeotaggerGapTest {

    private val t0 = Instant.parse("2026-09-12T18:00:00Z")
    private fun at(h: Long) = t0.plus(Duration.ofHours(h))

    @Test
    fun overnightStopReportsANarrowGap() {
        val g = Geotagger(
            listOf(
                TrackPoint(at(0), 46.4613, 9.9338, 1918.0),
                TrackPoint(at(13), 46.4614, 9.9339, 1918.0),
            ),
            Duration.ofMinutes(10),
        )
        val fix = g.resolve(at(12))
        assertNotNull(fix)
        assertTrue("13 h in one place is not a wide gap", fix!!.gapMeters!! < 50.0)
    }

    @Test
    fun shortDropoutWhileMovingReportsAWideGap() {
        val g = Geotagger(
            listOf(
                TrackPoint(t0, 46.4600, 9.9300, 1900.0),
                TrackPoint(t0.plus(Duration.ofMinutes(45)), 46.5100, 10.0800, 2100.0),
            ),
            Duration.ofMinutes(10),
        )
        val fix = g.resolve(t0.plus(Duration.ofMinutes(20)))!!
        assertTrue("45 min covering km is a wide gap", fix.gapMeters!! > 5_000.0)
    }

    @Test
    fun gapIsNullOutsideTheTrack() {
        val g = Geotagger(listOf(TrackPoint(t0, 46.46, 9.93, 1900.0)), Duration.ofMinutes(10))
        assertNull(g.resolve(t0.plus(Duration.ofMinutes(5)))!!.gapMeters)
        assertNull("beyond tolerance", g.resolve(t0.plus(Duration.ofMinutes(30))))
    }

    @Test
    fun locateStillReturnsThePointItAlwaysDid() {
        val g = Geotagger(
            listOf(
                TrackPoint(t0, 46.0, 9.0, 1000.0),
                TrackPoint(t0.plus(Duration.ofMinutes(10)), 46.2, 9.0, 1000.0),
            ),
            Duration.ofMinutes(10),
        )
        val mid = g.locate(t0.plus(Duration.ofMinutes(5)))!!
        assertEquals(46.1, mid.lat, 1e-9)
    }
}
