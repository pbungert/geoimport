package com.pbungert.geoimport.core.imports

import com.pbungert.geoimport.core.spi.ExifDateReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class PhotoImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** No EXIF in the fixtures; capture time is irrelevant to these tests. */
    private val noExif = ExifDateReader { null }

    private lateinit var source: File
    private lateinit var dest: File
    private val log = mutableListOf<String>()

    private fun importer(extensions: Set<String> = PhotoImporter.DEFAULT_EXTENSIONS) =
        PhotoImporter(source, dest, CaptureTimeResolver(noExif), { log.add(it) }, extensions)

    private fun setUpCard(folder: String, vararg names: String) {
        source = File(tmp.root, "DCIM").also { it.mkdirs() }
        dest = File(tmp.root, "Pictures").also { it.mkdirs() }
        val dir = File(source, folder).also { it.mkdirs() }
        names.forEach { File(dir, it).writeText(it) }
    }

    private fun importFolder(number: Int, vararg names: String): File {
        val folder = File(dest, "Import %02d".format(number)).also { it.mkdirs() }
        names.forEach { File(folder, it).writeText(it) }
        return folder
    }

    // --- the sidecar watermark bug ---------------------------------------

    @Test
    fun resumesAfterThePhotoNotTheSidecarItsGeotaggingLeftBehind() {
        setUpCard("101_FUJI", "DSCF0005.RAF", "DSCF0006.RAF", "DSCF0007.RAF", "DSCF0008.RAF")
        // A previous run copied 5 and 6, then geotagging wrote sidecars.
        importFolder(1, "DSCF0005.RAF", "DSCF0005.xmp", "DSCF0006.RAF", "DSCF0006.xmp")

        val result = importer().run(startFilename = null, startTimestamp = null)

        assertEquals(listOf("DSCF0007.RAF", "DSCF0008.RAF"), result.copied.map { it.name })
        assertTrue(
            "watermark should be the photo, not the sidecar",
            log.any { it.contains("Starting after 'DSCF0006.RAF'") },
        )
    }

    @Test
    fun sidecarOnlyFolderFallsBackToImportingEverything() {
        setUpCard("101_FUJI", "DSCF0005.RAF", "DSCF0006.RAF")
        importFolder(1, "DSCF0004.xmp")

        val result = importer().run(startFilename = null, startTimestamp = null)

        assertEquals(listOf("DSCF0005.RAF", "DSCF0006.RAF"), result.copied.map { it.name })
    }

    // --- resume semantics -------------------------------------------------

    @Test
    fun resumeIsExclusiveOfTheNamedFile() {
        setUpCard("101_FUJI", "DSCF0001.RAF", "DSCF0002.RAF", "DSCF0003.RAF")
        val result = importer().run(startFilename = "DSCF0002.RAF", startTimestamp = null)
        assertEquals(listOf("DSCF0003.RAF"), result.copied.map { it.name })
    }

    @Test
    fun copiesEverythingWhenThereIsNoPriorImport() {
        setUpCard("101_FUJI", "DSCF0001.RAF", "DSCF0002.RAF")
        val result = importer().run(startFilename = null, startTimestamp = null)
        assertEquals(2, result.copied.size)
        assertEquals("Import 01", result.destFolder!!.name)
    }

    @Test
    fun copiesPreserveTheModificationTime() {
        setUpCard("101_FUJI", "DSCF0001.RAF")
        val stamp = 1_600_000_000_000L
        File(File(source, "101_FUJI"), "DSCF0001.RAF").setLastModified(stamp)

        val result = importer().run(startFilename = null, startTimestamp = null)

        assertEquals(stamp, result.copied.single().lastModified())
    }

    // --- files with no number in their name ------------------------------

    @Test
    fun importsUnnumberedFilesWhenThereIsNoResumePoint() {
        setUpCard("101_FUJI", "DSCF0001.RAF", "PANO.JPG")
        val result = importer().run(startFilename = null, startTimestamp = null)
        assertEquals(listOf("DSCF0001.RAF", "PANO.JPG"), result.copied.map { it.name })
    }

    @Test
    fun judgesUnnumberedFilesByTimeWhenResumingFromATimestamp() {
        setUpCard("101_FUJI", "DSCF0001.RAF", "OLD.JPG", "NEW.JPG")
        val dir = File(source, "101_FUJI")
        File(dir, "OLD.JPG").setLastModified(631_152_000_000L) // 1990
        val since = LocalDateTime.of(2000, 1, 1, 0, 0)

        val result = importer().run(startFilename = null, startTimestamp = since)

        assertEquals(listOf("DSCF0001.RAF", "NEW.JPG"), result.copied.map { it.name })
    }

    @Test
    fun saysWhenAFilenameResumeSkipsUnnumberedFiles() {
        setUpCard("101_FUJI", "DSCF0001.RAF", "DSCF0002.RAF", "PANO.JPG")

        val result = importer().run(startFilename = "DSCF0001.RAF", startTimestamp = null)

        assertEquals(listOf("DSCF0002.RAF"), result.copied.map { it.name })
        assertTrue(log.any { it.contains("PANO.JPG") })
    }

    /**
     * A wrapped counter leaves the same name in two card folders. Only one can
     * land in the flat destination, and the result must say which - so the
     * other's position is not written into it.
     */
    @Test
    fun pairsEachCopyWithTheEntryItCameFrom() {
        setUpCard("100_FUJI", "DSCF0001.RAF")
        File(source, "101_FUJI").mkdirs()
        File(File(source, "101_FUJI"), "DSCF0001.RAF").writeText("the other one")

        val result = importer().run(startFilename = null, startTimestamp = null)

        val (entry, copy) = result.copies.single()
        assertEquals(entry.source.readText(), copy.readText())
    }

    // --- formats and folders ---------------------------------------------

    @Test
    fun collectsTheConfiguredExtensionsAcrossDcfFolders() {
        source = File(tmp.root, "DCIM").also { it.mkdirs() }
        dest = File(tmp.root, "Pictures").also { it.mkdirs() }
        File(source, "101_FUJI").mkdirs()
        File(source, "100CANON").mkdirs()
        File(source, "misc").mkdirs()
        File(File(source, "101_FUJI"), "DSCF0001.RAF").writeText("a")
        File(File(source, "101_FUJI"), "DSCF0002.JPG").writeText("b")
        File(File(source, "101_FUJI"), "DSCF0003.TXT").writeText("c")
        File(File(source, "100CANON"), "IMG_0004.CR3").writeText("d")
        File(File(source, "misc"), "DSCF0005.RAF").writeText("e")

        val result = importer(setOf("raf", "jpg", "cr3")).run(null, null)

        // .TXT excluded by extension, "misc" excluded as a non-DCF folder name.
        assertEquals(
            listOf("DSCF0001.RAF", "DSCF0002.JPG", "IMG_0004.CR3"),
            result.copied.map { it.name },
        )
    }

    @Test
    fun describesCountsPerExtensionWithNoFormatHardcoded() {
        val files = listOf("a.RAF", "b.raf", "c.mov", "d.CR3", "e.raf").map { File(it) }
        assertEquals("3 RAF, 1 CR3, 1 MOV", PhotoImporter.describe(files))
        assertEquals("no files", PhotoImporter.describe(emptyList()))
    }

    // --- wrap-around sort (previously untested) ---------------------------

    private fun sorted(vararg names: String) =
        PhotoImporter.sortByFilenameChronological(names.map { File(it) }).map { it.name }

    @Test
    fun sortsByTrailingSequenceNumber() {
        assertEquals(
            listOf("DSCF0002.RAF", "DSCF0010.RAF", "DSCF0100.RAF"),
            sorted("DSCF0100.RAF", "DSCF0002.RAF", "DSCF0010.RAF"),
        )
    }

    @Test
    fun rotatesSoAWrappedCounterStaysChronological() {
        // The camera rolled over 9999; 0001-0002 were shot after 9998-9999.
        assertEquals(
            listOf("DSCF9998.RAF", "DSCF9999.RAF", "DSCF0001.RAF", "DSCF0002.RAF"),
            sorted("DSCF0001.RAF", "DSCF0002.RAF", "DSCF9998.RAF", "DSCF9999.RAF"),
        )
    }

    @Test
    fun doesNotRotateWhenTheWrapGapIsTheLargestGap() {
        // A plain run with no wrap: the biggest jump is the modulus itself.
        assertEquals(
            listOf("DSCF0001.RAF", "DSCF0002.RAF", "DSCF0003.RAF"),
            sorted("DSCF0003.RAF", "DSCF0001.RAF", "DSCF0002.RAF"),
        )
    }

    @Test
    fun usesTheLastDigitRunSoFolderAndPrefixDigitsDoNotConfuseIt() {
        assertEquals(
            listOf("101_FUJI_0007.RAF", "101_FUJI_0008.RAF"),
            sorted("101_FUJI_0008.RAF", "101_FUJI_0007.RAF"),
        )
    }

    @Test
    fun dropsNamesWithoutAnyDigits() {
        assertEquals(listOf("DSCF0001.RAF"), sorted("README.RAF", "DSCF0001.RAF"))
    }

    @Test
    fun singleFileNeedsNoRotation() {
        assertEquals(listOf("DSCF0042.RAF"), sorted("DSCF0042.RAF"))
        assertEquals(emptyList<String>(), sorted())
    }
}
