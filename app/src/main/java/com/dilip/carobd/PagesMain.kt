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
            ui.btn("HUD") { a.show("HUD") },
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
    private lateinit var box: LinearLayout
    private lateinit var head: TextView
    private lateinit var chart: LineChart
    private val rows = HashMap<String, TextView>()
    private var sig = ""
    private var graphPid = 0x0C

    override fun build(): View {
        val c = ui.col()
        chart = LineChart(a)
        c.addView(chart, ui.lp(6))
        head = ui.tv("", 12f, Ui.DIM)
        c.addView(head, ui.lp())
        box = ui.vbox()
        c.addView(box)
        return ui.scroll(c)
    }

    private fun addRow(key: String, name: String, pid: Int?) {
        val value = ui.tv("…", 14f, Ui.WHITE, true).apply { gravity = Gravity.END }
        val r = LinearLayout(a).apply {
            background = ui.rounded(Ui.CARD, 6)
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            addView(ui.tv(name, 13f, Ui.DIM), LinearLayout.LayoutParams(0, Ui.WRAP, 1.4f))
            addView(value, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            if (pid != null) setOnClickListener { graphPid = pid }
        }
        rows[key] = value
        box.addView(r, ui.lp(4))
    }

    override fun refresh() {
        val m = ObdManager
        val pids = m.supported.filter { !Pids.isBitmap(it) }
        val customs = Prefs.customPids
        val s = pids.joinToString(",") + "|" + customs.joinToString(",") { it.name }
        if (s != sig) {
            sig = s
            box.removeAllViews(); rows.clear()
            addRow("calc:fuel", "Fuel consumption (calc.)", null)
            addRow("calc:econ", "Instant economy (calc.)", null)
            addRow("calc:acc", "Longitudinal accel (calc.)", null)
            addRow("calc:batt", "Battery voltage (OBD pin 16)", null)
            for (p in pids) addRow("p$p", "%02X  %s".format(p, Pids.nameOf(p)), p)
            for (cp in customs) addRow("c:${cp.name}", "★ ${cp.name}", null)
        }
        val u = m.vehicle.spec.unit
        head.text = "${pids.size} supported PIDs · ${customs.size} custom · cycle ${m.cycleMs} ms\nTap any PID to graph it."
        rows["calc:fuel"]?.text = ui.f(m.fuelRate(), 2, " $u/h")
        rows["calc:econ"]?.text = ui.econ(m.engine.instantKmpl)
        rows["calc:acc"]?.text = "%+.2f g".format(m.engine.liveAccelG)
        rows["calc:batt"]?.text = ui.f(m.batteryV, 2, " V")
        for (p in pids) {
            val def = Pids.byId[p]
            rows["p$p"]?.text = m.texts[p] ?: m.values[p]?.let { "%.${def?.decimals ?: 1}f %s".format(it, def?.unit ?: "") } ?: "…"
        }
        for (cp in customs) rows["c:${cp.name}"]?.text = m.customValues[cp.name] ?: "…"
        val def = Pids.byId[graphPid]
        chart.set(m.history[graphPid]?.values() ?: FloatArray(0), def?.name ?: Pids.nameOf(graphPid), def?.unit ?: "")
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
