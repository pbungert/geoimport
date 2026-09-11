package com.pbungert.geoimport.core.imports

import com.pbungert.geoimport.core.model.TrackPoint
import java.io.File
import java.time.Instant

/**
 * What an import would do, computed without touching the disk.
 *
 * This is the contract between the two front ends: the CLI renders it as text
 * for `--dry-run`, the Android preview screen renders the same object as a
 * list. Because execution consumes exactly this, the preview cannot drift from
 * what actually happens.
 */
data class ImportPlan(
    val sourceRoot: File,
    /** Created by [PhotoImporter.execute]; does not exist while planning. */
    val destFolder: File,
    /** The file the resume watermark landed on, for explaining an empty plan. */
    val resumeAfter: String?,
    val entries: List<PlannedFile>,
) {
    val isEmpty get() = entries.isEmpty()

    /** Entries actually selected for copying. */
    val selected get() = entries.filter { it.selected }

    val willGeotag get() = selected.count { it.fix != null }

    val withoutFix get() = selected.count { it.fix == null }
}

/**
 * One file the import would copy, with the position it would be tagged with.
 *
 * [fix] is null when no track was supplied, or when the capture time fell
 * outside the track beyond the tolerance. [writer] names the writer that would
 * handle it, so a dry run shows whether a file gets embedded tags or a sidecar
 * before anything is written.
 */
data class PlannedFile(
    val source: File,
    val destination: File,
    val captureTime: Instant,
    val fix: TrackPoint? = null,
    val writer: String? = null,
    /** Cleared when the user deselects a row in the preview. */
    val selected: Boolean = true,
)
