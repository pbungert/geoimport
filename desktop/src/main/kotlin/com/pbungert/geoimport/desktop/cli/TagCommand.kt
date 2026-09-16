package com.pbungert.geoimport.desktop.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import com.pbungert.geoimport.core.geotag.Geotagger
import com.pbungert.geoimport.core.geotag.GpsWriteResult
import com.pbungert.geoimport.core.imports.PhotoImporter
import com.pbungert.geoimport.core.track.TrackSelection
import java.io.File

/**
 * Geotags photos that are already on disk. Same matching and writer chain as
 * `import`, without the copying — for fixing up past imports.
 */
class TagCommand : CliktCommand(name = "tag") {

    override fun help(context: Context) =
        "Geotag photos already on disk, in place, from a track."

    private val folder: File by argument(
        "FOLDER",
        help = "Directory of photos to tag",
    ).file(mustExist = true, canBeFile = false, mustBeReadable = true)

    private val recursive: Boolean by option(
        "--recursive", "-r",
        help = "Descend into subdirectories",
    ).flag()

    private val extensions: List<String> by option(
        "--extensions",
        help = "Comma-separated extensions to tag " +
            "(default: ${PhotoImporter.DEFAULT_EXTENSIONS.joinToString(",")})",
    ).split(",").default(PhotoImporter.DEFAULT_EXTENSIONS.toList())

    private val dryRun: Boolean by option(
        "--dry-run", "-n",
        help = "Show what would be tagged without writing anything",
    ).flag()

    private val geotag by GeotagOptions()

    override fun run() {
        if (!geotag.hasTrackSource) {
            throw UsageError("--track or --tracks-dir is required for tagging")
        }
        val available = geotag.namedTracks()
        if (available.isEmpty()) throw UsageError("No readable tracks with timestamped points.")

        val wanted = extensions.map { it.trim().removePrefix(".").lowercase() }.toSet()
        val files = (if (recursive) folder.walkTopDown() else folder.walkTopDown().maxDepth(1))
            .filter { it.isFile && it.extension.lowercase() in wanted }
            .sortedBy { it.name }
            .toList()

        if (files.isEmpty()) {
            echo("No matching files in ${folder.path}.")
            return
        }

        val captureTime = geotag.captureTimeResolver()
        val timed = files.map { it to captureTime.instantOf(it) }

        val choice = TrackSelection.choose(available, timed.map { it.second }, geotag.tolerance())
        choice.lines().forEach { echo(it) }
        if (choice.used.isEmpty()) return
        val geotagger = Geotagger(choice.track, geotag.tolerance())

        val writer = geotag.writerChain { file, w, e ->
            echo("  ${w.name} failed on ${file.name} (${e.message ?: e}) - falling back", err = true)
        }

        val nameWidth = files.maxOf { it.name.length }
        var embedded = 0
        var sidecars = 0
        var skipped = 0
        var failed = 0

        for ((file, time) in timed) {
            val fix = geotagger.locate(time)
            if (fix == null) {
                echo("  ${file.name.padEnd(nameWidth)}  $time  no match within tolerance")
                skipped++
                continue
            }
            val backend = writer.effectiveWriterFor(file)?.name ?: "-"
            val where = "%9.5f, %9.5f".format(fix.lat, fix.lon)
            if (dryRun) {
                echo("  ${file.name.padEnd(nameWidth)}  $time  $where [$backend]")
                continue
            }
            try {
                when (writer.write(file, fix)) {
                    is GpsWriteResult.Embedded -> embedded++
                    is GpsWriteResult.Sidecar -> sidecars++
                }
                echo("  ${file.name.padEnd(nameWidth)}  $time  $where [$backend]")
            } catch (e: Exception) {
                echo("  could not tag ${file.name}: ${e.message ?: e}", err = true)
                failed++
            }
        }

        echo("")
        if (dryRun) {
            echo("${files.size - skipped} of ${files.size} would be tagged. Nothing was written (--dry-run).")
        } else {
            echo("Tagged ${embedded + sidecars} of ${files.size} files " +
                "($embedded embedded, $sidecars sidecars, ${skipped + failed} untagged).")
        }
    }
}
