package com.pbungert.geoimport.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pbungert.geoimport.importer.TrackPoint
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
    val pointCount = tracks.sumOf { it.points.size }
    LaunchedEffect(pointCount) {
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
