package com.dilip.carobd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import javax.net.SocketFactory

/* ============================ Dashboard ============================ */

/* ============================ Live data + graph ============================ */

/* ============================ HUD ============================ */

@SuppressLint("SetTextI18n")
class HudPage(a: MainActivity) : Page(a) {
    override val parent = "Dash"
    private lateinit var inner: LinearLayout
    private lateinit var speed: TextView
    private lateinit var unit: TextView
    private lateinit var clock: TextView
    private lateinit var road: TextView
    private lateinit var limit: TextView
    private lateinit var advice: TextView
    private lateinit var led: LedBarView
    private lateinit var shift: TextView
    private val cells = LinkedHashMap<String, TextView>()
    private var mirrored = false
    private val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    private val cellKeys = listOf("RPM", "GEAR", "THROTTLE", "COOLANT", "FUEL", "BATTERY", "TRIP", "RANGE", "ECONOMY", "LOAD", "HEADING", "MODE")
    private val cellCol = mapOf("RPM" to "#FFB020", "GEAR" to "#B388FF", "THROTTLE" to "#4FC3F7", "COOLANT" to "#FF8A65", "FUEL" to "#B2FF59",
        "BATTERY" to "#FFEA00", "TRIP" to "#69F0AE", "RANGE" to "#18FFFF", "ECONOMY" to "#69F0AE", "LOAD" to "#FF80AB", "HEADING" to "#80D8FF", "MODE" to "#E0E0E0")

    private fun cell(key: String): View = ui.vbox().apply {
        gravity = Gravity.CENTER
        val v = ui.tv("--", 24f, Color.parseColor(cellCol[key] ?: "#FFFFFF"), true).apply { gravity = Gravity.CENTER; isSingleLine = true }
        cells[key] = v
        addView(v)
        addView(ui.tv(key, 10f, Color.GRAY).apply { gravity = Gravity.CENTER })
        setPadding(0, ui.dp(6), 0, ui.dp(6))
    }

