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

class LivePage(a: MainActivity) : Page(a) {
    override val parent = "More"

    private class Entry(val key: String, val name: String, val unit: String, val cat: String, val pid: Int?)
    private class Cell(val card: LinearLayout, val value: TextView, val trend: TextView, val spark: SparkView)

    private lateinit var catRow: LinearLayout
    private lateinit var insightBox: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var head: TextView
    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var statsBox: LinearLayout
    private val cells = HashMap<String, Cell>()
    private val calcHist = HashMap<String, Ring>()
    private var entries: List<Entry> = emptyList()
    private var sig = ""
    private var cat = "All"
    private var pid1 = "p${0x0C}"
    private var pid2: String? = null
    private var lastInsight = 0L
    private val cats = listOf("All", "Engine", "Fuel", "Air", "Temp", "Electrical", "Emission", "Custom")

    override fun build(): View {
        val c = ui.col()
        head = ui.tv("", 12f, Ui.DIM)
        c.addView(head, ui.lp(4))
        catRow = LinearLayout(a)
        c.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(catRow) }, ui.lp(8))

        c.addView(ui.section("Analysis", Ic.PULSE))
        insightBox = ui.vbox()
        c.addView(insightBox)

        c.addView(ui.section("Graph", Ic.CHART))
        statsBox = ui.vbox()
        c.addView(statsBox)
        chart1 = LineChart(a, 150); chart2 = LineChart(a, 120)
        c.addView(chart1, ui.lp(8))
        c.addView(chart2, ui.lp(8))
        c.addView(ui.tv("Tap a card to graph it · long-press to compare with a second signal", 11f, Ui.DIM), ui.lp(4))

        c.addView(ui.section("Signals", Ic.GRID))
        grid = ui.vbox()
        c.addView(grid)
        c.addView(ui.btn("Save snapshot (CSV)", Ui.CARD) { snapshot() }, ui.lp(14))
        return ui.scroll(c)
    }

    override fun onShow() { sig = ""; paintCats() }

    private fun paintCats() {
        catRow.removeAllViews()
        for (cn in cats) {
            val on = cn == cat
            catRow.addView(ui.tv(cn, 13f, if (on) Ui.WHITE else Ui.DIM, on).apply {
                background = ui.rounded(if (on) Ui.ACC else Ui.CARD, 18)
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                setOnClickListener { cat = cn; sig = ""; paintCats() }
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
        }
    }

    private fun category(p: Int): String = when (p) {
        0x05, 0x5C, 0x3C, 0x3D, 0x3E, 0x3F -> "Temp"
        0x0F, 0x10, 0x0B, 0x33, 0x46, 0x68, 0x6B -> "Air"
        0x42, 0x43, 0x4D, 0x4E -> "Electrical"
        0x03, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x22, 0x23, 0x2F, 0x51, 0x52, 0x5E, 0x2D -> "Fuel"
        in 0x13..0x1B, in 0x24..0x2B, in 0x34..0x3B, 0x2C, 0x2E, 0x2D, 0x78, 0x79 -> "Emission"
        else -> "Engine"
    }

    private fun ring(key: String) = calcHist.getOrPut(key) { Ring(120) }

    private fun series(e: Entry): FloatArray {
        val m = ObdManager
        return if (e.pid != null) m.history[e.pid]?.values() ?: FloatArray(0) else ring(e.key).values()
    }

    private fun textOf(e: Entry): String {
        val m = ObdManager
        val u = m.vehicle.spec.unit
        if (e.pid != null) {
            val def = Pids.byId[e.pid]
            return m.texts[e.pid] ?: m.values[e.pid]?.let { val (cv, _) = Units.conv(e.unit, it); "%.${def?.decimals ?: 1}f".format(cv) } ?: "…"
        }
        return when (e.key) {
            "calc:fuel" -> m.fuelRate()?.let { "%.2f".format(Units.vol(it, "L")) } ?: "--"
            "calc:econ" -> m.engine.instantKmpl?.let { "%.1f".format(it) } ?: "--"
            "calc:acc" -> "%+.2f".format(m.engine.liveAccelG)
            "calc:batt" -> ui.f(m.batteryV, 2)
            else -> m.customValues[e.key.removePrefix("c:")] ?: "…"
        }
    }

    private fun rebuild() {
        val m = ObdManager
        val u = m.vehicle.spec.unit
        val pids = m.supported.filter { !Pids.isBitmap(it) }
        val list = mutableListOf<Entry>()
        list += Entry("calc:fuel", "Fuel rate", "$u/h", "Fuel", null)
        list += Entry("calc:econ", "Instant economy", "km/$u", "Fuel", null)
        list += Entry("calc:acc", "Acceleration", "g", "Engine", null)
        list += Entry("calc:batt", "Battery (pin 16)", "V", "Electrical", null)
        for (p in pids) { val d = Pids.byId[p]; list += Entry("p$p", d?.name ?: Pids.nameOf(p), d?.unit ?: "", category(p), p) }
        for (cp in Prefs.customPids) list += Entry("c:${cp.name}", cp.name, "", "Custom", null)
        entries = list.filter { cat == "All" || it.cat == cat }
        grid.removeAllViews(); cells.clear()
        entries.chunked(2).forEach { pair ->
            val views = pair.map { e -> makeCell(e) }.toMutableList<View>()
            if (views.size < 2) views.add(View(a))
            grid.addView(ui.grid(*views.toTypedArray()), ui.lp(8))
        }
    }

    private fun makeCell(e: Entry): View {
        val value = ui.tv("…", 21f, Ui.WHITE, true)
        val unit = ui.tv(Units.label(e.unit), 11.5f, Ui.DIM)
        val trend = ui.tv("", 14f, Ui.DIM, true)
        val spark = SparkView(a).apply { color = catColor(e.cat) }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(8))
            addView(ui.tv(e.name, 11.5f, Ui.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(LinearLayout(a).apply {
                gravity = Gravity.BOTTOM
                addView(value); addView(unit, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = ui.dp(4); bottomMargin = ui.dp(3) })
                addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)); addView(trend)
            }, ui.lp(2))
            addView(spark, ui.lp(6))
            if (e.pid != null || e.key.startsWith("calc")) {
                setOnClickListener { pid1 = e.key; if (pid2 == e.key) pid2 = null }
                setOnLongClickListener { pid2 = if (pid2 == e.key) null else e.key; true }
            }
        }
        cells[e.key] = Cell(card, value, trend, spark)
        return card
    }

    private fun catColor(c: String) = when (c) {
        "Fuel" -> Ui.ORANGE; "Air" -> Ui.CYAN; "Temp" -> Ui.BAD; "Electrical" -> Ui.WARN; "Emission" -> Ui.GOOD; "Custom" -> Ui.PINK; else -> Ui.ACC
    }

    private class Insight(val ic: Ic, val status: Int, val title: String, val body: String)

    private fun insights(): List<Insight> {
        val m = ObdManager; val v = m.values; val veh = m.vehicle
        val out = mutableListOf<Insight>()
        val st = v[0x06]; val lt = v[0x07]
        if (st != null || lt != null) {
            val tot = (st ?: 0.0) + (lt ?: 0.0)
            val s = if (Math.abs(tot) <= 10) 0 else if (Math.abs(tot) <= 20) 1 else 2
            out += Insight(Ic.SLIDERS, s, "Fuel trim %+.1f %%".format(tot),
                "Short %s · long %s".format(st?.let { "%+.1f".format(it) } ?: "--", lt?.let { "%+.1f".format(it) } ?: "--") +
                        if (s == 0) " · mixture OK" else if (tot > 0) " · running lean (air leak / low fuel pressure)" else " · running rich (injectors / sensor)")
        }
        m.idleRpmStdDev()?.let { sd ->
            out += Insight(Ic.ENGINE, if (sd < 40) 0 else if (sd < 90) 1 else 2, "Idle stability ±%.0f rpm".format(sd),
                if (sd < 40) "Smooth idle" else "Unsteady idle – check plugs, throttle body, vacuum leaks")
        }
        val cool = v[0x05]; val run = v[0x1F]
        if (cool != null) {
            val s = when { cool >= Prefs.coolantMax -> 2; run != null && run > 900 && cool < 70 -> 1; cool >= Prefs.coolantMax - 8 -> 1; else -> 0 }
            out += Insight(Ic.THERM, s, "Coolant %.0f °C".format(cool),
                when (s) { 2 -> "Overheating"; 1 -> if (cool < 70) "Slow warm-up – thermostat stuck open?" else "Running hot"; else -> if (cool < 70) "Warming up" else "Normal operating temperature" })
        }
        val rpm = v[0x0C]; val maf = v[0x10]
        if (rpm != null && rpm > 500 && maf != null && veh.dispL > 0) {
            val ve = maf * 120.0 / rpm / (veh.dispL * 1.184) * 100
            out += Insight(Ic.WIND, 0, "Volumetric efficiency ≈ %.0f %%".format(ve), "Air %.1f g/s at %.0f rpm".format(maf, rpm))
        }
        val iat = v[0x0F]; val amb = v[0x46]
        if (iat != null) out += Insight(Ic.WIND, if (iat > 65) 1 else 0, "Intake air %.0f °C".format(iat),
            if (iat > 65) "Heat soak – power and economy drop" else if (amb != null) "%.0f °C above ambient".format(iat - amb) else "Normal")
        val bv = m.batteryV ?: v[0x42]
        if (bv != null) {
            val s = if (m.engineOn) (if (bv < Prefs.chargeMinV || bv > Prefs.chargeMaxV) 2 else 0) else (if (bv < 11.8) 2 else if (bv < 12.3) 1 else 0)
            out += Insight(Ic.BATTERY, s, "Battery %.2f V".format(bv), if (m.engineOn) (if (s == 0) "Charging normally" else "Charging out of range") else (if (s == 0) "Healthy" else "Low – charge or test battery"))
        }
        val load = v[0x04]; val thr = v[0x11]
        if (load != null && thr != null) out += Insight(Ic.GAUGE, 0, "Load %.0f %% · throttle %.0f %%".format(load, thr), "Engine demand")
        out += Insight(Ic.TIMER, if (m.cycleMs in 1..400) 0 else 1, "Refresh %d ms".format(m.cycleMs), "%.1f updates/s · ${m.supported.count { !Pids.isBitmap(it) }} signals".format(if (m.cycleMs > 0) 1000.0 / m.cycleMs else 0.0))
        return out
    }

    private fun stats(a1: FloatArray): String? {
        if (a1.isEmpty()) return null
        val avg = a1.average(); val sd = Math.sqrt(a1.map { (it - avg) * (it - avg) }.average())
        return "%.1f|%.1f|%.1f|%.1f".format(a1.min(), avg, a1.max(), sd)
    }

    override fun refresh() {
        val m = ObdManager
        val u = m.vehicle.spec.unit
        val pids = m.supported.filter { !Pids.isBitmap(it) }
        val s = pids.joinToString(",") + "|" + Prefs.customPids.joinToString(",") { it.name } + "|" + cat
        if (s != sig) { sig = s; rebuild() }
        head.text = "${pids.size} signals · ${Prefs.customPids.size} custom · ${m.cycleMs} ms cycle"

        // calc series
        m.fuelRate()?.let { ring("calc:fuel").add(it.toFloat()) }
        m.engine.instantKmpl?.let { ring("calc:econ").add(it.toFloat()) }
        ring("calc:acc").add(m.engine.liveAccelG.toFloat())
        m.batteryV?.let { ring("calc:batt").add(it.toFloat()) }

        for (e in entries) {
            val c = cells[e.key] ?: continue
            c.value.text = textOf(e)
            val d = series(e)
            c.spark.set(d)
            if (d.size >= 12) {
                val n = d.size
                val recent = d.takeLast(6).average(); val prev = d.drop(n - 12).take(6).average()
                val rng = (d.max() - d.min()).coerceAtLeast(1e-6f)
                val dlt = (recent - prev) / rng
                c.trend.text = if (dlt > 0.08) "▲" else if (dlt < -0.08) "▼" else "▬"
                c.trend.setTextColor(if (dlt > 0.08) Ui.WARN else if (dlt < -0.08) Ui.CYAN else Ui.DIM)
            }
            val sel = e.key == pid1; val cmp = e.key == pid2
            c.card.background = if (sel) ui.outlined(Ui.SEL, Ui.CYAN, 16, 2) else if (cmp) ui.outlined(Ui.SEL, Ui.PINK, 16, 2) else ui.rounded(Ui.CARD, 16)
        }
        // graphs
        val e1 = entries.firstOrNull { it.key == pid1 } ?: allEntry(pid1)
        if (e1 != null) {
            val d1 = series(e1)
            chart1.set(d1, e1.name, if (e1.unit.isNotEmpty()) " ${e1.unit}" else "", Color.parseColor("#4FC3F7"))
            val st = stats(d1)
            statsBox.removeAllViews()
            if (st != null) {
                val p = st.split("|")
                val (x1, t1) = ui.stat(Ic.DOWNLOAD, "Min", Ui.CYAN); t1.text = p[0]
                val (x2, t2) = ui.stat(Ic.GAUGE, "Avg", Ui.ACC); t2.text = p[1]
                val (x3, t3) = ui.stat(Ic.BOLT, "Max", Ui.ORANGE); t3.text = p[2]
                val (x4, t4) = ui.stat(Ic.PULSE, "σ", Ui.VIOLET); t4.text = p[3]
                statsBox.addView(ui.row(x1, x2, x3, x4), ui.lp(2))
            }
        }
        val e2 = pid2?.let { k -> entries.firstOrNull { it.key == k } ?: allEntry(k) }
        if (e2 != null) { chart2.visibility = View.VISIBLE; chart2.set(series(e2), e2.name, if (e2.unit.isNotEmpty()) " ${e2.unit}" else "", Color.parseColor("#FF80AB")) }
        else chart2.visibility = View.GONE

        val now = System.currentTimeMillis()
        if (now - lastInsight > 2500) {
            lastInsight = now
            insightBox.removeAllViews()
            val ins = insights()
            if (ins.isEmpty() || m.state != ObdManager.State.CONNECTED)
                insightBox.addView(ui.iconCard(Ic.PLUG, Ui.DIM, "Waiting for live data", "Connect to the car to see automatic analysis"), ui.lp(4))
            else for (i in ins) {
                val col = when (i.status) { 0 -> Ui.GOOD; 1 -> Ui.WARN; else -> Ui.BAD }
                insightBox.addView(ui.iconCard(i.ic, col, i.title, i.body), ui.lp(6))
            }
        }
    }

    private fun allEntry(k: String): Entry? {
        val p = k.removePrefix("p").toIntOrNull()
        return if (k.startsWith("p") && p != null) Entry(k, Pids.byId[p]?.name ?: Pids.nameOf(p), Pids.byId[p]?.unit ?: "", category(p), p)
        else if (k.startsWith("calc:")) Entry(k, k.removePrefix("calc:"), "", "Engine", null) else null
    }

    private fun snapshot() {
        val m = ObdManager
        val sb = StringBuilder("pid,name,value,unit\n")
        for ((p, v) in m.values.toSortedMap()) { val d = Pids.byId[p]; sb.append("%02X,\"%s\",%.3f,%s\n".format(p, d?.name ?: Pids.nameOf(p), v, d?.unit ?: "")) }
        for ((k, v) in m.customValues) sb.append("custom,\"$k\",$v,\n")
        sb.append("\n# ${AppInfo.credit}\n")
        val path = Exporter.writeText(a, "carobd_snapshot_${stamp()}.csv", "text/csv", sb.toString())
        ui.toast(if (path != null) "Saved to $path" else "Export failed")
    }
}

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
        inner.addView(speed); inner.addView(unit); inner.addView(shift)
        for (rowKeys in cellKeys.chunked(3)) {
            inner.addView(LinearLayout(a).apply {
                for (k in rowKeys) addView(cell(k), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(2) })
        }
        advice = ui.tv("", 15f, Color.parseColor("#FFD54F"), true).apply { gravity = Gravity.CENTER }
        inner.addView(advice, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        inner.addView(ui.tv("tap: mirror for windscreen · long-press: exit\n${AppInfo.credit}", 11f, Color.DKGRAY).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(6) })
        root.addView(ui.scroll(inner).apply { setBackgroundColor(Color.BLACK) }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.setOnClickListener { flip() }
        inner.setOnClickListener { flip() }
        inner.setOnLongClickListener { a.show("Dash"); true }
        root.setOnLongClickListener { a.show("Dash"); true }
        return root
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
