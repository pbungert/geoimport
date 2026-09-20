package com.pbungert.geoimport.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.net.Uri
import android.provider.DocumentsContract
import com.pbungert.geoimport.MainActivity
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.core.track.GpxWriter
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

data class RecordingState(
    val fileName: String,
    val startedAtMillis: Long,
    val pointCount: Int,
    val paused: Boolean = false,
    val droppedCount: Int = 0,
    /** When the current pause began, or null while recording. */
    val pausedAtMillis: Long? = null,
    /** Time spent in pauses that have already ended. */
    val pausedTotalMillis: Long = 0,
)

/**
 * Foreground service (type location) that records GPS fixes into a GPX file.
 * Runs with a partial wake lock and START_STICKY so recording keeps going
 * with the screen off; after a process kill it restarts and resumes the same
 * file using the settings persisted in SharedPreferences.
 */
class TrackRecorderService : Service() {

    private var writer: GpxWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var requestingUpdates = false
    private var intervalMillis = DEFAULT_INTERVAL_MILLIS

    /** Last GPS fix actually written, which is what lets fused stand down. */
    private var lastGpsFixRealtime = 0L

    /**
     * When each provider last *delivered* anything, accepted or not. Kept per
     * provider and updated on delivery rather than on acceptance, because what
     * it answers is "has this provider been quiet long enough that its next fix
     * is a re-acquisition?" - and a provider that is handing back rejects is
     * not quiet. Zero means it has not spoken since updates were requested.
     */
    private var lastGpsDelivery = 0L
    private var lastFusedDelivery = 0L

    // One listener per provider: LocationManager keys registrations by listener,
    // so sharing one would replace the first request instead of adding to it.
    private val gpsListener = LocationListener { onFix(it, fromGps = true) }
    private val fusedListener = LocationListener { onFix(it, fromGps = false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GPS track recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            intent?.action == ACTION_PAUSE -> pauseRecording()
            intent?.action == ACTION_RESUME -> resumeRecording()
            intent?.action == ACTION_START -> {
                val interval = intent.getLongExtra(EXTRA_INTERVAL_MILLIS, DEFAULT_INTERVAL_MILLIS)
                    .coerceAtLeast(1000L)
                val name = sanitize(intent.getStringExtra(EXTRA_FILENAME)) ?: defaultFileName()
                prefs().edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putBoolean(KEY_PAUSED, false)
                    .putLong(KEY_INTERVAL, interval)
                    .putString(KEY_NAME, name)
                    .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                    .putLong(KEY_PAUSED_TOTAL, 0L)
                    .remove(KEY_PAUSED_AT)
                    .apply()
                startRecording(name, interval, paused = false)
            }
            // Sticky restart after the OS killed the process: resume the last recording.
            intent == null && prefs().getBoolean(KEY_ACTIVE, false) -> {
                val p = prefs()
                startRecording(
                    p.getString(KEY_NAME, null) ?: defaultFileName(),
                    p.getLong(KEY_INTERVAL, DEFAULT_INTERVAL_MILLIS),
                    paused = p.getBoolean(KEY_PAUSED, false),
                )
            }
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun startRecording(name: String, intervalMillis: Long, paused: Boolean) {
        if (writer != null) return

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            prefs().edit().clear().apply()
            stopSelf()
            return
        }

        writer = try {
            GpxWriter(File(tracksDir(), "$name.gpx"), name).also { scanTrack(it.file) }
        } catch (_: Exception) {
            prefs().edit().clear().apply()
            stopSelf()
            return
        }

        this.intervalMillis = intervalMillis
        // The clock is read back from preferences rather than started here, so
        // a sticky restart resumes the run's own elapsed time instead of
        // claiming the recording began the moment the process came back.
        val p = prefs()
        state.value = RecordingState(
            fileName = "$name.gpx",
            startedAtMillis = p.getLong(KEY_STARTED_AT, System.currentTimeMillis()),
            pointCount = 0,
            paused = paused,
            pausedAtMillis = p.getLong(KEY_PAUSED_AT, 0L).takeIf { it > 0L },
            pausedTotalMillis = p.getLong(KEY_PAUSED_TOTAL, 0L),
        )

        startForeground(
            NOTIFICATION_ID,
            buildNotification(name, 0, paused),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        if (!paused) {
            requestLocationUpdates()
            acquireWakeLock()
        }
    }

    private fun pauseRecording() {
        val s = state.value ?: return
        if (writer == null || s.paused) return
        stopLocationUpdates()
        releaseWakeLock()
        val pausedAt = System.currentTimeMillis()
        prefs().edit().putBoolean(KEY_PAUSED, true).putLong(KEY_PAUSED_AT, pausedAt).apply()
        state.value = s.copy(paused = true, pausedAtMillis = pausedAt)
        updateNotification()
    }

    private fun resumeRecording() {
        val s = state.value ?: return
        val w = writer ?: return
        if (!s.paused) return
        try {
            w.startNewSegment()
        } catch (_: Exception) {
            // A failed segment break is non-fatal; keep appending to the old one.
        }
        requestLocationUpdates()
        acquireWakeLock()
        val pausedTotal = s.pausedTotalMillis +
            (s.pausedAtMillis?.let { System.currentTimeMillis() - it } ?: 0L)
        prefs().edit()
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_PAUSED_TOTAL, pausedTotal)
            .remove(KEY_PAUSED_AT)
            .apply()
        state.value = s.copy(paused = false, pausedAtMillis = null, pausedTotalMillis = pausedTotal)
        updateNotification()
    }

    private fun requestLocationUpdates() {
        if (requestingUpdates) return
        val manager = getSystemService(LocationManager::class.java)
        val request = LocationRequest.Builder(intervalMillis)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()

        // GNSS leads: it is the only source that keeps delivering away from wifi
        // and cell coverage. Fused led here once and dropped ~4 fixes in 5 in
        // the mountains, answering the interval from network positioning alone.
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, request, mainExecutor, gpsListener,
        )

        if (manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            manager.requestLocationUpdates(
                LocationManager.FUSED_PROVIDER, request, mainExecutor, fusedListener,
            )
        }

        lastGpsDelivery = 0L
        lastFusedDelivery = 0L
        requestingUpdates = true
    }

