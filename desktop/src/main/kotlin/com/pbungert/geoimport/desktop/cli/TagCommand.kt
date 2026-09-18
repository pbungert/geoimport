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
import com.pbungert.geoimport.core.geotag.GeotagTarget
import com.pbungert.geoimport.core.geotag.writeGeotags
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
        val targets = timed.map { (file, time) -> GeotagTarget(file, geotagger.locate(time)) }

        /** One row of the report: name, capture time, and where it landed. */
        fun row(index: Int, where: String) =
            echo("  ${files[index].name.padEnd(nameWidth)}  ${timed[index].second}  $where")

        fun placement(target: GeotagTarget): String {
            val fix = target.fix ?: return "no match within tolerance"
            val backend = writer.effectiveWriterFor(target.file)?.name ?: "-"
            return "%9.5f, %9.5f".format(fix.lat, fix.lon) + " [$backend]"
        }

        if (dryRun) {
            targets.forEachIndexed { index, target -> row(index, placement(target)) }
            echo("")
            val would = targets.count { it.fix != null }
            echo("$would of ${files.size} would be tagged. Nothing was written (--dry-run).")
            return
        }

        var index = 0
        val counts = writeGeotags(targets, writer) { target, _, error ->
            val at = index++
            if (error != null) {
                echo("  could not tag ${target.file.name}: ${error.message ?: error}", err = true)
            } else {
                row(at, placement(target))
            }
        }

        echo("")
        echo("Tagged ${counts.written} of ${files.size} files " +
            "(${counts.embedded} embedded, ${counts.sidecars} sidecars, ${counts.untagged} untagged).")
    }
}
