package com.pbungert.geoimport

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.core.model.distanceMeters
import com.pbungert.geoimport.recorder.TrackRecorderService
import com.pbungert.geoimport.ui.map.formatDistance
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TRACK_DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM")

/** Newest first, which is the order you think about your own recordings in. */
internal fun orderedTracks(viewModel: ImportViewModel): List<ImportViewModel.LoadedTrack> =
    viewModel.tracks.sortedByDescending { it.track.span?.endInclusive ?: Instant.MIN }

/** "16 Sep · 8.4 km", or the point count when there is no distance to speak of. */
private fun trackSummary(track: Track, zone: ZoneId): String {
    val day = track.span?.let { TRACK_DAY_FORMAT.format(it.endInclusive.atZone(zone)) }
    val meters = track.distanceMeters
    val detail = if (meters >= 1.0) {
        formatDistance(meters)
    } else {
        "${track.size} point" + if (track.size == 1) "" else "s"
    }
    return listOfNotNull(day, detail).joinToString(" · ")
}

/**
 * The tracks, and the only list of them there is: the home sheet and the import
 * settings render this same composable off the same state, so ticking a track
 * in one is ticking it in the other.
 *
 * Ticking nothing is the normal case and means "work it out" - the import
 * narrows the whole set to what covers the photos. Ticking some says: only
 * these, on the map and in the next import.
 */
@Composable
internal fun TrackList(
    viewModel: ImportViewModel,
    zone: ZoneId,
    showHeader: Boolean,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Null means "take what you need": inside a screen that already scrolls,
    // a scrolling list within a scrolling list fights the finger.
    maxListHeight: Dp? = null,
) {
    val context = LocalContext.current
    val ordered = orderedTracks(viewModel)
    val selected = viewModel.selectedTrackNames
    val active = viewModel.activeTrackNames

    val pickTracks = rememberLauncherForActivityResult(OpenTrackDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addTracks(uris)
    }

    Column(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showHeader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            ) {
                Text(
                    "Tracks",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (ordered.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            if (selected.isEmpty()) viewModel.selectAllTracks()
                            else viewModel.clearTrackSelection()
                        }
                    ) {
                        Text(if (selected.isEmpty()) "Choose all" else "Clear")
                    }
                }
            }
        }

        val recordedHere = ordered.filter { it.fromRecorder }
        val addedByHand = ordered.filter { !it.fromRecorder }
        // Only worth labelling when there are both kinds to tell apart.
        val grouped = recordedHere.isNotEmpty() && addedByHand.isNotEmpty()

        Column(
            modifier = if (maxListHeight == null) {
                Modifier
            } else {
                Modifier.heightIn(max = maxListHeight).verticalScroll(rememberScrollState())
            },
        ) {
            if (ordered.isEmpty()) {
                Text(
                    if (viewModel.tracksLoading) "Reading tracks…"
                    else "Nothing yet. Tracks you record, and GPX files you add, are listed here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            if (grouped) TrackGroupLabel("Recorded")
            for (track in recordedHere + addedByHand) {
                if (grouped && track === addedByHand.firstOrNull()) TrackGroupLabel("Added")
                // Summing a track's length walks every point, so it is worked
                // out once per track rather than on every recomposition. The
                // point count has to be part of the key: a recording keeps its
                // path while it grows, so on the path alone the row for the run
                // in progress would freeze at whatever it read first.
                val measured = remember(track.key, track.track.size) {
                    trackSummary(track.track, zone)
                }
                TrackRow(
                    name = track.name,
                    summary = buildString {
                        append(measured)
                        // Once a plan exists it has said which tracks it
                        // matched against; a chosen track it ignored is worth
                        // saying out loud.
                        if (active?.contains(track.name) == false) {
                            append(" · outside this import")
                        }
                    },
                    checked = track.name in selected,
                    onToggle = { viewModel.toggleTrackSelected(track.name) },
                    trailing = {
                        if (track.fromRecorder) {
                            IconButton(onClick = {
                                // The key is the path it was read from - a
                                // recorded folder may hold .kml as well as .gpx,
                                // so the name alone would build a missing file.
                                val file = File(track.key)
                                if (!shareTrack(context, file)) {
                                    onMessage("Nothing here can share a file.")
                                }
                            }) {
                                Icon(
                                    Icons.Filled.Share,
                                    contentDescription = "Share ${track.name}",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            IconButton(onClick = { viewModel.removeTrack(track.key) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove ${track.name}",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 20.dp),
        ) {
            OutlinedButton(
                onClick = { pickTracks.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add a GPX file", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = {
                    if (!openTracksFolder(context)) {
                        onMessage("Tracks are in Documents/GPS-Tracks on this phone.")
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Says which tracks came from where, in place of a switch for the whole folder. */
@Composable
private fun TrackGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun TrackRow(
    name: String,
    summary: String,
    checked: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = 12.dp, end = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/**
 * Opens the tracks folder in whatever browses files on this phone. The one
 * thing the app could not do before: say where the GPX files are in a way you
 * can act on, rather than a path to type in somewhere else.
 *
 * Returns false when nothing on the device answers, which is the caller's cue
 * to fall back to telling the user the path.
 */
private fun openTracksFolder(context: Context): Boolean {
    val folder = TrackRecorderService.tracksDirDocumentUri() ?: return false
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching { context.startActivity(view) }.isSuccess
}

/** Hands one track to another app - mail, cloud storage, a mapping tool. */
private fun shareTrack(context: Context, file: File): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(TrackRecorderService.GPX_MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share ${file.nameWithoutExtension}",
        )
    )
}.isSuccess

/**
 * OpenMultipleDocuments that starts in the app's recorded-tracks folder when
 * at least one recorded track exists there.
 */
private class OpenTrackDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        recordedTracksDirUri()?.let {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it)
        }
        return intent
    }

    /** Null when nothing is recorded there yet, so the picker opens where it would anyway. */
    private fun recordedTracksDirUri(): Uri? {
        val hasTracks = TrackRecorderService.tracksDir().listFiles()
            ?.any { it.extension.equals("gpx", ignoreCase = true) } == true
        return if (hasTracks) TrackRecorderService.tracksDirDocumentUri() else null
    }
}
