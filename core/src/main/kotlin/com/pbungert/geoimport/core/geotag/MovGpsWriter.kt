package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Writes GPS coordinates into a QuickTime/MP4 container in place.
 *
 * The position goes into `moov/udta` as a `©xyz` box holding an ISO 6709
 * string — the tag Apple, Android and most cameras write, and the one
 * Lightroom, Photos and exiftool read back.
 *
 * The hazard is that growing `moov` shifts everything after it. When `moov`
 * sits before `mdat` (the streaming-friendly layout), every chunk offset in
 * `stco`/`co64` has to be shifted by the same delta or the file still plays
 * but decodes garbage. Cameras usually append `moov` last, where nothing
 * moves; both layouts are handled.
 */
object MovGpsWriter {

    fun writeGps(file: File, point: TrackPoint) {
        val lastModified = file.lastModified()
        val temp = File(file.parentFile, file.name + ".gps.tmp")

        try {
            RandomAccessFile(file, "r").use { input ->
                val fileLen = input.length()
                val top = readBoxes(input, 0, fileLen)
                if (top.none { it.type == "ftyp" || it.type == "moov" }) {
                    throw IOException("not an ISO base media file (no ftyp/moov)")
                }
                val moov = top.firstOrNull { it.type == "moov" }
                    ?: throw IOException("no moov box")

                val oldMoov = ByteArray(moov.size.toInt())
                input.seek(moov.start)
                input.readFully(oldMoov)

                var newMoov = rebuildMoovWithLocation(oldMoov, iso6709(point))
                val delta = newMoov.size - oldMoov.size

                // Media data living after moov moves by exactly delta.
                if (delta != 0 && top.any { it.type == "mdat" && it.start > moov.start }) {
                    newMoov = shiftChunkOffsets(newMoov, delta.toLong(), moov.start)
                }

                temp.outputStream().buffered().use { out ->
                    copyRange(input, 0, moov.start, out)
                    out.write(newMoov)
                    copyRange(input, moov.start + moov.size, fileLen, out)
                }
            }

            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            file.setLastModified(lastModified)
        } finally {
            temp.delete()
        }
    }

    /**
     * ISO 6709 as QuickTime wants it: fixed-width signed latitude, longitude
     * and optional altitude, terminated by a solidus — e.g.
     * `+48.1372+011.5756+519.400/`.
     */
    internal fun iso6709(point: TrackPoint): String = buildString {
        append(String.format(Locale.US, "%+08.4f", point.lat))
        append(String.format(Locale.US, "%+09.4f", point.lon))
        point.ele?.let { append(String.format(Locale.US, "%+08.3f", it)) }
        append('/')
    }

    // --- box model -------------------------------------------------------

    private class Box(val type: String, val start: Long, val size: Long, val headerSize: Int)

    /** Boxes recursed into when hunting for stco/co64; leaves are left alone. */
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")

    private fun readBoxes(input: RandomAccessFile, from: Long, to: Long): List<Box> {
        val boxes = mutableListOf<Box>()
        var pos = from
        val header = ByteArray(16)
        while (pos + 8 <= to) {
            input.seek(pos)
            input.readFully(header, 0, 8)
            var size = readU32(header, 0)
            var headerSize = 8
            when (size) {
                1L -> {
                    if (pos + 16 > to) break
                    input.readFully(header, 8, 8)
                    size = readU64(header, 8)
                    headerSize = 16
                }
                0L -> size = to - pos // extends to the end of its parent
            }
            if (size < headerSize || pos + size > to) break
            boxes.add(Box(typeAt(header, 0), pos, size, headerSize))
            pos += size
        }
        return boxes
    }

