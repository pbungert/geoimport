package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Matches photo capture times against a track. Positions between two track
 * points are linearly interpolated; outside the track's time range the nearest
 * endpoint is used if it is within [tolerance].
 *
 * Note that [tolerance] only clamps extrapolation past the ends of the track.
 * Inside the range a photo is always interpolated between its bracketing
 * points, however far apart in time those are.
 */
class Geotagger(track: List<TrackPoint>, private val tolerance: Duration) {

    private val points = track.sortedBy { it.time }

    fun locate(time: Instant): TrackPoint? {
        if (points.isEmpty()) return null
        val idx = points.indexOfFirst { !it.time.isBefore(time) }
        return when (idx) {
            -1 -> points.last().takeIf { Duration.between(it.time, time) <= tolerance }
            0 -> points.first().takeIf { Duration.between(time, points.first().time) <= tolerance }
            else -> interpolate(points[idx - 1], points[idx], time)
        }
    }

    private fun interpolate(a: TrackPoint, b: TrackPoint, time: Instant): TrackPoint {
        val span = Duration.between(a.time, b.time).toMillis()
        if (span == 0L) return a
        val f = Duration.between(a.time, time).toMillis().toDouble() / span
        return TrackPoint(
            time = time,
            lat = a.lat + (b.lat - a.lat) * f,
            lon = a.lon + (b.lon - a.lon) * f,
            ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * f else a.ele ?: b.ele,
        )
    }

    /** Writes `<photo>.xmp` next to the photo and returns the sidecar file. */
    fun writeSidecar(photo: File, point: TrackPoint): File {
        val sidecar = File(photo.parentFile, photo.nameWithoutExtension + ".xmp")
        sidecar.writeText(buildXmp(point))
        return sidecar
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
