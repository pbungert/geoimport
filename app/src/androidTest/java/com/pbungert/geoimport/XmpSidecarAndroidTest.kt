package com.pbungert.geoimport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pbungert.geoimport.core.geotag.XmpSidecarWriter
import com.pbungert.geoimport.core.model.TrackPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * The merge runs on Android's own DOM parser and identity transformer, not the
 * JDK's - the same kind of difference that once made every track fail to parse
 * on a phone while the JVM tests passed.
 */
@RunWith(AndroidJUnit4::class)
class XmpSidecarAndroidTest {

    @Test
    fun mergesIntoAnExistingSidecarOnDevice() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "xmp")
            .apply { deleteRecursively(); mkdirs() }
        val photo = File(dir, "DSCF0001.CR3").apply { writeText("") }
        val sidecar = File(dir, "DSCF0001.xmp").apply {
            writeText(
                """
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:about=""
                    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                    xmlns:exif="http://ns.adobe.com/exif/1.0/"
                    xmp:Rating="4"
                    exif:GPSLatitude="10,0.000000N"/>
                 </rdf:RDF>
                </x:xmpmeta>
                """.trimIndent()
            )
        }

        XmpSidecarWriter.write(photo, TrackPoint(Instant.EPOCH, 47.5, 8.25, null))

        val xmp = sidecar.readText()
        assertTrue(xmp, xmp.contains("xmp:Rating=\"4\""))
        assertTrue(xmp, xmp.contains("47,30.000000N"))
        assertFalse(xmp, xmp.contains("10,0.000000N"))
    }
}
