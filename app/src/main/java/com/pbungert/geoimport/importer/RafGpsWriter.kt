package com.pbungert.geoimport.importer

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Writes GPS coordinates into a Fuji RAF file in place.
 *
 * A RAF keeps its EXIF block inside an embedded JPEG preview; the RAF header
 * stores absolute (big-endian) offsets to that JPEG and to the metadata/CFA
 * sections behind it. The embedded JPEG is rewritten with [ExifInterface] and
 * every header pointer is shifted by the size change — the same scheme
 * exiftool's WriteRAF uses. Lightroom (including the Android app, which
 * ignores XMP sidecars) reads GPS from this EXIF block.
 */
object RafGpsWriter {

    fun writeGps(raf: File, point: TrackPoint) {
        rewriteEmbeddedJpeg(raf) { jpeg ->
            val exif = ExifInterface(jpeg)
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (point.lat >= 0) "N" else "S")
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDmsRational(point.lat))
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (point.lon >= 0) "E" else "W")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDmsRational(point.lon))
            point.ele?.let { ele ->
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${(abs(ele) * 1000).roundToLong()}/1000")
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (ele < 0) "1" else "0")
            }
            val utc = point.time.atOffset(ZoneOffset.UTC)
            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, utc.format(GPS_TIME))
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, utc.format(GPS_DATE))
            exif.saveAttributes()
        }
    }

    /** EXIF rational triplet "deg/1,min/1,sec*1e4/1e4" (~3 mm precision). */
    private fun toDmsRational(value: Double): String {
        var secondsE4 = (abs(value) * 3600.0 * 10000.0).roundToLong()
        val degrees = secondsE4 / 36_000_000
        secondsE4 %= 36_000_000
        val minutes = secondsE4 / 600_000
        secondsE4 %= 600_000
        return "$degrees/1,$minutes/1,$secondsE4/10000"
    }

    /**
     * Extracts the embedded JPEG into a temp file, runs [editJpeg] on it and
     * rebuilds the RAF around the (usually larger) result: the JPEG length
     * field is updated, the JPEG is zero-padded to 4-byte alignment and all
     * section pointers stored before the JPEG are shifted accordingly.
     * Throws [IOException] if the file does not match the known RAF layout.
     */
    fun rewriteEmbeddedJpeg(raf: File, editJpeg: (File) -> Unit) {
        val fileLen = raf.length()
        val lastModified = raf.lastModified()
        if (fileLen < MIN_HEADER) throw IOException("file too small for a RAF header")

        val fixed = ByteArray(MIN_HEADER)
        RandomAccessFile(raf, "r").use { it.readFully(fixed) }
        if (String(fixed, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) {
            throw IOException("not a RAF file (magic mismatch)")
        }

        val mrawLen = readU32(fixed, MRAW_LENGTH_FIELD)
        val jpegPos = readU32(fixed, JPEG_OFFSET_FIELD).toInt()
        val jpegLen = readU32(fixed, JPEG_LENGTH_FIELD).toInt()
        if (jpegPos < MIN_HEADER || jpegPos > 0x94 + mrawLen || jpegPos % 4 != 0 ||
            jpegLen <= 4 || jpegPos + jpegLen.toLong() > fileLen
        ) {
            throw IOException("unsupported RAF layout (JPEG at $jpegPos, length $jpegLen)")
        }
        if (mrawLen != 0L && mrawLen != 0x11CL) {
            throw IOException("unsupported RAF M-RAW header size $mrawLen")
        }

        val header = ByteArray(jpegPos)
        val jpegTemp = File(raf.parentFile, raf.name + ".gps.jpg")
        val outTemp = File(raf.parentFile, raf.name + ".gps.raf")
        try {
            RandomAccessFile(raf, "r").use { input ->
                input.readFully(header)
                val jpeg = ByteArray(jpegLen)
                input.readFully(jpeg)
                if (jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
                    throw IOException("embedded JPEG not found at offset $jpegPos")
                }
                jpegTemp.writeBytes(jpeg)
            }

            editJpeg(jpegTemp)

            val newLen = jpegTemp.length().toInt()
            val pad = 4 - (newLen % 4) // 1..4 zero bytes, as exiftool writes them
            val pointerFields = POINTER_FIELDS.filter { it + 4 <= jpegPos }
            val tailStart = pointerFields
                .map { readU32(header, it) }
                .filter { it != 0L }
                .minOrNull() ?: (jpegPos + jpegLen).toLong()
            if (tailStart < jpegPos + jpegLen || tailStart > fileLen) {
                throw IOException("RAF section pointers overlap the embedded JPEG")
            }

            val ptrDiff = jpegPos + newLen + pad - tailStart
            writeU32(header, JPEG_LENGTH_FIELD, newLen.toLong())
            for (field in pointerFields) {
                val old = readU32(header, field)
                if (old != 0L) writeU32(header, field, old + ptrDiff)
            }

            outTemp.outputStream().buffered().use { out ->
                out.write(header)
                jpegTemp.inputStream().use { it.copyTo(out) }
                out.write(ByteArray(pad))
                RandomAccessFile(raf, "r").use { input ->
                    input.seek(tailStart)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }

            Files.move(outTemp.toPath(), raf.toPath(), StandardCopyOption.REPLACE_EXISTING)
            raf.setLastModified(lastModified)
        } finally {
            jpegTemp.delete()
            outTemp.delete()
        }
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private const val MAGIC = "FUJIFILMCCD-RAW "
    private const val MRAW_LENGTH_FIELD = 0x4C
    private const val JPEG_OFFSET_FIELD = 0x54
    private const val JPEG_LENGTH_FIELD = 0x58
    private const val MIN_HEADER = 0x68

    /**
     * Header fields holding absolute offsets of the sections behind the JPEG
     * (RAF directories, FujiIFD, M-RAW raw data) — the set exiftool's
     * WriteRAF shifts. The last three exist only in the M-RAW extension.
     */
    private val POINTER_FIELDS = intArrayOf(0x5C, 0x64, 0x78, 0x80, 0xCC, 0x114, 0x164)

    private val GPS_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val GPS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd")
}
