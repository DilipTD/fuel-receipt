package com.dilip.carobd

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Smart gauges: AI cluster, live coaching, forecasts, trends, G-meter and detailed readings. */
@SuppressLint("SetTextI18n")
class DashPage(a: MainActivity) : Page(a) {
    override val parent = "Home"
    private lateinit var rSpd: TextView
    private lateinit var rRpm: TextView
    private lateinit var rLim: TextView
    private lateinit var rCool: RingGauge
    private lateinit var rFuel: RingGauge
    private lateinit var rBatt: RingGauge
    private lateinit var rThr: RingGauge
    private lateinit var rEco: RingGauge
    private lateinit var gMeter: GMeterView
    private lateinit var eco: EcoBar
    private lateinit var instTv: TextView
    private lateinit var ecoInfo: TextView
    private lateinit var driveTv: TextView
    private lateinit var eventTv: TextView
    private lateinit var alertTv: TextView
    private lateinit var led: LedBarView
    private lateinit var headTv: TextView
    private lateinit var headDot: View
    private lateinit var modeTv: TextView
    private lateinit var adviceBox: LinearLayout
    private lateinit var emBox: LinearLayout
    private var emSig = ""
    private var adviceSig = ""
    private lateinit var fRange: TextView
    private lateinit var fCool: TextView
    private lateinit var fVolt: TextView
    private lateinit var fGear: TextView
    private val vals = LinkedHashMap<String, TextView>()
    private val sparks = ArrayList<Triple<Int, SparkView, TextView>>()
    private val keys = listOf("Load", "Boost", "MAP", "MAF", "Intake air", "Oil temp", "Timing", "STFT", "LTFT", "Ambient", "Fuel rate", "Trip km")
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