    private fun stopLocationUpdates() {
        if (!requestingUpdates) return
        val manager = getSystemService(LocationManager::class.java)
        manager.removeUpdates(gpsListener)
        manager.removeUpdates(fusedListener)
        lastGpsFixRealtime = 0L
        lastGpsDelivery = 0L
        lastFusedDelivery = 0L
        requestingUpdates = false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geoimport:track-recording")
            .apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * A provider is re-acquiring when it has not been heard from in a while:
     * either it has said nothing since updates were requested, or it dropped
     * out and is only now coming back. Its next fix deserves the doubt that a
     * steady stream of them does not.
     */
    private fun isReacquiring(fromGps: Boolean): Boolean {
        val last = if (fromGps) lastGpsDelivery else lastFusedDelivery
        if (last == 0L) return true
        return SystemClock.elapsedRealtime() - last > maxOf(
            intervalMillis * 3, MIN_REACQUIRE_SILENCE_MILLIS,
        )
    }

    /**
     * Rejection is forever - the fix is gone from the file and no later pass
     * can second-guess it - so this bar only catches what is absurd on its
     * face, and everything merely imprecise is written with its own accuracy
     * beside it for a reader to weigh.
     *
     * The one judgement that cannot be deferred is staleness: a provider
     * answering with its last known position looks perfectly good in the file
     * and would silently mis-geotag photos, and the age that gives it away
     * exists only here, on the live [Location]. It is checked when a provider
     * is re-acquiring, which is when a cached position gets handed over;
     * applied to every fix it would instead discard good coalesced deliveries.
     */
    private fun isUsable(location: Location, fromGps: Boolean): Boolean {
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_METERS) return false
        if (isReacquiring(fromGps) &&
            location.elapsedRealtimeAgeMillis > maxOf(intervalMillis, MIN_MAX_AGE_MILLIS)
        ) return false
        return true
    }

    /** Fused is redundant while GNSS keeps up: writing both jitters the track. */
    private fun supersededByGps(fromGps: Boolean): Boolean {
        if (fromGps) return false
        val grace = maxOf(intervalMillis + intervalMillis / 2, MIN_FUSED_FALLBACK_MILLIS)
        return SystemClock.elapsedRealtime() - lastGpsFixRealtime < grace
    }

    private fun onFix(location: Location, fromGps: Boolean) {
        val w = writer ?: return
        // Judged before the delivery is stamped, or the fix would be measured
        // against its own arrival and no provider would ever be re-acquiring.
        val usable = isUsable(location, fromGps)
        val now = SystemClock.elapsedRealtime()
        if (fromGps) lastGpsDelivery = now else lastFusedDelivery = now

        if (supersededByGps(fromGps)) return
        if (!usable) {
            val s = state.value ?: return
            state.value = s.copy(droppedCount = s.droppedCount + 1)
            updateNotification()
            return
        }
        try {
            w.addPoint(
                location.toTrackPoint(
                    if (fromGps) TrackPoint.SOURCE_GPS else TrackPoint.SOURCE_FUSED
                )
            )
        } catch (_: Exception) {
            return
        }
        if (fromGps) lastGpsFixRealtime = now
        val s = state.value ?: return
        state.value = s.copy(pointCount = s.pointCount + 1)
        updateNotification()
    }

