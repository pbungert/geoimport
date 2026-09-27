package com.pbungert.geoimport.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.core.model.TrackPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.material3.ExpandingAttributionButton
import org.maplibre.compose.material3.ScaleBar
import org.maplibre.compose.material3.ScaleBarMeasure
import org.maplibre.compose.material3.ScaleBarMeasures
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.rememberStyleState
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import kotlin.math.abs

/**
 * A named track ready to be drawn on a map. Segments are drawn as separate
 * lines, so a pause or a gap between two merged recordings shows up as the
 * break it is rather than a straight line across it.
 *
 * [underlay] and [marks] exist to show what a pass over the track changed:
 * the before-version is drawn faintly underneath and the fixes it lost are
 * ringed, so the difference is visible instead of having to be trusted. Both
 * are tappable - see `onMarkClick` and `onUnderlayClick` on [TrackMap] - which
 * is where the explanation lives, rather than in a banner nobody asked for.
 */
data class DisplayTrack(
    val name: String,
    val track: Track,
    /** Drawn faint and beneath [track]. Null when there is nothing to compare. */
    val underlay: Track? = null,
    /** Individual fixes to ring, typically the ones [track] no longer has. */
    val marks: List<TrackPoint> = emptyList(),
) {
    val points get() = track.points

    /** Everything the camera has to fit, which includes what was removed. */
    val framedPoints get() = if (underlay == null) points else underlay.points

    /**
     * The mark a tapped feature stands for, or null if the tap landed on none
     * of them. Matched on position rather than by identity: the feature has
     * been out to the map through GeoJSON and back, and its coordinates are
     * the only part of it that made the round trip.
     */
    internal fun markAt(point: Point): TrackPoint? = marks.firstOrNull {
        abs(it.lat - point.latitude) < MARK_MATCH_DEGREES &&
            abs(it.lon - point.longitude) < MARK_MATCH_DEGREES
    }
}

/** About a metre, far below the distance between two separate spikes. */
private const val MARK_MATCH_DEGREES = 1e-5

private const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

private val TRACK_COLORS = listOf(
    Color(0xFFD32F2F), Color(0xFF1976D2), Color(0xFF388E3C), Color(0xFFF57C00),
    Color(0xFF7B1FA2), Color(0xFF00796B), Color(0xFFC2185B), Color(0xFF5D4037),
)

/** Blue dot marking the device's own position, the usual map convention. */
private val MY_LOCATION_COLOR = Color(0xFF1A73E8)

/** The before-version of a track: present, clearly behind, not competing. */
private val UNDERLAY_COLOR = Color(0xFF9E9E9E)

/** Rings a fix that was taken out, in the colour of a correction. */
private val MARK_COLOR = Color(0xFFD32F2F)

/**
 * What a tap actually aims at, as opposed to what is drawn. Short of the 48dp
 * Android asks for - a track doubles back on itself and two parts of the same
 * line would start fighting over the same tap - but wide enough for a thumb.
 */
private val TOUCH_WIDTH = 28.dp
private val TOUCH_RADIUS = 16.dp

/**
 * Not [Color.Transparent], which is the whole point: the map hit-tests what it
 * has drawn, and a layer at zero alpha is drawn nowhere and hits nothing. At
 * this alpha it is present to the renderer and invisible to the eye - a 5dp
 * grey line under a 28dp veil of itself looks exactly like a 5dp grey line.
 */
private const val TOUCH_ALPHA = 0.01f

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** Most accurate first; the first enabled one gets asked for a fix. */
private val LOCATION_PROVIDERS = listOf(
    LocationManager.FUSED_PROVIDER,
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
)

/**
 * Map showing [tracks] as colored polylines with a dot on each track's latest
 * point. The camera fits all points whenever they change, so a live recording
 * stays in view, and a different set of [tracks] is always framed - choosing
 * tracks shows them even if the map was centred on the device's own position.
 * [onExpand], when set, adds a full-screen button overlay.
 *
 * [contentPadding] is the part of the map something else covers - a bottom
 * sheet, say. The ornaments move inside it and the camera fits the track into
 * what is left, so neither ends up under the thing on top.
 */