    override fun build(): View {
        val root = FrameLayout(a).apply { setBackgroundColor(Color.BLACK) }
        inner = ui.vbox().apply { gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(14), ui.dp(10), ui.dp(8)) }
        clock = ui.tv("--:--", 20f, Color.LTGRAY, true)
        road = ui.tv("", 14f, Color.GRAY).apply { gravity = Gravity.CENTER; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        limit = ui.tv("", 22f, Color.BLACK, true).apply { gravity = Gravity.CENTER }
        inner.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(clock)
            addView(road, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = ui.dp(10); rightMargin = ui.dp(10) })
            addView(limit, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
        })
        led = LedBarView(a)
        inner.addView(led, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        speed = ui.tv("--", 130f, Color.parseColor("#4FC3F7"), true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        unit = ui.tv("km/h", 16f, Color.GRAY).apply { gravity = Gravity.CENTER }
        shift = ui.tv("", 18f, Color.parseColor("#B2FF59"), true).apply { gravity = Gravity.CENTER }
        // speed block + readings grid side by side in landscape, stacked in portrait (HUD must fit without scrolling)
        speedCol = ui.vbox().apply { gravity = Gravity.CENTER; addView(speed); addView(unit); addView(shift) }
        cellsCol = ui.vbox().apply { gravity = Gravity.CENTER }
        for (rowKeys in cellKeys.chunked(3)) {
            cellsCol.addView(LinearLayout(a).apply {
                for (k in rowKeys) addView(cell(k), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(2) })
        }
        body = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        body.addView(speedCol); body.addView(cellsCol)
        inner.addView(body, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        advice = ui.tv("", 15f, Color.parseColor("#FFD54F"), true).apply { gravity = Gravity.CENTER }
        inner.addView(advice, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        footer = ui.tv("tap: mirror for windscreen · long-press: exit\n${AppInfo.credit}", 11f, Color.DKGRAY).apply { gravity = Gravity.CENTER }
        inner.addView(footer, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(6) })
        arrange(false)
        root.addOnLayoutChangeListener { _, l, t, r, bt, _, _, _, _ -> val land = (r - l) > (bt - t); if (land != isLand) root.post { arrange(land) } }
        root.addView(ui.scroll(inner).apply { setBackgroundColor(Color.BLACK) }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.setOnClickListener { flip() }
        inner.setOnClickListener { flip() }
        inner.setOnLongClickListener { a.show("Dash"); true }
        root.setOnLongClickListener { a.show("Dash"); true }
        return root
    }

    private lateinit var speedCol: LinearLayout
    private lateinit var cellsCol: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var footer: TextView
    private var isLand = false

    private fun arrange(land: Boolean) {
        isLand = land
        body.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        speedCol.layoutParams = if (land) LinearLayout.LayoutParams(0, Ui.WRAP, 1f) else LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        cellsCol.layoutParams = if (land) LinearLayout.LayoutParams(0, Ui.WRAP, 1.25f).apply { leftMargin = ui.dp(8) } else LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        speed.textSize = if (land) 110f else 130f
        for (c in cells.values) c.textSize = if (land) 19f else 24f
        for (i in 0 until cellsCol.childCount) (cellsCol.getChildAt(i) as LinearLayout).let { r ->
            for (j in 0 until r.childCount) r.getChildAt(j).setPadding(0, ui.dp(if (land) 2 else 6), 0, ui.dp(if (land) 2 else 6))
        }
        footer.visibility = if (land) View.GONE else View.VISIBLE
        inner.setPadding(ui.dp(10), ui.dp(if (land) 6 else 14), ui.dp(10), ui.dp(if (land) 4 else 8))
        body.requestLayout()
    }

    private fun flip() { mirrored = !mirrored; inner.scaleY = if (mirrored) -1f else 1f }

    private fun compass(d: Float): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((d + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]

    override fun refresh() {
        val m = ObdManager; val v = m.values; val veh = m.vehicle
        SmartLive.tick()
        val spd = v[0x0D]
        val ri = RoadAssist.info
        val lim = ri?.limit
        val over = m.engine.overspeedNow || (spd != null && lim != null && spd > lim + Prefs.speedMargin)
        speed.text = spd?.let { "%.0f".format(Units.spd(it)) } ?: "--"
        unit.text = Units.spdLabel
        speed.setTextColor(if (over) Color.parseColor("#FF5252") else Color.parseColor("#4FC3F7"))
        clock.text = hm.format(java.util.Date())
        road.text = ri?.let { listOf(it.road, it.ref).filter { s -> s.isNotBlank() }.joinToString(" · ") } ?: ""
        if (lim != null) {
            limit.text = "$lim"; limit.visibility = View.VISIBLE
            limit.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(ui.dp(5), Color.parseColor("#D7263D")) }
        } else limit.visibility = View.INVISIBLE
        led.frac = ((v[0x0C] ?: 0.0) / veh.rpmLimit.toDouble().coerceAtLeast(1000.0)).toFloat().let { if (it < 0.45f) 0f else (it - 0.45f) / 0.55f }
        val t = m.engine.current
        val g = SmartLive.gear
        val cool = v[0x05]
        cells["RPM"]?.text = v[0x0C]?.let { "%.0f".format(it) } ?: "--"
        cells["GEAR"]?.text = if (g > 0) "D$g" else if (m.engineOn && (spd ?: 0.0) > 1) "D" else "N"
        cells["THROTTLE"]?.text = ui.f(v[0x11] ?: v[0x45], 0, "%")
        cells["COOLANT"]?.text = ui.temp(cool, 0); cells["COOLANT"]?.setTextColor(if (cool != null && cool >= Prefs.coolantMax) Color.parseColor("#FF5252") else Color.parseColor("#FF8A65"))
        cells["FUEL"]?.text = ui.f(v[0x2F], 0, "%")
        val bv = m.batteryV ?: v[0x42]
        cells["BATTERY"]?.text = ui.f(bv, 1, " V")
        cells["TRIP"]?.text = ui.dist(t?.distanceKm, 1)
        cells["RANGE"]?.text = ui.dist(m.rangeKm(), 0)
        cells["ECONOMY"]?.text = ui.econ(m.engine.instantKmpl)
        cells["LOAD"]?.text = ui.f(v[0x04], 0, "%")
        cells["HEADING"]?.text = RoadAssist.heading?.let { "${compass(it)} %.0f°".format(it) } ?: "--"
        val (mt, _) = SmartLive.mode()
        cells["MODE"]?.text = mt
        shift.text = when (SmartLive.shiftHint()) { 1 -> "▲ SHIFT UP"; -1 -> "▼ DOWNSHIFT"; else -> "" }
        advice.text = SmartLive.advice().firstOrNull { it.third >= 1 }?.second ?: ""
        advice.setTextColor(if (SmartLive.advice().any { it.third == 2 }) Color.parseColor("#FF5252") else Color.parseColor("#FFD54F"))
    }
}
