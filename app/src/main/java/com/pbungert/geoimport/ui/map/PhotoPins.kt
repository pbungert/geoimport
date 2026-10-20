package com.pbungert.geoimport.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Collections
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A photo at the position it was, or is about to be, tagged with. Shown on the
 * map so a wrong placement is something you see rather than something you
 * find out about in Lightroom a week later.
 */
data class PhotoPin(
    val file: File,
    val lat: Double,
    val lon: Double,
    val time: Instant,
    /** Left out of the run, so shown faded rather than not at all. */
    val dimmed: Boolean = false,
)

/**
 * The square a thumbnail fits inside, on the map and in the import list, and
 * the spacing they keep from each other on the map.
 */
internal val THUMB_SIZE = 40.dp

/** Between a thumbnail and the point it stands for, so the dot under it stays visible. */
private val THUMB_GAP = 6.dp

private val THUMB_SHAPE = RoundedCornerShape(6.dp)

private val PREVIEW_SIZE = 120.dp

private val VIDEO_EXTENSIONS = setOf("mov", "mp4")

/**
 * Small previews of photos in their own proportions, taken from the thumbnail
 * the camera already embedded where there is one. Decoding the full image of a
 * 40 MB RAF to show it at 40dp would take longer than reading the whole card did.
 */
internal object PhotoThumbnails {

    /** The long side; big enough for the enlarged preview, the largest of the uses. */
    private const val SIZE_PX = 256

    // An eighth of what the app may use, the usual share for a bitmap cache.
    // A whole card never fits, and one that drops out is only a re-read away.
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Files that had nothing to show, so a pan back over them does not try again. */
    private val failed = Collections.synchronizedSet(HashSet<String>())

