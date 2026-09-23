package com.pbungert.geoimport.core.track

import com.pbungert.geoimport.core.model.Track
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A parsed track together with the name to report it under. */
data class NamedTrack(val name: String, val track: Track)

/**
 * Picks the tracks worth matching a set of photos against.
 *
 * Handing every track you own to the geotagger is correct but wasteful and
 * unreadable: most of them cover days the photos have nothing to do with, and
 * they crowd the preview map. Time ranges settle it without any guesswork -
 * a track that ends before the first photo was taken cannot place any of them.
 *
 * Errors here fail safe in one direction. The photo range is a union, so a file
 * with a nonsense capture time only widens it and pulls in more tracks than
 * needed; nothing can narrow it enough to drop a track that would have matched.
 */
object TrackSelection {

    /**
     * What [choose] decided, in a form both front ends can report the same way.
     */
    data class Choice(
        val used: List<NamedTrack>,
        val skipped: List<NamedTrack>,
        /** First to last capture time, or null when there were no photos. */
        val photos: ClosedRange<Instant>?,
    ) {
        /** The used tracks as one track, with every segment boundary intact. */
        val track: Track get() = Track.merge(used.map { it.track })

        /**
         * [track] with the misleading fixes taken out. Cleaning the merged
         * track is the same as cleaning each one first, because judging never
         * crosses a segment boundary.
         */
        val cleanup: TrackCleanup.Result by lazy { TrackCleanup.clean(track) }

        /**
         * What a photo's position should be read from: a spike placed the
         * device somewhere it never was, and a fix interpolated towards one
         * inherits that. The raw [track] stays available for showing what was
         * left out.
         */
        val cleanedTrack: Track get() = cleanup.track

        /**
         * Lines for the log. Worth saying out loud: an import that silently
         * tags nothing because the only track is a day off is the kind of thing
         * that otherwise gets blamed on the writer.
         */
        fun lines(zone: ZoneId = ZoneId.systemDefault()): List<String> {
            val total = used.size + skipped.size
            // Nothing to match, or nothing to match it against: either way the
            // caller has something better to report than the state of the tracks.
            if (total == 0 || photos == null) return emptyList()
            val range = "${format(photos.start, zone)} to ${format(photos.endInclusive, zone)}"

            if (used.isEmpty()) {
                val nearest = skipped
                    .mapNotNull { named -> named.track.span?.let { named to it } }
                    .minByOrNull { (_, span) -> distanceTo(span, photos) }
                val subject =
                    if (total == 1) "The only track does not cover"
                    else "None of the $total tracks cover"
                return listOfNotNull(
                    "$subject these photos ($range).",
                    nearest?.let { (named, span) ->
                        "Nearest is ${named.name}: " +
                            "${format(span.start, zone)} to ${format(span.endInclusive, zone)}. " +
                            "A wrong camera clock or photo time zone looks exactly like this."
                    },
                )
            }

            val head = if (skipped.isEmpty()) {
                "Matching against ${describeUsed()}."
            } else {
                "Matching against ${used.size} of $total tracks: ${describeUsed()} " +
                    "(${skipped.size} outside the photos' time range)."
            }
            return listOfNotNull(head, cleanupLine())
        }

        /** Only says anything when cleaning actually changed the track. */
        private fun cleanupLine(): String? {
            val fixes = cleanup.removedCount
            val altitudes = cleanup.count(TrackCleanup.Finding.ELEVATION_EXCURSION) +
                cleanup.count(TrackCleanup.Finding.ELEVATION_UNTRUSTED)
            val parts = listOfNotNull(
                if (fixes > 0) "$fixes misleading ${plural(fixes, "fix", "fixes")}" else null,
                if (altitudes > 0) {
                    "$altitudes ${plural(altitudes, "altitude", "altitudes")}"
                } else {
                    null
                },
            )
            if (parts.isEmpty()) return null
            return "Leaving out ${parts.joinToString(" and ")}."
        }

        private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

        private fun describeUsed() = used.joinToString(", ") { it.name } +
            " (${used.sumOf { it.track.size }} points)"

        /** How far a non-overlapping track sits from the photos, either way round. */
        private fun distanceTo(span: ClosedRange<Instant>, photos: ClosedRange<Instant>) = when {
            span.endInclusive < photos.start -> Duration.between(span.endInclusive, photos.start)
            span.start > photos.endInclusive -> Duration.between(photos.endInclusive, span.start)
            else -> Duration.ZERO
        }

        private fun format(t: Instant, zone: ZoneId) = FORMAT.format(t.atZone(zone))
    }

    /**
     * Keeps the tracks whose recorded span reaches the photos, padded by
     * [tolerance] at both ends because that is how far past a segment's end a
     * photo can still be placed. Tracks with no timestamped points are dropped.
     */
    fun choose(
        tracks: List<NamedTrack>,
        captureTimes: List<Instant>,
        tolerance: Duration,
    ): Choice {
        val first = captureTimes.minOrNull() ?: return Choice(emptyList(), tracks, null)
        val photos = first..captureTimes.maxOrNull()!!

        val from = photos.start.minus(tolerance)
        val to = photos.endInclusive.plus(tolerance)
        val (used, skipped) = tracks.partition { named ->
            val span = named.track.span ?: return@partition false
            !span.endInclusive.isBefore(from) && !span.start.isAfter(to)
        }
        return Choice(used, skipped, photos)
    }

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
