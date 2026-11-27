package com.dilip.carobd

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Large, high-contrast landscape dashboard meant for a phone/tablet mounted in the car. Works for petrol, diesel, hybrid and EV. */
class CarDashPage(a: MainActivity) : Page(a) {
    override val parent = "Dash"
    private lateinit var led: LedBarView
    private lateinit var gRpm: GaugeView
    private lateinit var rSoc: RingGauge
    private lateinit var speed: TextView
    private lateinit var unitTv: TextView
    private lateinit var coolant: RingGauge
    private lateinit var fuel: RingGauge
    private lateinit var clock: TextView
    private lateinit var coach: TextView
    private val stats = LinkedHashMap<String, TextView>()
    private lateinit var leftBox: FrameLayout

    private fun statBlock(ic: Ic, cap: String, col: Int): LinearLayout {
        val v = ui.tv("--", 15f, Ui.WHITE, true).apply { maxLines = 1 }
        stats[cap] = v
        return LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(Ui.CARD, 12)
            setPadding(ui.dp(8), ui.dp(5), ui.dp(6), ui.dp(5))
            addView(ui.iconView(ic, col, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(6) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(v)
                addView(ui.tv(cap, 9.5f, Ui.DIM).apply { maxLines = 1 })
            }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
    }

    private var statRow2: LinearLayout? = null

    override fun build(): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#06090D")); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) }
        // top strip
        clock = ui.tv("", 16f, Ui.DIM, true)
        coach = ui.tv("", 15f, Ui.CYAN, true).apply { gravity = Gravity.CENTER }
        val close = ui.btn("Exit", Ui.CARD) { a.show("Dash") }
        root.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(clock, LinearLayout.LayoutParams(ui.dp(90), Ui.WRAP))
            addView(coach, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(ui.btn("⚠ Hazard", Ui.WARN) { HazardQuick.show(a) }, LinearLayout.LayoutParams(ui.dp(110), Ui.WRAP).apply { rightMargin = ui.dp(6) })
            addView(close, LinearLayout.LayoutParams(ui.dp(90), Ui.WRAP))
        })
        led = LedBarView(a)
        root.addView(led, ui.lp(4))

