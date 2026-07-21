package com.pbungert.geoimport.recorder

import android.location.Location
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.time.Instant
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

    fun addPoint(location: Location) {
        val sb = StringBuilder()
        sb.append(
            String.format(
                Locale.US, "   <trkpt lat=\"%.7f\" lon=\"%.7f\">\n",
                location.latitude, location.longitude,
            )
        )
        if (location.hasAltitude()) {
            sb.append(String.format(Locale.US, "    <ele>%.1f</ele>\n", location.altitude))
        }
        sb.append("    <time>").append(Instant.ofEpochMilli(location.time)).append("</time>\n")
        sb.append("   </trkpt>\n")
        val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        raf.seek(footerOffset)
        raf.write(bytes)
        footerOffset += bytes.size
        raf.write(FOOTER)
        raf.setLength(footerOffset + FOOTER.size)
        raf.fd.sync()
    }

    fun close() = raf.close()

    private fun escapeXml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"Geoimport\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
            " <trk>\n" +
            "  <name>%s</name>\n" +
            "  <trkseg>\n"
        val FOOTER = "  </trkseg>\n </trk>\n</gpx>\n".toByteArray(StandardCharsets.UTF_8)
    }
}
