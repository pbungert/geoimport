package com.pbungert.geoimport.core.track

import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.core.model.TrackPoint
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/**
 * Parses timestamped positions from a GPX file (trkpt/rtept/wpt with a time
 * element) or a KML file (gx:Track when/coord pairs, or Placemarks with a
 * TimeStamp and a Point). Points without a usable timestamp are skipped.
 *
 * The file's own segmentation is preserved: each trkseg, rte and gx:Track
 * becomes one segment of the returned [Track], and loose waypoints or
 * timestamped placemarks become another. What that buys is described on
 * [Track] itself.
 *
 * Uses SAX rather than StAX because this runs on Android too, where
 * javax.xml.stream does not exist.
 */
object TrackParser {

    /**
     * [zoneForLocalTimes] interprets timestamps that carry no offset. Defaults
     * to the machine's zone, which is only right when the track was recorded
     * where the import runs.
     */
    fun parse(
        input: InputStream,
        zoneForLocalTimes: ZoneId = ZoneId.systemDefault(),
    ): Track {
        val handler = TrackHandler(zoneForLocalTimes)
        try {
            newParser().parse(input, handler)
        } catch (e: SAXException) {
            (e.cause as? IllegalArgumentException)?.let { throw it }
            throw IllegalArgumentException("Could not parse track file: ${e.message}", e)
        }
        return Track(handler.finish())
    }

    private fun newParser() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newSAXParser()

    private class TrackHandler(private val zone: ZoneId) : DefaultHandler() {

        private val segments = mutableListOf<List<TrackPoint>>()
        private val current = mutableListOf<TrackPoint>()

        /** Ends the segment being collected; empty ones are dropped. */
        private fun endSegment() {
            if (current.isNotEmpty()) {
                segments.add(current.sortedBy { it.time })
                current.clear()
            }
        }

        fun finish(): List<List<TrackPoint>> {
            endSegment()
            return segments.toList()
        }

        private var isGpx = false
        private var rootSeen = false
        private val text = StringBuilder()

        // GPX state
        private var inPoint = false
        private var lat = 0.0
        private var lon = 0.0
        private var ele: Double? = null
        private var time: Instant? = null
        private var accuracy: Double? = null

        // KML state
        private val whens = mutableListOf<Instant?>()
        private val coords = mutableListOf<DoubleArray?>()
        private var trackDepth = 0
        private var inTimeStamp = false
        private var inKmlPoint = false
        private var placemarkWhen: Instant? = null
        private var placemarkCoord: DoubleArray? = null

        override fun startElement(uri: String?, localName: String, qName: String?, attrs: Attributes) {
            if (!rootSeen) {
                rootSeen = true
                when (localName.lowercase()) {
                    "gpx" -> isGpx = true
                    "kml" -> isGpx = false
                    else -> throw SAXException(
                        IllegalArgumentException(
                            "Unsupported track format: root element '$localName' (expected gpx or kml)"
                        )
                    )
                }
            }
            text.setLength(0)

            if (isGpx) {
                when (localName) {
                    // A fresh stretch of recording starts here, so whatever was
                    // being collected before it - loose waypoints, the previous
                    // segment - must not run into it.
                    "trkseg", "rte" -> endSegment()
                    "trkpt", "rtept", "wpt" -> {
                        val pLat = attrs.getValue("lat")?.toDoubleOrNull()
                        val pLon = attrs.getValue("lon")?.toDoubleOrNull()
                        if (pLat != null && pLon != null) {
                            inPoint = true
                            lat = pLat
                            lon = pLon
                            ele = null
                            time = null
                            accuracy = null
                        }
                    }
                }
            } else {
                when (localName) {
                    "Track" -> {
                        if (trackDepth == 0) endSegment()
                        trackDepth++
                    }
                    "TimeStamp" -> inTimeStamp = true
                    "Point" -> inKmlPoint = true
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String, qName: String?) {
            val body = text.toString()
            text.setLength(0)

            if (isGpx) {
                when (localName) {
                    "ele" -> if (inPoint) ele = body.trim().toDoubleOrNull()
                    "time" -> if (inPoint) time = parseTime(body)
                    "acc" -> if (inPoint && uri == GpxWriter.NS) {
                        accuracy = body.trim().toDoubleOrNull()
                    }
                    "trkpt", "rtept", "wpt" -> if (inPoint) {
                        time?.let { current.add(TrackPoint(it, lat, lon, ele, accuracy)) }
                        inPoint = false
                    }
                    "trkseg", "rte" -> endSegment()
                }
                return
            }

            when (localName) {
                "when" -> {
                    val t = parseTime(body)
                    if (trackDepth > 0) whens.add(t) else if (inTimeStamp) placemarkWhen = t
                }
                "coord" -> if (trackDepth > 0) coords.add(parseCoord(body, commaSeparated = false))
                "coordinates" -> if (inKmlPoint) {
                    placemarkCoord = parseCoord(body, commaSeparated = true)
                }
                "Track" -> {
                    trackDepth--
                    if (trackDepth == 0) {
                        for (i in 0 until minOf(whens.size, coords.size)) {
                            val t = whens[i]
                            val c = coords[i]
                            // KML coordinates are lon,lat[,alt]
                            if (t != null && c != null) {
                                current.add(TrackPoint(t, c[1], c[0], c.getOrNull(2)))
                            }
                        }
                        whens.clear()
                        coords.clear()
                        endSegment()
                    }
                }
                "TimeStamp" -> inTimeStamp = false
                "Point" -> inKmlPoint = false
                "Placemark" -> {
                    val t = placemarkWhen
                    val c = placemarkCoord
                    if (t != null && c != null) {
                        current.add(TrackPoint(t, c[1], c[0], c.getOrNull(2)))
                    }
                    placemarkWhen = null
                    placemarkCoord = null
                }
            }
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
                        LocalDateTime.parse(t).atZone(zone).toInstant()
                    } catch (_: DateTimeParseException) {
                        null
                    }
                }
            }
        }
    }
}
