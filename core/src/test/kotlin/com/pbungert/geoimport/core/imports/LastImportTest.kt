package com.pbungert.geoimport.core.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class LastImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val noon = Instant.parse("2026-09-27T12:00:00Z")
    private val evening = Instant.parse("2026-09-27T19:00:00Z")

    @Test
    fun survivesARoundTrip() {
        val state = LastImport("DSCF1234.RAF", 7, noon, "Galaxy Tab S10 Ü")

        assertEquals(state, LastImport.parse(state.toBytes()))
    }

    @Test
    fun theSameStateIsTheSameBytes() {
        val state = LastImport("DSCF1234.RAF", 7, noon, "Pixel 9")

        assertEquals(state.toBytes().toList(), state.copy().toBytes().toList())
    }

    @Test
    fun incompleteContentIsNoState() {
        assertNull(LastImport.parse("lastFile=DSCF0001.RAF\n".toByteArray()))
    }

    @Test
    fun mergeKeepsTheLaterPhotoAndTheHigherFolder() {
        val tablet = LastImport("DSCF0120.RAF", 7, evening, "Tablet")
        val pc = LastImport("DSCF0150.RAF", 6, noon, "PC")

        val merged = LastImport.merge(tablet, pc)

        assertEquals(LastImport("DSCF0150.RAF", 7, noon, "PC"), merged)
        assertEquals(merged, LastImport.merge(pc, tablet))
    }

    @Test
    fun aCounterThatWrappedStillMovesForward() {
        val before = LastImport("DSCF9990.RAF", 3, noon, "PC")
        val after = LastImport("DSCF0010.RAF", 4, evening, "Tablet")

        assertEquals(after, LastImport.merge(before, after))
    }

    @Test
    fun theSamePhotoTwiceKeepsTheLaterImport() {
        val first = LastImport("DSCF0010.RAF", 3, noon, "PC")
        val second = LastImport("DSCF0010.RAF", 4, evening, "Tablet")

        assertEquals(second, LastImport.merge(first, second))
    }

    @Test
    fun unreadableContentGivesWayToTheOtherSide() {
        val good = LastImport("DSCF0010.RAF", 3, noon, "PC").toBytes()

        assertEquals(good.toList(), LastImport.mergeBytes("garbage".toByteArray(), good).toList())
    }

    @Test
    fun recordingAnOlderImportDoesNotMoveTheResumePointBack() {
        val file = LastImport.fileIn(tmp.root)
        LastImport.record(LastImport("DSCF0200.RAF", 5, noon, "Tablet"), file)

        // Re-importing older photos from an explicit start point.
        LastImport.record(LastImport("DSCF0100.RAF", 6, evening, "Tablet"), file)

        assertEquals(LastImport("DSCF0200.RAF", 6, noon, "Tablet"), LastImport.read(file))
    }
}
