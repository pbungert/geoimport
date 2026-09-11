package com.pbungert.geoimport.desktop.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import com.pbungert.geoimport.core.geotag.BuiltInGpsWriter
import com.pbungert.geoimport.core.geotag.FallbackGpsWriter
import com.pbungert.geoimport.core.geotag.GpsWriter
import com.pbungert.geoimport.core.geotag.XmpSidecarWriter
import com.pbungert.geoimport.core.imports.CaptureTimeResolver
import com.pbungert.geoimport.core.track.TrackParser
import com.pbungert.geoimport.desktop.platform.DesktopExifDateReader
import com.pbungert.geoimport.desktop.platform.DesktopJpegGpsWriter
import com.pbungert.geoimport.desktop.platform.ExifToolGpsWriter
import java.io.File
import java.time.Duration
import java.time.ZoneId

/** Options shared by `import` and `tag`. */
class GeotagOptions : OptionGroup("Geotagging") {

    val track: File? by option("--track", "-t", help = "GPX or KML file to match photos against")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)

    val toleranceMinutes: Long by option(
        "--tolerance",
        help = "How far outside the track's time range a photo may still be placed, in minutes",
    ).long().default(30)

    val photoZone: String? by option(
        "--photo-tz",
        help = "Zone the camera's clock was set to, e.g. Asia/Tokyo. " +
            "Used only when the camera recorded no EXIF offset; defaults to this machine's zone, " +
            "which is wrong for a trip imported after you get home",
    )

    val trackZone: String? by option(
        "--track-tz",
        help = "Zone for track timestamps that carry no offset (defaults to this machine's zone)",
    )

    val clockOffsetMinutes: Double by option(
        "--clock-offset",
        help = "Camera clock error in minutes, added to every capture time. " +
            "Negative if the camera runs fast",
    ).double().default(0.0)

    val builtInOnly: Boolean by option(
        "--builtin",
        help = "Ignore exiftool and use only the built-in writer, reproducing the phone's behaviour",
    ).flag()

    fun captureTimeResolver() = CaptureTimeResolver(
        exifDateReader = DesktopExifDateReader,
        assumedZone = zoneOf(photoZone, "--photo-tz"),
        cameraClockOffset = Duration.ofMillis((clockOffsetMinutes * 60_000).toLong()),
    )

    fun tolerance(): Duration = Duration.ofMinutes(toleranceMinutes)

    fun parseTrack() = track?.inputStream()?.use { TrackParser.parse(it, zoneOf(trackZone, "--track-tz")) }

    /**
     * exiftool first when present, then the portable writer, then a sidecar so
     * a position is never lost. `--builtin` drops exiftool.
     */
    fun writerChain(onFallback: (File, GpsWriter, Exception) -> Unit): FallbackGpsWriter {
        val writers = buildList {
            if (!builtInOnly) {
                ExifToolGpsWriter.discover(appDir())?.let { add(ExifToolGpsWriter(it)) }
            }
            add(BuiltInGpsWriter(DesktopJpegGpsWriter))
            add(XmpSidecarWriter)
        }
        return FallbackGpsWriter(writers, onFallback)
    }

    private fun zoneOf(value: String?, flag: String): ZoneId {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return ZoneId.systemDefault()
        return runCatching { ZoneId.of(text) }.getOrElse {
            throw IllegalArgumentException("$flag: '$text' is not a known time zone id")
        }
    }
}

/**
 * Directory of the installed application, where jpackage puts the bundled
 * exiftool. Null in a plain `gradle run`, where discovery falls back to PATH.
 */
fun appDir(): File? =
    System.getProperty("jpackage.app-path")?.let { File(it).parentFile?.parentFile }
        ?: System.getProperty("app.home")?.let { File(it) }
