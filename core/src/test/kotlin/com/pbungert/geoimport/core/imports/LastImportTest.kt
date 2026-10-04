package com.pbungert.geoimport.core.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class LastImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val noon = Instant.parse("2026-09-27T12:00:00Z")
    private val evening = Instant.parse("2026-09-27T19:00:00Z")

    private val xt2 = Camera("FUJIFILM", "X-T2", "81M52794")
    private val xt5 = Camera("FUJIFILM", "X-T5", "1AB00042")

    private fun only(state: LastImport) = LastImports(listOf(state))

    @Test
    fun survivesARoundTrip() {
        val states = LastImports(
            listOf(
                LastImport("DSCF1234.RAF", 7, noon, "Galaxy Tab S10 Ü"),
                LastImport("DSCF0042.RAF", 8, evening, "PC", xt2),
                LastImport("DSCF0050.RAF", 6, evening, "PC", Camera("Canon", "Canon EOS R5", null)),
            )
        )

        assertEquals(states, LastImports.parse(states.toBytes()))
    }

    @Test
    fun theSameStateIsTheSameBytes() {
        val a = LastImport("DSCF1234.RAF", 7, noon, "Pixel 9", xt2)
        val b = LastImport("DSCF0001.RAF", 6, noon, "Pixel 9", xt5)

        assertEquals(LastImports(listOf(a, b)).toBytes().toList(), LastImports(listOf(b, a)).toBytes().toList())
    }

    @Test
    fun eachCameraSaysWhichItIs() {
        val text = only(LastImport("DSCF2175.RAF", 12, noon, "Pixel 9", xt2)).toBytes().toString(Charsets.UTF_8)

        assertTrue(text.contains("FUJIFILM_X-T2_81M52794.make=FUJIFILM\n"))
        assertTrue(text.contains("FUJIFILM_X-T2_81M52794.model=X-T2\n"))
        assertTrue(text.contains("FUJIFILM_X-T2_81M52794.serial=81M52794\n"))
        assertTrue(text.contains("FUJIFILM_X-T2_81M52794.lastFile=DSCF2175.RAF\n"))
    }

    @Test
    fun aFileFromBeforeCamerasWereToldApartIsTheUnknownCamera() {
        val old = "lastFile=DSCF0001.RAF\nimportNumber=3\nimportedAt=$noon\ndevice=PC\n".toByteArray()

        assertEquals(LastImport("DSCF0001.RAF", 3, noon, "PC"), LastImports.parse(old).forCamera(null))
    }

    @Test
    fun incompleteContentIsNoState() {
        assertEquals(LastImports(), LastImports.parse("lastFile=DSCF0001.RAF\n".toByteArray()))
    }

    @Test
    fun theUnknownCameraStandsInOnlyUntilCamerasHaveTheirOwn() {
        val unknown = LastImport("DSCF0001.RAF", 3, noon, "PC")

        assertEquals(unknown, only(unknown).forCamera(xt2))

        val both = only(unknown).with(LastImport("DSCF0100.RAF", 4, evening, "PC", xt5))
        assertNull(both.forCamera(xt2))
        assertEquals(unknown, both.forCamera(null))
    }

    @Test
    fun aCameraReadWithoutItsSerialStillFindsItsEntry() {
        val states = only(LastImport("DSCF0100.RAF", 4, evening, "PC", xt2))

        assertEquals("DSCF0100.RAF", states.forCamera(xt2.copy(serial = null))?.lastFile)
        assertNull(states.forCamera(xt2.copy(serial = "OTHER")))
    }

    @Test
    fun theHighestFolderCountsAcrossCameras() {
        val states = LastImports(
            listOf(LastImport("DSCF0001.RAF", 9, noon, "PC", xt2), LastImport("DSCF0001.RAF", 4, noon, "PC", xt5))
        )

        assertEquals(9, states.highestImportNumber)
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
    fun mergeKeepsEveryCamera() {
        val tablet = only(LastImport("DSCF0120.RAF", 7, evening, "Tablet", xt2))
        val pc = only(LastImport("DSCF0150.RAF", 6, noon, "PC", xt5))

        val merged = LastImports.merge(tablet, pc)

        assertEquals(setOf(xt2, xt5), merged.entries.map { it.camera }.toSet())
        assertEquals(merged, LastImports.merge(pc, tablet))
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
        val good = only(LastImport("DSCF0010.RAF", 3, noon, "PC", xt2)).toBytes()

        assertEquals(good.toList(), LastImports.mergeBytes("garbage".toByteArray(), good).toList())
    }

    @Test
    fun recordingAnOlderImportDoesNotMoveTheResumePointBack() {
        val file = LastImport.fileIn(tmp.root)
        LastImports.record(LastImport("DSCF0200.RAF", 5, noon, "Tablet", xt2), file)

        // Re-importing older photos from an explicit start point.
        LastImports.record(LastImport("DSCF0100.RAF", 6, evening, "Tablet", xt2), file)

        assertEquals(LastImport("DSCF0200.RAF", 6, noon, "Tablet", xt2), LastImports.read(file).forCamera(xt2))
    }

    @Test
    fun recordingOneCameraLeavesTheOthersAlone() {
        val file = LastImport.fileIn(tmp.root)
        LastImports.record(LastImport("DSCF0200.RAF", 5, noon, "Tablet", xt2), file)
        LastImports.record(LastImport("DSCF0010.RAF", 6, evening, "PC", xt5), file)

        val states = LastImports.read(file)
        assertEquals("DSCF0200.RAF", states.forCamera(xt2)?.lastFile)
        assertEquals("DSCF0010.RAF", states.forCamera(xt5)?.lastFile)
    }

    @Test
    fun cameraKeysAndLabelsReadWell() {
        val canon = Camera.of("Canon", "Canon EOS R5", null)!!

        assertEquals("Canon_EOS-R5", canon.key)
        assertEquals("Canon EOS R5", canon.label)
        assertEquals("FUJIFILM X-T2 (81M52794)", xt2.label)
        assertNull(Camera.of(" ", null, "\u0000"))
        assertFalse(xt2.matches(xt5))
    }
}
