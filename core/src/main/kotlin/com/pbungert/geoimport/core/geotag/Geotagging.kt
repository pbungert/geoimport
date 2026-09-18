package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import java.io.File

/** A file and the position it should be given, or null when none was found. */
data class GeotagTarget(val file: File, val fix: TrackPoint?)

/**
 * How a batch of writes landed.
 *
 * [missing] and [failed] are kept apart because they mean different things to
 * a user: nothing was near enough in the track, versus something was and the
 * write did not work. Both front ends end up adding them together for a
 * headline count, and both want the distinction in the detail.
 */
data class GeotagCounts(
    val embedded: Int = 0,
    val sidecars: Int = 0,
    val missing: Int = 0,
    val failed: Int = 0,
) {
    val written get() = embedded + sidecars

    val untagged get() = missing + failed
}

/**
 * Writes each target's position with [writer], counting how they landed.
 *
 * This loop was written out four times - twice in the Android view model,
 * twice in the CLI - which is three more places for the fallback chain's two
 * outcomes to be counted differently, or for a failed write to stop a run that
 * should carry on. A file that throws is reported through [onFile] and the
 * batch continues: one unwritable photo is not a reason to abandon the rest.
 *
 * [onFile] is called once per target, in order, with the result or the error;
 * both null means there was no position to write. Progress, logging and
 * per-file output are the caller's business and differ between the two front
 * ends, so they happen there.
 */
fun writeGeotags(
    targets: List<GeotagTarget>,
    writer: GpsWriter,
    onFile: (target: GeotagTarget, result: GpsWriteResult?, error: Exception?) -> Unit =
        { _, _, _ -> },
): GeotagCounts {
    var counts = GeotagCounts()
    for (target in targets) {
        val fix = target.fix
        if (fix == null) {
            counts = counts.copy(missing = counts.missing + 1)
            onFile(target, null, null)
            continue
        }
        try {
            val result = writer.write(target.file, fix)
            counts = when (result) {
                is GpsWriteResult.Embedded -> counts.copy(embedded = counts.embedded + 1)
                is GpsWriteResult.Sidecar -> counts.copy(sidecars = counts.sidecars + 1)
            }
            onFile(target, result, null)
        } catch (e: Exception) {
            counts = counts.copy(failed = counts.failed + 1)
            onFile(target, null, e)
        }
    }
    return counts
}
