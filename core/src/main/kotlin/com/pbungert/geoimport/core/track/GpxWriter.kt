package com.pbungert.geoimport.core.track

import com.pbungert.geoimport.core.model.TrackPoint
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Incrementally writes a GPX 1.1 file that stays well-formed after every
 * point: each write inserts the new trkpt in front of the closing tags and
 * syncs to disk, so a process kill never leaves a truncated file. Opening an
 * existing file resumes it by appending further points to the same segment.
 */
class GpxWriter(val file: File, trackName: String) {

    private val raf: RandomAccessFile
    private var footerOffset: Long

    init {
        file.parentFile?.mkdirs()
        val isNew = !file.exists() || file.length() < FOOTER.size
        raf = RandomAccessFile(file, "rw")
        if (isNew) {
            raf.write(HEADER.format(escapeXml(trackName)).toByteArray(StandardCharsets.UTF_8))
            footerOffset = raf.filePointer
            raf.write(FOOTER)
        } else {
            footerOffset = file.length() - FOOTER.size
        }
    }

    fun addPoint(point: TrackPoint) {
        val sb = StringBuilder()
        sb.append(
            String.format(
                Locale.US, "   <trkpt lat=\"%.7f\" lon=\"%.7f\">\n",
                point.lat, point.lon,
            )
        )
        point.ele?.let { sb.append(String.format(Locale.US, "    <ele>%.1f</ele>\n", it)) }
        sb.append("    <time>").append(point.time).append("</time>\n")
        // What the receiver said about its own fix, so a later pass can judge
        // the track from the file alone. Metres, so not GPX's dimensionless
        // <hdop>. Kept in an extension rather than fudged into the standard
        // elements - <speed> in particular means something else in GPX 1.0 -
        // and readers that do not know the namespace skip <extensions> whole.
        val ext = StringBuilder()
        point.accuracy?.let { ext.append(fmt("acc", "%.1f", it)) }
        point.eleAccuracy?.let { ext.append(fmt("eleacc", "%.1f", it)) }
        point.speed?.let { ext.append(fmt("speed", "%.2f", it)) }
        point.source?.let {
            ext.append("<geoimport:src>").append(escapeXml(it)).append("</geoimport:src>")
        }
        if (ext.isNotEmpty()) {
            sb.append("    <extensions>").append(ext).append("</extensions>\n")
        }
        sb.append("   </trkpt>\n")
        val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        raf.seek(footerOffset)
        raf.write(bytes)
        footerOffset += bytes.size
        raf.write(FOOTER)
        raf.setLength(footerOffset + FOOTER.size)
        raf.fd.sync()
    }

    /**
     * Closes the current track segment and opens a fresh one, so a pause shows
     * up as a gap between segments instead of a straight line bridging it.
     */
    fun startNewSegment() {
        raf.seek(footerOffset)
        raf.write(SEGMENT_BREAK)
        footerOffset += SEGMENT_BREAK.size
        raf.write(FOOTER)
        raf.setLength(footerOffset + FOOTER.size)
        raf.fd.sync()
    }

    fun close() = raf.close()

    private fun fmt(name: String, format: String, value: Double) = String.format(
        Locale.US, "<geoimport:%s>$format</geoimport:%s>", name, value, name,
    )

    private fun escapeXml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        /** Namespace for the accuracy extension; read back by TrackParser. */
        const val NS = "https://pbungert.github.io/geoimport/gpx/1"

        private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"Geoimport\"" +
            " xmlns=\"http://www.topografix.com/GPX/1/1\"" +
            " xmlns:geoimport=\"$NS\">\n" +
            " <trk>\n" +
            "  <name>%s</name>\n" +
            "  <trkseg>\n"
        private val FOOTER = "  </trkseg>\n </trk>\n</gpx>\n".toByteArray(StandardCharsets.UTF_8)
        private val SEGMENT_BREAK = "  </trkseg>\n  <trkseg>\n".toByteArray(StandardCharsets.UTF_8)
    }
}
