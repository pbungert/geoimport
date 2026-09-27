package com.pbungert.geoimport.desktop.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.pbungert.geoimport.core.track.TrackParser
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

class TracksCommand : CliktCommand(name = "tracks") {
    override fun help(context: Context) = "Work with recorded GPS tracks."
    override fun run() = Unit
}

/**
 * Copies tracks off the phone with adb.
 *
 * Deliberately not a cloud sync: a day of one-minute fixes is under 100 KB,
 * and you are standing at the machine with the card in your hand anyway. adb
 * needs no account, and works over USB or `adb tcpip`.
 */
class TracksPull : CliktCommand(name = "pull") {

    override fun help(context: Context) = "Copy GPS tracks from a connected Android device via adb."

    private val deviceDir: String? by option(
        "--device-dir",
        help = "Directory on the device to pull from (default: $DEVICE_TRACKS_DIR)",
    )

    private val dest: File by option(
        "--dest", "-d",
        help = "Local directory to copy tracks into",
    ).file(canBeFile = false).default(defaultTracksDir())

    private val adb: String by option("--adb", help = "Path to the adb executable").default("adb")

    override fun run() {
        dest.mkdirs()
        var (exit, output) = pull(deviceDir ?: DEVICE_TRACKS_DIR)
        // A phone still on an app from before the folder was renamed.
        if (exit != 0 && deviceDir == null) {
            val legacy = pull(LEGACY_DEVICE_TRACKS_DIR)
            if (legacy.first == 0) {
                exit = 0
                output = legacy.second
            }
        }
        if (exit != 0) {
            throw CliktError(
                "adb pull failed: ${output.trim().ifEmpty { "exit $exit" }}\n" +
                    "Is the device connected with USB debugging enabled? Try 'adb devices'."
            )
        }
        echo(output.trim())
        echo("Tracks are in ${dest.path}")
    }

    /** Exit code and combined output of one `adb pull`. */
    private fun pull(from: String): Pair<Int, String> {
        val process = ProcessBuilder(adb, "pull", "-a", from, dest.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw CliktError("adb pull timed out")
        }
        return process.exitValue() to output
    }
}

class TracksList : CliktCommand(name = "list") {

    override fun help(context: Context) = "List local track files with their time span and point count."

    private val tracksDir: File by option(
        "--tracks-dir",
        help = "Directory holding .gpx/.kml files",
    ).file(canBeFile = false).default(defaultTracksDir())

    override fun run() {
        val files = tracksDir.listFiles { f ->
            f.isFile && f.extension.lowercase() in TrackParser.EXTENSIONS
        }?.sortedByDescending { it.lastModified() }.orEmpty()

        if (files.isEmpty()) {
            echo("No tracks in ${tracksDir.path}.")
            return
        }

        val nameWidth = files.maxOf { it.name.length }
        for (file in files) {
            val track = try {
                file.inputStream().use { TrackParser.parse(it) }
            } catch (e: Exception) {
                echo("  ${file.name.padEnd(nameWidth)}  unreadable (${e.message ?: e})")
                continue
            }
            val range = track.span
            if (range == null) {
                echo("  ${file.name.padEnd(nameWidth)}  no timestamped points")
                continue
            }
            val segments = track.segments.size.takeIf { it > 1 }?.let { ", $it segments" }.orEmpty()
            echo(
                "  ${file.name.padEnd(nameWidth)}  ${track.size} points  " +
                    "${range.start} -> ${range.endInclusive}  " +
                    "(${format(Duration.between(range.start, range.endInclusive))}$segments)"
            )
        }
    }

    private fun format(d: Duration) = "%dh %02dm".format(d.toHours(), d.toMinutesPart())
}

private const val DEVICE_TRACKS_DIR = "/sdcard/Documents/Geoimport"
private const val LEGACY_DEVICE_TRACKS_DIR = "/sdcard/Documents/GPS-Tracks"

/**
 * Where `tracks pull` puts files, and where `list` looks by default. A machine
 * that already has tracks in the folder's old name keeps using it until the
 * new one exists, rather than starting over in an empty folder.
 */
fun defaultTracksDir(): File {
    val home = System.getProperty("user.home")
    val current = File(home, "Geoimport")
    val legacy = File(home, "GPS-Tracks")
    return if (!current.exists() && legacy.isDirectory) legacy else current
}
