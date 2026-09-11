package com.pbungert.geoimport.platform

import androidx.exifinterface.media.ExifInterface
import com.pbungert.geoimport.core.spi.ExifDateReader
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** [ExifDateReader] backed by androidx.exifinterface. */
object AndroidExifDateReader : ExifDateReader {

    private val EXIF_DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    override fun readDateTaken(file: File): Pair<LocalDateTime, ZoneOffset?>? {
        try {
            val exif = ExifInterface(file)
            val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            if (!dateStr.isNullOrEmpty()) {
                val local = LocalDateTime.parse(dateStr, EXIF_DATE_FORMAT)
                val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.let {
                    try {
                        ZoneOffset.of(it)
                    } catch (_: Exception) {
                        null
                    }
                }
                return local to offset
            }
        } catch (_: Exception) {
        }
        return null
    }
}
