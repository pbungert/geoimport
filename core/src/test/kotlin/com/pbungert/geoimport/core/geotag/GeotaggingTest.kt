package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant

class GeotaggingTest {

    private val fix = TrackPoint(Instant.parse("2026-09-16T08:00:00Z"), 47.0, 8.0, null)

    /** Answers for named files; anything else is written normally. */
    private class StubWriter(
        val embeds: Set<String> = emptySet(),
        val throws: Set<String> = emptySet(),
    ) : GpsWriter {
        override val name = "stub"
        override fun supports(file: File) = true
        override fun write(file: File, point: TrackPoint): GpsWriteResult {
            if (file.name in throws) throw IllegalStateException("nope")
            return if (file.name in embeds) {
                GpsWriteResult.Embedded(file)
            } else {
                GpsWriteResult.Sidecar(File(file.parentFile, "${file.nameWithoutExtension}.xmp"))
            }
        }
    }

    @Test
    fun countsEachOutcomeSeparately() {
        val targets = listOf(
            GeotagTarget(File("a.raf"), fix),
            GeotagTarget(File("b.cr3"), fix),
            GeotagTarget(File("c.raf"), fix),
            GeotagTarget(File("d.raf"), null),
        )

        val counts = writeGeotags(targets, StubWriter(embeds = setOf("a.raf"), throws = setOf("c.raf")))

        assertEquals(GeotagCounts(embedded = 1, sidecars = 1, missing = 1, failed = 1), counts)
        assertEquals(2, counts.written)
        assertEquals(2, counts.untagged)
    }

    /** One unwritable file is no reason to abandon the ones after it. */
    @Test
    fun carriesOnPastAFailure() {
        val targets = (1..3).map { GeotagTarget(File("$it.raf"), fix) }

        val counts = writeGeotags(targets, StubWriter(throws = setOf("1.raf")))

        assertEquals(2, counts.sidecars)
        assertEquals(1, counts.failed)
    }

    @Test
    fun reportsEveryFileOnceInOrder() {
        val targets = listOf(
            GeotagTarget(File("a.raf"), fix),
            GeotagTarget(File("b.raf"), null),
            GeotagTarget(File("c.raf"), fix),
        )
        val seen = mutableListOf<String>()

        writeGeotags(targets, StubWriter(throws = setOf("c.raf"))) { target, result, error ->
            seen += "${target.file.name}:${result?.let { "written" } ?: ""}${error?.let { "error" } ?: ""}"
        }

        assertEquals(listOf("a.raf:written", "b.raf:", "c.raf:error"), seen)
    }
}
