package com.dilip.carobd

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/* ============================ FAQ ============================ */

class FaqPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private class Q(val cat: String, val q: String, val a: String)

    private val cats = listOf("All", "Connection", "General", "Function", "Data & privacy", "Troubleshooting")
    private var cat = "All"
    private var query = ""
    private lateinit var chips: LinearLayout
    private lateinit var list: LinearLayout

    private val qs = listOf(
        Q("Connection", "Which adapters work?", "Any ELM327-compatible adapter: Bluetooth classic, Bluetooth LE or Wi-Fi. Genuine, good-quality adapters are far more reliable than cheap clones, especially v1.5 clones that cannot handle all protocols."),
        Q("Connection", "How do I pair a Bluetooth adapter?", "Pair it first in Android Bluetooth settings (PIN is usually 1234 or 0000), then open Connect in the app and pick it from the list. Bluetooth LE adapters need no pairing, just select them."),
        Q("Connection", "How do I connect over Wi-Fi?", "Join the adapter's own Wi-Fi network in Android settings, then choose Wi-Fi in Connect. The usual address is 192.168.0.10 port 35000. Android may warn that the network has no internet; keep the connection."),
        Q("Connection", "It connects but shows no data", "Switch the ignition on (engine need not run). If it still fails, try another protocol in Settings > Connection, or unplug the adapter for a minute and retry. Some cars sleep the OBD port when the ignition is off."),
        Q("Connection", "Can it auto-connect?", "Yes. Turn on auto-connect in Settings > Connection. The app reconnects to the last adapter when it is in range. Background watch keeps it alive when the app is closed."),
        Q("General", "What is Car OBD Pro?", "A universal OBD-II app for ELM327 adapters. It shows live data, logs trips and fuel, tracks maintenance, reads fault codes and monitors the battery. It works with any OBD-II car, with extra detail for Tata Nexon."),
        Q("General", "Does it need internet?", "No. Everything runs on the phone. Internet is only used for optional map tiles, Home Assistant/MQTT, cloud backup and road-limit lookups, and only when you enable them."),
        Q("General", "Is the AI a cloud service?", "No. The insights are on-device rules and statistics over your own logs. Nothing is sent to an AI server."),
        Q("General", "Can I change units, language and theme?", "Yes. Settings > Display & units sets distance, speed, fuel, economy (km/L, L/100km, MPG), currency, temperature, pressure, torque, 12/24-hour time, language and theme."),
        Q("General", "Which languages are supported?", "English and Hindi. Hindi covers the main menus and labels; longer texts such as this FAQ and legal documents stay in English."),
        Q("Function", "How are trips recorded?", "A trip starts with the engine and ends after it stops (stops shorter than the merge time continue the same trip). Distance, fuel, idle time, events and the GPS route are stored."),
        Q("Function", "What events are detected?", "Over-speed, rapid acceleration, rapid deceleration (hard braking), sudden braking, harsh cornering, over-revving, cold-engine revving and long idling. In Driving replay each event has a symbol and is coloured by speed: green below 50, yellow 50-80, red above 80 km/h."),
        Q("Function", "How is fuel use calculated?", "From the mass-air-flow or fuel-rate PID when the car provides it, otherwise estimated from engine load, RPM and displacement. Use the fuel calibration or Settings > Corrections to match the real tank-to-tank figure."),
        Q("Function", "What do the corrections do?", "Speed correction scales the speed reading by a percentage (for example if tyres are not stock size). Distance can come from ECU speed or from GPS. Battery voltage can come from the adapter, or from the ECU. Fuel efficiency correction adjusts estimated fuel use."),
        Q("Function", "What is the calculator for?", "More > Calculator works out trip cost, distance you can drive, fuel consumption and fuel required for a trip, using your car's economy and fuel price by default."),
        Q("Function", "How does the battery page work?", "It tracks resting voltage, cranking dip and charging voltage, estimates health and the cold-cranking amps (CCA) trend, and flags weak batteries. Choose the battery type (lead-acid, AGM, LFP) in the battery page."),
        Q("Function", "Can I publish data to Home Assistant?", "Yes, via MQTT. Set the broker in Settings > Home Assistant. Discovery entities appear automatically."),
        Q("Data & privacy", "Where is my data stored?", "Only on your phone. Backups are files you choose to export. The MQTT password is not included in backups."),
        Q("Data & privacy", "Which permissions are needed and why?", "Bluetooth (adapter), location (GPS routes, Bluetooth scanning on older Android), notifications (alerts), camera/storage (receipts and documents). Review each in More > Permissions."),
        Q("Data & privacy", "How do I delete my data?", "Swipe a log entry left to delete it (with confirmation), or clear everything from Settings > Data. Uninstalling the app also removes it."),
        Q("Troubleshooting", "Fuel or distance looks wrong", "Check Settings > Corrections: pick GPS distance if speed is off, apply a speed correction, and calibrate fuel with a full-tank fill-up."),
        Q("Troubleshooting", "Battery voltage looks off", "Switch the voltage source between adapter and ECU in Settings > Corrections. The adapter reads the OBD socket; the ECU value can differ slightly."),
        Q("Troubleshooting", "Some values show '…' or are missing", "Not every car supports every PID. Unsupported values are simply not reported. Try the Live data page after the engine is running."),
        Q("Troubleshooting", "App stops in the background", "Allow notifications and disable battery optimisation for the app in Android settings, and enable background watch if you want logging while the screen is off.")
    )

    override fun build(): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        chips = LinearLayout(a).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(4)) }
        root.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val search = ui.edit("Search", "").apply {
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); fill() }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { leftMargin = ui.dp(14); rightMargin = ui.dp(14); bottomMargin = ui.dp(4) })
        list = ui.col(4)
        root.addView(ui.scroll(list), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        return root
    }

    override fun onShow() { fill() }

    private fun fill() {
        chips.removeAllViews()
        for (c in cats) {
            val on = c == cat
            chips.addView(ui.tv(c, 13f, if (on) Ui.WHITE else Ui.DIM, on).apply {
                background = if (on) ui.outlined(Ui.SEL, Ui.CYAN, 20, 1) else ui.rounded(Ui.CARD, 20)
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                setOnClickListener { cat = c; fill() }
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
        }
        list.removeAllViews()
        val q = query.lowercase()
        val shown = qs.filter { (cat == "All" || it.cat == cat) && (q.isEmpty() || it.q.lowercase().contains(q) || it.a.lowercase().contains(q)) }
        if (shown.isEmpty()) list.addView(ui.card("No matching questions."), ui.lp(8))
        for (item in shown) list.addView(entry(item), ui.lp(8))
        list.addView(ui.tv("Still stuck? Email ${AppInfo.SUPPORT_EMAIL.ifBlank { "the developer" }}.", 12f, Ui.DIM).apply { gravity = Gravity.CENTER }, ui.lp(16))
    }

    private fun entry(item: Q): View {
        val ans = ui.tv(item.a, 13f, Ui.DIM).apply { visibility = View.GONE; setPadding(ui.dp(2), ui.dp(8), ui.dp(2), 0) }
        val arrow = ui.iconView(Ic.CHEV, Ui.DIM, 18)
        val head = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.tv(item.q, 14.5f, Ui.WHITE, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(ui.chip(item.cat, Ui.CYAN), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(6) })
            addView(arrow)
        }
        return LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Ui.CARD, 14)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            addView(head); addView(ans)
            setOnClickListener {
                val open = ans.visibility != View.VISIBLE
                ans.visibility = if (open) View.VISIBLE else View.GONE
                arrow.animate().rotation(if (open) 90f else 0f).setDuration(180).start()
            }
        }
    }
}