@Composable
fun TrackMap(
    tracks: List<DisplayTrack>,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** A ringed fix from [DisplayTrack.marks] was tapped. */
    onMarkClick: ((TrackPoint) -> Unit)? = null,
    /** The faint [DisplayTrack.underlay] line was tapped, away from any mark. */
    onUnderlayClick: (() -> Unit)? = null,
) {
    val cameraState = rememberCameraState()
    val styleState = rememberStyleState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var myLocation by remember { mutableStateOf<Position?>(null) }
    var locating by remember { mutableStateOf(false) }
    // Once the user has asked to see where they are, stop yanking the camera
    // back to the track bounds every time a new recorded point arrives.
    var followingMyLocation by remember { mutableStateOf(false) }

    fun showLocation(location: Location?) {
        locating = false
        if (location == null) {
            Toast.makeText(context, "Current location unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val position = Position(location.longitude, location.latitude)
        myLocation = position
        followingMyLocation = true
        scope.launch {
            cameraState.animateTo(
                CameraPosition(
                    target = position,
                    // Keep the user's zoom if they were already looking closely.
                    zoom = maxOf(cameraState.position.zoom, 15.0),
                )
            )
        }
    }

    fun locate() {
        if (locating) return
        locating = true
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = LOCATION_PROVIDERS.firstOrNull {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (provider == null) {
            showLocation(null)
            return
        }
        try {
            manager.getCurrentLocation(provider, null, context.mainExecutor) { showLocation(it) }
        } catch (_: SecurityException) {
            showLocation(null)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) locate()
        else Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    val layoutDirection = LocalLayoutDirection.current
    // 48dp of breathing room inside whatever the caller left uncovered.
    val fitPadding = remember(contentPadding, layoutDirection) {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + 48.dp,
            top = contentPadding.calculateTopPadding() + 48.dp,
            end = contentPadding.calculateEndPadding(layoutDirection) + 48.dp,
            bottom = contentPadding.calculateBottomPadding() + 48.dp,
        )
    }

    val pointCount = tracks.sumOf { it.framedPoints.size }
    // Names as well as the count: switching between two track sets of the same
    // size is a different view and has to refit.
    val trackNames = tracks.map { it.name }
    // Which set the camera was last framed on. A different set means the user
    // picked other tracks, and that outranks wherever the camera is pointing -
    // including at the blue dot.
    var framedNames by remember { mutableStateOf<List<String>?>(null) }
    var framedPadding by remember { mutableStateOf<PaddingValues?>(null) }
    LaunchedEffect(pointCount, trackNames, fitPadding) {
        val pickedOtherTracks = trackNames != framedNames
        if (followingMyLocation && !pickedOtherTracks) return@LaunchedEffect
        // Same tracks, same padding: only points were added, as while recording.
        // The track was framed when it was opened; zooming back out on every new
        // fix would undo whatever the user has panned or zoomed to since.
        if (!pickedOtherTracks && fitPadding == framedPadding) return@LaunchedEffect
        val points = tracks.flatMap { it.framedPoints }
        if (points.isEmpty()) return@LaunchedEffect
        // Only once there is something to frame, so an empty selection leaves
        // the camera where it was and the next real one still counts as new.
        framedNames = trackNames
        framedPadding = fitPadding
        if (pickedOtherTracks) followingMyLocation = false
        val south = points.minOf { it.lat }
        val north = points.maxOf { it.lat }
        val west = points.minOf { it.lon }
        val east = points.maxOf { it.lon }
        // A (near) zero-area box would make the camera zoom in to the maximum.
        if (north - south < 1e-6 && east - west < 1e-6) {
            cameraState.animateTo(
                CameraPosition(target = Position(west, south), zoom = 14.0)
            )
        } else {
            cameraState.animateTo(
                boundingBox = BoundingBox(
                    southwest = Position(west, south),
                    northeast = Position(east, north),
                ),
                padding = fitPadding,
            )
        }
    }

    Box(modifier = modifier) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(STYLE_URI),
            cameraState = cameraState,
            styleState = styleState,
            // All built-in ornaments off (logo included); the scale bar below
            // takes the logo's bottom-left spot. Compose controls replace them.
            options = MapOptions(ornamentOptions = OrnamentOptions.AllDisabled),
        ) {
            tracks.forEachIndexed { index, track ->
                key(track.name) {
                    val color = TRACK_COLORS[index % TRACK_COLORS.size]
                    // Laid down first so the cleaned line covers it: what shows
                    // through is exactly what cleaning took away.
                    track.underlay?.let { before ->
                        val drawnBefore = before.segments
                            .filter { it.size >= 2 }
                            .map { segment -> segment.map { Position(it.lon, it.lat) } }
                        if (drawnBefore.isNotEmpty()) {
                            val beforeLine = rememberGeoJsonSource(
                                GeoJsonData.Features(MultiLineString(drawnBefore))
                            )
                            LineLayer(
                                id = "track-before-${track.name}",
                                source = beforeLine,
                                color = const(UNDERLAY_COLOR),
                                width = const(5.dp),
                                cap = const(LineCap.Round),
                                join = const(LineJoin.Round),
                            )
                            // A 5dp line is a five-pixel target: findable by
                            // eye, unhittable by thumb. The tap goes to a
                            // wider near-invisible line laid over the top.
                            onUnderlayClick?.let { notify ->
                                LineLayer(
                                    id = "track-before-touch-${track.name}",
                                    source = beforeLine,
                                    color = const(UNDERLAY_COLOR.copy(alpha = TOUCH_ALPHA)),
                                    width = const(TOUCH_WIDTH),
                                    cap = const(LineCap.Round),
                                    join = const(LineJoin.Round),
                                    onClick = { notify(); ClickResult.Consume },
                                )
                            }
                        }
                    }
                    // A one-point segment has no line to draw, and MultiLineString
                    // rejects it outright.
                    val drawn = track.track.segments
                        .filter { it.size >= 2 }
                        .map { segment -> segment.map { Position(it.lon, it.lat) } }
                    if (drawn.isNotEmpty()) {
                        val line = rememberGeoJsonSource(
                            GeoJsonData.Features(MultiLineString(drawn))
                        )
                        LineLayer(
                            id = "track-casing-${track.name}",
                            source = line,
                            color = const(Color.White),
                            width = const(6.dp),
                            cap = const(LineCap.Round),
                            join = const(LineJoin.Round),
                        )
                        LineLayer(
                            id = "track-${track.name}",
                            source = line,
                            color = const(color),
                            width = const(3.dp),
                            cap = const(LineCap.Round),
                            join = const(LineJoin.Round),
                        )
                    }
                    // A track with no points has no head to mark. The view
                    // model filters empty ones out today, which is not a
                    // reason for a public composable to crash on one.
                    track.points.lastOrNull()?.let { last ->
                        val head = rememberGeoJsonSource(
                            GeoJsonData.Features(Point(Position(last.lon, last.lat)))
                        )
                        CircleLayer(
                            id = "track-head-${track.name}",
                            source = head,
                            color = const(color),
                            radius = const(5.dp),
                            strokeColor = const(Color.White),
                            strokeWidth = const(2.dp),
                        )
                    }
                    if (track.marks.isNotEmpty()) {
                        // One feature per mark rather than a single MultiPoint,
                        // so a tap comes back as the one position that was hit
                        // instead of the whole set at once.
                        val marked = rememberGeoJsonSource(
                            GeoJsonData.Features(
                                FeatureCollection(
                                    track.marks.map {
                                        Feature(
                                            Point(Position(it.lon, it.lat)),
                                            JsonObject(emptyMap()),
                                        )
                                    }
                                )
                            )
                        )
                        CircleLayer(
                            id = "track-marks-${track.name}",
                            source = marked,
                            color = const(MARK_COLOR.copy(alpha = 0.25f)),
                            radius = const(7.dp),
                            strokeColor = const(MARK_COLOR),
                            strokeWidth = const(2.dp),
                        )
                        // As with the line: what you aim at is bigger than
                        // what you see, and sits above everything so a ring
                        // wins the tap over the raw line running under it.
                        onMarkClick?.let { notify ->
                            CircleLayer(
                                id = "track-marks-touch-${track.name}",
                                source = marked,
                                color = const(MARK_COLOR.copy(alpha = TOUCH_ALPHA)),
                                radius = const(TOUCH_RADIUS),
                                onClick = { features ->
                                    val hit = features
                                        .asSequence()
                                        .mapNotNull { it.geometry as? Point }
                                        .mapNotNull { track.markAt(it) }
                                        .firstOrNull()
                                    if (hit == null) {
                                        ClickResult.Pass
                                    } else {
                                        notify(hit)
                                        ClickResult.Consume
                                    }
                                },
                            )
                        }
                    }
                }
            }
            myLocation?.let { position ->
                val here = rememberGeoJsonSource(GeoJsonData.Features(Point(position)))
                CircleLayer(
                    id = "my-location",
                    source = here,
                    color = const(MY_LOCATION_COLOR),
                    radius = const(6.dp),
                    strokeColor = const(Color.White),
                    strokeWidth = const(2.5.dp),
                )
            }
        }
        ScaleBar(
            metersPerDp = cameraState.metersPerDpAtTarget,
            measures = ScaleBarMeasures(primary = ScaleBarMeasure.Metric),
            // The tile style is always light, so use a muted dark grey with a
            // soft white halo: legible over land and water without shouting.
            color = Color.Black.copy(alpha = 0.42f),
            haloColor = Color.White.copy(alpha = 0.55f),
            haloWidth = 1.5.dp,
            modifier = Modifier.align(Alignment.BottomStart).padding(contentPadding).padding(8.dp),
        )
        var attributionExpanded by remember { mutableStateOf(false) }
        ExpandingAttributionButton(
            expanded = attributionExpanded,
            onClick = { attributionExpanded = !attributionExpanded },
            styleState = styleState,
            modifier = Modifier.align(Alignment.BottomEnd).padding(contentPadding).padding(4.dp),
        )
        FilledTonalIconButton(
            onClick = {
                if (LOCATION_PERMISSIONS.any {
                        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                    }
                ) {
                    locate()
                } else {
                    permissionLauncher.launch(LOCATION_PERMISSIONS)
                }
            },
            // Sits one row above the attribution button in the same corner.
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(contentPadding)
                .padding(end = 8.dp, bottom = 48.dp),
        ) {
            if (locating) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(Icons.Filled.MyLocation, contentDescription = "Show my location")
            }
        }
        if (onExpand != null) {
            FilledTonalIconButton(
                onClick = onExpand,
                modifier = Modifier.align(Alignment.TopEnd).padding(contentPadding).padding(8.dp),
            ) {
                Icon(Icons.Filled.Fullscreen, contentDescription = "Full screen map")
            }
        }
    }
}

/** Full-screen [TrackMap] shown as a dialog over the whole app. */
@Composable
fun FullscreenTrackMapDialog(tracks: List<DisplayTrack>, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                TrackMap(tracks, modifier = Modifier.fillMaxSize())
                FilledTonalIconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close full screen map")
                }
            }
        }
    }
}
