package id.gowes.offline

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var stats: TextView
    private lateinit var action: Button
    private val prefs by lazy { getSharedPreferences("rides", MODE_PRIVATE) }
    private val tickReceiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { refreshStats() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16,16,16); window.navigationBarColor = Color.rgb(16,16,16)
        buildUi()
        
        // registerReceiver(tickReceiver, IntentFilter("id.gowes.offline.TICK"), RECEIVER_NOT_EXPORTED)
    
        val filter = IntentFilter("id.gowes.offline.TICK")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tickReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(tickReceiver, filter)
        }

        refreshStats()
    }
    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 28); setBackgroundColor(Color.rgb(16,16,16)) }
        scroll.addView(root); setContentView(scroll)
        root.addView(text("GOWES OFFLINE", 28, Color.WHITE, true))
        root.addView(text("Catat perjalananmu. Data disimpan di perangkat.", 14, 0xFFB0B0B0.toInt(), false).apply { setPadding(0,8,0,24) })
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18,18,18,18); setBackgroundColor(0xFF222222.toInt()) }
        panel.addView(text("PERJALANAN SAAT INI", 13, 0xFFFF9800.toInt(), true))
        stats = text("Jarak  0,00 km\nDurasi  00:00:00\nKecepatan rata-rata  0,0 km/j", 18, Color.WHITE, false).apply { setPadding(0,16,0,16) }
        panel.addView(stats); root.addView(panel)
        action = Button(this).apply { textSize = 16f; setOnClickListener { toggleRecording() } }
        root.addView(action, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18; bottomMargin = 14 })
        root.addView(text("RIWAYAT GOWES", 18, Color.WHITE, true).apply { setPadding(0,18,0,10) })
        root.addView(text("Perjalanan tersimpan hanya di HP ini.", 13, 0xFFAAAAAA.toInt(), false))
        showHistory()
        root.addView(text("Catatan: peta offline, editor rute, GPX, dan kartu gambar akan ditambahkan pada tahap berikutnya.", 12, 0xFF999999.toInt(), false).apply { setPadding(0,28,0,0) })
    }
    private fun text(s: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply { text=s; textSize=size.toFloat(); setTextColor(color); if (bold) setTypeface(null, 1) }
    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun toggleRecording() {
        if (prefs.getBoolean("recording", false)) {
            startService(Intent(this, TrackingService::class.java).setAction("STOP")); refreshStats(); showHistory(); action.text = "Mulai Gowes"; return
        }
        if (!hasLocation()) { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 72); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 73)
        val i = Intent(this, TrackingService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        action.text = "Selesaikan Gowes"; Toast.makeText(this, "Rekaman GPS dimulai", Toast.LENGTH_SHORT).show(); refreshStats()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 72 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) toggleRecording()
        else if (requestCode == 72) Toast.makeText(this, "Izin lokasi diperlukan untuk merekam gowes", Toast.LENGTH_LONG).show()
    }
    private fun refreshStats() {
        val d = prefs.getFloat("active_distance", 0f).toDouble()
        val e = prefs.getLong("active_elapsed", 0L)
        val h = e / 3600000; val m = (e / 60000) % 60; val s = (e / 1000) % 60
        val speed = if (e > 0) d / (e / 3600000.0) / 1000 else 0.0
        stats.text = String.format(Locale("id","ID"), "Jarak  %.2f km\nDurasi  %02d:%02d:%02d\nKecepatan rata-rata  %.1f km/j", d/1000, h,m,s,speed)
        if (::action.isInitialized) action.text = if (prefs.getBoolean("recording", false)) "Selesaikan Gowes" else "Mulai Gowes"
    }
    private fun showHistory() {
        val history = JSONArray(prefs.getString("history", "[]"))
        for (i in history.length()-1 downTo 0) {
            val ride = history.optJSONObject(i) ?: continue
            val date = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id","ID")).format(Date(ride.optLong("date")))
            val km = ride.optDouble("distance") / 1000.0
            val sec = ride.optLong("elapsed") / 1000
            root.addView(text("${date}\n${String.format(Locale.US,"%.2f",km)} km  •  ${sec/60} menit", 15, Color.WHITE, false).apply { setPadding(0,12,0,12) })
        }
    }
    override fun onDestroy() { try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}; super.onDestroy() }
}
