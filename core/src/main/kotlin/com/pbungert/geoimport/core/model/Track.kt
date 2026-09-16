package com.pbungert.geoimport.core.model

import java.time.Instant

/**
 * A recording, kept in the segments it was actually recorded in: a GPX
 * `<trkseg>`, a separate `<trk>`/`<rte>`, or a KML `gx:Track`.
 *
 * The distinction matters because a gap *inside* a segment is a dropout — the
 * device kept moving and the positions either side bracket where it went — while
 * a gap *between* two segments is a stretch that was never recorded at all. The
 * recorder already writes a segment break whenever it is paused, and merging
 * several files produces the same thing at a larger scale: the space between
 * one outing and the next is not a straight line anybody walked.
 */
data class Track(val segments: List<List<TrackPoint>>) {

    /** Every point in time order, for callers that only draw or count them. */
    val points: List<TrackPoint> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        segments.flatten().sortedBy { it.time }
    }

    val size get() = segments.sumOf { it.size }

    val isEmpty get() = size == 0

    /**
     * First to last recorded instant, or null when there are no points. This
     * is what decides whether a track is worth loading for a given set of
     * photos; it says nothing about the gaps in between.
     */
    val span: ClosedRange<Instant>?
        get() = points.takeIf { it.isNotEmpty() }?.let { it.first().time..it.last().time }

    companion object {
        val EMPTY = Track(emptyList())

        /** Treats [points] as one continuous segment. */
        fun of(points: List<TrackPoint>) = Track(listOf(points.sortedBy { it.time }))

        /** Concatenates tracks, keeping every segment boundary intact. */
        fun merge(tracks: Iterable<Track>) = Track(tracks.flatMap { it.segments })
    }
}
