package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Writes the position to an XMP sidecar. The universal fallback: it works for
 * any format, and desktop editors (Lightroom Classic, Capture One, darktable,
 * digiKam) honour it even where nothing can write into the container.
 *
 * The sidecar is named after the basename — `DSCF0001.RAF` becomes
 * `DSCF0001.xmp` — which is the Adobe convention Lightroom looks for. A RAW
 * and JPEG of the same shot therefore share one sidecar, which is harmless:
 * same shot, same capture time, same position, identical content.
 */
object XmpSidecarWriter : GpsWriter {

    override val name = "sidecar"

    override fun supports(file: File) = true

    override fun write(file: File, point: TrackPoint): GpsWriteResult {
        val sidecar = File(file.parentFile, file.nameWithoutExtension + ".xmp")
        sidecar.writeText(buildXmp(point))
        return GpsWriteResult.Sidecar(sidecar)
    }

    private fun buildXmp(p: TrackPoint): String = buildString {
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
        append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("  <rdf:Description rdf:about=\"\"\n")
        append("    xmlns:exif=\"http://ns.adobe.com/exif/1.0/\"\n")
        append("    exif:GPSVersionID=\"2.3.0.0\"\n")
        append("    exif:GPSLatitude=\"${formatCoordinate(p.lat, 'N', 'S')}\"\n")
        append("    exif:GPSLongitude=\"${formatCoordinate(p.lon, 'E', 'W')}\"\n")
        p.ele?.let { ele ->
            append("    exif:GPSAltitude=\"${(abs(ele) * 1000).roundToLong()}/1000\"\n")
            append("    exif:GPSAltitudeRef=\"${if (ele < 0) 1 else 0}\"\n")
        }
        append("  />\n")
        append(" </rdf:RDF>\n")
        append("</x:xmpmeta>\n")
    }

    /** XMP GPS format: degrees,decimal-minutes plus hemisphere, e.g. "48,7.634512N". */
    private fun formatCoordinate(value: Double, positive: Char, negative: Char): String {
        val hemisphere = if (value >= 0) positive else negative
        val abs = abs(value)
        val degrees = abs.toInt()
        val minutes = (abs - degrees) * 60
        return String.format(Locale.US, "%d,%.6f%c", degrees, minutes, hemisphere)
    }
}
