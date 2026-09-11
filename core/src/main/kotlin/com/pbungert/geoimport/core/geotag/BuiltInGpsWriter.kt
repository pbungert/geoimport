package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.core.spi.JpegGpsWriter
import java.io.File

/**
 * The portable writer: identical behaviour on Android and desktop, with no
 * external tooling. Handles the formats that can be written without a
 * per-vendor container rewrite.
 *
 * Everything else is left to a chained fallback (normally [XmpSidecarWriter]),
 * because embedding GPS into CR3/NEF/ARW/HEIC means reimplementing each
 * vendor's container — the job exiftool exists to do.
 */
class BuiltInGpsWriter(jpegGpsWriter: JpegGpsWriter) : GpsWriter {

    private val rafGpsWriter = RafGpsWriter(jpegGpsWriter)
    private val jpeg = jpegGpsWriter

    override val name = "built-in"

    override fun supports(file: File) = file.extension.lowercase() in SUPPORTED

    override fun write(file: File, point: TrackPoint): GpsWriteResult {
        when (file.extension.lowercase()) {
            "raf" -> rafGpsWriter.writeGps(file, point)
            "jpg", "jpeg" -> jpeg.writeGps(file, point)
            "mov", "mp4" -> MovGpsWriter.writeGps(file, point)
            else -> throw IllegalArgumentException("unsupported format: ${file.name}")
        }
        return GpsWriteResult.Embedded(file)
    }

    private companion object {
        val SUPPORTED = setOf("raf", "jpg", "jpeg", "mov", "mp4")
    }
}
