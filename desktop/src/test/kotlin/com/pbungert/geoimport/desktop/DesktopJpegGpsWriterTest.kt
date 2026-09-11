package com.pbungert.geoimport.desktop

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.GpsDirectory
import com.pbungert.geoimport.core.geotag.BuiltInGpsWriter
import com.pbungert.geoimport.core.geotag.ExifGpsFormat
import com.pbungert.geoimport.core.geotag.GpsWriteResult
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.desktop.platform.DesktopJpegGpsWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO

/**
 * Round-trips real JPEGs through Commons Imaging and reads them back with
 * metadata-extractor — an independent parser, so agreement means the bytes are
 * genuinely right rather than merely self-consistent.
 */
class DesktopJpegGpsWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val munich = TrackPoint(
        time = Instant.parse("2026-07-17T09:02:30Z"),
        lat = 48.1372,
        lon = 11.5756,
        ele = 519.4,
    )

    private fun jpeg(name: String = "photo.jpg"): File {
        val file = tmp.newFile(name)
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpeg", file)
        return file
    }

    private fun gpsOf(file: File): GpsDirectory =
        ImageMetadataReader.readMetadata(file).getFirstDirectoryOfType(GpsDirectory::class.java)
            ?: error("no GPS directory in ${file.name}")

    @Test
    fun writesCoordinatesThatReadBackAtTheRightPlace() {
        val file = jpeg()
        DesktopJpegGpsWriter.writeGps(file, munich)

        val location = gpsOf(file).geoLocation
        assertNotNull("GPS location should be readable", location)
        // Wrong argument order on setGpsInDegrees would swap these two.
        assertEquals(48.1372, location!!.latitude, 1e-6)
        assertEquals(11.5756, location.longitude, 1e-6)
    }

    @Test
    fun keepsTheSouthernAndWesternHemispheresStraight() {
        val file = jpeg()
        val sydney = munich.copy(lat = -33.8688, lon = -70.6693)
        DesktopJpegGpsWriter.writeGps(file, sydney)

        val location = gpsOf(file).geoLocation!!
        assertEquals(-33.8688, location.latitude, 1e-6)
        assertEquals(-70.6693, location.longitude, 1e-6)
    }

    @Test
    fun writesAltitudeAndItsReference() {
        val file = jpeg()
        DesktopJpegGpsWriter.writeGps(file, munich)

        val gps = gpsOf(file)
        assertEquals(519.4, gps.getRational(GpsDirectory.TAG_ALTITUDE).toDouble(), 1e-3)
        assertEquals(0, gps.getInt(GpsDirectory.TAG_ALTITUDE_REF))
    }

    @Test
    fun marksAltitudesBelowSeaLevel() {
        val file = jpeg()
        DesktopJpegGpsWriter.writeGps(file, munich.copy(ele = -430.5))
        val gps = gpsOf(file)
        assertEquals(430.5, gps.getRational(GpsDirectory.TAG_ALTITUDE).toDouble(), 1e-3)
        assertEquals(1, gps.getInt(GpsDirectory.TAG_ALTITUDE_REF))
    }

    @Test
    fun writesTheTimestampInUtc() {
        val file = jpeg()
        DesktopJpegGpsWriter.writeGps(file, munich)

        val gps = gpsOf(file)
        assertEquals("2026:07:17", gps.getString(GpsDirectory.TAG_DATE_STAMP))
        val time = gps.getRationalArray(GpsDirectory.TAG_TIME_STAMP).map { it.toDouble().toInt() }
        assertEquals(listOf(9, 2, 30), time)
    }

    @Test
    fun agreesWithTheValuesTheAndroidBackendWouldWrite() {
        // Both platforms derive their tags from ExifGpsFormat; this pins the
        // desktop output to the same numbers.
        val file = jpeg()
        DesktopJpegGpsWriter.writeGps(file, munich)

        val dms = ExifGpsFormat.toDms(munich.lat)
        val readBack = gpsOf(file).getRationalArray(GpsDirectory.TAG_LATITUDE)
        assertEquals(dms.degrees.toDouble(), readBack[0].toDouble(), 1e-9)
        assertEquals(dms.minutes.toDouble(), readBack[1].toDouble(), 1e-9)
        assertEquals(dms.secondsE4 / 10000.0, readBack[2].toDouble(), 1e-4)
    }

    @Test
    fun tagsWrittenTwiceDoNotAccumulate() {
        val file = jpeg()
        DesktopJpegGpsWriter.writeGps(file, munich)
        DesktopJpegGpsWriter.writeGps(file, munich.copy(lat = 1.0, lon = 2.0))

        val location = gpsOf(file).geoLocation!!
        assertEquals(1.0, location.latitude, 1e-6)
        assertEquals(2.0, location.longitude, 1e-6)
    }

    @Test
    fun leavesTheImageItselfDecodable() {
        val file = jpeg()
        DesktopJpegGpsWriter.writeGps(file, munich)
        val image = ImageIO.read(file)
        assertNotNull("image must still decode after the EXIF rewrite", image)
        assertEquals(8, image.width)
    }

    @Test
    fun theBuiltInChainRoutesJpegHereAndSidecarsWhatItCannotEmbed() {
        val writer = BuiltInGpsWriter(DesktopJpegGpsWriter)
        assertTrue(writer.supports(File("a.jpg")))
        assertTrue(writer.supports(File("a.RAF")))
        assertTrue(writer.supports(File("a.mp4")))
        assertTrue("CR3 is exiftool's job, not the built-in writer's", !writer.supports(File("a.cr3")))

        val file = jpeg("routed.jpg")
        val result = writer.write(file, munich)
        assertTrue(result is GpsWriteResult.Embedded)
        assertEquals(48.1372, gpsOf(file).geoLocation!!.latitude, 1e-6)
    }
}
