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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var shell: LinearLayout
    private lateinit var stats: TextView
    private lateinit var routeView: RouteView
    private lateinit var action: Button
    private val prefs by lazy { getSharedPreferences("rides", MODE_PRIVATE) }
    private var page = 0
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
        window.statusBarColor = Color.rgb(246, 245, 240)
        window.navigationBarColor = Color.rgb(255, 255, 255)
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildUi()
        val filter = IntentFilter(TrackingService.ACTION_TICK)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(tickReceiver, filter, RECEIVER_NOT_EXPORTED) else {
            @Suppress("DEPRECATION")
            registerReceiver(tickReceiver, filter)
        }
        refreshStats()
    }

    private fun buildUi() {
        shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(CANVAS) }
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(26))
            setBackgroundColor(CANVAS)
        }
        scroll.addView(root)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(bottomNavigation(), LinearLayout.LayoutParams(-1, dp(66)))
        setContentView(shell)
        when (page) { 0 -> showHome(); 1 -> showHistory(); 2 -> showAnalytics(); else -> showSettings() }
    }

    private fun sectionTitle(title: String, subtitle: String? = null) {
        root.addView(text(title, 28, INK, true))
        if (subtitle != null) root.addView(text(subtitle, 13, MUTED, false).apply { setPadding(0, dp(4), 0, dp(14)) })
    }

    private fun bottomNavigation(): LinearLayout = LinearLayout(this).apply {
        setBackgroundColor(Color.WHITE); gravity = Gravity.CENTER
        val labels = listOf("Beranda", "Riwayat", "Analitik", "Pengaturan")
        val icons = listOf("⌂", "◷", "▥", "⚙")
        labels.forEachIndexed { index, label ->
            val selected = page == index
            val item = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { page = index; buildUi() } }
            item.addView(text(icons[index], 20, if (selected) ACCENT_DARK else MUTED, true).apply { gravity = Gravity.CENTER })
            item.addView(text(label, 10, if (selected) ACCENT_DARK else MUTED, selected).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
            addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun showHome() {
        sectionTitle("Selamat gowes", "RIDE TRACKER  ·  SIAP MENEMANI")
        root.addView(text("ROAD BIKE  /  DAILY RIDE", 10, MUTED, true).apply { setPadding(0, dp(4), 0, dp(7)) })
        root.addView(BikeView(this), LinearLayout.LayoutParams(-1, dp(205)))
        val current = prefs.getBoolean("recording", false)
        val card = column(Color.WHITE, 17).apply { background = rounded(Color.WHITE, 22) }
        card.addView(text(if (current) "PERJALANAN BERLANGSUNG" else "SIAP UNTUK BERANGKAT", 10, MUTED, true))
        stats = text("0,00", 38, INK, true).apply { setPadding(0, dp(6), 0, 0) }
        card.addView(LinearLayout(this).apply { gravity = Gravity.BOTTOM; addView(stats); addView(text(" km", 16, MUTED, false).apply { setPadding(0, 0, 0, dp(7)) }) })
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0); tag = "metrics" }
        metrics.addView(metric("DURASI", "00:00:00"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("RATA-RATA", "0,0 km/j"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("TANJAKAN", "0 m"), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(metrics); root.addView(card)
        root.addView(text("JEJAK GPS", 11, MUTED, true).apply { setPadding(0, dp(20), 0, dp(9)) })
        routeView = RouteView(this, animate = true).apply { background = rounded(Color.WHITE, 20) }
        root.addView(routeView, LinearLayout.LayoutParams(-1, dp(150)))
        action = Button(this).apply {
            textSize = 15f; setTextColor(INK); isAllCaps = false; typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded(ACCENT, 17); setOnClickListener { toggleRecording() }
        }
        root.addView(action, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        root.addView(text("Gowes terakhir", 19, INK, true).apply { setPadding(0, dp(24), 0, dp(8)) })
        val history = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        if (history.length() == 0) root.addView(text("Perjalanan tersimpan akan muncul di sini.", 13, MUTED, false))
        else {
            val ride = history.optJSONObject(history.length() - 1)!!
            val km = ride.optDouble("distance") / 1000
            val date = SimpleDateFormat("dd MMM · HH:mm", Locale("id", "ID")).format(Date(ride.optLong("date")))
            val last = column(Color.WHITE, 14).apply { background = rounded(Color.WHITE, 18); setOnClickListener { showRideDetail(ride, date) } }
            last.addView(text(date, 11, MUTED, false))
            last.addView(text(String.format(Locale("id", "ID"), "%.2f km    ·    %d menit", km, ride.optLong("elapsed") / 60000), 15, INK, true).apply { setPadding(0, dp(5), 0, 0) })
            root.addView(last)
        }
    }

    private fun metric(label: String, value: String): LinearLayout = column(Color.TRANSPARENT, 0).apply {
        addView(text(label, 9, MUTED, true))
        addView(text(value, 13, INK, true).apply { tag = label })
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
            page = 1
            buildUi()
            refreshStats()
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
        stats.text = String.format(Locale("id", "ID"), "%.2f", distance / 1000)
        val metrics = root.findViewWithTag<LinearLayout>("metrics")
        metrics?.findViewWithTag<TextView>("DURASI")?.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        metrics?.findViewWithTag<TextView>("RATA-RATA")?.text = String.format(Locale("id", "ID"), "%.1f km/j", speed)
        metrics?.findViewWithTag<TextView>("TANJAKAN")?.text = String.format(Locale("id", "ID"), "%.0f m", ascent)
        routeView.setPoints(prefs.getString("active_points", "[]") ?: "[]", prefs.getBoolean("recording", false))
        action.text = if (prefs.getBoolean("recording", false)) "Selesaikan Gowes" else "Mulai Gowes"
    }

    private fun showHistory() {
        if (!::root.isInitialized) return
        sectionTitle("Riwayat", "Semua perjalanan tersimpan di perangkat")
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
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); background = rounded(Color.WHITE, 18) }
            item.addView(text(date, 12, MUTED, false))
            item.addView(text(String.format(Locale("id", "ID"), "%.2f km     %d menit     %.0f m tanjakan", km, elapsedMin, ride.optDouble("ascent")), 14, INK, true).apply { setPadding(0, dp(5), 0, 0) })
            val preview = RouteView(this)
            preview.setPoints(ride.optJSONArray("points")?.toString() ?: "[]")
            item.addView(preview, LinearLayout.LayoutParams(-1, dp(105)).apply { topMargin = dp(10) })
            item.setOnClickListener { showRideDetail(ride, date) }
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        root.addView(list)
    }

    private fun showAnalytics() {
        sectionTitle("Analitik", "Ringkasan aktivitas bersepeda")
        val history = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        val km = (0 until history.length()).sumOf { history.optJSONObject(it)?.optDouble("distance") ?: 0.0 } / 1000
        val ascent = (0 until history.length()).sumOf { history.optJSONObject(it)?.optDouble("ascent") ?: 0.0 }
        val elapsed = (0 until history.length()).sumOf { history.optJSONObject(it)?.optLong("elapsed") ?: 0L }
        val summary = column(Color.WHITE, 16).apply { background = rounded(Color.WHITE, 20) }
        summary.addView(text("TOTAL JARAK", 10, MUTED, true))
        summary.addView(text(String.format(Locale("id", "ID"), "%.1f km", km), 32, INK, true).apply { setPadding(0, dp(5), 0, dp(12)) })
        summary.addView(text("${history.length()} perjalanan   ·   ${ascent.toInt()} m tanjakan   ·   ${elapsed / 3600000} jam ${elapsed / 60000 % 60} mnt", 12, MUTED, false))
        root.addView(summary)
        root.addView(text("JARAK PER PERJALANAN", 11, MUTED, true).apply { setPadding(0, dp(22), 0, dp(9)) })
        val chart = column(Color.WHITE, 13).apply { background = rounded(Color.WHITE, 20) }
        chart.addView(SummaryChartView(this, history), LinearLayout.LayoutParams(-1, dp(190)))
        root.addView(chart)
        root.addView(text("Grafik menampilkan hingga 12 perjalanan terakhir. Data dihitung dari rekaman GPS yang tersimpan lokal.", 12, MUTED, false).apply { setPadding(0, dp(10), 0, 0) })
    }

    private fun showSettings() {
        sectionTitle("Pengaturan", "Preferensi dan informasi aplikasi")
        val box = column(Color.WHITE, 17).apply { background = rounded(Color.WHITE, 20) }
        box.addView(text("PROFIL SEPEDA", 10, MUTED, true))
        box.addView(text("Road bike", 16, INK, true).apply { setPadding(0, dp(5), 0, dp(14)) })
        box.addView(text("UNIT JARAK", 10, MUTED, true))
        box.addView(text("Kilometer (km)", 15, INK, true).apply { setPadding(0, dp(5), 0, dp(14)) })
        box.addView(text("PENYIMPANAN", 10, MUTED, true))
        box.addView(text("Lokal di perangkat · berfungsi tanpa internet", 14, INK, true).apply { setPadding(0, dp(5), 0, dp(14)) })
        box.addView(text("KOMPATIBILITAS", 10, MUTED, true))
        box.addView(text("Android 8.0 atau lebih baru", 14, INK, true).apply { setPadding(0, dp(5), 0, 0) })
        root.addView(box)
        root.addView(text("Gowes Offline", 12, MUTED, false).apply { gravity = Gravity.CENTER; setPadding(0, dp(22), 0, 0) })
    }

    private fun showRideDetail(ride: JSONObject, date: String) {
        root.findViewWithTag<View>("ride-detail")?.let { root.removeView(it) }
        val points = ride.optJSONArray("points")?.toString() ?: "[]"
        val distance = ride.optDouble("distance")
        val elapsed = ride.optLong("elapsed")
        val ascent = ride.optDouble("ascent")
        val avg = if (elapsed > 0) distance / (elapsed / 3_600_000.0) / 1000 else 0.0
        val detail = column(Color.WHITE, 16).apply { tag = "ride-detail"; background = rounded(Color.WHITE, 20) }
        detail.addView(text("DETAIL PERJALANAN", 16, INK, true))
        detail.addView(text(date, 12, MUTED, false).apply { setPadding(0, dp(5), 0, dp(12)) })
        val map = RouteView(this).apply { setPoints(points) }
        detail.addView(map, LinearLayout.LayoutParams(-1, dp(210)))
        detail.addView(text(String.format(Locale("id", "ID"), "%.2f km    %.1f km/j    %.0f m tanjakan", distance / 1000, avg, ascent), 15, INK, true).apply { setPadding(0, dp(13), 0, dp(3)) })
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
        detail.addView(text(insight, 13, INK, false))
        root.addView(detail)
        detail.post { detail.parent?.let { (it as? ScrollView)?.smoothScrollTo(0, detail.bottom) } }
    }

    private fun showSummaryChart() {
        root.findViewWithTag<View>("summary-chart")?.let { root.removeView(it) }
        val history = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        val box = column(Color.WHITE, 14).apply { tag = "summary-chart"; background = rounded(Color.WHITE, 20) }
        box.addView(text("REKAP JARAK PER PERJALANAN", 12, MUTED, true))
        box.addView(SummaryChartView(this, history), LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(10) })
        val totalKm = (0 until history.length()).sumOf { history.optJSONObject(it)?.optDouble("distance") ?: 0.0 } / 1000
        val totalAscent = (0 until history.length()).sumOf { history.optJSONObject(it)?.optDouble("ascent") ?: 0.0 }
        box.addView(text(String.format(Locale("id", "ID"), "%d perjalanan  •  %.1f km total  •  %.0f m total tanjakan", history.length(), totalKm, totalAscent), 13, INK, true).apply { setPadding(0, dp(10), 0, 0) })
        box.addView(text("Bandingkan jarak dari perjalanan ke perjalanan. Untuk menilai perubahan performa, bandingkan kecepatan rata-rata pada rute, durasi, dan kondisi yang serupa; GPS dan elevasi memiliki ketidakpastian pengukuran.", 12, MUTED, false).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(box)
        box.post { (root.parent as? ScrollView)?.smoothScrollTo(0, box.bottom) }
    }

    override fun onResume() { super.onResume(); handler.post(ticker); refreshStats() }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { try { unregisterReceiver(tickReceiver) } catch (_: Exception) { }; super.onDestroy() }

    private class RouteView(context: Context, private val animate: Boolean = false) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_DARK; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE7E5DF.toInt(); strokeWidth = 1f }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; style = Paint.Style.FILL }
        private val endDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8A72.toInt(); style = Paint.Style.FILL }
        private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; style = Paint.Style.STROKE; strokeWidth = 2.5f }
        private val pulseEnd = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD88A52.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f }
        private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        private var recording = false
        private var pulsePhase = 0f
        private var pulseAnimator: ValueAnimator? = null
        private var coordinates = emptyList<Pair<Double, Double>>()
        init { setBackgroundColor(Color.TRANSPARENT) }
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
        companion object { private const val GREEN = ACCENT_DARK }
    }

    private class ProfileView(context: Context, value: String) : View(context) {
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 11f }
        private val graph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_DARK; style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3374A943; style = Paint.Style.FILL }
        private val altitudes = try { val a = JSONArray(value); (0 until a.length()).map { a.optJSONObject(it)?.optDouble("alt", Double.NaN) ?: Double.NaN }.filter { it.isFinite() } } catch (_: Exception) { emptyList() }
        init { setBackgroundColor(Color.TRANSPARENT) }
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
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_DARK; style = Paint.Style.FILL }
        init { setBackgroundColor(Color.TRANSPARENT) }
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

    private inner class BikeView(context: Context) : View(context) {
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_DARK; style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val wheel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555956.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_DARK; style = Paint.Style.FILL }
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE2E0D9.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(24).toFloat(), dp(24).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE7E5DE.toInt() })
            val cx = width / 2f; val top = dp(17).toFloat(); val bottom = height - dp(17).toFloat()
            // Front view road bike: narrow wheel, fork, stem and drop bars.
            canvas.drawRoundRect(cx - dp(9), top, cx + dp(9), bottom, dp(8).toFloat(), dp(8).toFloat(), wheel)
            canvas.drawLine(cx, top + dp(10), cx, bottom - dp(18), frame)
            canvas.drawLine(cx, top + dp(40), cx - dp(7), bottom - dp(18), shine)
            canvas.drawLine(cx, top + dp(40), cx + dp(7), bottom - dp(18), shine)
            val barY = top + dp(47); val endX = width * .31f
            canvas.drawLine(cx, barY, cx - dp(28), barY - dp(9), dark)
            canvas.drawLine(cx - dp(28), barY - dp(9), endX, barY + dp(3), dark)
            canvas.drawLine(endX, barY + dp(3), endX + dp(11), barY + dp(3), dark)
            canvas.drawLine(cx, barY, cx + dp(28), barY - dp(9), dark)
            canvas.drawLine(cx + dp(28), barY - dp(9), width - endX, barY + dp(3), dark)
            canvas.drawLine(width - endX, barY + dp(3), width - endX - dp(11), barY + dp(3), dark)
            canvas.drawRoundRect(cx - dp(11), barY - dp(5), cx + dp(11), barY + dp(14), dp(8).toFloat(), dp(8).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF343936.toInt() })
            canvas.drawCircle(cx, barY + dp(23), dp(8).toFloat(), hub)
            canvas.drawCircle(cx, barY + dp(23), dp(3).toFloat(), dark)
            canvas.drawLine(width * .23f, height - dp(13).toFloat(), width * .77f, height - dp(13).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCECCC5.toInt(); strokeWidth = dp(1).toFloat() })
        }
    }

    companion object {
        private const val REQUEST_LOCATION = 72
        private const val REQUEST_NOTIFICATIONS = 73
        private const val GREEN = 0xFFB7F36B.toInt()
        private const val ACCENT = 0xFFFFB83F.toInt()
        private const val ACCENT_DARK = 0xFFE09A18.toInt()
        private const val CANVAS = 0xFFF6F5F0.toInt()
        private const val INK = 0xFF202321.toInt()
        private const val MUTED = 0xFF828782.toInt()
    }
}