    private fun readBoxes(buf: ByteArray, from: Int, to: Int): List<Box> {
        val boxes = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            var size = readU32(buf, pos)
            var headerSize = 8
            when (size) {
                1L -> {
                    if (pos + 16 > to) break
                    size = readU64(buf, pos + 8)
                    headerSize = 16
                }
                0L -> size = (to - pos).toLong()
            }
            if (size < headerSize || pos + size > to) break
            boxes.add(Box(typeAt(buf, pos), pos.toLong(), size, headerSize))
            pos += size.toInt()
        }
        return boxes
    }

    // --- moov rewriting --------------------------------------------------

    /** Replaces (or adds) moov/udta/©xyz, returning the whole rebuilt moov box. */
    private fun rebuildMoovWithLocation(moov: ByteArray, location: String): ByteArray {
        val children = readBoxes(moov, headerSizeOf(moov), moov.size)
        val udta = children.firstOrNull { it.type == "udta" }

        val newUdta = if (udta == null) {
            box("udta", locationBox(location))
        } else {
            val keep = ByteArrayOutputStream()
            for (child in readBoxes(moov, (udta.start + udta.headerSize).toInt(), (udta.start + udta.size).toInt())) {
                if (child.type == LOCATION_TYPE) continue // replaced below
                keep.write(moov, child.start.toInt(), child.size.toInt())
            }
            keep.write(locationBox(location))
            box("udta", keep.toByteArray())
        }

        val payload = ByteArrayOutputStream()
        var replaced = false
        for (child in children) {
            if (child.type == "udta") {
                payload.write(newUdta)
                replaced = true
            } else {
                payload.write(moov, child.start.toInt(), child.size.toInt())
            }
        }
        if (!replaced) payload.write(newUdta)

        return box("moov", payload.toByteArray())
    }

    /** `©xyz` payload is [u16 byte length][u16 language][utf8 text]. */
    private fun locationBox(location: String): ByteArray {
        val text = location.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(4 + text.size)
        writeU16(payload, 0, text.size)
        writeU16(payload, 2, LANGUAGE_ENG)
        text.copyInto(payload, 4)
        return box(LOCATION_TYPE, payload)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU32(out, 0, (8 + payload.size).toLong())
        type.toByteArray(StandardCharsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    // --- chunk offset patching -------------------------------------------

    /**
     * Adds [delta] to every stco/co64 entry pointing at or past [movedFrom],
     * i.e. into the region the larger moov pushed down the file.
     */
    private fun shiftChunkOffsets(moov: ByteArray, delta: Long, movedFrom: Long): ByteArray {
        fun walk(from: Int, to: Int) {
            for (box in readBoxes(moov, from, to)) {
                val payloadStart = (box.start + box.headerSize).toInt()
                val payloadEnd = (box.start + box.size).toInt()
                when (box.type) {
                    in CONTAINERS -> walk(payloadStart, payloadEnd)
                    "stco" -> {
                        val count = readU32(moov, payloadStart + 4).toInt()
                        for (i in 0 until count) {
                            val at = payloadStart + 8 + i * 4
                            if (at + 4 > payloadEnd) break
                            val o = readU32(moov, at)
                            if (o >= movedFrom) writeU32(moov, at, o + delta)
                        }
                    }
                    "co64" -> {
                        val count = readU32(moov, payloadStart + 4).toInt()
                        for (i in 0 until count) {
                            val at = payloadStart + 8 + i * 8
                            if (at + 8 > payloadEnd) break
                            val o = readU64(moov, at)
                            if (o >= movedFrom) writeU64(moov, at, o + delta)
                        }
                    }
                }
            }
        }
        walk(headerSizeOf(moov), moov.size)
        return moov
    }

    // --- primitives ------------------------------------------------------

    private fun headerSizeOf(box: ByteArray) = if (readU32(box, 0) == 1L) 16 else 8

    /** The four-character type of the box starting at [boxStart]. */
    private fun typeAt(buf: ByteArray, boxStart: Int) =
        String(buf, boxStart + 4, 4, StandardCharsets.ISO_8859_1)

    private fun copyRange(input: RandomAccessFile, from: Long, to: Long, out: java.io.OutputStream) {
        if (to <= from) return
        input.seek(from)
        val buffer = ByteArray(64 * 1024)
        var remaining = to - from
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readU16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte()
        b[at + 1] = v.toByte()
    }

    private fun readU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)

    private fun writeU32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun readU64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun writeU64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
    }

    /** `©xyz` — the © is byte 0xA9, hence the Latin-1 round trip everywhere. */
    private const val LOCATION_TYPE = "©xyz"

    /** Packed ISO-639-2/T "eng", the value Apple and exiftool write. */
    private const val LANGUAGE_ENG = 0x15C7
}