    private fun updateNotification() {
        val s = state.value ?: return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(s.fileName.removeSuffix(".gpx"), s.pointCount, s.paused, s.droppedCount),
        )
    }

    /**
     * Tells the media database the file is there, once when it is created and
     * again when it is finished. Without the first call a track stays invisible
     * to file managers and to a PC over USB for the whole run - and invisible
     * for good if the process is killed before it stops.
     */
    private fun scanTrack(file: File) {
        MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf(GPX_MIME), null)
    }

    private fun stopRecording() {
        prefs().edit().clear().apply()
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        stopLocationUpdates()
        releaseWakeLock()
        writer?.let {
            it.close()
            scanTrack(it.file)
        }
        writer = null
        state.value = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun buildNotification(
        name: String,
        points: Int,
        paused: Boolean,
        dropped: Int = 0,
    ): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TrackRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleIntent = PendingIntent.getService(
            this, 3,
            Intent(this, TrackRecorderService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (paused) "GPS track paused" else "Recording GPS track")
            .setContentText(
                // Readable in the field, where logcat is not.
                if (dropped > 0) "$name.gpx — $points points, $dropped dropped"
                else "$name.gpx — $points points"
            )
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    null, if (paused) "Resume" else "Pause", toggleIntent,
                ).build()
            )
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun sanitize(name: String?): String? = name
        ?.trim()
        ?.removeSuffix(".gpx")
        ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        ?.ifEmpty { null }

    companion object {
        const val ACTION_START = "com.pbungert.geoimport.action.START_RECORDING"
        const val ACTION_STOP = "com.pbungert.geoimport.action.STOP_RECORDING"
        const val ACTION_PAUSE = "com.pbungert.geoimport.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.pbungert.geoimport.action.RESUME_RECORDING"
        const val EXTRA_INTERVAL_MILLIS = "interval_millis"
        const val EXTRA_FILENAME = "filename"
        const val DEFAULT_INTERVAL_MINUTES = 1.0
        const val DEFAULT_INTERVAL_MILLIS = (DEFAULT_INTERVAL_MINUTES * 60_000).toLong()

        /**
         * A safety bar, not a quality bar: above this a fix is not a degraded
         * position but a different kind of answer altogether - a cell-tower
         * estimate rather than a fix. Anything under it is written and left for
         * a later pass to weigh, because that pass can see what came next and
         * this code cannot.
         */
        private const val MAX_ACCURACY_METERS = 200f

        /** Floor on how long GNSS must be silent before a fused fix is written. */
        private const val MIN_FUSED_FALLBACK_MILLIS = 90_000L

        /** Floor on the silence that makes a provider's next fix a re-acquisition. */
        private const val MIN_REACQUIRE_SILENCE_MILLIS = 90_000L

        /** Age cut-off for a re-acquired fix, when the interval is shorter. */
        private const val MIN_MAX_AGE_MILLIS = 60_000L

        private const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 42

        /** What a .gpx is: for the media database, and for a share. */
        const val GPX_MIME = "application/gpx+xml"
        private const val PREFS = "recorder"
        private const val KEY_ACTIVE = "active"
        private const val KEY_PAUSED = "paused"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_NAME = "name"

        // The elapsed clock, kept here so it survives a sticky restart.
        private const val KEY_STARTED_AT = "startedAt"
        private const val KEY_PAUSED_AT = "pausedAt"
        private const val KEY_PAUSED_TOTAL = "pausedTotal"

        /** Null while idle; observed by the UI to show recording progress. */
        val state = MutableStateFlow<RecordingState?>(null)

        fun tracksDir(): File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "GPS-Tracks",
        )

        /**
         * [tracksDir] as a document URI, for handing a file browser or a
         * picker a place to open at. Null when the folder does not sit under
         * external storage, which is the only shape this URI can describe.
         */
        fun tracksDirDocumentUri(): Uri? {
            val relative = tracksDir()
                .relativeToOrNull(Environment.getExternalStorageDirectory())
                ?.path?.replace('\\', '/')
                ?: return null
            return DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:$relative",
            )
        }

        /** Today's date plus the first free counter, e.g. 2026-07-17_01. */
        fun defaultFileName(dir: File = tracksDir()): String {
            val date = LocalDate.now().toString()
            var n = 1
            while (File(dir, String.format(Locale.US, "%s_%02d.gpx", date, n)).exists()) n++
            return String.format(Locale.US, "%s_%02d", date, n)
        }
    }
}

/**
 * Android fix to the portable track model. Altitude is the raw WGS-84
 * ellipsoidal height.
 *
 * Everything the receiver says about its own fix is carried across, because
 * this is the last point at which it exists: whatever is dropped here can
 * never be recovered from the file. Bearing is the one reading left behind -
 * a direction is only as good as the movement under it, and [speed] already
 * says whether there was any.
 */
private fun Location.toTrackPoint(source: String) = TrackPoint(
    time = Instant.ofEpochMilli(time),
    lat = latitude,
    lon = longitude,
    ele = if (hasAltitude()) altitude else null,
    accuracy = if (hasAccuracy()) accuracy.toDouble() else null,
    eleAccuracy = if (hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else null,
    speed = if (hasSpeed()) speed.toDouble() else null,
    source = source,
)
