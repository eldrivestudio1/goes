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
    private lateinit var lm: LocationManager
    private var last: Location? = null
    private var distanceM = 0.0
    private var startMs = 0L
    private var points = JSONArray()

    override fun onCreate() {
        super.onCreate()
        startMs = SystemClock.elapsedRealtime()
        val prefs = getSharedPreferences("rides", MODE_PRIVATE)
        prefs.edit().putBoolean("recording", true).apply()
        val channel = "gowes_recording"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(channel, "Rekaman gowes", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel).setContentTitle("Gowes Offline").setContentText("Sedang merekam perjalanan GPS…").setSmallIcon(android.R.drawable.ic_menu_mylocation).build() else Notification.Builder(this).setContentTitle("Gowes Offline").setContentText("Sedang merekam perjalanan GPS…").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
        startForeground(41, notification)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2500L, 5f, this) } catch (_: SecurityException) { stopSelf() }
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasAccuracy() && location.accuracy > 35f) return
        val prev = last
        if (prev != null && location.distanceTo(prev) < 150f) distanceM += location.distanceTo(prev).toDouble()
        last = location
        points.put(JSONObject().put("lat", location.latitude).put("lon", location.longitude).put("alt", if (location.hasAltitude()) location.altitude else 0.0).put("time", location.time).put("accuracy", location.accuracy))
        val prefs = getSharedPreferences("rides", MODE_PRIVATE)
        prefs.edit().putString("active_points", points.toString()).putFloat("active_distance", distanceM.toFloat()).putLong("active_elapsed", SystemClock.elapsedRealtime() - startMs).apply()
        sendBroadcast(Intent("id.gowes.offline.TICK").setPackage(packageName))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            val prefs = getSharedPreferences("rides", MODE_PRIVATE)
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val ride = JSONObject().put("date", System.currentTimeMillis()).put("distance", distanceM).put("elapsed", elapsed).put("points", points)
            val history = JSONArray(prefs.getString("history", "[]"))
            history.put(ride)
            prefs.edit().putString("history", history.toString()).putBoolean("recording", false).remove("active_points").apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onDestroy() {
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        getSharedPreferences("rides", MODE_PRIVATE).edit().putBoolean("recording", false).apply()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
