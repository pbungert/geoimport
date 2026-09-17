package com.pbungert.geoimport

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pbungert.geoimport.core.track.TrackParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parsing has to be exercised on a device, not only on the JVM: Android ships
 * Apache Harmony's SAX implementation, which rejects features Xerces accepts.
 * Setting FEATURE_SECURE_PROCESSING threw there and nowhere else, so every
 * track file failed to parse on a phone while the desktop tests stayed green.
 */
@RunWith(AndroidJUnit4::class)
class TrackParserAndroidTest {

    @Test
    fun parsesAGpxTrackOnDevice() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Geoimport" xmlns="http://www.topografix.com/GPX/1/1">
             <trk><name>test</name><trkseg>
              <trkpt lat="47.3769" lon="8.5417"><time>2026-09-16T08:00:00Z</time></trkpt>
              <trkpt lat="47.3789" lon="8.5437"><time>2026-09-16T08:05:00Z</time></trkpt>
             </trkseg></trk>
            </gpx>
        """.trimIndent()

        val track = TrackParser.parse(gpx.byteInputStream())

        assertEquals(2, track.size)
        assertEquals(47.3769, track.points.first().lat, 1e-6)
    }

    /** A DOCTYPE is still refused, which is the hardening that matters. */
    @Test
    fun refusesADoctype() {
        val withDoctype = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE gpx [<!ENTITY x "boom">]>
            <gpx version="1.1"><trk><trkseg>
             <trkpt lat="1.0" lon="2.0"><time>2026-09-16T08:00:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()

        val failed = runCatching { TrackParser.parse(withDoctype.byteInputStream()) }.isFailure

        assertEquals(true, failed)
    }
}
