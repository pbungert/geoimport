package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

class XmpSidecarWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fix = TrackPoint(Instant.parse("2026-09-16T08:00:00Z"), 47.5, 8.25, null)

    /** A Lightroom sidecar, with an old position kept as elements rather than attributes. */
    private val lightroom = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about=""
            xmlns:xmp="http://ns.adobe.com/xap/1.0/"
            xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
            xmlns:exif="http://ns.adobe.com/exif/1.0/"
            xmp:Rating="4"
            crs:Exposure2012="+0.35">
           <exif:GPSLatitude>10,0.000000N</exif:GPSLatitude>
           <exif:GPSAltitude>999000/1000</exif:GPSAltitude>
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    @Test
    fun writesAFreshSidecar() {
        val photo = tmp.newFile("DSCF0001.CR3")

        XmpSidecarWriter.write(photo, fix)

        val xmp = tmp.root.resolve("DSCF0001.xmp").readText()
        assertTrue(xmp.contains("exif:GPSLatitude=\"47,30.000000N\""))
        assertTrue(xmp.contains("exif:GPSLongitude=\"8,15.000000E\""))
    }

    @Test
    fun keepsWhatAnEditorWroteAndReplacesOnlyThePosition() {
        val photo = tmp.newFile("DSCF0001.CR3")
        val sidecar = tmp.root.resolve("DSCF0001.xmp").apply { writeText(lightroom) }

        XmpSidecarWriter.write(photo, fix)

        val xmp = sidecar.readText()
        assertTrue(xmp, xmp.contains("xmp:Rating=\"4\""))
        assertTrue(xmp, xmp.contains("crs:Exposure2012=\"+0.35\""))
        assertTrue(xmp, xmp.contains("47,30.000000N"))
        assertFalse("old position left behind", xmp.contains("10,0.000000N"))
        assertFalse("stale altitude left behind", xmp.contains("999000/1000"))
    }

    @Test
    fun leavesAnUnreadableSidecarAlone() {
        val photo = tmp.newFile("DSCF0001.CR3")
        val sidecar = tmp.root.resolve("DSCF0001.xmp").apply { writeText("not xml at all") }

        try {
            XmpSidecarWriter.write(photo, fix)
            fail("expected the write to fail")
        } catch (_: IOException) {
        }

        assertEquals("not xml at all", sidecar.readText())
    }
}
