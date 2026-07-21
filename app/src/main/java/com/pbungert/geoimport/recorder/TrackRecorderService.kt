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
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.time.LocalDate
import java.util.Locale

data class RecordingState(
    val fileName: String,
    val startedAtMillis: Long,
    val pointCount: Int,
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
            intent?.action == ACTION_START -> {
                val interval = intent.getLongExtra(EXTRA_INTERVAL_MILLIS, DEFAULT_INTERVAL_MILLIS)
                    .coerceAtLeast(1000L)
                val name = sanitize(intent.getStringExtra(EXTRA_FILENAME)) ?: defaultFileName()
                prefs().edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putLong(KEY_INTERVAL, interval)
                    .putString(KEY_NAME, name)
                    .apply()
                startRecording(name, interval)
            }
            // Sticky restart after the OS killed the process: resume the last recording.
            intent == null && prefs().getBoolean(KEY_ACTIVE, false) -> {
                val p = prefs()
                startRecording(
                    p.getString(KEY_NAME, null) ?: defaultFileName(),
                    p.getLong(KEY_INTERVAL, DEFAULT_INTERVAL_MILLIS),
                )
            }
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun startRecording(name: String, intervalMillis: Long) {
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

        startForeground(
            NOTIFICATION_ID,
            buildNotification(name, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        val request = LocationRequest.Builder(intervalMillis)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()
        getSystemService(LocationManager::class.java)
            .requestLocationUpdates(LocationManager.GPS_PROVIDER, request, mainExecutor, this)
        requestingUpdates = true

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geoimport:track-recording")
            .apply { acquire() }

        state.value = RecordingState("$name.gpx", System.currentTimeMillis(), 0)
    }

    override fun onLocationChanged(location: Location) {
        val w = writer ?: return
        try {
            w.addPoint(location)
        } catch (_: Exception) {
            return
        }
        val s = state.value ?: return
        val updated = s.copy(pointCount = s.pointCount + 1)
        state.value = updated
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(updated.fileName.removeSuffix(".gpx"), updated.pointCount),
        )
    }

    private fun stopRecording() {
        prefs().edit().clear().apply()
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        if (requestingUpdates) {
            getSystemService(LocationManager::class.java).removeUpdates(this)
            requestingUpdates = false
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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

    private fun buildNotification(name: String, points: Int): Notification {
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Recording GPS track")
            .setContentText("$name.gpx — $points points")
            .setOngoing(true)
            .setContentIntent(contentIntent)
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
        const val EXTRA_INTERVAL_MILLIS = "interval_millis"
        const val EXTRA_FILENAME = "filename"
        const val DEFAULT_INTERVAL_MINUTES = 1.0
        const val DEFAULT_INTERVAL_MILLIS = (DEFAULT_INTERVAL_MINUTES * 60_000).toLong()

        private const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 42
        private const val PREFS = "recorder"
        private const val KEY_ACTIVE = "active"
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
