package com.pbungert.geoimport.core.model

import java.time.Instant

/**
 * A timestamped position. Used both for points read from a track file and for
 * the (usually interpolated) fix written into a photo.
 *
 * Everything past [ele] is evidence about how good the fix is rather than part
 * of the fix itself, and every field of it is nullable on purpose: a GPX or KML
 * file from another source carries none of it, and nothing in core may require
 * it. A reader that wants to judge a point has to cope with knowing nothing.
 */
data class TrackPoint(
    val time: Instant,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    /** Horizontal accuracy in metres, when the source recorded one. */
    val accuracy: Double? = null,
    /** Accuracy of [ele] in metres. Often far worse than [accuracy]. */
    val eleAccuracy: Double? = null,
    /**
     * Ground speed in m/s as the receiver reported it. Independent evidence of
     * movement: unlike a speed derived from two positions, a spike cannot
     * manufacture it.
     */
    val speed: Double? = null,
    /**
     * Which sensor produced the fix — [SOURCE_GPS], [SOURCE_FUSED], or whatever
     * a future recorder writes. Null for files this app did not record, which
     * is the normal case for an imported track.
     */
    val source: String? = null,
) {
    companion object {
        /** A GNSS fix, from the satellites directly. */
        const val SOURCE_GPS = "gps"

        /** A platform-fused fix, which may rest on wifi or cell positioning. */
        const val SOURCE_FUSED = "fused"
    }
}
