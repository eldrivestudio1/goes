package id.gowes.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

class TrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var previous: Location? = null
    private var distanceMeters = 0.0
    private var ascentMeters = 0.0
    private var startedAtElapsed = 0L
    private var points = JSONArray()
    private var stopped = false
    private var altitudeBaseline: Double? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "gowes_recording"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Rekaman gowes", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channelId) else Notification.Builder(this)
        startForeground(41, builder.setContentTitle("Gowes Offline").setContentText("Sedang merekam perjalanan GPS").setSmallIcon(android.R.drawable.ic_menu_mylocation).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("rides", MODE_PRIVATE)
        if (intent?.action == ACTION_STOP) {
            if (!prefs.getBoolean("recording", false)) {
                stopSelf()
                return START_NOT_STICKY
            }
            distanceMeters = prefs.getFloat("active_distance", 0f).toDouble()
            ascentMeters = prefs.getFloat("active_ascent", 0f).toDouble()
            points = try { JSONArray(prefs.getString("active_points", "[]")) } catch (_: Exception) { JSONArray() }
            startedAtElapsed = prefs.getLong("active_started_at", SystemClock.elapsedRealtime())
            finishRide()
            return START_NOT_STICKY
        }
        if (!prefs.getBoolean("recording", false)) {
            distanceMeters = 0.0
            ascentMeters = 0.0
            points = JSONArray()
            previous = null
            startedAtElapsed = SystemClock.elapsedRealtime()
            prefs.edit().putBoolean("recording", true)
                .putLong("active_started_at", startedAtElapsed)
                .putFloat("active_distance", 0f).putFloat("active_ascent", 0f)
                .putLong("active_elapsed", 0L).putString("active_points", "[]").apply()
        } else {
            // Keep a ride recoverable if Android recreates the foreground service.
            distanceMeters = prefs.getFloat("active_distance", 0f).toDouble()
            ascentMeters = prefs.getFloat("active_ascent", 0f).toDouble()
            points = try { JSONArray(prefs.getString("active_points", "[]")) } catch (_: Exception) { JSONArray() }
            startedAtElapsed = prefs.getLong("active_started_at", SystemClock.elapsedRealtime())
        }
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                prefs.edit().putBoolean("recording", false).apply()
                stopSelf()
                broadcastTick()
                return START_NOT_STICKY
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
        } catch (_: SecurityException) {
            getSharedPreferences("rides", MODE_PRIVATE).edit().putBoolean("recording", false).apply()
            stopSelf()
        } catch (_: IllegalArgumentException) {
            broadcastTick()
        }
        broadcastTick()
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasAccuracy() && location.accuracy > 20f) return
        val prev = previous
        if (prev != null) {
            val segment = location.distanceTo(prev)
            val elapsedSeconds = ((location.time - prev.time).coerceAtLeast(1L)) / 1000.0
            val speed = segment / elapsedSeconds
            // Reject implausible jumps; retain short segments so corners are represented.
            if (segment > 100f || speed > 15.0) return
            else {
                if (segment >= 1.2f) distanceMeters += segment
                if (location.hasAltitude() && location.hasVerticalAccuracy() && location.verticalAccuracyMeters <= 12f) {
                    val altitude = location.altitude
                    val baseline = altitudeBaseline
                    if (baseline == null) altitudeBaseline = altitude
                    else {
                        val delta = altitude - baseline
                        // Require sustained elevation change before counting ascent to suppress GPS altitude noise.
                        if (delta >= 3.0) {
                            ascentMeters += delta
                            altitudeBaseline = altitude
                        } else if (delta <= -3.0) altitudeBaseline = altitude
                    }
                }
                points.put(JSONObject().put("lat", location.latitude).put("lon", location.longitude)
                    .put("alt", if (location.hasAltitude()) location.altitude else 0.0)
                    .put("time", location.time).put("accuracy", location.accuracy))
            }
            previous = location
        } else {
            previous = location
        }
        if (points.length() == 0) points.put(JSONObject().put("lat", location.latitude).put("lon", location.longitude)
            .put("alt", if (location.hasAltitude()) location.altitude else 0.0)
            .put("time", location.time).put("accuracy", location.accuracy))
        saveActive()
        broadcastTick()
    }

    private fun saveActive() {
        val elapsed = (SystemClock.elapsedRealtime() - startedAtElapsed).coerceAtLeast(0L)
        getSharedPreferences("rides", MODE_PRIVATE).edit()
            .putString("active_points", points.toString()).putFloat("active_distance", distanceMeters.toFloat())
            .putFloat("active_ascent", ascentMeters.toFloat()).putLong("active_elapsed", elapsed).apply()
    }

    private fun finishRide() {
        if (stopped) return
        stopped = true
        saveActive()
        val prefs = getSharedPreferences("rides", MODE_PRIVATE)
        val ride = JSONObject().put("date", System.currentTimeMillis())
            .put("distance", distanceMeters).put("ascent", ascentMeters)
            .put("elapsed", (SystemClock.elapsedRealtime() - startedAtElapsed).coerceAtLeast(0L)).put("points", points)
        val history = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        history.put(ride)
        prefs.edit().putString("history", history.toString()).putBoolean("recording", false)
            .putLong("active_elapsed", 0L).putFloat("active_distance", 0f).putFloat("active_ascent", 0f)
            .remove("active_started_at").remove("active_points").apply()
        broadcastTick()
        stopSelf()
    }

    private fun broadcastTick() = sendBroadcast(Intent(ACTION_TICK).setPackage(packageName))

    override fun onDestroy() {
        if (::locationManager.isInitialized) try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        // Do not clear recording on system recreation; START_STICKY resumes it from saved state.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }

    companion object {
        const val ACTION_START = "id.gowes.offline.START"
        const val ACTION_STOP = "id.gowes.offline.STOP"
        const val ACTION_TICK = "id.gowes.offline.TICK"
    }
}
