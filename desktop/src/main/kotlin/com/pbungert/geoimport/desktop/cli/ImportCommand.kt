package com.pbungert.geoimport.desktop.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.pbungert.geoimport.core.geotag.Geotagger
import com.pbungert.geoimport.core.geotag.GpsWriteResult
import com.pbungert.geoimport.core.imports.ImportPlan
import com.pbungert.geoimport.core.imports.PhotoImporter
import java.io.File

class ImportCommand : CliktCommand(name = "import") {

    override fun help(context: Context) =
        "Copy new photos off a card into the next Import NN folder, geotagging them from a track."

    private val source: File by argument(
        "SOURCE",
        help = "The card's DCIM directory, or the card root",
    ).file(mustExist = true, canBeFile = false, mustBeReadable = true)

    private val dest: File by option(
        "--dest", "-d",
        help = "Directory holding the Import NN folders",
    ).file(canBeFile = false).required()

    private val resume: String? by option(
        "--resume",
        help = "Where to resume: 'auto' (after the last file of the newest import), " +
            "a filename, or a timestamp like 2026-07-17T14:30",
    )

    private val dryRun: Boolean by option(
        "--dry-run", "-n",
        help = "Show what would be copied and tagged without writing anything",
    ).flag()

    private val geotag by GeotagOptions()

    override fun run() {
        val dcim = if (File(source, "DCIM").isDirectory) File(source, "DCIM") else source
        val track = try {
            geotag.parseTrack()
        } catch (e: Exception) {
            throw UsageError("Could not read track file: ${e.message ?: e}")
        }
        if (geotag.track != null && track.isNullOrEmpty()) {
            throw UsageError("Track file contains no timestamped points - nothing to geotag with.")
        }

        val geotagger = track?.let { Geotagger(it, geotag.tolerance()) }
        val writer = geotag.writerChain { file, w, e ->
            echo("  ${w.name} failed on ${file.name} (${e.message ?: e}) - falling back", err = true)
        }

        val importer = PhotoImporter(
            sourcePath = dcim,
            destBasePath = dest,
            captureTime = geotag.captureTimeResolver(),
            log = { if (dryRun) Unit else echo(it) },
        )

        val (startFilename, startTimestamp) = parseResume(resume)
        val plan = importer.plan(startFilename, startTimestamp, geotagger, writer)

        if (plan.isEmpty) {
            echo("Nothing to import.")
            plan.resumeAfter?.let { echo("(resuming after '$it' - no newer files on the card)") }
            return
        }

        if (dryRun) {
            printPlan(plan, hasTrack = geotagger != null)
            return
        }

        val result = importer.execute(plan) { done, total ->
            if (done == total) echo("Copied $done of $total files.")
        }

        if (geotagger == null) return

        var embedded = 0
        var sidecars = 0
        var failed = 0
        for (entry in plan.selected) {
            val fix = entry.fix ?: continue
            val copied = File(plan.destFolder, entry.source.name)
            if (!copied.exists()) continue
            try {
                when (writer.write(copied, fix)) {
                    is GpsWriteResult.Embedded -> embedded++
                    is GpsWriteResult.Sidecar -> sidecars++
                }
            } catch (e: Exception) {
                echo("  could not geotag ${copied.name}: ${e.message ?: e}", err = true)
                failed++
            }
        }
        echo(
            "Geotagged ${embedded + sidecars} of ${result.copied.size} files " +
                "($embedded embedded, $sidecars sidecars, ${plan.withoutFix + failed} untagged)."
        )
    }

    private fun printPlan(plan: ImportPlan, hasTrack: Boolean) {
        echo("Would copy ${plan.selected.size} files into ${plan.destFolder.path}")
        plan.resumeAfter?.let { echo("Resuming after '$it'") }
        echo("")
        val nameWidth = plan.selected.maxOf { it.source.name.length }.coerceAtLeast(8)
        for (entry in plan.selected) {
            val where = entry.fix?.let { "%9.5f, %9.5f".format(it.lat, it.lon) }
                ?: if (hasTrack) "no match within tolerance" else "-"
            val how = entry.writer?.let { " [$it]" } ?: ""
            echo("  ${entry.source.name.padEnd(nameWidth)}  ${entry.captureTime}  $where$how")
        }
        echo("")
        if (hasTrack) {
            echo("${plan.willGeotag} would be geotagged, ${plan.withoutFix} would not.")
            if (plan.acrossWideGap > 0) {
                echo(
                    "${plan.acrossWideGap} of those sit in a track gap wider than " +
                        "${ImportPlan.WIDE_GAP_METERS.toInt()} m, so the position is inferred."
                )
            }
        }
        echo("Nothing was written (--dry-run).")
    }

    /** `auto` / null means the watermark; otherwise a filename or a timestamp. */
    private fun parseResume(value: String?) = when {
        value == null || value.equals("auto", ignoreCase = true) -> null to null
        else -> parseTimestamp(value)?.let { null to it } ?: (value to null)
    }
}
