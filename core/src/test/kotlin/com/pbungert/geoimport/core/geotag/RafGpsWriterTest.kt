package com.pbungert.geoimport.core.geotag


import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Exercises the RAF surgery (header parsing, offset patching, tail
 * preservation) with a synthetic file; the ExifInterface part needs a device.
 */
class RafGpsWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val headerSize = 0x94

    /** Builds a minimal RAF: header, JPEG of [jpegLen] bytes, two tail sections. */
    private fun buildRaf(jpegLen: Int, dir1: ByteArray, cfa: ByteArray): File {
        val header = ByteArray(headerSize)
        "FUJIFILMCCD-RAW ".toByteArray(Charsets.US_ASCII).copyInto(header)
        val dir1Offset = headerSize + jpegLen
        writeU32(header, 0x54, headerSize)
        writeU32(header, 0x58, jpegLen)
        writeU32(header, 0x5C, dir1Offset)
        writeU32(header, 0x60, dir1.size)
        writeU32(header, 0x64, dir1Offset + dir1.size)
        writeU32(header, 0x68, cfa.size)

        val jpeg = ByteArray(jpegLen) { it.toByte() }
        jpeg[0] = 0xFF.toByte()
        jpeg[1] = 0xD8.toByte()

        val file = tmp.newFile("test.raf")
        file.writeBytes(header + jpeg + dir1 + cfa)
        return file
    }

    @Test
    fun growingTheJpegShiftsPointersAndPreservesTail() {
        val dir1 = ByteArray(16) { (0x40 + it).toByte() }
        val cfa = ByteArray(32) { (0x70 + it).toByte() }
        val raf = buildRaf(jpegLen = 100, dir1 = dir1, cfa = cfa)

        val newJpeg = ByteArray(150) { (it + 1).toByte() }
        newJpeg[0] = 0xFF.toByte()
        newJpeg[1] = 0xD8.toByte()
        RafGpsWriter.rewriteEmbeddedJpeg(raf) { it.writeBytes(newJpeg) }

        val bytes = raf.readBytes()
        val pad = 4 - 150 % 4 // 2 alignment bytes after the new JPEG
        val newDir1Offset = headerSize + 150 + pad

        assertEquals("FUJIFILMCCD-RAW ", String(bytes, 0, 16, Charsets.US_ASCII))
        assertEquals(headerSize.toLong(), readU32(bytes, 0x54))
        assertEquals(150L, readU32(bytes, 0x58))
        assertEquals(newDir1Offset.toLong(), readU32(bytes, 0x5C))
        assertEquals(16L, readU32(bytes, 0x60))
        assertEquals((newDir1Offset + 16).toLong(), readU32(bytes, 0x64))
        assertEquals(32L, readU32(bytes, 0x68))

        assertArrayEquals(newJpeg, bytes.copyOfRange(headerSize, headerSize + 150))
        assertArrayEquals(ByteArray(pad), bytes.copyOfRange(headerSize + 150, newDir1Offset))
        assertArrayEquals(dir1, bytes.copyOfRange(newDir1Offset, newDir1Offset + 16))
        assertArrayEquals(cfa, bytes.copyOfRange(newDir1Offset + 16, bytes.size))
        assertEquals((newDir1Offset + 16 + 32).toLong(), raf.length())
    }

    @Test
    fun unchangedJpegStillAlignsAndKeepsSections() {
        val dir1 = ByteArray(8) { 0x11 }
        val cfa = ByteArray(24) { 0x22 }
        val raf = buildRaf(jpegLen = 100, dir1 = dir1, cfa = cfa)

        RafGpsWriter.rewriteEmbeddedJpeg(raf) { /* no edit */ }

        val bytes = raf.readBytes()
        val newDir1Offset = headerSize + 100 + 4 // 100 % 4 == 0 → 4 pad bytes
        assertEquals(100L, readU32(bytes, 0x58))
        assertEquals(newDir1Offset.toLong(), readU32(bytes, 0x5C))
        assertArrayEquals(dir1, bytes.copyOfRange(newDir1Offset, newDir1Offset + 8))
        assertArrayEquals(cfa, bytes.copyOfRange(newDir1Offset + 8, bytes.size))
    }

    @Test
    fun rejectsNonRafFiles() {
        val file = tmp.newFile("not-a-raf.raf")
        file.writeBytes(ByteArray(4096) { 0x33 })
        assertThrows(IOException::class.java) {
            RafGpsWriter.rewriteEmbeddedJpeg(file) { }
        }
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}
