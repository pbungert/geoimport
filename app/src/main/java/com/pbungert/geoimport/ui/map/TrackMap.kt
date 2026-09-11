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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pbungert.geoimport.core.model.TrackPoint
import kotlinx.coroutines.launch
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
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** A named list of track points ready to be drawn on a map. */
data class DisplayTrack(val name: String, val points: List<TrackPoint>)

private const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

private val TRACK_COLORS = listOf(
    Color(0xFFD32F2F), Color(0xFF1976D2), Color(0xFF388E3C), Color(0xFFF57C00),
    Color(0xFF7B1FA2), Color(0xFF00796B), Color(0xFFC2185B), Color(0xFF5D4037),
)

/** Blue dot marking the device's own position, the usual map convention. */
private val MY_LOCATION_COLOR = Color(0xFF1A73E8)

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
 * stays in view. [onExpand], when set, adds a full-screen button overlay.
 */
@Composable
fun TrackMap(
    tracks: List<DisplayTrack>,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
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

    val pointCount = tracks.sumOf { it.points.size }
    LaunchedEffect(pointCount) {
        if (followingMyLocation) return@LaunchedEffect
        val points = tracks.flatMap { it.points }
        if (points.isEmpty()) return@LaunchedEffect
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
                padding = PaddingValues(48.dp),
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
                    if (track.points.size >= 2) {
                        val line = rememberGeoJsonSource(
                            GeoJsonData.Features(
                                LineString(track.points.map { Position(it.lon, it.lat) })
                            )
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
                    val last = track.points.last()
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
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
        var attributionExpanded by remember { mutableStateOf(false) }
        ExpandingAttributionButton(
            expanded = attributionExpanded,
            onClick = { attributionExpanded = !attributionExpanded },
            styleState = styleState,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
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
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 48.dp),
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
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
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