/* ============================ Calculator ============================ */

class CalcPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private val modes = listOf("Trip cost", "Distance", "Consumption", "Required fuel")
    private var mode = 0
    private lateinit var modeRow: LinearLayout
    private lateinit var form: LinearLayout
    private lateinit var result: LinearLayout

    override fun build(): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        modeRow = LinearLayout(a).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)) }
        root.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(modeRow) }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val body = ui.col(4)
        form = ui.col(0); result = ui.col(0)
        body.addView(form); body.addView(result, ui.lp(12))
        root.addView(ui.scroll(body), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        return root
    }

    override fun onShow() = draw()

    private fun defaultEcon(): Double {
        val m = ObdManager
        val ts = Db.trips(ObdManager.vehicle.id, 0, 200)
        val d = ts.sumOf { it.distanceKm }; val fl = ts.sumOf { it.fuel }
        val e = if (fl > 0.5 && d > 5) d / fl else null
        return e ?: m.engine.instantKmpl ?: 15.0
    }

    private fun draw() {
        modeRow.removeAllViews()
        modes.forEachIndexed { i, n ->
            val on = i == mode
            modeRow.addView(ui.tv(n, 13f, if (on) Ui.WHITE else Ui.DIM, on).apply {
                background = if (on) ui.outlined(Ui.SEL, Ui.CYAN, 20, 1) else ui.rounded(Ui.CARD, 20)
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                setOnClickListener { mode = i; draw() }
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
        }
        form.removeAllViews(); result.removeAllViews()
        val v = ObdManager.vehicle
        val spec = v.spec.unit
        val dl = Units.distLabel
        val vl = Units.volLabel(spec)
        val ec = Units.km(defaultEcon()) / Units.vol(1.0, spec)   // distance-unit per volume-unit
        val price = v.fuelPrice * (1.0 / Units.vol(1.0, spec))      // price per displayed volume unit
        val ecL = "Economy ($dl/$vl)"
        val prL = "Fuel price (${Prefs.currency}/$vl)"
        fun f(v: Double) = "%.2f".format(Locale.US, v)
        val fields = ArrayList<Pair<String, android.widget.EditText>>()
        fun field(label: String, value: Double): android.widget.EditText {
            val et = ui.edit("", f(value), true)
            form.addView(ui.labeled(label, et), ui.lp(10)); fields += label to et; return et
        }
        when (mode) {
            0 -> { field("Distance ($dl)", 100.0); field(ecL, ec); field(prL, price) }
            1 -> { field("Fuel ($vl)", Units.vol(v.tankL, spec)); field(ecL, ec) }
            2 -> { field("Distance ($dl)", 100.0); field("Fuel used ($vl)", Units.vol(100.0 / defaultEcon(), spec)) }
            else -> { field("Distance ($dl)", 100.0); field(ecL, ec); field("Fuel in tank now ($vl)", 0.0) }
        }
        form.addView(ui.btn("Calculate", Ui.GOOD) {
            val x = fields.map { it.second.text.toString().toDoubleOrNull() ?: 0.0 }
            result.removeAllViews()
            fun row(ic: Ic, c: Int, t: String, b: String) = result.addView(ui.iconCard(ic, c, t, b), ui.lp(8))
            when (mode) {
                0 -> if (x[1] > 0) {
                    val fuel = x[0] / x[1]
                    row(Ic.CHART, Ui.GOOD, "%s%.2f".format(Prefs.currency, fuel * x[2]), "Total trip cost")
                    row(Ic.FUEL, Ui.ACC, "%.2f %s".format(Locale.US, fuel, vl), "Fuel needed")
                    row(Ic.ROUTE, Ui.CYAN, "%s%.2f / %s".format(Prefs.currency, fuel * x[2] / x[0].coerceAtLeast(0.0001), dl), "Cost per $dl")
                }
                1 -> if (x[0] > 0) {
                    row(Ic.ROUTE, Ui.GOOD, "%.0f %s".format(Locale.US, x[0] * x[1], dl), "Distance you can drive")
                }
                2 -> if (x[0] > 0 && x[1] > 0) {
                    val e = x[0] / x[1]
                    row(Ic.GAUGE, Ui.GOOD, "%.2f %s/%s".format(Locale.US, e, dl, vl), "Economy")
                    val per100 = x[1] / (x[0] / (if (Units.mi) 62.1371 else 100.0))
                    row(Ic.FUEL, Ui.ACC, "%.2f %s / %s".format(Locale.US, per100, vl, if (Units.mi) "100 mi" else "100 km"), "Consumption")
                }
                else -> if (x[1] > 0) {
                    val need = x[0] / x[1]
                    row(Ic.FUEL, Ui.GOOD, "%.2f %s".format(Locale.US, need, vl), "Fuel required")
                    val add = (need - x[2]).coerceAtLeast(0.0)
                    row(Ic.PLUS, Ui.ACC, "%.2f %s".format(Locale.US, add, vl), "To add")
                    row(Ic.CHART, Ui.CYAN, "%s%.2f".format(Prefs.currency, add * price), "Estimated cost to add")
                }
            }
            if (result.childCount == 0) result.addView(ui.card("Enter values greater than zero."))
        }, ui.lp(14))
        form.addView(ui.tv("Defaults come from your car's logged economy, fuel price and tank size.", 12f, Ui.DIM), ui.lp(8))
    }
}
