package com.pbungert.geoimport.desktop.platform

import com.pbungert.geoimport.core.geotag.ExifGpsFormat
import com.pbungert.geoimport.core.geotag.GpsWriteResult
import com.pbungert.geoimport.core.geotag.GpsWriter
import com.pbungert.geoimport.core.model.TrackPoint
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Writes GPS tags with a bundled or system exiftool.
 *
 * This exists because no library writes metadata into the full range of RAW
 * and video containers. exiv2 cannot write RAF, ARW, CR3 or HEIF; Commons
 * Imaging covers JPEG and TIFF; metadata-extractor is read-only. Each vendor's
 * container needs its own offset-correct rewrite, which is exactly the job
 * exiftool has been doing since 2003.
 *
 * Placed ahead of the built-in writer, it adds embedded tags for CR3, NEF,
 * ARW, DNG and HEIC. Dropping it from the chain reproduces the phone's
 * behaviour exactly.
 */
class ExifToolGpsWriter(private val executable: File) : GpsWriter {

    override val name = "exiftool"

    override fun supports(file: File) = file.extension.lowercase() in SUPPORTED

    override fun write(file: File, point: TrackPoint): GpsWriteResult {
        val args = buildList {
            add(executable.absolutePath)
            add("-overwrite_original")
            // Preserve the capture date; exiftool would otherwise bump FileModifyDate.
            add("-preserve")
            add("-GPSLatitudeRef=${ExifGpsFormat.latitudeRef(point.lat)}")
            add("-GPSLatitude=${"%.8f".format(Locale.US, kotlin.math.abs(point.lat))}")
            add("-GPSLongitudeRef=${ExifGpsFormat.longitudeRef(point.lon)}")
            add("-GPSLongitude=${"%.8f".format(Locale.US, kotlin.math.abs(point.lon))}")
            point.ele?.let {
                add("-GPSAltitudeRef=${ExifGpsFormat.altitudeRef(it)}")
                add("-GPSAltitude=${"%.3f".format(Locale.US, kotlin.math.abs(it))}")
            }
            add("-GPSTimeStamp=${ExifGpsFormat.timeStamp(point.time)}")
            add("-GPSDateStamp=${ExifGpsFormat.dateStamp(point.time)}")
            add(file.absolutePath)
        }

        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("exiftool timed out on ${file.name}")
        }
        if (process.exitValue() != 0) {
            throw IOException("exiftool failed on ${file.name}: ${output.trim().ifEmpty { "exit ${process.exitValue()}" }}")
        }
        return GpsWriteResult.Embedded(file)
    }

    companion object {
        private const val TIMEOUT_SECONDS = 60L

        /**
         * Formats worth handing to exiftool. Deliberately excludes the ones
         * the built-in writer already handles, so the shared path stays the
         * one that runs for RAF, JPEG and MOV and the two platforms cannot
         * drift apart on the formats both support.
         */
        val SUPPORTED = setOf(
            "cr2", "cr3", "nef", "nrw", "arw", "sr2", "srf",
            "dng", "orf", "rw2", "pef", "raw", "3fr", "iiq",
            "heic", "heif", "avif", "tif", "tiff", "png", "webp",
        )

        /**
         * Finds exiftool: next to the installed app first (the bundled copy),
         * then on PATH. Returns null when neither is present, in which case
         * the chain simply runs without it.
         */
        fun discover(appDir: File? = null): File? {
            val name = if (isWindows) "exiftool.exe" else "exiftool"

            appDir?.let { dir ->
                val bundled = File(File(dir, "exiftool"), name)
                if (bundled.canExecute()) return bundled
            }

            val path = System.getenv("PATH") ?: return null
            return path.split(File.pathSeparatorChar)
                .asSequence()
                .map { File(it.trim(), name) }
                .firstOrNull { it.canExecute() }
        }

        val isWindows: Boolean
            get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}
