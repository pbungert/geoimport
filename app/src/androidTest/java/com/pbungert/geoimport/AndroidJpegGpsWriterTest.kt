package com.pbungert.geoimport

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.platform.AndroidJpegGpsWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AndroidJpegGpsWriterTest {

    private val time = Instant.parse("2026-09-16T08:00:00Z")

    private fun jpeg(): File {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(dir, "tag-test.jpg").apply {
            outputStream().use {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
        }
    }

    /** Re-tagging with a fix that has no altitude must not keep the old one. */
    @Test
    fun retaggingWithoutAnAltitudeRemovesTheOldOne() {
        val file = jpeg()
        AndroidJpegGpsWriter.writeGps(file, TrackPoint(time, 47.0, 8.0, 512.0))
        assertEquals(512.0, ExifInterface(file).getAltitude(0.0), 0.001)

        AndroidJpegGpsWriter.writeGps(file, TrackPoint(time, 47.1, 8.1, null))

        val exif = ExifInterface(file)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF))
        assertEquals(47.1, exif.latLong!![0], 1e-6)
    }
}