    /** A handful at once: the card is one device, and it does not get faster with more readers. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decoding = Dispatchers.IO.limitedParallelism(4)

    fun cached(file: File): ImageBitmap? = cache.get(file.path)

    suspend fun load(file: File): ImageBitmap? {
        cache.get(file.path)?.let { return it }
        if (file.path in failed) return null
        return withContext(decoding) {
            val bitmap = runCatching { decode(file) }.getOrNull()
            if (bitmap == null) {
                failed += file.path
                null
            } else {
                bitmap.asImageBitmap().also { cache.put(file.path, it) }
            }
        }
    }

    private fun decode(file: File): Bitmap? {
        if (file.extension.lowercase() in VIDEO_EXTENSIONS) {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.path)
                // Scaled to fit inside the box, so it keeps the video's proportions.
                retriever.getScaledFrameAtTime(
                    0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE_PX, SIZE_PX,
                )?.let { upright(it, 0, aspect = null) }
            } finally {
                retriever.release()
            }
        }
        val exif = ExifInterface(file)
        // The embedded thumbnail is stored as shot; the orientation tag is
        // what turns it the right way up, same as for the full image.
        exif.thumbnailBitmap?.let { return upright(it, exif.rotationDegrees, storedAspect(exif)) }
        if (file.extension.lowercase() !in setOf("jpg", "jpeg")) return null
        return sampledJpeg(file)?.let { upright(it, exif.rotationDegrees, aspect = null) }
    }

    /** A JPEG without an EXIF thumbnail, decoded at the smallest scale still big enough. */
    private fun sampledJpeg(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        var sample = 1
        while (longSide / (sample * 2) >= SIZE_PX) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Width over height of the full image as stored, before turning, if the EXIF says. */
    private fun storedAspect(exif: ExifInterface): Float? {
        fun ratio(widthTag: String, heightTag: String): Float? {
            val width = exif.getAttributeInt(widthTag, 0)
            val height = exif.getAttributeInt(heightTag, 0)
            return if (width > 0 && height > 0) width.toFloat() / height else null
        }
        return ratio(ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION)
            ?: ratio(ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH)
    }

    /**
     * [source] turned upright, its long side no bigger than [SIZE_PX].
     *
     * Cameras embed a 4:3 thumbnail whatever the photo, with black bars above
     * and below a 3:2 one; cutting it to the photo's [aspect] takes the bars
     * off. Only when both lie the same way, though - an aspect that disagrees
     * on which side is longer is not describing this thumbnail, and cutting to
     * it would leave a sliver.
     */
    private fun upright(source: Bitmap, rotation: Int, aspect: Float?): Bitmap {
        var width = source.width
        var height = source.height
        val own = width.toFloat() / height
        if (aspect != null && (aspect - 1) * (own - 1) >= 0) {
            if (aspect > own) {
                height = (width / aspect).roundToInt().coerceIn(1, height)
            } else {
                width = (height * aspect).roundToInt().coerceIn(1, width)
            }
        }
        val scale = minOf(1f, SIZE_PX.toFloat() / maxOf(width, height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(
            source, (source.width - width) / 2, (source.height - height) / 2, width, height, matrix, true,
        )
    }
}

/** One thumbnail drawn on the map, standing in for [count] photos close enough to share it. */
internal data class PinGroup<T>(val lead: T, val at: DpOffset, val count: Int)

/**
 * Thins [points] to ones at least [spacing] apart on screen, in the order
 * given, so the first wins. Every point dropped is counted on the kept one
 * nearest to it, so a group of thirty photos at one viewpoint reads as thirty
 * rather than as one.
 *
 * A grid of [spacing]-sized cells keeps it to looking at nine cells per point
 * instead of at every point kept so far.
 */
internal fun <T> declutter(points: List<Pair<T, DpOffset>>, spacing: Dp): List<PinGroup<T>> {
    val cell = spacing.value
    val kept = ArrayList<Pair<T, DpOffset>>()
    val counts = ArrayList<Int>()
    val grid = HashMap<Long, MutableList<Int>>()
    fun cellKey(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    for ((item, at) in points) {
        val cx = floor(at.x.value / cell).toInt()
        val cy = floor(at.y.value / cell).toInt()
        var nearest = -1
        var nearestDistance = Float.MAX_VALUE
        for (dx in -1..1) for (dy in -1..1) {
            for (index in grid[cellKey(cx + dx, cy + dy)].orEmpty()) {
                val other = kept[index].second
                // Square thumbnails overlap when both axes are close, not by
                // straight-line distance.
                val distance = maxOf(abs(other.x.value - at.x.value), abs(other.y.value - at.y.value))
                if (distance < cell && distance < nearestDistance) {
                    nearest = index
                    nearestDistance = distance
                }
            }
        }
        if (nearest >= 0) {
            counts[nearest]++
        } else {
            grid.getOrPut(cellKey(cx, cy)) { mutableListOf() } += kept.size
            kept += item to at
            counts += 1
        }
    }
    return kept.mapIndexed { index, (item, at) -> PinGroup(item, at, counts[index]) }
}

/** Whether [lat], [lon] is inside, minding a box that crosses the antimeridian. */
private fun BoundingBox.covers(lat: Double, lon: Double): Boolean {
    if (lat < south || lat > north) return false
    return if (west <= east) lon in west..east else lon >= west || lon <= east
}

/**
 * Draws [pins] as thumbnails over a map, each one just above its point. Only
 * those in view are placed, and close ones share a thumbnail with a count on
 * it, so a full card zoomed out is a readable handful rather than a pile.
 *
 * [selected] is shown enlarged with its name and time. Tapping a thumbnail
 * selects it; tapping the enlarged one lets it go.
 */
@Composable
internal fun PhotoThumbnailOverlay(
    pins: List<PhotoPin>,
    cameraState: CameraState,
    selected: PhotoPin?,
    onSelect: (PhotoPin?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val projection = cameraState.projection ?: return
    // Keyed on the camera, so the thumbnails follow every pan and zoom.
    val groups = remember(pins, projection, cameraState.position) {
        val bounds = projection.queryVisibleBoundingBox()
        declutter(
            pins.filter { bounds.covers(it.lat, it.lon) }
                .map { it to projection.screenLocationFromPosition(Position(it.lon, it.lat)) },
            THUMB_SIZE,
        )
    }
    val selectedAt = remember(selected, projection, cameraState.position) {
        selected?.let { projection.screenLocationFromPosition(Position(it.lon, it.lat)) }
    }

    Box(modifier = modifier.clipToBounds()) {
        for (group in groups) {
            if (group.lead.file == selected?.file) continue
            key(group.lead.file.path) {
                Box(
                    modifier = Modifier
                        .anchoredAbove(group.at, THUMB_GAP)
                        .clickable { onSelect(group.lead) },
                ) {
                    // Fits inside THUMB_SIZE whatever its shape, so the
                    // spacing declutter keeps still stops them overlapping.
                    PhotoThumbnail(group.lead.file, THUMB_SIZE, dimmed = group.lead.dimmed)
                    if (group.count > 1) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd)
                                .layout { measurable, constraints ->
                                    // Hangs off the corner rather than covering the photo.
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, placeable.height) {
                                        placeable.place(placeable.width / 3, -placeable.height / 3)
                                    }
                                },
                        ) {
                            Text(
                                "${group.count}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
        if (selected != null && selectedAt != null) {
            SelectedPhoto(
                selected,
                modifier = Modifier
                    .anchoredAbove(selectedAt, THUMB_GAP, keepInside = true)
                    .clickable { onSelect(null) },
            )
        }
    }
}

@Composable
private fun SelectedPhoto(pin: PhotoPin, modifier: Modifier = Modifier) {
    val takenFormat = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.width(PREVIEW_SIZE + 12.dp).padding(6.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                PhotoThumbnail(pin.file, PREVIEW_SIZE, dimmed = pin.dimmed, framed = false)
            }
            Text(
                pin.file.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                pin.time.atZone(ZoneId.systemDefault()).format(takenFormat),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One photo in its own proportions, its long side filling [box] - the same
 * picture on the map and in the import list. Shows a square placeholder until
 * the image is read, and for good when there is nothing to read - a CR3 has no
 * thumbnail this can find.
 */
@Composable
internal fun PhotoThumbnail(file: File, box: Dp, dimmed: Boolean = false, framed: Boolean = true) {
    val image by produceState(PhotoThumbnails.cached(file), file) {
        if (value == null) value = PhotoThumbnails.load(file)
    }
    val frame = if (framed) {
        Modifier.shadow(3.dp, THUMB_SHAPE).border(2.dp, Color.White, THUMB_SHAPE)
    } else {
        Modifier
    }
    val shown = image
    val sized = when {
        shown == null -> Modifier.size(box)
        shown.width >= shown.height -> Modifier.size(box, box * (shown.height.toFloat() / shown.width))
        else -> Modifier.size(box * (shown.width.toFloat() / shown.height), box)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = sized
            .then(frame)
            .clip(THUMB_SHAPE)
            .background(Color(0xFFE0E0E0)),
    ) {
        val alpha = if (dimmed) 0.4f else 1f
        if (shown != null) {
            Image(
                shown,
                contentDescription = file.name,
                // The box already has the photo's proportions; this only
                // swallows the rounding, rather than leaving a grey hairline.
                contentScale = ContentScale.Crop,
                alpha = alpha,
                modifier = Modifier.fillMaxSize().padding(if (framed) 2.dp else 0.dp).clip(THUMB_SHAPE),
            )
        } else {
            Icon(
                if (file.extension.lowercase() in VIDEO_EXTENSIONS) Icons.Filled.Videocam else Icons.Filled.Photo,
                contentDescription = file.name,
                tint = Color(0xFF757575).copy(alpha = alpha),
                modifier = Modifier.size(box / 2),
            )
        }
    }
}

/**
 * Places the content centred horizontally on [at] with its bottom edge [gap]
 * above it - the way a pin stands on the point it marks.
 *
 * With [keepInside], content that would stick out of the parent is pushed
 * back in: it goes on whichever side of the point has more room, and is
 * moved inside the edges if that still is not enough - on a short map it can
 * end up covering the point, which beats being cut in half. Meant for
 * something too big to read half of, not for the small thumbnails, which are
 * better cut off than shown somewhere else.
 */
private fun Modifier.anchoredAbove(at: DpOffset, gap: Dp, keepInside: Boolean = false) =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(placeable.width, placeable.height) {
            var x = at.x.roundToPx() - placeable.width / 2
            var y = at.y.roundToPx() - gap.roundToPx() - placeable.height
            if (keepInside) {
                x = x.coerceIn(0, maxOf(0, constraints.maxWidth - placeable.width))
                val below = at.y.roundToPx() + gap.roundToPx()
                if (y < 0 && constraints.maxHeight - below > at.y.roundToPx() - gap.roundToPx()) y = below
                y = y.coerceIn(0, maxOf(0, constraints.maxHeight - placeable.height))
            }
            placeable.place(x, y)
        }
    }
