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

    private fun distinct(vararg tracks: NamedTrack) =
        TrackSelection.distinct(tracks.toList(), { it.name }, { it.track }) { t, n -> t.copy(name = n) }

    /** Same name, different recordings: both are kept, the later one renamed. */
    @Test
    fun keepsTwoTracksThatOnlyShareAName() {
        val other = tuesday.copy(name = "monday")

        val kept = distinct(monday, other)

        assertEquals(listOf("monday", "monday (2)"), kept.map { it.name })
        assertEquals(tuesday.track, kept[1].track)
    }

    /** The same recording offered twice, under any name, is used once. */
    @Test
    fun dropsTheSameRecordingOfferedTwice() {
        val kept = distinct(monday, monday.copy(name = "picked copy"), tuesday)

        assertEquals(listOf("monday", "tuesday"), kept.map { it.name })
    }

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
    fun aTrackWithNoTimestampedPointsIsUndatedRatherThanSkipped() {
        val choice = TrackSelection.choose(
            listOf(monday, NamedTrack("empty", Track.EMPTY)),
            listOf(at(4)),
            Duration.ofMinutes(30),
        )
        assertEquals(listOf("monday"), choice.used.map { it.name })
        assertEquals(emptyList<String>(), choice.skipped.map { it.name })
        assertEquals(listOf("empty"), choice.undated.map { it.name })
    }

    /** Files that write the epoch for every point exist, and parse cleanly. */
    private val epochTrack = NamedTrack(
        "placeholder",
        Track.of(
            listOf(
                TrackPoint(Instant.EPOCH, 46.95, 7.44, null),
                TrackPoint(Instant.EPOCH, 46.96, 7.45, null),
            )
        ),
    )

    @Test
    fun aTrackTimestampedAtTheEpochCarriesNoTimes() {
        val choice = TrackSelection.choose(
            listOf(monday, epochTrack), listOf(at(4)), Duration.ofMinutes(30),
        )
        assertEquals(listOf("placeholder"), choice.undated.map { it.name })
        val line = choice.lines(ZoneId.of("UTC")).last()
        assertTrue(line, line.contains("1 track carries no usable timestamps"))
        assertTrue(line, line.contains("placeholder"))
    }

    @Test
    fun saysWhyWhenEveryTrackIsUndated() {
        val choice = TrackSelection.choose(
            listOf(epochTrack), listOf(at(4)), Duration.ofMinutes(30),
        )
        assertEquals(
            listOf("1 track carries no usable timestamps and cannot place anything: placeholder."),
            choice.lines(ZoneId.of("UTC")),
        )
    }

    @Test
    fun mergingKeepsEachTrackAsItsOwnSegment() {
        val choice = choose(listOf(at(4), at(52)))
        assertEquals(3, choice.track.segments.size)
    }

    /**
     * A fix 400 m out and straight back, in a track that carries nothing but
     * time and position - which is all a GPX or KML from elsewhere has.
     */
    private val spiky = NamedTrack(
        "spiky",
        Track.of(
            listOf(
                bare(0, 0.0), bare(1, 20.0), bare(2, 420.0), bare(3, 37.0),
            )
        ),
    )

    private fun bare(minutes: Long, eastMeters: Double) = TrackPoint(
        time = at(25).plus(Duration.ofMinutes(minutes)),
        lat = 46.95,
        lon = 7.44 + eastMeters / (111_320.0 * 0.6820),
        ele = null,
    )

    private fun chooseSpiky() =
        TrackSelection.choose(listOf(spiky), listOf(at(25)), Duration.ofMinutes(30))

    @Test
    fun tagsFromTheCleanedTrackAndKeepsTheRawOne() {
        val choice = chooseSpiky()
        assertEquals(3, choice.cleanedTrack.size)
        assertEquals(4, choice.track.size)
    }

    @Test
    fun cleaningTheSelectionKeepsEachTrackItsOwnSegment() {
        assertEquals(3, choose(listOf(at(4), at(52))).cleanedTrack.segments.size)
    }

    @Test
    fun saysWhatCleaningLeftOut() {
        val lines = chooseSpiky().lines(ZoneId.of("UTC"))
        assertTrue(lines.last(), lines.last().contains("1 misleading fix"))
    }

    @Test
    fun saysNothingWhenThereWasNothingToClean() {
        assertEquals(1, choose(listOf(at(25), at(30))).lines(ZoneId.of("UTC")).size)
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
