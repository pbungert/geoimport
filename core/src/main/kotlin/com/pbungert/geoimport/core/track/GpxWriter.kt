package com.pbungert.geoimport.core.track

import com.pbungert.geoimport.core.model.TrackPoint
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Incrementally writes a GPX 1.1 file that stays well-formed after every
 * point: each write puts the new trkpt and the closing tags down in a single
 * call and syncs to disk, so a process kill never leaves a truncated file.
 * Opening an existing file resumes it by appending further points to the same
 * segment, and repairs it first if it does not end the way this writer leaves
 * it - see [repair].
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
            footerOffset = repair()
        }
    }

    /**
     * Where the closing tags of an existing file begin, cutting it back to the
     * last complete point first if they are not where they should be.
     *
     * Assuming the tags are there when they are not overwrites the end of the
     * last point on the next write, and then the parser rejects the whole
     * file - a day's recording lost to one interrupted write. Whatever follows
     * the last complete point or segment opening is a write that never
     * finished, so it goes.
     */
    private fun repair(): Long {
        val length = raf.length()
        val tailSize = minOf(length, REPAIR_WINDOW.toLong()).toInt()
        val tail = ByteArray(tailSize)
        raf.seek(length - tailSize)
        raf.readFully(tail)
        if (tail.endsWith(FOOTER)) return length - FOOTER.size

        val text = String(tail, StandardCharsets.ISO_8859_1)
        val cut = listOf(POINT_END, SEGMENT_START)
            .map { marker -> text.lastIndexOf(marker).let { if (it < 0) -1 else it + marker.length } }
            .max()
            .takeIf { it >= 0 }
            ?: throw IOException("${file.name} is not a track this writer can resume")
        val offset = length - tailSize + cut
        raf.seek(offset)
        raf.write(FOOTER)
        raf.setLength(offset + FOOTER.size)
        raf.fd.sync()
        return offset
    }

    private fun ByteArray.endsWith(suffix: ByteArray) =
        size >= suffix.size && suffix.indices.all { this[size - suffix.size + it] == suffix[it] }

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
        insert(sb.toString().toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Closes the current track segment and opens a fresh one, so a pause shows
     * up as a gap between segments instead of a straight line bridging it.
     */
    fun startNewSegment() = insert(SEGMENT_BREAK)

    /**
     * Puts [bytes] in front of the closing tags. One write for both, because a
     * kill between two would leave the file with no closing tags at all.
     */
    private fun insert(bytes: ByteArray) {
        raf.seek(footerOffset)
        raf.write(bytes + FOOTER)
        footerOffset += bytes.size
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

        /** What a complete point, and a freshly opened segment, end with. */
        private const val POINT_END = "</trkpt>\n"
        private const val SEGMENT_START = "<trkseg>\n"

        /** Far more than one point, which is all a repair ever has to cut. */
        private const val REPAIR_WINDOW = 64 * 1024
    }
}