        // middle: [rpm|soc] [speed] [rings]
        gRpm = GaugeView(a, "Engine", "rpm", 8000f, 6500f, 1000f)
        rSoc = RingGauge(a, "Battery", "%", 0f, 100f, 0) { v -> if (v <= 10) 2 else if (v <= 20) 1 else 0 }.apply { icon = Ic.BATTERY }
        leftBox = FrameLayout(a).apply { addView(gRpm); addView(rSoc) }
        speed = ui.tv("--", 96f, Ui.WHITE, true).apply { gravity = Gravity.CENTER }
        unitTv = ui.tv("km/h", 16f, Ui.DIM).apply { gravity = Gravity.CENTER }
        val mid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; addView(speed); addView(unitTv) }
        speed.includeFontPadding = false
        // shrink the big speed number on short screens so the unit label always fits underneath
        mid.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
            val hDp = (b - t) / a.resources.displayMetrics.density
            val want = ((hDp - 26f) / 1.2f).coerceIn(40f, 96f)
            if (hDp > 0 && kotlin.math.abs(speed.textSize / a.resources.displayMetrics.scaledDensity - want) > 1f) mid.post { speed.textSize = want }
        }
        coolant = RingGauge(a, "Coolant", "°C", 40f, 120f, 0) { v -> if (v >= Prefs.coolantMax) 2 else if (v >= Prefs.coolantMax - 5) 1 else 0 }.apply { icon = Ic.THERM }
        fuel = RingGauge(a, "Fuel", "%", 0f, 100f) { v -> if (v <= Prefs.lowFuelPct) 2 else if (v <= 25) 1 else 0 }.apply { icon = Ic.FUEL }
        val rightBox = LinearLayout(a).apply { addView(coolant, LinearLayout.LayoutParams(0, Ui.MATCH, 1f)); addView(fuel, LinearLayout.LayoutParams(0, Ui.MATCH, 1f)) }
        root.addView(LinearLayout(a).apply {
            minimumHeight = ui.dp(130)
            gravity = Gravity.CENTER_VERTICAL
            addView(leftBox, LinearLayout.LayoutParams(0, Ui.MATCH, 1.1f))
            addView(mid, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
            addView(rightBox, LinearLayout.LayoutParams(0, Ui.MATCH, 1.2f))
        }, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // bottom stats: two compact rows
        val rows = listOf(
            listOf(statBlock(Ic.ROUTE, "Range", Ui.GOOD), statBlock(Ic.LEAF, "Economy now", Ui.GOOD), statBlock(Ic.GAUGE, "Trip economy", Ui.GOOD),
                statBlock(Ic.FUEL, "Fuel rate", Ui.ORANGE), statBlock(Ic.ROUTE, "Trip dist", Ui.ACC), statBlock(Ic.TIMER, "Trip time", Ui.ACC)),
            listOf(statBlock(Ic.GAUGE, "Avg speed", Ui.CYAN), statBlock(Ic.BOLT, "Max speed", Ui.CYAN), statBlock(Ic.BATTERY, "12 V", Ui.WARN),
                statBlock(Ic.ENGINE, "Engine load", Ui.VIOLET), statBlock(Ic.WIND, "Intake air", Ui.VIOLET), statBlock(Ic.USER, "Drive score", Ui.GOOD))
        )
        rows.forEachIndexed { ri, r ->
            val row = LinearLayout(a).apply {
                r.forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { if (i > 0) leftMargin = ui.dp(5) }) }
            }
            if (ri == 1) statRow2 = row
            root.addView(row, ui.lp(4))
        }
        // very short screens: keep the gauges visible, drop the second row of readings
        root.addOnLayoutChangeListener { _, _, t, _, bt, _, _, _, _ ->
            val hDp = (bt - t) / a.resources.displayMetrics.density
            val want = if (hDp < 330f) View.GONE else View.VISIBLE
            if (statRow2?.visibility != want) statRow2?.visibility = want
        }
        return root
    }

    override fun onShow() {
        val ev = ObdManager.vehicle.isEv
        gRpm.visibility = if (ev) View.GONE else View.VISIBLE
        rSoc.visibility = if (ev) View.VISIBLE else View.GONE
        fuel.label = if (ev) "Range" else "Fuel"
        val diesel = ObdManager.vehicle.fuel.contains("Diesel", true)
        gRpm.max = if (diesel) 6000f else 8000f
        gRpm.redFrom = if (diesel) 4500f else 6500f
    }

    override fun refresh() {
        val m = ObdManager; val v = m.values; val veh = m.vehicle
        clock.text = ui.date(System.currentTimeMillis(), "HH:mm")
        val spd = v[0x0D]
        speed.text = spd?.let { "%.0f".format(Units.spd(it)) } ?: "--"
        unitTv.text = Units.spdLabel
        speed.setTextColor(if (m.engine.overspeedNow) Ui.BAD else Ui.WHITE)
        val rpm = v[0x0C]
        gRpm.value = rpm?.toFloat()
        led.frac = if (veh.isEv) 0f else ((rpm ?: 0.0) / veh.rpmLimit.toDouble().coerceAtLeast(1000.0)).toFloat().let { if (it < 0.45f) 0f else (it - 0.45f) / 0.55f }
        val soc = v[0x2F]
        rSoc.value = soc?.toFloat()
        fuel.value = soc?.toFloat()
        coolant.value = v[0x05]?.toFloat()
        val tip = Coach.tip()
        coach.text = tip?.second ?: ""
        val t = m.engine.current
        stats["Range"]?.text = m.rangeKm()?.let { ui.dist(it, 0) } ?: "--"
        stats["Economy now"]?.text = ui.econ(m.engine.instantKmpl)
        stats["Trip economy"]?.text = ui.econ(t?.kmpl)
        stats["Fuel rate"]?.text = m.fuelRate()?.let { ui.vol(it, 1) + "/h" } ?: "--"
        stats["Trip dist"]?.text = ui.dist(t?.distanceKm, 1)
        stats["Trip time"]?.text = t?.let { "%d:%02d".format((it.durationS / 3600).toInt(), ((it.durationS % 3600) / 60).toInt()) } ?: "--"
        stats["Avg speed"]?.text = t?.let { ui.spd(it.avgSpeed) } ?: "--"
        stats["Max speed"]?.text = t?.let { ui.spd(it.maxSpeed) } ?: "--"
        stats["12 V"]?.text = ui.f(m.batteryV ?: v[0x42], 1, " V")
        stats["Engine load"]?.text = v[0x04]?.let { "%.0f %%".format(it) } ?: "--"
        stats["Intake air"]?.text = ui.temp(v[0x0F])
        stats["Drive score"]?.text = t?.let { "${it.score}" } ?: "--"
    }
}
