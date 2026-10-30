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
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var stats: TextView
    private lateinit var mapView: MapView
    private lateinit var action: Button
    private lateinit var planButton: Button
    private lateinit var planInfo: TextView
    private val plannedPoints = mutableListOf<GeoPoint>()
    private var planMode = false
    private var renderedPlannedRoute = ""
    private var renderedActiveRoute = ""
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
        window.statusBarColor = Color.rgb(10, 15, 17)
        window.navigationBarColor = Color.rgb(10, 15, 17)
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
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.rgb(10, 15, 17))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("gowes", 32, Color.WHITE, true))
        root.addView(text("RIDE TRACKER  ·  OFFLINE GPS", 11, GREEN, true).apply { setPadding(0, dp(3), 0, dp(22)) })

        root.addView(text("PETA RUTE", 12, MUTED, true).apply { setPadding(0, dp(16), 0, dp(8)) })
        planInfo = text("Peta memuat…", 13, MUTED, false).apply { setPadding(0, 0, 0, dp(8)) }
        root.addView(planInfo)
        val planActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        planButton = Button(this).apply {
            text = "＋  Buat rute"; textSize = 13f; isAllCaps = false; setTextColor(Color.WHITE)
            background = rounded(Color.rgb(35, 45, 47), 14)
            setOnClickListener { planMode = !planMode; updatePlannerStatus(); if (planMode) Toast.makeText(this@MainActivity, "Ketuk peta untuk menambahkan titik rute", Toast.LENGTH_LONG).show() }
        }
        planActions.addView(planButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        val saveRoute = Button(this).apply {
            text = "Simpan rute"; textSize = 13f; isAllCaps = false; setTextColor(Color.WHITE)
            background = rounded(Color.rgb(35, 45, 47), 14)
            setOnClickListener { planMode = false; persistPlannedRoute(); updatePlannerStatus(); Toast.makeText(this@MainActivity, "Rute tersimpan di perangkat", Toast.LENGTH_SHORT).show() }
        }
        planActions.addView(saveRoute, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        root.addView(planActions)
        val locate = Button(this).apply {
            text = "◎  Ke lokasi saya"; textSize = 12f; isAllCaps = false; setTextColor(MUTED)
            background = rounded(Color.TRANSPARENT, 12)
            setOnClickListener { centerOnMyLocation() }
        }
        root.addView(locate, LinearLayout.LayoutParams(-1, dp(38)))
        val clearRoute = Button(this).apply {
            text = "Hapus rute rencana"; textSize = 12f; isAllCaps = false; setTextColor(MUTED)
            background = rounded(Color.TRANSPARENT, 12)
            setOnClickListener { plannedPoints.clear(); persistPlannedRoute(); updatePlannerStatus(); refreshMapOverlays(force = true) }
        }
        root.addView(clearRoute, LinearLayout.LayoutParams(-1, dp(40)))
        setupMap()
        root.addView(mapView, LinearLayout.LayoutParams(-1, dp(360)))
        root.addView(text("© OpenStreetMap contributors", 10, MUTED, false).apply { gravity = Gravity.END; setPadding(0, dp(4), 0, 0) })
        updatePlannerStatus()
        val card = column(Color.rgb(22, 30, 31), 20).apply { background = rounded(Color.rgb(22, 30, 31), 22) }
        card.addView(text("PERJALANAN SAAT INI", 11, MUTED, true))
        stats = text("0,00 km", 38, Color.WHITE, true).apply { setPadding(0, dp(12), 0, dp(1)) }
        card.addView(stats)
        card.addView(text("Jarak tempuh", 12, MUTED, false))
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(20), 0, dp(4)) }
        metrics.addView(metric("DURASI", "00:00:00"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("RATA-RATA", "0,0 km/j"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("TANJAKAN", "0 m"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.tag = "metrics"
        card.addView(metrics)
        root.addView(card)
        action = Button(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(12, 15, 17))
            isAllCaps = false
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded(GREEN, 18)
            setOnClickListener { toggleRecording() }
        }
        root.addView(action, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(20) })

        root.addView(text("Riwayat gowes", 21, Color.WHITE, true).apply { setPadding(0, dp(30), 0, dp(4)) })
        root.addView(text("Tersimpan aman di perangkat ini.", 12, MUTED, false).apply { setPadding(0, 0, 0, dp(10)) })
        val chartButton = Button(this).apply { text = "Lihat grafik rekap"; isAllCaps = false; background = rounded(Color.rgb(35, 45, 47), 16); setTextColor(Color.WHITE); setOnClickListener { showSummaryChart() } }
        root.addView(chartButton, LinearLayout.LayoutParams(-1, dp(48)))
        showHistory()
    }

    private fun metric(label: String, value: String): LinearLayout = column(Color.TRANSPARENT, 0).apply {
        addView(text(label, 10, MUTED, true))
        addView(text(value, 15, Color.WHITE, true).apply { tag = label })
    }

    private fun setupMap() {
        val base = File(filesDir, "osmdroid")
        val tiles = File(base, "tiles")
        val userAgent = "GowesOffline/${BuildConfig.VERSION_NAME} (+https://github.com/eldrivestudio1/goes)"
        val mapPrefs = getSharedPreferences("map-config", MODE_PRIVATE)
        // Remove tiles that may have stored the old 403 policy error image.
        if (mapPrefs.getString("tile_user_agent", null) != userAgent) {
            tiles.deleteRecursively()
            mapPrefs.edit().putString("tile_user_agent", userAgent).apply()
        }
        base.mkdirs(); tiles.mkdirs()
        Configuration.getInstance().apply {
            load(this@MainActivity, mapPrefs)
            userAgentValue = userAgent
            osmdroidBasePath = base
            osmdroidTileCache = tiles
        }
        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.5)
            controller.setCenter(GeoPoint(-6.2088, 106.8456))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                    if (!planMode) return false
                    plannedPoints.add(point)
                    persistPlannedRoute()
                    updatePlannerStatus()
                    refreshMapOverlays(force = true)
                    return true
                }
                override fun longPressHelper(point: GeoPoint): Boolean = false
            }))
        }
        loadPlannedRoute()
        val lastRecordedPoint = try {
            val active = JSONArray(prefs.getString("active_points", "[]"))
            active.optJSONObject(active.length() - 1)?.let { GeoPoint(it.optDouble("lat"), it.optDouble("lon")) }
        } catch (_: Exception) { null }
        val rememberedCenter = plannedPoints.firstOrNull() ?: lastRecordedPoint
        if (rememberedCenter != null) mapView.controller.setCenter(rememberedCenter)
        refreshMapOverlays(force = true)
    }

    private fun loadPlannedRoute() {
        plannedPoints.clear()
        val saved = try { JSONArray(prefs.getString("planned_route", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until saved.length()) saved.optJSONObject(i)?.let { point -> plannedPoints.add(GeoPoint(point.optDouble("lat"), point.optDouble("lon"))) }
    }

    private fun persistPlannedRoute() {
        val json = JSONArray()
        plannedPoints.forEach { json.put(JSONObject().put("lat", it.latitude).put("lon", it.longitude)) }
        prefs.edit().putString("planned_route", json.toString()).apply()
    }

    private fun planDistanceMeters(): Double {
        var distance = 0.0
        for (i in 1 until plannedPoints.size) {
            val from = plannedPoints[i - 1]; val to = plannedPoints[i]
            val result = FloatArray(1)
            Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, result)
            distance += result[0]
        }
        return distance
    }

    private fun updatePlannerStatus() {
        if (!::planInfo.isInitialized) return
        val distance = planDistanceMeters()
        planInfo.text = when {
            planMode -> "Mode gambar aktif · ketuk peta untuk menambah titik (${plannedPoints.size} titik)"
            plannedPoints.size < 2 -> "Ketuk Buat rute, lalu pilih minimal 2 titik pada peta."
            else -> String.format(Locale("id", "ID"), "Rute tersimpan · %d titik · %.2f km", plannedPoints.size, distance / 1000.0)
        }
        planButton.text = if (planMode) "Selesai menambah titik" else "＋  Buat rute"
        if (::mapView.isInitialized) mapView.invalidate()
    }

    private fun centerOnMyLocation() {
        if (!hasLocation()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_MAP_LOCATION)
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null } }
            .maxByOrNull { it.time }
        if (last == null) Toast.makeText(this, "Lokasi belum tersedia. Aktifkan GPS dan coba lagi.", Toast.LENGTH_LONG).show()
        else { mapView.controller.animateTo(GeoPoint(last.latitude, last.longitude)); mapView.controller.setZoom(16.0) }
    }

    private fun refreshMapOverlays(force: Boolean = false) {
        if (!::mapView.isInitialized) return
        val plannedKey = plannedPoints.joinToString(";") { "${it.latitude},${it.longitude}" }
        val activeKey = prefs.getString("active_points", "[]") ?: "[]"
        if (!force && plannedKey == renderedPlannedRoute && activeKey == renderedActiveRoute) return
        renderedPlannedRoute = plannedKey
        renderedActiveRoute = activeKey
        mapView.overlays.removeAll { it is Polyline || it is Marker }
        if (plannedPoints.size >= 2) {
            mapView.overlays.add(Polyline(mapView).apply {
                setPoints(plannedPoints)
                setWidth(dp(8).toFloat())
                setColor(0xFF29C9B3.toInt())
            })
        }
        if (plannedPoints.isNotEmpty()) {
            mapView.overlays.add(Marker(mapView).apply { position = plannedPoints.first(); title = "Mulai rute"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
            if (plannedPoints.size > 1) mapView.overlays.add(Marker(mapView).apply { position = plannedPoints.last(); title = "Tujuan rute"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
        }
        val active = try { JSONArray(activeKey) } catch (_: Exception) { JSONArray() }
        val recorded = (0 until active.length()).mapNotNull { i -> active.optJSONObject(i)?.let { GeoPoint(it.optDouble("lat"), it.optDouble("lon")) } }
        if (recorded.size >= 2) mapView.overlays.add(Polyline(mapView).apply {
            setPoints(recorded); setWidth(dp(5).toFloat()); setColor(0xFFFF8B67.toInt())
        })
        mapView.invalidate()
    }

    private fun column(color: Int, padding: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        setBackgroundColor(color)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
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
        } else if (requestCode == REQUEST_MAP_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) centerOnMyLocation()
            else Toast.makeText(this, "Izin lokasi diperlukan untuk menampilkan posisi Anda", Toast.LENGTH_LONG).show()
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
        stats.text = String.format(Locale("id", "ID"), "%.2f km", distance / 1000)
        val metrics = root.findViewWithTag<LinearLayout>("metrics")
        metrics?.findViewWithTag<TextView>("DURASI")?.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        metrics?.findViewWithTag<TextView>("RATA-RATA")?.text = String.format(Locale("id", "ID"), "%.1f km/j", speed)
        metrics?.findViewWithTag<TextView>("TANJAKAN")?.text = String.format(Locale("id", "ID"), "%.0f m", ascent)
        refreshMapOverlays()
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
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); background = rounded(Color.rgb(22, 30, 31), 18) }
            item.addView(text(date, 12, MUTED, false))
            item.addView(text(String.format(Locale("id", "ID"), "%.2f km     %d menit     %.0f m tanjakan", km, elapsedMin, ride.optDouble("ascent")), 14, Color.WHITE, true).apply { setPadding(0, dp(5), 0, 0) })
            val preview = RouteView(this)
            preview.setPoints(ride.optJSONArray("points")?.toString() ?: "[]")
            item.addView(preview, LinearLayout.LayoutParams(-1, dp(105)).apply { topMargin = dp(10) })
            item.setOnClickListener { showRideDetail(ride, date) }
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        root.addView(list)
    }

    private fun showRideDetail(ride: JSONObject, date: String) {
        root.findViewWithTag<View>("ride-detail")?.let { root.removeView(it) }
        val points = ride.optJSONArray("points")?.toString() ?: "[]"
        val distance = ride.optDouble("distance")
        val elapsed = ride.optLong("elapsed")
        val ascent = ride.optDouble("ascent")
        val avg = if (elapsed > 0) distance / (elapsed / 3_600_000.0) / 1000 else 0.0
        val detail = column(Color.rgb(22, 30, 31), 16).apply { tag = "ride-detail"; background = rounded(Color.rgb(22, 30, 31), 20) }
        detail.addView(text("DETAIL PERJALANAN", 16, Color.WHITE, true))
        detail.addView(text(date, 12, MUTED, false).apply { setPadding(0, dp(5), 0, dp(12)) })
        val map = RouteView(this).apply { setPoints(points) }
        detail.addView(map, LinearLayout.LayoutParams(-1, dp(210)))
        detail.addView(text(String.format(Locale("id", "ID"), "%.2f km    %.1f km/j    %.0f m tanjakan", distance / 1000, avg, ascent), 15, Color.WHITE, true).apply { setPadding(0, dp(13), 0, dp(3)) })
        detail.addView(text("Durasi ${elapsed / 3_600_000}:" + String.format(Locale.US, "%02d:%02d", elapsed / 60_000 % 60, elapsed / 1000 % 60) + "  •  ${ride.optJSONArray("points")?.length() ?: 0} titik GPS", 12, MUTED, false))
        detail.addView(text("PROFIL KETINGGIAN", 11, MUTED, true).apply { setPadding(0, dp(18), 0, dp(6)) })
        detail.addView(ProfileView(this, points), LinearLayout.LayoutParams(-1, dp(150)))
        val km = distance / 1000.0
        val climbPerKm = if (km > 0) ascent / km else 0.0
        val insight = when {
            distance < 100 -> "Jarak GPS terlalu sedikit untuk menyimpulkan performa."
            else -> String.format(Locale("id", "ID"), "Rata-rata %.1f km/j • %.0f m tanjakan per km. Bandingkan kecepatan pada rute dan kondisi yang serupa untuk melihat perubahan performa.", avg, climbPerKm)
        }
        detail.addView(text("RINGKASAN DATA", 11, MUTED, true).apply { setPadding(0, dp(16), 0, dp(5)) })
        detail.addView(text(insight, 13, Color.WHITE, false))
        root.addView(detail)
        detail.post { detail.parent?.let { (it as? ScrollView)?.smoothScrollTo(0, detail.bottom) } }
    }

    private fun showSummaryChart() {
        root.findViewWithTag<View>("summary-chart")?.let { root.removeView(it) }
        val history = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        val box = column(Color.rgb(22, 30, 31), 14).apply { tag = "summary-chart"; background = rounded(Color.rgb(22, 30, 31), 20) }
        box.addView(text("REKAP JARAK PER PERJALANAN", 12, MUTED, true))
        box.addView(SummaryChartView(this, history), LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(10) })
        val totalKm = (0 until history.length()).sumOf { history.optJSONObject(it)?.optDouble("distance") ?: 0.0 } / 1000
        val totalAscent = (0 until history.length()).sumOf { history.optJSONObject(it)?.optDouble("ascent") ?: 0.0 }
        box.addView(text(String.format(Locale("id", "ID"), "%d perjalanan  •  %.1f km total  •  %.0f m total tanjakan", history.length(), totalKm, totalAscent), 13, Color.WHITE, true).apply { setPadding(0, dp(10), 0, 0) })
        box.addView(text("Bandingkan jarak dari perjalanan ke perjalanan. Untuk menilai perubahan performa, bandingkan kecepatan rata-rata pada rute, durasi, dan kondisi yang serupa; GPS dan elevasi memiliki ketidakpastian pengukuran.", 12, MUTED, false).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(box)
        box.post { (root.parent as? ScrollView)?.smoothScrollTo(0, box.bottom) }
    }

    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume(); handler.post(ticker); refreshStats() }
    override fun onPause() { handler.removeCallbacks(ticker); if (::mapView.isInitialized) mapView.onPause(); super.onPause() }
    override fun onDestroy() { try { unregisterReceiver(tickReceiver) } catch (_: Exception) { }; super.onDestroy() }

    private class RouteView(context: Context, private val animate: Boolean = false) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF293033.toInt(); strokeWidth = 1f }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; style = Paint.Style.FILL }
        private val endDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8A72.toInt(); style = Paint.Style.FILL }
        private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; style = Paint.Style.STROKE; strokeWidth = 2.5f }
        private val pulseEnd = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8A72.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f }
        private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        private var recording = false
        private var pulsePhase = 0f
        private var pulseAnimator: ValueAnimator? = null
        private var coordinates = emptyList<Pair<Double, Double>>()
        init { setBackgroundColor(Color.rgb(20, 24, 26)) }
        fun setPoints(value: String, isRecording: Boolean = false) {
            recording = isRecording
            coordinates = try {
                val array = JSONArray(value)
                (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { it.optDouble("lat") to it.optDouble("lon") } }
            } catch (_: Exception) { emptyList() }
            if (animate && recording && coordinates.isNotEmpty()) startPulse() else stopPulse()
            invalidate()
        }
        private fun startPulse() {
            if (pulseAnimator?.isStarted == true || !isAttachedToWindow) return
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1500L; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { pulsePhase = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        private fun stopPulse() { pulseAnimator?.cancel(); pulseAnimator = null; pulsePhase = 0f }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (animate && recording && coordinates.isNotEmpty()) startPulse() }
        override fun onDetachedFromWindow() { stopPulse(); super.onDetachedFromWindow() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = 22f
            for (i in 1..3) {
                val y = height * i / 4f
                canvas.drawLine(inset, y, width - inset, y, grid)
                val x = width * i / 4f
                canvas.drawLine(x, inset, x, height - inset, grid)
            }
            if (coordinates.isEmpty()) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7D898C.toInt(); textSize = 13f; textAlign = Paint.Align.CENTER }
                canvas.drawText("Jejak GPS akan tampil di sini", width / 2f, height / 2f, p)
                return
            }
            val meanLat = coordinates.map { it.first }.average()
            val projected = coordinates.map { (it.second * kotlin.math.cos(Math.toRadians(meanLat))) to it.first }
            val minX = projected.minOf { it.first }; val maxX = projected.maxOf { it.first }
            val minY = projected.minOf { it.second }; val maxY = projected.maxOf { it.second }
            val spanX = (maxX - minX).coerceAtLeast(0.00001); val spanY = (maxY - minY).coerceAtLeast(0.00001)
            val scale = minOf((width - inset * 2) / spanX, (height - inset * 2) / spanY)
            val centerX = (minX + maxX) / 2; val centerY = (minY + maxY) / 2
            val path = Path()
            projected.forEachIndexed { index, point ->
                val x = width / 2f + ((point.first - centerX) * scale).toFloat()
                val y = height / 2f - ((point.second - centerY) * scale).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val first = projected.first()
            canvas.drawPath(path, line)
            val startX = width / 2f + ((first.first - centerX) * scale).toFloat()
            val startY = height / 2f - ((first.second - centerY) * scale).toFloat()
            drawMarker(canvas, startX, startY, true)
            if (projected.size > 1) {
                val last = projected.last()
                val endX = width / 2f + ((last.first - centerX) * scale).toFloat()
                val endY = height / 2f - ((last.second - centerY) * scale).toFloat()
                drawMarker(canvas, endX, endY, false)
            }
        }
        private fun drawMarker(canvas: Canvas, x: Float, y: Float, isStart: Boolean) {
            val color = if (isStart) dot else endDot
            val halo = if (isStart) pulse else pulseEnd
            if (animate && recording) {
                val radius = 9f + pulsePhase * 17f
                halo.alpha = ((1f - pulsePhase) * 220).toInt()
                canvas.drawCircle(x, y, radius, halo)
                canvas.drawCircle(x, y, radius + 8f, halo)
            } else {
                halo.alpha = 125
                canvas.drawCircle(x, y, 10f, halo)
            }
            canvas.drawCircle(x, y, 5.5f, color)
            markerText.setShadowLayer(3f, 0f, 1f, Color.BLACK)
            canvas.drawText(if (isStart) "S" else if (recording) "LIVE" else "E", x + 9f, y - 8f, markerText)
            markerText.clearShadowLayer()
        }
        companion object { private const val GREEN = 0xFFB7F36B.toInt() }
    }

    private class ProfileView(context: Context, value: String) : View(context) {
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7D898C.toInt(); textSize = 11f }
        private val graph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3333CC66; style = Paint.Style.FILL }
        private val altitudes = try { val a = JSONArray(value); (0 until a.length()).map { a.optJSONObject(it)?.optDouble("alt", Double.NaN) ?: Double.NaN }.filter { it.isFinite() } } catch (_: Exception) { emptyList() }
        init { setBackgroundColor(Color.rgb(20, 24, 26)) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (altitudes.size < 2 || altitudes.maxOrNull()!! - altitudes.minOrNull()!! < 0.5) {
                axis.textAlign = Paint.Align.CENTER; canvas.drawText("Data ketinggian GPS belum cukup", width / 2f, height / 2f, axis); return
            }
            val min = altitudes.minOrNull()!!; val max = altitudes.maxOrNull()!!; val pad = 18f
            val path = Path(); val area = Path()
            altitudes.forEachIndexed { i, h ->
                val x = pad + i.toFloat() / (altitudes.size - 1) * (width - pad * 2)
                val y = height - pad - ((h - min) / (max - min) * (height - pad * 2)).toFloat()
                if (i == 0) { path.moveTo(x, y); area.moveTo(x, height - pad); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
            }
            area.lineTo(width - pad, height - pad); area.close(); canvas.drawPath(area, fill); canvas.drawPath(path, graph)
            axis.textAlign = Paint.Align.LEFT; canvas.drawText("${min.toInt()} m", 6f, height - 5f, axis)
            axis.textAlign = Paint.Align.RIGHT; canvas.drawText("${max.toInt()} m", width - 6f, 14f, axis)
        }
    }

    private class SummaryChartView(context: Context, private val rides: JSONArray) : View(context) {
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 11f; textAlign = Paint.Align.CENTER }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; style = Paint.Style.FILL }
        init { setBackgroundColor(Color.rgb(20, 24, 26)) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (rides.length() == 0) { canvas.drawText("Belum ada data perjalanan", width / 2f, height / 2f, label); return }
            val count = minOf( rides.length(), 12 )
            val start = rides.length() - count
            val max = (start until rides.length()).maxOf { rides.optJSONObject(it)?.optDouble("distance") ?: 0.0 }.coerceAtLeast(1.0)
            val slot = width.toFloat() / count
            for (j in 0 until count) {
                val ride = rides.optJSONObject(start + j) ?: continue
                val km = ride.optDouble("distance") / 1000
                val heightBar = ((ride.optDouble("distance") / max) * (height - 42)).toFloat()
                val left = j * slot + slot * 0.2f
                canvas.drawRoundRect(left, height - 24 - heightBar, left + slot * 0.6f, height - 24f, 7f, 7f, bar)
                canvas.drawText(String.format(Locale.US, "%.1f", km), left + slot * 0.3f, height - 8f, label)
            }
            label.textAlign = Paint.Align.LEFT
            canvas.drawText("km", 8f, 16f, label)
        }
    }

    companion object {
        private const val REQUEST_LOCATION = 72
        private const val REQUEST_NOTIFICATIONS = 73
        private const val REQUEST_MAP_LOCATION = 74
        private const val GREEN = 0xFFB7F36B.toInt()
        private const val MUTED = 0xFF9DA8AA.toInt()
    }
}
