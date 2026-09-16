package com.pbungert.geoimport.core.model

import java.time.Instant

/**
 * A timestamped position. Used both for points read from a track file and for
 * the (usually interpolated) fix written into a photo.
 */
data class TrackPoint(
    val time: Instant,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    /** Horizontal accuracy in metres, when the source recorded one. */
    val accuracy: Double? = null,
)
