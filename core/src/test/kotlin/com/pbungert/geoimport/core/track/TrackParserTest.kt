package com.pbungert.geoimport.core.track

import com.pbungert.geoimport.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

/**
 * Pins the parse semantics carried over from the XmlPullParser implementation:
 * element sets, coordinate ordering, the time-parsing ladder and the dropping
 * of untimed points.
 */
class TrackParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun parse(xml: String, zone: ZoneId = ZoneId.of("UTC")) =
        TrackParser.parse(xml.trimIndent().byteInputStream(), zone).points

    @Test
    fun roundTripsAccuracyThroughTheWriter() {
        val file = tmp.newFile("acc.gpx")
        file.delete()
        val writer = GpxWriter(file, "acc")
        writer.addPoint(TrackPoint(Instant.parse("2026-09-12T14:19:44Z"), 46.46, 9.93, 1918.9, 12.5))
        writer.close()

        val read = TrackParser.parse(file.inputStream(), ZoneId.of("UTC")).points.single()
        assertEquals(12.5, read.accuracy!!, 0.05)
        assertEquals(46.46, read.lat, 1e-6)
        assertEquals(1918.9, read.ele!!, 0.05)
    }

    @Test
    fun leavesAccuracyNullWhenTheFileHasNone() {
        val points = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
             <trk><trkseg>
              <trkpt lat="1.0" lon="2.0"><time>2026-01-01T00:00:00Z</time></trkpt>
             </trkseg></trk>
            </gpx>
            """
        )
        assertEquals(null, points.single().accuracy)
    }

    @Test
    fun readsTrkptWithElevationAndTime() {
        val points = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
             <trk><trkseg>
              <trkpt lat="48.1372" lon="11.5756"><ele>519.4</ele><time>2026-07-17T09:00:00Z</time></trkpt>
              <trkpt lat="48.1400" lon="11.5800"><ele>521.0</ele><time>2026-07-17T09:01:00Z</time></trkpt>
             </trkseg></trk>
            </gpx>
            """
        )
        assertEquals(2, points.size)
        assertEquals(TrackPoint(Instant.parse("2026-07-17T09:00:00Z"), 48.1372, 11.5756, 519.4), points[0])
        assertEquals(521.0, points[1].ele!!, 1e-9)
    }

    @Test
    fun acceptsRteptAndWptToo() {
        val points = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
             <wpt lat="1.0" lon="2.0"><time>2026-01-01T00:00:00Z</time></wpt>
             <rte><rtept lat="3.0" lon="4.0"><time>2026-01-01T00:01:00Z</time></rtept></rte>
            </gpx>
            """
        )
        assertEquals(listOf(1.0, 3.0), points.map { it.lat })
    }

    @Test
    fun dropsPointsWithoutAParseableTime() {
        val points = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
             <trk><trkseg>
              <trkpt lat="1.0" lon="2.0"/>
              <trkpt lat="3.0" lon="4.0"><time>not-a-time</time></trkpt>
              <trkpt lat="5.0" lon="6.0"><time>2026-01-01T00:00:00Z</time></trkpt>
             </trkseg></trk>
            </gpx>
            """
        )
        assertEquals(listOf(5.0), points.map { it.lat })
    }

    @Test
    fun elevationIsOptionalAndDoesNotLeakToTheNextPoint() {
        val points = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
             <trk><trkseg>
              <trkpt lat="1.0" lon="2.0"><ele>100.0</ele><time>2026-01-01T00:00:00Z</time></trkpt>
              <trkpt lat="3.0" lon="4.0"><time>2026-01-01T00:01:00Z</time></trkpt>
             </trkseg></trk>
            </gpx>
            """
        )
        assertEquals(100.0, points[0].ele!!, 1e-9)
        assertEquals(null, points[1].ele)
    }

    @Test
    fun segmentsAreFlattenedAndPointsSortedByTime() {
        val points = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
             <trk>
              <trkseg><trkpt lat="9.0" lon="9.0"><time>2026-01-01T00:05:00Z</time></trkpt></trkseg>
              <trkseg><trkpt lat="1.0" lon="1.0"><time>2026-01-01T00:00:00Z</time></trkpt></trkseg>
             </trk>
            </gpx>
            """
        )
        assertEquals(listOf(1.0, 9.0), points.map { it.lat })
    }

    @Test
    fun keepsTrksegBoundariesAsSeparateSegments() {
        val track = TrackParser.parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
             <trk>
              <trkseg>
               <trkpt lat="1.0" lon="1.0"><time>2026-01-01T00:00:00Z</time></trkpt>
               <trkpt lat="2.0" lon="2.0"><time>2026-01-01T00:01:00Z</time></trkpt>
              </trkseg>
              <trkseg><trkpt lat="9.0" lon="9.0"><time>2026-01-01T05:00:00Z</time></trkpt></trkseg>
             </trk>
            </gpx>
            """.trimIndent().byteInputStream(),
            ZoneId.of("UTC"),
        )
        assertEquals(listOf(2, 1), track.segments.map { it.size })
    }

    @Test
    fun looseWaypointsDoNotRunIntoTheFollowingSegment() {
        val track = TrackParser.parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
             <wpt lat="1.0" lon="2.0"><time>2026-01-01T00:00:00Z</time></wpt>
             <trk><trkseg>
              <trkpt lat="3.0" lon="4.0"><time>2026-01-01T00:01:00Z</time></trkpt>
             </trkseg></trk>
            </gpx>
            """.trimIndent().byteInputStream(),
            ZoneId.of("UTC"),
        )
        assertEquals(2, track.segments.size)
    }

    @Test
    fun eachKmlGxTrackIsItsOwnSegment() {
        val track = TrackParser.parse(
            """
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
             <Document>
              <Placemark><gx:Track>
               <when>2026-07-17T09:00:00Z</when><gx:coord>11.0 48.0</gx:coord>
              </gx:Track></Placemark>
              <Placemark><gx:Track>
               <when>2026-07-17T18:00:00Z</when><gx:coord>12.0 49.0</gx:coord>
              </gx:Track></Placemark>
             </Document>
            </kml>
            """.trimIndent().byteInputStream(),
            ZoneId.of("UTC"),
        )
        assertEquals(listOf(1, 1), track.segments.map { it.size })
    }

    @Test
    fun zonelessTimesUseTheSuppliedZoneNotTheMachineZone() {
        val points = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
             <trk><trkseg>
              <trkpt lat="1.0" lon="2.0"><time>2026-07-17T12:00:00</time></trkpt>
             </trkseg></trk>
            </gpx>
            """,
            zone = ZoneId.of("Europe/Berlin"),
        )
        // Berlin is UTC+2 in July.
        assertEquals(Instant.parse("2026-07-17T10:00:00Z"), points.single().time)
    }

    @Test
    fun acceptsOffsetTimes() {
        val points = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
             <trk><trkseg>
              <trkpt lat="1.0" lon="2.0"><time>2026-07-17T12:00:00+02:00</time></trkpt>
             </trkseg></trk>
            </gpx>
            """
        )
        assertEquals(Instant.parse("2026-07-17T10:00:00Z"), points.single().time)
    }

    @Test
    fun kmlGxTrackPairsWhenWithCoordAndSwapsLonLat() {
        val points = parse(
            """
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
             <Document><Placemark><gx:Track>
              <when>2026-07-17T09:00:00Z</when>
              <gx:coord>11.5756 48.1372 519.4</gx:coord>
              <when>2026-07-17T09:01:00Z</when>
              <gx:coord>11.5800 48.1400 521.0</gx:coord>
             </gx:Track></Placemark></Document>
            </kml>
            """
        )
        assertEquals(2, points.size)
        // KML is lon,lat[,alt] — lat must come back as 48.x, not 11.x.
        assertEquals(48.1372, points[0].lat, 1e-9)
        assertEquals(11.5756, points[0].lon, 1e-9)
        assertEquals(519.4, points[0].ele!!, 1e-9)
    }

    @Test
    fun kmlTrackZipsToTheShorterOfWhensAndCoords() {
        val points = parse(
            """
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
             <Document><Placemark><gx:Track>
              <when>2026-07-17T09:00:00Z</when>
              <gx:coord>11.0 48.0</gx:coord>
              <when>2026-07-17T09:01:00Z</when>
             </gx:Track></Placemark></Document>
            </kml>
            """
        )
        assertEquals(1, points.size)
    }

    @Test
    fun kmlPlacemarkWithTimeStampAndPoint() {
        val points = parse(
            """
            <kml xmlns="http://www.opengis.net/kml/2.2">
             <Document><Placemark>
              <TimeStamp><when>2026-07-17T09:00:00Z</when></TimeStamp>
              <Point><coordinates>11.5756,48.1372,519.4</coordinates></Point>
             </Placemark></Document>
            </kml>
            """
        )
        val p = points.single()
        assertEquals(48.1372, p.lat, 1e-9)
        assertEquals(11.5756, p.lon, 1e-9)
        assertEquals(519.4, p.ele!!, 1e-9)
    }

    @Test
    fun rejectsAnUnknownRootElement() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            parse("<nmea><sentence/></nmea>")
        }
        assertTrue(e.message!!.contains("Unsupported track format"))
    }

    @Test
    fun roundTripsWhatGpxWriterProduces() {
        val file = tmp.newFile("round-trip.gpx")
        file.delete()
        val written = listOf(
            TrackPoint(Instant.parse("2026-07-17T09:00:00Z"), 48.1372000, 11.5756000, 519.4),
            TrackPoint(Instant.parse("2026-07-17T09:01:00Z"), 48.1400000, 11.5800000, null),
        )
        val writer = GpxWriter(file, "round & trip <test>")
        written.forEach { writer.addPoint(it) }
        writer.startNewSegment()
        writer.addPoint(TrackPoint(Instant.parse("2026-07-17T09:02:00Z"), -33.8688, 151.2093, -5.0))
        writer.close()

        val read = TrackParser.parse(file.inputStream(), ZoneId.of("UTC")).points
        assertEquals(3, read.size)
        assertEquals(written[0], read[0])
        assertEquals(written[1].lat, read[1].lat, 1e-7)
        assertEquals(null, read[1].ele)
        assertEquals(-33.8688, read[2].lat, 1e-7)
        assertEquals(-5.0, read[2].ele!!, 1e-9)
    }
}
