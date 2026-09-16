package com.pbungert.geoimport.core.track

import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TrackSelectionTest {

    private val t0 = Instant.parse("2026-07-17T08:00:00Z")
    private fun at(h: Long) = t0.plus(Duration.ofHours(h))

    private fun namedTrack(name: String, from: Long, to: Long) = NamedTrack(
        name,
        Track.of(listOf(TrackPoint(at(from), 1.0, 1.0, null), TrackPoint(at(to), 2.0, 2.0, null))),
    )

    private val monday = namedTrack("monday", 0, 8)
    private val tuesday = namedTrack("tuesday", 24, 32)
    private val wednesday = namedTrack("wednesday", 48, 56)

    private fun choose(times: List<Instant>, tolerance: Duration = Duration.ofMinutes(30)) =
        TrackSelection.choose(listOf(monday, tuesday, wednesday), times, tolerance)

    @Test
    fun keepsOnlyTheTracksThatOverlapThePhotos() {
        val choice = choose(listOf(at(25), at(30)))
        assertEquals(listOf("tuesday"), choice.used.map { it.name })
        assertEquals(listOf("monday", "wednesday"), choice.skipped.map { it.name })
    }

    @Test
    fun keepsEveryTrackThePhotosSpan() {
        val choice = choose(listOf(at(4), at(52)))
        assertEquals(listOf("monday", "tuesday", "wednesday"), choice.used.map { it.name })
    }

    @Test
    fun toleranceReachesPastTheEndsOfATrack() {
        // 20 minutes after monday ends: close enough to be placed, so the
        // track that would place it has to survive the filter.
        assertEquals(
            listOf("monday"),
            choose(listOf(at(8).plus(Duration.ofMinutes(20)))).used.map { it.name },
        )
        assertEquals(
            emptyList<String>(),
            choose(listOf(at(8).plus(Duration.ofMinutes(40)))).used.map { it.name },
        )
    }

    @Test
    fun oneOddCaptureTimeOnlyWidensTheRange() {
        // A video with no EXIF falls back to its file time; that must never
        // cost the photos around it their track.
        val choice = choose(listOf(at(26), at(30), Instant.parse("1980-01-01T00:00:00Z")))
        assertTrue("tuesday" in choice.used.map { it.name })
    }

    @Test
    fun aTrackWithNoTimestampedPointsIsDropped() {
        val choice = TrackSelection.choose(
            listOf(monday, NamedTrack("empty", Track.EMPTY)),
            listOf(at(4)),
            Duration.ofMinutes(30),
        )
        assertEquals(listOf("monday"), choice.used.map { it.name })
        assertEquals(listOf("empty"), choice.skipped.map { it.name })
    }

    @Test
    fun mergingKeepsEachTrackAsItsOwnSegment() {
        val choice = choose(listOf(at(4), at(52)))
        assertEquals(3, choice.track.segments.size)
    }

    @Test
    fun explainsItselfWhenNothingOverlaps() {
        val lines = choose(listOf(at(100))).lines(ZoneId.of("UTC"))
        assertTrue(lines[0].startsWith("None of the 3 tracks cover these photos"))
        assertTrue("names the nearest track", lines[1].contains("wednesday"))
    }

    @Test
    fun namesTheTracksItUsed() {
        val line = choose(listOf(at(25), at(30))).lines(ZoneId.of("UTC")).single()
        assertTrue(line, line.contains("1 of 3 tracks: tuesday"))
    }
}