    private fun spark(label: String, pid: Int, col: Int, ic: Ic): View {
        val sv = SparkView(a).apply { color = col }
        val vt = ui.tv("--", 15f, Ui.WHITE, true)
        sparks += Triple(pid, sv, vt)
        return LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Ui.CARD, 16); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(8))
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.iconView(ic, col, 14), LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { rightMargin = ui.dp(6) })
                addView(ui.tv(label, 11f, Ui.DIM), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                addView(vt)
            })
            addView(sv, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(30)).apply { topMargin = ui.dp(6) })
        }
    }

    override fun build(): View {
        val c = ui.col()
        // AI header
        headTv = ui.tv("AI watching", 14f, Ui.WHITE, true)
        headDot = View(a).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.GOOD) } }
        modeTv = ui.tv("", 12f, Ui.WHITE, true).apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)) }
        c.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(Ui.CARD, 18); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            addView(ui.chip("AI", Ui.VIOLET, Ic.SHIELD), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
            addView(headTv, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(modeTv)
            addView(headDot, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { leftMargin = ui.dp(8) })
            setOnClickListener { a.show("Diag") }
        }, ui.lp(2))

        led = LedBarView(a)
        c.addView(led, ui.lp(8))
        val (q1, u1) = ui.stat(Ic.GAUGE, "Speed", Ui.ACC); rSpd = u1
        val (q2, u2) = ui.stat(Ic.ENGINE, "RPM", Ui.ORANGE); rRpm = u2
        val (q3, u3) = ui.stat(Ic.FLAG, "Road limit", Ui.GOOD); rLim = u3
        val rdRow = ui.row(q1, q2, q3)
        rdRow.setOnLongClickListener { SmartLive.resetGears(); ui.toast("Gear learning reset"); true }
        c.addView(rdRow, ui.lp(8))

        adviceBox = ui.vbox()
        c.addView(adviceBox, ui.lp(6))
        emBox = ui.vbox()
        c.addView(emBox, ui.lp(6))
        eventTv = ui.card("", 15f).apply { visibility = View.GONE; gravity = Gravity.CENTER }
        c.addView(eventTv, ui.lp(6))
        alertTv = ui.card("", 13f).apply { visibility = View.GONE; setOnClickListener { a.show("Alerts") } }
        c.addView(alertTv, ui.lp(6))

        // AI forecasts
        c.addView(ui.section("AI forecast", Ic.SHIELD))
        rEco = RingGauge(a, "Eco score", "", 0f, 100f, 0) { v -> if (v < 40) 2 else if (v < 60) 1 else 0 }
        rEco.icon = Ic.LEAF
        val (f1, t1) = ui.stat(Ic.ROUTE, "Range", Ui.CYAN); fRange = t1
        val (f2, t2) = ui.stat(Ic.THERM, "Coolant trend", Ui.ORANGE); fCool = t2
        val (f3, t3) = ui.stat(Ic.BATTERY, "Voltage", Ui.GOOD); fVolt = t3
        val (f4, t4) = ui.stat(Ic.GEAR, "Gear (learned)", Ui.VIOLET); fGear = t4
        c.addView(LinearLayout(a).apply {
            addView(rEco, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(ui.vbox().apply {
                addView(ui.row(f1, f2)); addView(ui.row(f3, f4), ui.lp(8))
            }, LinearLayout.LayoutParams(0, Ui.WRAP, 2f).apply { leftMargin = ui.dp(8) })
        }, ui.lp(2))

        // ring gauges
        rCool = RingGauge(a, "Coolant", "°C", 40f, 120f, 0) { v -> if (v >= Prefs.coolantMax) 2 else if (v >= Prefs.coolantMax - 5) 1 else 0 }
        rFuel = RingGauge(a, "Fuel", "%", 0f, 100f) { v -> if (v <= Prefs.lowFuelPct) 2 else if (v <= 25) 1 else 0 }
        rBatt = RingGauge(a, "Battery", "V", 10f, 16f, 1) { v -> if (v < 11.8f || v > Prefs.chargeMaxV) 2 else if (v < 12.3f) 1 else 0 }
        rThr = RingGauge(a, "Throttle", "%", 0f, 100f) { v -> if (v > 85) 1 else 0 }
        rCool.icon = Ic.THERM; rFuel.icon = Ic.FUEL; rBatt.icon = Ic.BATTERY; rThr.icon = Ic.ENGINE
        c.addView(ui.row(rCool, rFuel, rBatt, rThr), ui.lp(10))

        // trends
        c.addView(ui.section("Live trends", Ic.CHART))
        c.addView(ui.row(spark("Speed", 0x0D, Ui.ACC, Ic.GAUGE), spark("RPM", 0x0C, Ui.ORANGE, Ic.ENGINE)), ui.lp(2))
        c.addView(ui.row(spark("Engine load %", 0x04, Ui.VIOLET, Ic.BOLT), spark("Coolant °C", 0x05, Ui.CYAN, Ic.THERM)), ui.lp(8))

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
        c.addView(LinearLayout(a).apply {
            addView(gMeter, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(ecoBox, LinearLayout.LayoutParams(0, Ui.MATCH, 1.25f).apply { leftMargin = ui.dp(8) })
        }, ui.lp(10))

        driveTv = ui.card("", 13f).apply { setOnClickListener { a.show("Trips") } }
        c.addView(driveTv, ui.lp(10))
        c.addView(ui.section("Detailed readings", Ic.GRID))
        for (chunk in keys.chunked(3)) {
            val tiles = chunk.map { key ->
                val (box, v) = ui.stat(keyIcons[key] ?: Ic.GAUGE, key, keyColors[key] ?: Ui.ACC)
                vals[key] = v; box
            }
            c.addView(ui.row(*tiles.toTypedArray()), ui.lp(8))
        }
        c.addView(ui.row(
            ui.btn("Drive mode") { a.show("Car") },
            ui.btn("HUD", Ui.CARD) { a.show("HUD") },
            ui.btn("Performance", Ui.CARD) { a.show("Perf") },
            ui.btn("New trip", Ui.CARD) { ObdManager.stopTrip(); ObdManager.startTrip() }
        ), ui.lp(12))
        c.addView(ui.tv("Long-press the speed / RPM row to reset learned gears.", 11f, Ui.DIM).apply { gravity = Gravity.CENTER }, ui.lp(8))
        return ui.scroll(c)
    }

    override fun onShow() {
        val veh = ObdManager.vehicle
        val diesel = veh.fuel.contains("Diesel", true)
        rFuel.label = if (veh.isEv) "Battery SoC" else "Fuel"
        rBatt.label = if (veh.isEv) "12 V aux" else "Battery"
        adviceSig = ""
    }

    override fun refresh() {
        val m = ObdManager; val v = m.values; val veh = m.vehicle; val u = veh.spec.unit
        SmartLive.tick()
        val spd = v[0x0D]
        val ri = RoadAssist.info
        val lim = ri?.limit ?: veh.speedLimit
        rSpd.text = ui.spd(spd, 0); rSpd.setTextColor(if (spd != null && spd > lim + (if (ri?.limit != null) Prefs.speedMargin else 0)) Ui.BAD else Ui.WHITE)
        rRpm.text = ui.f(v[0x0C], 0)
        rLim.text = ri?.limit?.let { "$it" + if (ri.estimated) " (est.)" else "" } ?: "--"
        val (mt, mc) = SmartLive.mode()
        modeTv.text = mt; modeTv.background = ui.rounded(Ui.alpha(mc, 50), 14); modeTv.setTextColor(Ui.lighten(mc, 1.3f))
        val st = SmartLive.status()
        val sc = when (st.overall) { 2 -> Ui.BAD; 1 -> Ui.WARN; else -> Ui.GOOD }
        headTv.text = st.headline
        headDot.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(sc) }
        led.frac = ((v[0x0C] ?: 0.0) / veh.rpmLimit.toDouble().coerceAtLeast(1000.0)).toFloat().let { if (it < 0.45f) 0f else (it - 0.45f) / 0.55f }

        val er = EmissionAi.analyse()
        val esig = "${er.level}|${er.headline}|${ObdManager.vehicle.fuel}"
        if (esig != emSig) {
            emSig = esig; emBox.removeAllViews()
            if (er.applicable) emBox.addView(ui.iconCard(Ic.LEAF, when (er.level) { 0 -> Ui.GOOD; 1 -> Ui.WARN; 2 -> Ui.BAD; else -> Ui.DIM },
                "Emission readiness · ${ObdManager.vehicle.fuel}", er.headline + if (er.monitors.isNotEmpty()) " · ${er.readyPct} % monitors done" else "") { a.show("Diag") }, ui.lp(0))
        }

        // advice
        val adv = SmartLive.advice()
        val sig = adv.joinToString("|") { it.second }
        if (sig != adviceSig) {
            adviceSig = sig; adviceBox.removeAllViews()
            for ((ic, tx, sev) in adv) adviceBox.addView(ui.iconCard(ic, when (sev) { 2 -> Ui.BAD; 1 -> Ui.VIOLET; else -> Ui.GOOD }, "AI coach", tx, Ui.CARD), ui.lp(6))
        }

        // forecasts
        rEco.value = SmartLive.eco
        fRange.text = ui.dist(m.rangeKm(), 0)
        val sl = SmartLive.coolantSlope()
        fCool.text = sl?.let { if (kotlin.math.abs(it) < 0.6) "Steady" else "%+.1f °/min".format(it) } ?: "--"
        fCool.setTextColor(if (sl != null && sl > 4) Ui.BAD else Ui.WHITE)
        val bv = m.batteryV ?: v[0x42]
        fVolt.text = ui.f(bv, 1, " V") + (SmartLive.voltTrend()?.let { if (it < -0.3) " ↘" else if (it > 0.3) " ↗" else "" } ?: "")
        fGear.text = if (SmartLive.gear > 0) "Gear ${SmartLive.gear}" else "Learning…"

        rCool.value = v[0x05]?.toFloat()
        val fuelPct = v[0x2F]
        rFuel.value = fuelPct?.toFloat()
        rFuel.subText = fuelPct?.let { "%.0f$u".format(it * veh.tankL / 100) } ?: ""
        rBatt.value = bv?.toFloat()
        rThr.value = (v[0x11] ?: v[0x45])?.toFloat()
        for ((pid, sv, vt) in sparks) {
            val h = m.history[pid]?.values() ?: continue
            sv.set(h.takeLast(90).toFloatArray())
            vt.text = v[pid]?.let { "%.0f".format(it) } ?: "--"
        }

        val now = System.currentTimeMillis()
        val e = m.engine
        gMeter.push(e.liveAccelG.toFloat(), if (now - e.liveLatAt < 3000) e.liveLatG.toFloat() else 0f)
        val t = e.current
        instTv.text = ui.econ(e.instantKmpl)
        eco.instant = e.instantKmpl?.toFloat()
        eco.average = t?.kmpl?.toFloat()
        eco.maxVal = maxOf(25f, (t?.kmpl?.toFloat() ?: 12f) * 2f)
        eco.invalidate()
        ecoInfo.text = "Trip avg ${ui.econ(t?.kmpl)}\n${ui.f(m.fuelRate(), 2, " $u/h")} · CO₂ ${ui.f(t?.co2Kg, 1, " kg")}"

        val map = v[0x0B]
        val baro = v[0x33] ?: 100.0
        val s = mapOf(
            "Load" to ui.f(v[0x04], 0, "%"), "Boost" to ui.f(map?.let { (it - baro) / 100.0 }, 2, " bar"),
            "MAP" to ui.press(map), "MAF" to ui.f(v[0x10], 1, " g/s"), "Intake air" to ui.temp(v[0x0F], 0),
            "Oil temp" to ui.temp(v[0x5C], 0), "Timing" to ui.f(v[0x0E], 1, "°"), "STFT" to ui.f(v[0x06], 1, "%"),
            "LTFT" to ui.f(v[0x07], 1, "%"), "Ambient" to ui.temp(v[0x46], 0),
            "Fuel rate" to ui.f(m.fuelRate(), 2, " $u/h"), "Trip km" to ui.f(t?.distanceKm, 1)
        )
        for ((k, x) in s) vals[k]?.text = x
        vals["STFT"]?.setTextColor(if (kotlin.math.abs(v[0x06] ?: 0.0) > 15) Ui.WARN else Ui.WHITE)
        vals["LTFT"]?.setTextColor(if (kotlin.math.abs(v[0x07] ?: 0.0) > 15) Ui.WARN else Ui.WHITE)

        driveTv.text = if (t == null) "🚗 No trip running – starts automatically when the engine runs." else
            "🚗 Trip %s · %s · score %d · %s\nRapid acc %d · Hard brake %d · Sudden %d · Corner %d · Overspeed %d · Over-rev %d".format(
                ui.hms(t.durationS), ui.dist(t.distanceKm, 1), t.score, ui.money(t.cost), t.rapidAcc, t.hardBrake, t.suddenBrake,
                t.corner, t.overspeed, t.overRev)
        driveTv.background = ui.rounded(if (t == null) Ui.CARD else Color.parseColor("#14324D"), 10)

        val ev = m.lastEvent
        if (ev != null && now - m.lastEventAt < 8000) {
            eventTv.visibility = View.VISIBLE; eventTv.text = "⚠ ${ev.label}"; eventTv.background = ui.rounded(Ui.eventColor(ev.type), 8)
        } else if (e.overspeedNow) {
            eventTv.visibility = View.VISIBLE; eventTv.text = "🚨 OVERSPEED – limit ${Units.sStr(veh.speedLimit.toDouble())}"; eventTv.background = ui.rounded(Color.parseColor("#B71C1C"), 8)
        } else eventTv.visibility = View.GONE

        val al = AlertCenter.latest
        if (al != null && al.level >= AlertCenter.WARN && now - AlertCenter.latestAt < 120_000) {
            alertTv.visibility = View.VISIBLE; alertTv.text = "${AlertCenter.levelIcon(al.level)} ${al.title}\n${al.text}"
            alertTv.background = ui.rounded(AlertCenter.levelColor(al.level), 8)
        } else alertTv.visibility = View.GONE
    }
}
