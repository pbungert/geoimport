package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Exercises the ISO-BMFF surgery with synthetic files: the ©xyz box itself,
 * and the chunk-offset patching that keeps media decodable when a grown moov
 * pushes mdat down the file.
 */
class MovGpsWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val point = TrackPoint(
        time = Instant.parse("2026-07-17T09:00:00Z"),
        lat = 48.1372,
        lon = 11.5756,
        ele = 519.4,
    )

    // --- fixtures --------------------------------------------------------

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU32(out, 0, 8 + payload.size)
        type.toByteArray(StandardCharsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** stco payload: version/flags, entry count, then one u32 per chunk. */
    private fun stco(chunks: Int): ByteArray {
        val payload = ByteArray(8 + chunks * 4)
        writeU32(payload, 4, chunks)
        return box("stco", payload)
    }

    private fun moov(udtaPayload: ByteArray? = null, chunks: Int = 3): ByteArray {
        val stbl = box("stbl", stco(chunks))
        val trak = box("trak", box("mdia", box("minf", stbl)))
        val children = if (udtaPayload == null) trak else cat(trak, box("udta", udtaPayload))
        return box("moov", children)
    }

    /** Finds the stco chunk-offset table and writes [base], [base]+16, ... */
    private fun setChunkOffsets(buf: ByteArray, base: Int, chunks: Int) {
        val at = indexOfType(buf, "stco") + 12
        for (i in 0 until chunks) writeU32(buf, at + i * 4, base + i * 16)
    }

    private fun readChunkOffsets(buf: ByteArray, chunks: Int): List<Long> {
        val at = indexOfType(buf, "stco") + 12
        return (0 until chunks).map { readU32(buf, at + it * 4) }
    }

    private fun indexOfType(buf: ByteArray, type: String): Int {
        val needle = type.toByteArray(StandardCharsets.ISO_8859_1)
        outer@ for (i in 0..buf.size - 4) {
            for (j in 0 until 4) if (buf[i + j] != needle[j]) continue@outer
            return i
        }
        error("box type '$type' not found")
    }

    private val ftyp = box("ftyp", "isom".toByteArray(StandardCharsets.ISO_8859_1))

    /** mdat payload with recognisable content so corruption is obvious. */
    private fun mdatPayload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun writeFile(name: String, bytes: ByteArray): File {
        val f = tmp.newFile(name)
        f.writeBytes(bytes)
        return f
    }

    // --- tests -----------------------------------------------------------

    @Test
    fun formatsIso6709WithFixedWidthFields() {
        assertEquals("+48.1372+011.5756+519.400/", MovGpsWriter.iso6709(point))
    }

    @Test
    fun formatsSouthernAndWesternHemispheresAndNegativeAltitude() {
        val dead = TrackPoint(point.time, -33.8688, -5.2093, -430.5)
        assertEquals("-33.8688-005.2093-430.500/", MovGpsWriter.iso6709(dead))
    }

    @Test
    fun omitsAltitudeWhenTheTrackHasNone() {
        assertEquals("+48.1372+011.5756/", MovGpsWriter.iso6709(point.copy(ele = null)))
    }

    @Test
    fun writesLocationAndShiftsChunkOffsetsWhenMoovPrecedesMdat() {
        val chunks = 3
        val mv = moov(chunks = chunks)
        val payload = mdatPayload(64)
        val mdatPayloadStart = ftyp.size + mv.size + 8
        setChunkOffsets(mv, mdatPayloadStart, chunks)
        val before = readChunkOffsets(mv, chunks)

        val file = writeFile("moov-first.mp4", cat(ftyp, mv, box("mdat", payload)))
        val oldMoovSize = mv.size
        MovGpsWriter.writeGps(file, point)

        val after = file.readBytes()
        val newMoovSize = readU32(after, ftyp.size).toInt()
        val delta = newMoovSize - oldMoovSize
        assertTrue("moov should have grown", delta > 0)

        // Every chunk offset must have moved by exactly the moov growth.
        val shifted = readChunkOffsets(after, chunks)
        assertEquals(before.map { it + delta }, shifted)

        // And each offset must still land on the original media bytes.
        val mdatStart = ftyp.size + newMoovSize + 8
        assertEquals(mdatStart.toLong(), shifted.first())
        assertArrayEquals(payload, after.copyOfRange(mdatStart, mdatStart + payload.size))
        assertTrue(locationOf(after).contentEquals("+48.1372+011.5756+519.400/"))
    }

    @Test
    fun leavesChunkOffsetsAloneWhenMdatPrecedesMoov() {
        val chunks = 2
        val payload = mdatPayload(48)
        val mdat = box("mdat", payload)
        val mv = moov(chunks = chunks)
        setChunkOffsets(mv, ftyp.size + 8, chunks)
        val before = readChunkOffsets(mv, chunks)

        val file = writeFile("mdat-first.mp4", cat(ftyp, mdat, mv))
        MovGpsWriter.writeGps(file, point)

        val after = file.readBytes()
        assertEquals(before, readChunkOffsets(after, chunks))
        assertArrayEquals(payload, after.copyOfRange(ftyp.size + 8, ftyp.size + 8 + payload.size))
        assertTrue(locationOf(after).contentEquals("+48.1372+011.5756+519.400/"))
    }

    @Test
    fun replacesAnExistingLocationRatherThanAppendingASecond() {
        val existing = locationBoxFor("+00.0000+000.0000/")
        val mv = moov(udtaPayload = existing)
        val file = writeFile("relocate.mp4", cat(ftyp, mv, box("mdat", mdatPayload(16))))

        MovGpsWriter.writeGps(file, point)
        MovGpsWriter.writeGps(file, point.copy(lat = 1.0, lon = 2.0, ele = null))

        val after = file.readBytes()
        assertEquals("only one ©xyz box may remain", 1, countType(after, "©xyz"))
        assertEquals("+01.0000+002.0000/", locationOf(after))
    }

    @Test
    fun preservesOtherUdtaChildren() {
        val name = box("©nam", "a clip".toByteArray(StandardCharsets.UTF_8))
        val mv = moov(udtaPayload = name)
        val file = writeFile("with-name.mp4", cat(ftyp, mv, box("mdat", mdatPayload(16))))

        MovGpsWriter.writeGps(file, point)

        val after = file.readBytes()
        assertEquals(1, countType(after, "©nam"))
        assertEquals(1, countType(after, "©xyz"))
    }

    @Test
    fun addsUdtaWhenTheFileHasNone() {
        val file = writeFile("no-udta.mp4", cat(ftyp, moov(), box("mdat", mdatPayload(16))))
        MovGpsWriter.writeGps(file, point)
        val after = file.readBytes()
        assertEquals(1, countType(after, "udta"))
        assertEquals("+48.1372+011.5756+519.400/", locationOf(after))
    }

    @Test
    fun rejectsFilesThatAreNotIsoBaseMedia() {
        val file = writeFile("not-a-movie.mp4", ByteArray(512) { 0x37 })
        assertThrows(IOException::class.java) { MovGpsWriter.writeGps(file, point) }
    }

    @Test
    fun preservesTheModificationTime() {
        val file = writeFile("mtime.mp4", cat(ftyp, moov(), box("mdat", mdatPayload(16))))
        val stamp = 1_600_000_000_000L
        file.setLastModified(stamp)
        MovGpsWriter.writeGps(file, point)
        assertEquals(stamp, file.lastModified())
    }

    // --- helpers ---------------------------------------------------------

    private fun locationBoxFor(text: String): ByteArray {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(4 + bytes.size)
        payload[0] = (bytes.size ushr 8).toByte()
        payload[1] = bytes.size.toByte()
        payload[2] = 0x15
        payload[3] = 0xC7.toByte()
        bytes.copyInto(payload, 4)
        return box("©xyz", payload)
    }

    /** Reads back the ©xyz text. */
    private fun locationOf(buf: ByteArray): String {
        val at = indexOfType(buf, "©xyz")
        val size = readU32(buf, at - 4).toInt()
        val len = ((buf[at + 4].toInt() and 0xFF) shl 8) or (buf[at + 5].toInt() and 0xFF)
        assertEquals("declared length must match the box size", size - 12, len)
        return String(buf, at + 8, len, StandardCharsets.UTF_8)
    }

    private fun countType(buf: ByteArray, type: String): Int {
        val needle = type.toByteArray(StandardCharsets.ISO_8859_1)
        var count = 0
        outer@ for (i in 0..buf.size - 4) {
            for (j in 0 until 4) if (buf[i + j] != needle[j]) continue@outer
            count++
        }
        return count
    }

    private fun writeU32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun readU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)
}
