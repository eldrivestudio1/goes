package id.gowes.offline

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var stats: TextView
    private lateinit var routeView: RouteView
    private lateinit var action: Button
    private val prefs by lazy { getSharedPreferences("rides", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshStats()
    }
    private val ticker = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 15, 17)
        window.navigationBarColor = Color.rgb(12, 15, 17)
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        buildUi()
        val filter = IntentFilter(TrackingService.ACTION_TICK)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(tickReceiver, filter, RECEIVER_NOT_EXPORTED) else {
            @Suppress("DEPRECATION")
            registerReceiver(tickReceiver, filter)
        }
        refreshStats()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(30))
            setBackgroundColor(Color.rgb(12, 15, 17))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("GOWES", 29, Color.WHITE, true))
        root.addView(text("OFFLINE  •  GPS RIDE TRACKER", 11, GREEN, true).apply { setPadding(0, dp(3), 0, dp(22)) })

        val card = column(Color.rgb(27, 32, 34), 18)
        card.addView(text("PERJALANAN SAAT INI", 12, MUTED, true))
        stats = text("0,00\nKM", 38, Color.WHITE, true).apply { setPadding(0, dp(16), 0, dp(2)) }
        card.addView(stats)
        card.addView(text("Jarak tempuh", 12, MUTED, false))
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(20), 0, dp(4)) }
        metrics.addView(metric("DURASI", "00:00:00"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("RATA-RATA", "0,0 km/j"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("TANJAKAN", "0 m"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.tag = "metrics"
        card.addView(metrics)
        root.addView(card)

        root.addView(text("JEJAK PERJALANAN", 12, MUTED, true).apply { setPadding(0, dp(25), 0, dp(10)) })
        routeView = RouteView(this)
        root.addView(routeView, LinearLayout.LayoutParams(-1, dp(190)))
        action = Button(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(12, 15, 17))
            setBackgroundColor(GREEN)
            setOnClickListener { toggleRecording() }
        }
        root.addView(action, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(20) })

        root.addView(text("RIWAYAT GOWES", 17, Color.WHITE, true).apply { setPadding(0, dp(30), 0, dp(4)) })
        root.addView(text("Tersimpan aman di perangkat ini.", 12, MUTED, false).apply { setPadding(0, 0, 0, dp(10)) })
        showHistory()
    }

    private fun metric(label: String, value: String): LinearLayout = column(Color.TRANSPARENT, 0).apply {
        addView(text(label, 10, MUTED, true))
        addView(text(value, 15, Color.WHITE, true).apply { tag = label })
    }

    private fun column(color: Int, padding: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        setBackgroundColor(color)
    }

    private fun text(s: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(null, 1)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun toggleRecording() {
        if (prefs.getBoolean("recording", false)) {
            startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
            refreshStats()
            showHistory()
            return
        }
        if (!hasLocation()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Aktifkan Lokasi/GPS di pengaturan perangkat untuk mulai merekam", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        startTracking()
    }

    private fun startTracking() {
        val intent = Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "Rekaman GPS dimulai", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Tidak dapat memulai GPS. Periksa izin lokasi dan pengaturan perangkat.", Toast.LENGTH_LONG).show()
        }
        refreshStats()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) toggleRecording()
            else Toast.makeText(this, "Izin lokasi diperlukan untuk merekam gowes", Toast.LENGTH_LONG).show()
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            // Android 13+ allows location foreground services without notification permission; continue tracking.
            startTracking()
        }
    }

    private fun refreshStats() {
        if (!::stats.isInitialized) return
        val distance = prefs.getFloat("active_distance", 0f).toDouble()
        val elapsed = if (prefs.getBoolean("recording", false)) {
            val started = prefs.getLong("active_started_at", 0L)
            if (started > 0L) (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) else prefs.getLong("active_elapsed", 0L)
        } else prefs.getLong("active_elapsed", 0L)
        val ascent = prefs.getFloat("active_ascent", 0f).toDouble()
        val hours = elapsed / 3600000
        val minutes = elapsed / 60000 % 60
        val seconds = elapsed / 1000 % 60
        val speed = if (elapsed > 0) distance / (elapsed / 3600000.0) / 1000 else 0.0
        stats.text = String.format(Locale("id", "ID"), "%.2f\nKM", distance / 1000)
        val metrics = root.findViewWithTag<LinearLayout>("metrics")
        metrics?.findViewWithTag<TextView>("DURASI")?.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        metrics?.findViewWithTag<TextView>("RATA-RATA")?.text = String.format(Locale("id", "ID"), "%.1f km/j", speed)
        metrics?.findViewWithTag<TextView>("TANJAKAN")?.text = String.format(Locale("id", "ID"), "%.0f m", ascent)
        routeView.setPoints(prefs.getString("active_points", "[]") ?: "[]")
        action.text = if (prefs.getBoolean("recording", false)) "Selesaikan Gowes" else "Mulai Gowes"
    }

    private fun showHistory() {
        if (!::root.isInitialized) return
        val existing = root.findViewWithTag<View>("history-list")
        if (existing != null) root.removeView(existing)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "history-list" }
        val history = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        if (history.length() == 0) {
            list.addView(text("Belum ada perjalanan. Mulai gowes untuk merekam rute pertamamu.", 13, MUTED, false).apply { setPadding(0, dp(12), 0, dp(12)) })
        }
        for (i in history.length() - 1 downTo 0) {
            val ride = history.optJSONObject(i) ?: continue
            val date = SimpleDateFormat("dd MMM yyyy  •  HH:mm", Locale("id", "ID")).format(Date(ride.optLong("date")))
            val km = ride.optDouble("distance") / 1000.0
            val elapsedMin = ride.optLong("elapsed") / 60000
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); setBackgroundColor(Color.rgb(27, 32, 34)) }
            item.addView(text(date, 12, MUTED, false))
            item.addView(text(String.format(Locale("id", "ID"), "%.2f km     %d menit     %.0f m tanjakan", km, elapsedMin, ride.optDouble("ascent")), 14, Color.WHITE, true).apply { setPadding(0, dp(5), 0, 0) })
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        root.addView(list)
    }

    override fun onResume() { super.onResume(); handler.post(ticker); refreshStats() }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { try { unregisterReceiver(tickReceiver) } catch (_: Exception) { }; super.onDestroy() }

    private class RouteView(context: Context) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF293033.toInt(); strokeWidth = 1f }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; style = Paint.Style.FILL }
        private var coordinates = emptyList<Pair<Double, Double>>()
        init { setBackgroundColor(Color.rgb(20, 24, 26)) }
        fun setPoints(value: String) {
            coordinates = try {
                val array = JSONArray(value)
                (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { it.optDouble("lat") to it.optDouble("lon") } }
            } catch (_: Exception) { emptyList() }
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = 22f
            for (i in 1..3) {
                val y = height * i / 4f
                canvas.drawLine(inset, y, width - inset, y, grid)
                val x = width * i / 4f
                canvas.drawLine(x, inset, x, height - inset, grid)
            }
            if (coordinates.size < 2) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7D898C.toInt(); textSize = 13f; textAlign = Paint.Align.CENTER }
                canvas.drawText("Jejak GPS akan tampil di sini", width / 2f, height / 2f, p)
                return
            }
            val lats = coordinates.map { it.first }; val lons = coordinates.map { it.second }
            val minLat = lats.minOrNull()!!; val maxLat = lats.maxOrNull()!!
            val minLon = lons.minOrNull()!!; val maxLon = lons.maxOrNull()!!
            val latSpan = (maxLat - minLat).coerceAtLeast(0.00001); val lonSpan = (maxLon - minLon).coerceAtLeast(0.00001)
            val path = Path()
            coordinates.forEachIndexed { index, point ->
                val x = inset + ((point.second - minLon) / lonSpan * (width - inset * 2)).toFloat()
                val y = height - inset - ((point.first - minLat) / latSpan * (height - inset * 2)).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                if (index == coordinates.lastIndex) canvas.drawCircle(x, y, 6f, dot)
            }
            canvas.drawPath(path, line)
        }
        companion object { private const val GREEN = 0xFFB7F36B.toInt() }
    }

    companion object {
        private const val REQUEST_LOCATION = 72
        private const val REQUEST_NOTIFICATIONS = 73
        private const val GREEN = 0xFFB7F36B.toInt()
        private const val MUTED = 0xFF9DA8AA.toInt()
    }
}
