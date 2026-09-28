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

    /**
     * Selected files whose position was interpolated across a wide dropout.
     * Reported rather than acted on: a long gap where the track did not move
     * (a night in one place) is harmless, while a short one covering kilometres
     * is not, so only the distance is worth flagging - and only to the user.
     */
    val acrossWideGap get() = selected.count { (it.gapMeters ?: 0.0) >= WIDE_GAP_METERS }

    /** How many files of one type the plan holds, and how many of them are picked. */
    data class TypeCount(val type: String, val total: Int, val selected: Int)

    /**
     * The file types on offer, most files first, by extension in lower case -
     * what the preview offers to include or leave out.
     */
    val types: List<TypeCount>
        get() = entries.groupBy { it.source.extension.lowercase() }
            .map { (type, files) -> TypeCount(type, files.size, files.count { it.selected }) }
            .sortedWith(compareByDescending<TypeCount> { it.total }.thenBy { it.type })

    /** A copy with every file of [type] picked, or none of them. */
    fun withType(type: String, selected: Boolean) = copy(
        entries = entries.map {
            if (it.source.extension.equals(type, ignoreCase = true)) it.copy(selected = selected) else it
        },
    )

    companion object {
        const val WIDE_GAP_METERS = 500.0
    }
}

/**
 * One file the import would copy, with the position it would be tagged with.
 *
 * [fix] is null when no track was supplied, or when the capture time fell
 * outside the track beyond the tolerance. [writer] names the writer that would
 * handle it, so a dry run shows whether a file gets embedded tags or a sidecar
 * before anything is written. [gapMeters] bounds how far off [fix] can be.
 */
data class PlannedFile(
    val source: File,
    val destination: File,
    val captureTime: Instant,
    val fix: TrackPoint? = null,
    val writer: String? = null,
    /** Distance between the bracketing track points; see [Geotagger.Fix]. */
    val gapMeters: Double? = null,
    /** Cleared when the user deselects a row in the preview. */
    val selected: Boolean = true,
)
