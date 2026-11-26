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

@SuppressLint("SetTextI18n")
class DashPage(a: MainActivity) : Page(a) {
    override val parent = "Home"
    private lateinit var gRpm: GaugeView
    private lateinit var gSpd: GaugeView
    private lateinit var rCool: RingGauge
    private lateinit var rFuel: RingGauge
    private lateinit var rBatt: RingGauge
    private lateinit var rThr: RingGauge
    private lateinit var gMeter: GMeterView
    private lateinit var eco: EcoBar
    private lateinit var instTv: TextView
    private lateinit var ecoInfo: TextView
    private lateinit var driveTv: TextView
    private lateinit var eventTv: TextView
    private lateinit var alertTv: TextView
    private lateinit var led: LedBarView
    private lateinit var coachTv: TextView
    private val vals = LinkedHashMap<String, TextView>()
    private val keys = listOf(
        "Load", "Boost", "MAP", "MAF", "Intake air", "Oil temp",
        "Timing", "STFT", "LTFT", "Ambient", "Fuel rate", "Trip km"
    )
    private val keyIcons = mapOf(
        "Load" to Ic.ENGINE, "Boost" to Ic.GAUGE, "MAP" to Ic.GAUGE, "MAF" to Ic.WIND, "Intake air" to Ic.WIND,
        "Oil temp" to Ic.DROP, "Timing" to Ic.BOLT, "STFT" to Ic.SLIDERS, "LTFT" to Ic.SLIDERS,
        "Ambient" to Ic.THERM, "Fuel rate" to Ic.FUEL, "Trip km" to Ic.ROUTE
    )
    private val keyColors = mapOf(
        "Load" to Ui.ORANGE, "Boost" to Ui.CYAN, "MAP" to Ui.CYAN, "MAF" to Ui.ACC, "Intake air" to Ui.ACC,
        "Oil temp" to Ui.WARN, "Timing" to Ui.VIOLET, "STFT" to Ui.PINK, "LTFT" to Ui.PINK,
        "Ambient" to Ui.GOOD, "Fuel rate" to Ui.ORANGE, "Trip km" to Ui.GOOD
    )

    override fun build(): View {
        val c = ui.col()
        gRpm = GaugeView(a, "Engine", "rpm", 8000f, 6500f, 1000f)
        gSpd = GaugeView(a, "Speed", "km/h", 220f, 120f)
        led = LedBarView(a)
        c.addView(led, ui.lp(2))
        c.addView(ui.row(gRpm, gSpd), ui.lp(4))
        coachTv = ui.card("", 13f).apply {
            visibility = View.GONE; compoundDrawablePadding = ui.dp(10)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); gravity = Gravity.CENTER_VERTICAL
        }
        c.addView(coachTv, ui.lp(4))

        eventTv = ui.card("", 15f).apply { visibility = View.GONE; gravity = Gravity.CENTER }
        c.addView(eventTv, ui.lp(4))
        alertTv = ui.card("", 13f).apply { visibility = View.GONE; setOnClickListener { a.show("Alerts") } }
        c.addView(alertTv, ui.lp(4))

        rCool = RingGauge(a, "Coolant", "°C", 40f, 120f, 0) { v -> if (v >= Prefs.coolantMax) 2 else if (v >= Prefs.coolantMax - 5) 1 else 0 }
        rFuel = RingGauge(a, "Fuel", "%", 0f, 100f) { v -> if (v <= Prefs.lowFuelPct) 2 else if (v <= 25) 1 else 0 }
        rBatt = RingGauge(a, "Battery", "V", 10f, 16f, 1) { v -> if (v < 11.8f || v > Prefs.chargeMaxV) 2 else if (v < 12.3f) 1 else 0 }
        rThr = RingGauge(a, "Throttle", "%", 0f, 100f) { v -> if (v > 85) 1 else 0 }
        rCool.icon = Ic.THERM; rFuel.icon = Ic.FUEL; rBatt.icon = Ic.BATTERY; rThr.icon = Ic.ENGINE
        c.addView(ui.row(rCool, rFuel, rBatt, rThr), ui.lp(10))

