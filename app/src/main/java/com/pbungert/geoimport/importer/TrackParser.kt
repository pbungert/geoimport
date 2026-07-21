package com.pbungert.geoimport.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

data class TrackPoint(
    val time: Instant,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
)

/**
 * Parses timestamped positions from a GPX file (trkpt/rtept/wpt with a time
 * element) or a KML file (gx:Track when/coord pairs, or Placemarks with a
 * TimeStamp and a Point). Points without a usable timestamp are skipped.
 */
object TrackParser {

    fun parse(input: InputStream): List<TrackPoint> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        parser.nextTag()
        return when (parser.name?.lowercase()) {
            "gpx" -> parseGpx(parser)
            "kml" -> parseKml(parser)
            else -> throw IllegalArgumentException(
                "Unsupported track format: root element '${parser.name}' (expected gpx or kml)"
            )
        }.sortedBy { it.time }
    }

    private fun parseGpx(parser: XmlPullParser): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        var inPoint = false
        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        var time: Instant? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkpt", "rtept", "wpt" -> {
                        val pLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val pLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (pLat != null && pLon != null) {
                            inPoint = true
                            lat = pLat
                            lon = pLon
                            ele = null
                            time = null
                        }
                    }
                    "ele" -> if (inPoint) ele = parser.nextText().trim().toDoubleOrNull()
                    "time" -> if (inPoint) time = parseTime(parser.nextText())
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "trkpt", "rtept", "wpt" -> {
                        if (inPoint) {
                            time?.let { points.add(TrackPoint(it, lat, lon, ele)) }
                            inPoint = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        return points
    }

    private fun parseKml(parser: XmlPullParser): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        val whens = mutableListOf<Instant?>()
        val coords = mutableListOf<DoubleArray?>()
        var trackDepth = 0
        var inTimeStamp = false
        var inPoint = false
        var placemarkWhen: Instant? = null
        var placemarkCoord: DoubleArray? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Track" -> trackDepth++
                    "TimeStamp" -> inTimeStamp = true
                    "Point" -> inPoint = true
                    "when" -> {
                        val t = parseTime(parser.nextText())
                        if (trackDepth > 0) whens.add(t)
                        else if (inTimeStamp) placemarkWhen = t
                    }
                    "coord" -> if (trackDepth > 0) {
                        coords.add(parseCoord(parser.nextText(), commaSeparated = false))
                    }
                    "coordinates" -> if (inPoint) {
                        placemarkCoord = parseCoord(parser.nextText(), commaSeparated = true)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "Track" -> {
                        trackDepth--
                        if (trackDepth == 0) {
                            for (i in 0 until minOf(whens.size, coords.size)) {
                                val t = whens[i]
                                val c = coords[i]
                                // KML coordinates are lon,lat[,alt]
                                if (t != null && c != null) {
                                    points.add(TrackPoint(t, c[1], c[0], c.getOrNull(2)))
                                }
                            }
                            whens.clear()
                            coords.clear()
                        }
                    }
                    "TimeStamp" -> inTimeStamp = false
                    "Point" -> inPoint = false
                    "Placemark" -> {
                        val t = placemarkWhen
                        val c = placemarkCoord
                        if (t != null && c != null) {
                            points.add(TrackPoint(t, c[1], c[0], c.getOrNull(2)))
                        }
                        placemarkWhen = null
                        placemarkCoord = null
                    }
                }
            }
            event = parser.next()
        }
        return points
    }

    private fun parseCoord(text: String, commaSeparated: Boolean): DoubleArray? {
        val parts = text.trim()
            .split(if (commaSeparated) Regex(",") else Regex("""\s+"""))
            .mapNotNull { it.trim().toDoubleOrNull() }
        return if (parts.size >= 2) parts.toDoubleArray() else null
    }

    private fun parseTime(text: String): Instant? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return try {
            Instant.parse(t)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(t).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(t).atZone(ZoneId.systemDefault()).toInstant()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }
}
