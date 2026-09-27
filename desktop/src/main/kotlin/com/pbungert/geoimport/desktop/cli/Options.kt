package com.pbungert.geoimport.desktop.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import com.pbungert.geoimport.core.geotag.BuiltInGpsWriter
import com.pbungert.geoimport.core.geotag.FallbackGpsWriter
import com.pbungert.geoimport.core.geotag.GpsWriter
import com.pbungert.geoimport.core.geotag.XmpSidecarWriter
import com.pbungert.geoimport.core.imports.CaptureTimeResolver
import com.pbungert.geoimport.core.track.NamedTrack
import com.pbungert.geoimport.core.track.TrackParser
import com.pbungert.geoimport.core.track.TrackSelection
import com.pbungert.geoimport.desktop.platform.DesktopExifDateReader
import com.pbungert.geoimport.desktop.platform.DesktopJpegGpsWriter
import com.pbungert.geoimport.desktop.platform.ExifToolGpsWriter
import java.io.File
import java.time.Duration
import java.time.ZoneId

/** Options shared by `import` and `tag`. */
class GeotagOptions : OptionGroup("Geotagging") {

    val tracks: List<File> by option(
        "--track", "-t",
        help = "GPX or KML file to match photos against. Repeat it for several; " +
            "only the ones covering the photos are used",
    ).file(mustExist = true, canBeDir = false, mustBeReadable = true).multiple()

    val tracksDir: File? by option(
        "--tracks-dir",
        help = "Directory of .gpx/.kml files to choose from, such as where 'tracks pull' " +
            "leaves them. The ones covering the photos are used and the rest ignored",
    ).file(mustExist = true, canBeFile = false, mustBeReadable = true)

    val toleranceMinutes: Long by option(
        "--tolerance",
        help = "How far outside a recorded stretch of track a photo may still be placed, " +
            "in minutes. Also decides which tracks count as covering the photos",
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

    /** True when the user asked for geotagging at all. */
    val hasTrackSource get() = tracks.isNotEmpty() || tracksDir != null

    /**
     * Every track on offer, named by filename. A file named with `--track` is
     * meant to be used, so a broken one stops the run; one that merely turned
     * up in `--tracks-dir` is skipped, because a directory of recordings will
     * eventually hold something unreadable and that is no reason to refuse.
     */
    fun namedTracks(): List<NamedTrack> {
        val zone = zoneOf(trackZone, "--track-tz")
        val named = tracks.map { file ->
            val track = try {
                file.inputStream().use { TrackParser.parse(it, zone) }
            } catch (e: Exception) {
                throw UsageError("Could not read ${file.name}: ${e.message ?: e}")
            }
            if (track.isEmpty) throw UsageError("${file.name} contains no timestamped points.")
            NamedTrack(file.nameWithoutExtension, track)
        }
        val scanned = tracksDir
            ?.listFiles { f -> f.isFile && f.extension.lowercase() in TrackParser.EXTENSIONS }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching { file.inputStream().use { TrackParser.parse(it, zone) } }
                    .getOrNull()
                    ?.takeIf { !it.isEmpty }
                    ?.let { NamedTrack(file.nameWithoutExtension, it) }
            }
            .orEmpty()
        return TrackSelection.distinct(named + scanned, { it.name }, { it.track }) { t, name ->
            t.copy(name = name)
        }
    }

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