        gMeter = GMeterView(a)
        instTv = ui.tv("--", 30f, Ui.WHITE, true)
        eco = EcoBar(a)
        ecoInfo = ui.tv("", 12f, Ui.DIM)
        val ecoBox = ui.vbox().apply {
            background = ui.rounded(Ui.CARD, 12)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            addView(ui.tv("INSTANT ECONOMY", 11f, Ui.DIM))
            addView(instTv)
            addView(eco, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(4) })
            addView(ecoInfo, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(6) })
        }
        val mid = LinearLayout(a).apply {
            addView(gMeter, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(ecoBox, LinearLayout.LayoutParams(0, Ui.MATCH, 1.25f).apply { leftMargin = ui.dp(8) })
        }
        c.addView(mid, ui.lp(10))

        driveTv = ui.card("", 13f).apply { setOnClickListener { a.show("Trips") } }
        c.addView(driveTv, ui.lp(10))
        for (chunk in keys.chunked(3)) {
            val tiles = chunk.map { key ->
                val (box, v) = ui.stat(keyIcons[key] ?: Ic.GAUGE, key, keyColors[key] ?: Ui.ACC)
                vals[key] = v
                box
            }
            c.addView(ui.row(*tiles.toTypedArray()), ui.lp(8))
        }
        c.addView(ui.row(
            ui.btn("Drive mode") { a.show("Car") },
            ui.btn("HUD", Ui.CARD) { a.show("HUD") },
            ui.btn("Performance", Ui.CARD) { a.show("Perf") },
            ui.btn("New trip", Ui.CARD) { ObdManager.stopTrip(); ObdManager.startTrip() }
        ), ui.lp(12))
        c.addView(ui.tv("Long-press a gauge or the G-meter to reset its peak.", 11f, Ui.DIM).apply { gravity = Gravity.CENTER }, ui.lp(8))
        return ui.scroll(c)
    }

    override fun onShow() {
        val veh = ObdManager.vehicle
        val diesel = veh.fuel.contains("Diesel", true)
        gRpm.max = if (diesel) 6000f else 8000f
        gRpm.redFrom = if (diesel) 4500f else 6500f
        gSpd.redFrom = veh.speedLimit.toFloat()
        rFuel.label = if (veh.isEv) "Battery SoC" else "Fuel"
        rBatt.label = if (veh.isEv) "12 V aux" else "Battery"
    }

    override fun refresh() {
        val m = ObdManager
        val v = m.values
        val veh = m.vehicle
        val u = veh.spec.unit
        val spd = v[0x0D]
        gRpm.value = v[0x0C]?.toFloat()
        led.frac = ((v[0x0C] ?: 0.0) / veh.rpmLimit.toDouble().coerceAtLeast(1000.0)).toFloat().let { if (it < 0.45f) 0f else (it - 0.45f) / 0.55f }
        val tip = Coach.tip()
        if (tip == null) coachTv.visibility = View.GONE else {
            coachTv.visibility = View.VISIBLE
            coachTv.text = tip.second
            coachTv.setCompoundDrawablesWithIntrinsicBounds(ui.icon(tip.first, Ui.CYAN, 22), null, null, null)
        }
        gSpd.value = spd?.toFloat()
        rCool.value = v[0x05]?.toFloat()
        val fuelPct = v[0x2F]
        rFuel.value = fuelPct?.toFloat()
        rFuel.subText = fuelPct?.let { "%.0f$u".format(it * veh.tankL / 100) } ?: ""
        rBatt.value = (m.batteryV ?: v[0x42])?.toFloat()
        rThr.value = (v[0x11] ?: v[0x45])?.toFloat()

        val now = System.currentTimeMillis()
        val e = m.engine
        gMeter.push(e.liveAccelG.toFloat(), if (now - e.liveLatAt < 3000) e.liveLatG.toFloat() else 0f)

        val t = e.current
        instTv.text = ui.econ(e.instantKmpl)
        eco.instant = e.instantKmpl?.toFloat()
        eco.average = t?.kmpl?.toFloat()
        eco.maxVal = maxOf(25f, (t?.kmpl?.toFloat() ?: 12f) * 2f)
        eco.invalidate()
        val rng = m.rangeKm()
        ecoInfo.text = "Trip avg ${ui.econ(t?.kmpl)}\nRange ${ui.f(rng, 0, " km")}\n${ui.f(m.fuelRate(), 2, " $u/h")} · CO₂ ${ui.f(t?.co2Kg, 1, " kg")}"

        val map = v[0x0B]
        val baro = v[0x33] ?: 100.0
        val s = mapOf(
            "Load" to ui.f(v[0x04], 0, "%"),
            "Boost" to ui.f(map?.let { (it - baro) / 100.0 }, 2, " bar"),
            "MAP" to ui.f(map, 0, " kPa"),
            "MAF" to ui.f(v[0x10], 1, " g/s"),
            "Intake air" to ui.f(v[0x0F], 0, "°C"),
            "Oil temp" to ui.f(v[0x5C], 0, "°C"),
            "Timing" to ui.f(v[0x0E], 1, "°"),
            "STFT" to ui.f(v[0x06], 1, "%"),
            "LTFT" to ui.f(v[0x07], 1, "%"),
            "Ambient" to ui.f(v[0x46], 0, "°C"),
            "Fuel rate" to ui.f(m.fuelRate(), 2, " $u/h"),
            "Trip km" to ui.f(t?.distanceKm, 1)
        )
        for ((k, x) in s) vals[k]?.text = x
        val st = v[0x06] ?: 0.0
        vals["STFT"]?.setTextColor(if (kotlin.math.abs(st) > 15) Ui.WARN else Ui.WHITE)
        val lt = v[0x07] ?: 0.0
        vals["LTFT"]?.setTextColor(if (kotlin.math.abs(lt) > 15) Ui.WARN else Ui.WHITE)

        driveTv.text = if (t == null) "🚗 No trip running – starts automatically when the engine runs." else
            "🚗 Trip %s · %s · score %d · %s\nRapid acc %d · Hard brake %d · Sudden %d · Corner %d · Overspeed %d · Over-rev %d · limit %d km/h".format(
                ui.hms(t.durationS), ui.f(t.distanceKm, 1, " km"), t.score, ui.money(t.cost), t.rapidAcc, t.hardBrake, t.suddenBrake,
                t.corner, t.overspeed, t.overRev, veh.speedLimit
            )
        driveTv.background = ui.rounded(if (t == null) Ui.CARD else Color.parseColor("#14324D"), 10)

        val ev = m.lastEvent
        if (ev != null && now - m.lastEventAt < 8000) {
            eventTv.visibility = View.VISIBLE
            eventTv.text = "⚠ ${ev.label}"
            eventTv.background = ui.rounded(Ui.eventColor(ev.type), 8)
        } else if (e.overspeedNow) {
            eventTv.visibility = View.VISIBLE
            eventTv.text = "🚨 OVERSPEED – limit ${veh.speedLimit} km/h"
            eventTv.background = ui.rounded(Color.parseColor("#B71C1C"), 8)
        } else eventTv.visibility = View.GONE

        val al = AlertCenter.latest
        if (al != null && al.level >= AlertCenter.WARN && now - AlertCenter.latestAt < 120_000) {
            alertTv.visibility = View.VISIBLE
            alertTv.text = "${AlertCenter.levelIcon(al.level)} ${al.title}\n${al.text}"
            alertTv.background = ui.rounded(AlertCenter.levelColor(al.level), 8)
        } else alertTv.visibility = View.GONE
    }
}

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
            return m.texts[e.pid] ?: m.values[e.pid]?.let { "%.${def?.decimals ?: 1}f".format(it) } ?: "…"
        }
        return when (e.key) {
            "calc:fuel" -> ui.f(m.fuelRate(), 2)
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
        val unit = ui.tv(e.unit, 11.5f, Ui.DIM)
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

class HudPage(a: MainActivity) : Page(a) {
    override val parent = "Dash"
    private lateinit var inner: LinearLayout
    private lateinit var speed: TextView
    private lateinit var info: TextView
    private var mirrored = false

    override fun build(): View {
        val root = FrameLayout(a).apply { setBackgroundColor(Color.BLACK) }
        inner = ui.vbox().apply { gravity = Gravity.CENTER }
        speed = ui.tv("--", 140f, Color.parseColor("#4FC3F7"), true).apply { gravity = Gravity.CENTER }
        info = ui.tv("", 26f, Color.parseColor("#B2FF59"), true).apply { gravity = Gravity.CENTER }
        inner.addView(speed)
        inner.addView(info)
        inner.addView(ui.tv("tap: mirror for windscreen · long-press: exit\n${AppInfo.credit}", 12f, Color.DKGRAY).apply { gravity = Gravity.CENTER })
        root.addView(inner, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.setOnClickListener {
            mirrored = !mirrored
            inner.scaleY = if (mirrored) -1f else 1f
        }
        root.setOnLongClickListener { a.show("Dash"); true }
        return root
    }

    override fun refresh() {
        val m = ObdManager
        val spd = m.values[0x0D]
        speed.text = spd?.let { "%.0f".format(it) } ?: "--"
        speed.setTextColor(if (m.engine.overspeedNow) Color.parseColor("#FF5252") else Color.parseColor("#4FC3F7"))
        info.text = "${ui.f(m.values[0x0C], 0, " rpm")}   ${ui.econ(m.engine.instantKmpl)}\n" +
                "${ui.f(m.values[0x05], 0, "°C")}   ${ui.f(m.batteryV, 1, " V")}   ${ui.f(m.engine.current?.distanceKm, 1, " km")}"
    }
}
