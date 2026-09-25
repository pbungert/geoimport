package com.pbungert.geoimport.core.track

import com.pbungert.geoimport.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

class GpxWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = Instant.parse("2026-09-16T08:00:00Z")
    private fun point(minute: Long) = TrackPoint(t0.plusSeconds(minute * 60), 47.0 + minute / 1000.0, 8.0, 500.0)

    private fun parse(file: File) = file.inputStream().use { TrackParser.parse(it) }

    private fun recordTwo(): File {
        val file = File(tmp.root, "day.gpx")
        GpxWriter(file, "day").apply {
            addPoint(point(0))
            addPoint(point(1))
            close()
        }
        return file
    }

    private fun truncate(file: File, by: Int) =
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - by) }

    @Test
    fun resumesAFileItClosedCleanly() {
        val file = recordTwo()

        GpxWriter(file, "day").apply { addPoint(point(2)); close() }

        assertEquals(3, parse(file).size)
    }

    /** A write cut off inside a point: the partial point goes, the rest stays readable. */
    @Test
    fun cutsBackAWriteThatStoppedMidPoint() {
        val file = recordTwo()
        val footer = "  </trkseg>\n </trk>\n</gpx>\n".length
        truncate(file, footer + 30)

        GpxWriter(file, "day").apply { addPoint(point(2)); close() }

        val track = parse(file)
        assertEquals(listOf(point(0).time, point(2).time), track.points.map { it.time })
    }

    /** A complete point with no closing tags after it is kept. */
    @Test
    fun keepsTheLastPointWhenOnlyTheClosingTagsAreMissing() {
        val file = recordTwo()
        truncate(file, 10)

        GpxWriter(file, "day").apply { addPoint(point(2)); close() }

        assertEquals(3, parse(file).size)
    }
}
