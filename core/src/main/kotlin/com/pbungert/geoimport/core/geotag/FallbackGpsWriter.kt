package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import java.io.File

/**
 * Tries each writer in turn, falling through on both "cannot handle this
 * format" and "tried and failed".
 *
 * Desktop chains exiftool ahead of the built-in writer so extra formats gain
 * embedded tags, while `--writer=builtin` drops exiftool to reproduce the
 * phone's behaviour exactly. The last writer in the chain should accept
 * everything ([XmpSidecarWriter]) so a position is never silently lost.
 */
class FallbackGpsWriter(
    private val writers: List<GpsWriter>,
    private val onFallback: (file: File, writer: GpsWriter, error: Exception) -> Unit = { _, _, _ -> },
) : GpsWriter {

    init {
        require(writers.isNotEmpty()) { "a writer chain needs at least one writer" }
    }

    override val name = writers.joinToString(" -> ") { it.name }

    override fun supports(file: File) = writers.any { it.supports(file) }

    override fun effectiveWriterFor(file: File): GpsWriter? =
        writers.firstOrNull { it.supports(file) }?.effectiveWriterFor(file)

    override fun write(file: File, point: TrackPoint): GpsWriteResult {
        var last: Exception? = null
        for (writer in writers) {
            if (!writer.supports(file)) continue
            try {
                return writer.write(file, point)
            } catch (e: Exception) {
                last = e
                onFallback(file, writer, e)
            }
        }
        throw last ?: IllegalArgumentException("no writer accepted ${file.name}")
    }
}
