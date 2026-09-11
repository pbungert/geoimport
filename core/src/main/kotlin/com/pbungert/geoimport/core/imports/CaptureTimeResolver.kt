package com.pbungert.geoimport.core.imports

import com.pbungert.geoimport.core.spi.ExifDateReader
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.min

/**
 * Works out when a photo was actually taken.
 *
 * Two things make this harder than reading one EXIF tag:
 *
 * Cameras usually record DateTimeOriginal without an OffsetTimeOriginal, so
 * the wall-clock reading alone does not identify an instant. Assuming the
 * importing machine's zone is right only when you shoot and import in the same
 * one — import a trip abroad after flying home and every photo lands hours off
 * the track. [assumedZone] makes that assumption explicit and overridable.
 *
 * Camera clocks also drift, and are often simply never set past the factory
 * default. [cameraClockOffset] is added to the capture time to correct it: if
 * the camera runs two minutes fast, pass `Duration.ofMinutes(-2)`. At walking
 * pace two minutes is a couple of streets; in a car it is a mile or more.
 */
class CaptureTimeResolver(
    private val exifDateReader: ExifDateReader,
    private val assumedZone: ZoneId = ZoneId.systemDefault(),
    private val cameraClockOffset: Duration = Duration.ZERO,
) {

    /**
     * The absolute instant to match against a track, corrected for clock
     * drift. Uses OffsetTimeOriginal when the camera recorded one, otherwise
     * interprets the local reading in [assumedZone]; falls back to file
     * timestamps for formats with no EXIF at all, such as video.
     */
    fun instantOf(file: File): Instant {
        val exif = readExif(file)
        val base = when {
            exif == null -> fileFallbackTime(file)
            exif.second != null -> exif.first.toInstant(exif.second)
            else -> exif.first.atZone(assumedZone).toInstant()
        }
        return base.plus(cameraClockOffset)
    }

    /**
     * The capture time as the camera recorded it — no zone, no drift
     * correction. This is what the resume-by-timestamp filter compares
     * against, because the timestamp a user types is read off the files or the
     * camera, not off a corrected clock.
     */
    fun localOf(file: File): LocalDateTime {
        readExif(file)?.let { (local, _) -> return local }
        return LocalDateTime.ofInstant(fileFallbackTime(file), assumedZone)
    }

    private fun readExif(file: File) =
        try {
            exifDateReader.readDateTaken(file)
        } catch (_: Exception) {
            null
        }

    /**
     * The earlier of creation and last-modified: a copy often carries a
     * creation time newer than the original capture, so the minimum is the
     * better guess.
     */
    private fun fileFallbackTime(file: File): Instant {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        return Instant.ofEpochMilli(
            min(attrs.creationTime().toMillis(), attrs.lastModifiedTime().toMillis())
        )
    }
}
