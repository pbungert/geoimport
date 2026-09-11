package com.pbungert.geoimport.desktop.platform

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.pbungert.geoimport.core.spi.ExifDateReader
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * [ExifDateReader] backed by metadata-extractor, which reads far more formats
 * than it can write — including every RAW the built-in writer can only
 * sidecar, so capture times are available even where positions are not.
 */
object DesktopExifDateReader : ExifDateReader {

    private val EXIF_DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    override fun readDateTaken(file: File): Pair<LocalDateTime, ZoneOffset?>? {
        try {
            val exif = ImageMetadataReader.readMetadata(file)
                .getFirstDirectoryOfType(ExifSubIFDDirectory::class.java) ?: return null

            val dateStr = exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
            if (dateStr.isNullOrEmpty()) return null
            val local = LocalDateTime.parse(dateStr.trim(), EXIF_DATE_FORMAT)

            val offset = exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL)?.let {
                try {
                    ZoneOffset.of(it.trim())
                } catch (_: Exception) {
                    null
                }
            }
            return local to offset
        } catch (_: Exception) {
            return null
        }
    }
}
