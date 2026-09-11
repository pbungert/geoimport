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
)

/**
 * Foreground service (type location) that records GPS fixes into a GPX file.
 * Runs with a partial wake lock and START_STICKY so recording keeps going
 * with the screen off; after a process kill it restarts and resumes the same
 * file using the settings persisted in SharedPreferences.
 */
class TrackRecorderService : Service(), LocationListener {

    private var writer: GpxWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var requestingUpdates = false
    private var intervalMillis = DEFAULT_INTERVAL_MILLIS

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
            GpxWriter(File(tracksDir(), "$name.gpx"), name)
        } catch (_: Exception) {
            prefs().edit().clear().apply()
            stopSelf()
            return
        }

        this.intervalMillis = intervalMillis
        state.value = RecordingState("$name.gpx", System.currentTimeMillis(), 0, paused)

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
        prefs().edit().putBoolean(KEY_PAUSED, true).apply()
        state.value = s.copy(paused = true)
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
        prefs().edit().putBoolean(KEY_PAUSED, false).apply()
        state.value = s.copy(paused = false)
        updateNotification()
    }

    private fun requestLocationUpdates() {
        if (requestingUpdates) return
        val manager = getSystemService(LocationManager::class.java)
        // Fused mixes GPS with wifi- and cell-based positioning, so the track
        // keeps going indoors where raw GPS drops out. Its coarse cell-only
        // fixes are thrown away again by the accuracy filter in isUsable().
        val provider = if (manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }
        val request = LocationRequest.Builder(intervalMillis)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()
        manager.requestLocationUpdates(provider, request, mainExecutor, this)
        requestingUpdates = true
    }

    private fun stopLocationUpdates() {
        if (!requestingUpdates) return
        getSystemService(LocationManager::class.java).removeUpdates(this)
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
     * Fused fixes range from a few metres (GPS, or wifi in a well-mapped area)
     * to several kilometres (cell tower only). Only the accurate ones are worth
     * writing: a cell fix drags the track across town and back for one point.
     *
     * Stale fixes are dropped too. Fused likes to answer the first request with
     * its last known position, and that point's old timestamp would go on to
     * mis-geotag whichever photo happened to match it.
     */
    private fun isUsable(location: Location): Boolean =
        location.hasAccuracy() &&
            location.accuracy <= MAX_ACCURACY_METERS &&
            location.elapsedRealtimeAgeMillis <= maxOf(intervalMillis, MIN_MAX_AGE_MILLIS)

    override fun onLocationChanged(location: Location) {
        val w = writer ?: return
        if (!isUsable(location)) return
        try {
            w.addPoint(location.toTrackPoint())
        } catch (_: Exception) {
            return
        }
        val s = state.value ?: return
        state.value = s.copy(pointCount = s.pointCount + 1)
        updateNotification()
    }

    private fun updateNotification() {
        val s = state.value ?: return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(s.fileName.removeSuffix(".gpx"), s.pointCount, s.paused),
        )
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
            MediaScannerConnection.scanFile(this, arrayOf(it.file.path), null, null)
        }
        writer = null
        state.value = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun buildNotification(name: String, points: Int, paused: Boolean): Notification {
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
            .setContentText("$name.gpx — $points points")
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
         * Accuracy cut-off for a recorded point. Comfortably above a wifi fix
         * (~15-40 m) and far below a cell-tower one (500 m and up).
         */
        private const val MAX_ACCURACY_METERS = 50f

        /** Age cut-off, when the recording interval is shorter than this. */
        private const val MIN_MAX_AGE_MILLIS = 60_000L

        private const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 42
        private const val PREFS = "recorder"
        private const val KEY_ACTIVE = "active"
        private const val KEY_PAUSED = "paused"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_NAME = "name"

        /** Null while idle; observed by the UI to show recording progress. */
        val state = MutableStateFlow<RecordingState?>(null)

        fun tracksDir(): File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "GPS-Tracks",
        )

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
 * ellipsoidal height; accuracy, speed and bearing are used for filtering only
 * and are deliberately not persisted.
 */
private fun Location.toTrackPoint() = TrackPoint(
    time = Instant.ofEpochMilli(time),
    lat = latitude,
    lon = longitude,
    ele = if (hasAltitude()) altitude else null,
)
