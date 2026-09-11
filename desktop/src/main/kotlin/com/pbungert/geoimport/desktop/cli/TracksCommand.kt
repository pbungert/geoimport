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

    private val deviceDir: String by option(
        "--device-dir",
        help = "Directory on the device to pull from",
    ).default("/sdcard/Documents/GPS-Tracks")

    private val dest: File by option(
        "--dest", "-d",
        help = "Local directory to copy tracks into",
    ).file(canBeFile = false).default(defaultTracksDir())

    private val adb: String by option("--adb", help = "Path to the adb executable").default("adb")

    override fun run() {
        dest.mkdirs()
        val process = ProcessBuilder(adb, "pull", "-a", deviceDir, dest.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw CliktError("adb pull timed out")
        }
        if (process.exitValue() != 0) {
            throw CliktError(
                "adb pull failed: ${output.trim().ifEmpty { "exit ${process.exitValue()}" }}\n" +
                    "Is the device connected with USB debugging enabled? Try 'adb devices'."
            )
        }
        echo(output.trim())
        echo("Tracks are in ${dest.path}")
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
            f.isFile && f.extension.lowercase() in setOf("gpx", "kml")
        }?.sortedByDescending { it.lastModified() }.orEmpty()

        if (files.isEmpty()) {
            echo("No tracks in ${tracksDir.path}.")
            return
        }

        val nameWidth = files.maxOf { it.name.length }
        for (file in files) {
            val points = try {
                file.inputStream().use { TrackParser.parse(it) }
            } catch (e: Exception) {
                echo("  ${file.name.padEnd(nameWidth)}  unreadable (${e.message ?: e})")
                continue
            }
            if (points.isEmpty()) {
                echo("  ${file.name.padEnd(nameWidth)}  no timestamped points")
                continue
            }
            val span = Duration.between(points.first().time, points.last().time)
            echo(
                "  ${file.name.padEnd(nameWidth)}  ${points.size} points  " +
                    "${points.first().time} -> ${points.last().time}  (${format(span)})"
            )
        }
    }

    private fun format(d: Duration) = "%dh %02dm".format(d.toHours(), d.toMinutesPart())
}

/** Where `tracks pull` puts files, and where `list` looks by default. */
fun defaultTracksDir(): File =
    File(System.getProperty("user.home"), "GPS-Tracks")
