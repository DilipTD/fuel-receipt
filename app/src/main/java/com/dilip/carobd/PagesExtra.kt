package com.dilip.carobd

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/* ============================ Performance meter ============================ */

/**
 * Drag-strip style timing from OBD speed samples (fast-poll mode, ~5–10 samples/s).
 * Crossing times are linearly interpolated between samples; distance is integrated.
 */
object PerfMeter {
    enum class Mode { IDLE, ARMED_LAUNCH, RUN_LAUNCH, ARMED_BRAKE, RUN_BRAKE }

    @Volatile var mode = Mode.IDLE
        private set
    @Volatile var status = "Choose a test below."
        private set
    @Volatile var liveT = 0.0
        private set
    @Volatile var liveDist = 0.0
        private set
    val splits = LinkedHashMap<String, Double>()
    @Volatile var trap = 0.0
        private set
    private var ready = false
    private var t0 = 0L
    private var lastT = 0L
    private var lastV = 0.0
    private var dist = 0.0

    @Synchronized
    fun armLaunch() {
        splits.clear(); trap = 0.0; liveT = 0.0; liveDist = 0.0; ready = false
        mode = Mode.ARMED_LAUNCH
        status = "Stop the car completely. The timer starts the moment you move."
        ObdManager.perfMode = true
    }

    @Synchronized
    fun armBrake() {
        splits.clear(); trap = 0.0; liveT = 0.0; liveDist = 0.0
        mode = Mode.ARMED_BRAKE
        status = "Drive above 100 km/h (only where safe and legal), then brake firmly to a stop."
        ObdManager.perfMode = true
    }

    @Synchronized
    fun cancel() {
        mode = Mode.IDLE
        status = "Cancelled."
        ObdManager.perfMode = false
    }

    private fun cross(target: Double, v: Double, t: Long): Long =
        if (v - lastV < 1e-6) t else lastT + ((target - lastV) / (v - lastV) * (t - lastT)).toLong()

    @Synchronized
    fun onSpeed(t: Long, v: Double) {
        val dt = if (lastT > 0) (t - lastT) / 1000.0 else 0.0
        when (mode) {
            Mode.IDLE -> {}
            Mode.ARMED_LAUNCH -> {
                if (v < 0.5) {
                    ready = true; status = "READY – launch when safe!"
                    t0 = t
                } else if (ready) {
                    mode = Mode.RUN_LAUNCH
                    dist = (v / 2) / 3.6 * dt
                    status = "Running…"
                } else status = "Come to a complete stop first (now %.0f km/h).".format(v)
            }
            Mode.RUN_LAUNCH -> {
                if (dt in 0.0..2.0) {
                    val prevDist = dist
                    dist += (lastV + v) / 2 / 3.6 * dt
                    for (target in listOf(60.0, 100.0)) {
                        val key = "0-%.0f km/h".format(target)
                        if (lastV < target && v >= target && key !in splits) splits[key] = (cross(target, v, t) - t0) / 1000.0
                    }
                    if (prevDist < 402.3 && dist >= 402.3 && "1/4 mile" !in splits) {
                        val f = (402.3 - prevDist) / (dist - prevDist)
                        splits["1/4 mile"] = (lastT + f * (t - lastT) - t0) / 1000.0
                        trap = lastV + f * (v - lastV)
                    }
                    val s60 = splits["0-60 km/h"]
                    val s100 = splits["0-100 km/h"]
                    if (s60 != null && s100 != null && "60-100 km/h" !in splits) splits["60-100 km/h"] = s100 - s60
                }
                liveT = (t - t0) / 1000.0
                liveDist = dist
                val done = splits.containsKey("1/4 mile") && splits.containsKey("0-100 km/h")
                if (done || v < lastV - 4 || liveT > 60) finish("Launch")
            }
            Mode.ARMED_BRAKE -> {
                status = if (v >= 100) "Above 100 km/h – brake firmly when safe." else "Speed %.0f km/h – get above 100 km/h.".format(v)
                if (lastV >= 100 && v < 100) {
                    mode = Mode.RUN_BRAKE
                    t0 = cross(100.0, v, t)
                    dist = 0.0
                }
            }
            Mode.RUN_BRAKE -> {
                if (dt in 0.0..2.0) dist += (lastV + v) / 2 / 3.6 * dt
                liveT = (t - t0) / 1000.0
                liveDist = dist
                if (v <= 0.5) {
                    splits["100-0 km/h"] = liveT
                    splits["Braking distance m"] = dist
                    finish("Braking")
                } else if (v > lastV + 3 || liveT > 20) {
                    status = "Braking run aborted (speed rose). Try again."
                    mode = Mode.IDLE; ObdManager.perfMode = false
                }
            }
        }
        lastT = t
        lastV = v
    }

    private fun finish(type: String) {
        mode = Mode.IDLE
        ObdManager.perfMode = false
        if (splits.isEmpty()) {
            status = "Run ended – no split reached. Try again."; return
        }
        status = "$type run finished."
        try {
            val o = JSONObject().put("t", System.currentTimeMillis()).put("type", type)
                .put("vid", ObdManager.vehicle.id).put("trap", trap)
            val sp = JSONObject()
            splits.forEach { (k, v) -> sp.put(k, v) }
            o.put("splits", sp)
            val a = JSONArray(Prefs.perfRuns)
            a.put(o)
            val out = JSONArray()
            for (i in maxOf(0, a.length() - 50) until a.length()) out.put(a.get(i))
            Prefs.perfRuns = out.toString()
        } catch (_: Exception) {
        }
        splits["0-100 km/h"]?.let { Alerts.say("perf", "Zero to one hundred in %.1f seconds".format(it), 5000) }
        splits["100-0 km/h"]?.let { Alerts.say("perf", "Stopped from one hundred in %.0f metres".format(splits["Braking distance m"] ?: 0.0), 5000) }
    }
}

@SuppressLint("SetTextI18n")
class PerformancePage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var timer: TextView
    private lateinit var speed: TextView
    private lateinit var statusTv: TextView
    private lateinit var splitsTv: TextView
    private lateinit var histBox: LinearLayout

    override fun build(): View {
        val c = ui.col()
        c.addView(ui.section("⏱ Performance meter"))
        timer = ui.tv("0.00 s", 54f, Color.parseColor("#00E5FF"), true).apply {
            gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        speed = ui.tv("-- km/h", 20f, Ui.WHITE, true).apply { gravity = Gravity.CENTER }
        val panel = ui.vbox().apply {
            background = ui.rounded(Color.parseColor("#0E1419"), 14)
            setPadding(ui.dp(10), ui.dp(16), ui.dp(10), ui.dp(16))
            addView(timer); addView(speed)
        }
        c.addView(panel, ui.lp())
        statusTv = ui.card("", 14f)
        c.addView(statusTv, ui.lp())
        c.addView(ui.row(
            ui.btn("Launch 0-100 / ¼ mile", Ui.GOOD) { PerfMeter.armLaunch() },
            ui.btn("Braking 100-0", Color.parseColor("#C25E00")) { PerfMeter.armBrake() }
        ), ui.lp(10))
        c.addView(ui.btn("Cancel", Ui.CARD) { PerfMeter.cancel() }, ui.lp(6))
        splitsTv = ui.mono("")
        c.addView(splitsTv, ui.lp(10))
        c.addView(ui.section("Results history"))
        histBox = ui.vbox()
        c.addView(histBox)
        c.addView(ui.btn("Clear history", Ui.BAD) {
            AlertDialog.Builder(a).setTitle("Delete all performance runs?")
                .setPositiveButton("Delete") { _, _ -> Prefs.perfRuns = "[]"; onShow() }
                .setNegativeButton("Cancel", null).show()
        }, ui.lp(10))
        c.addView(ui.tv("⚠ Test only on a closed road or track where it is safe and legal. Timing uses OBD speed " +
                "(1 km/h steps, fast-poll mode) – accuracy about ±0.2 s. Many speedometers read 3–6 % high.", 12f, Ui.DIM), ui.lp())
        return ui.scroll(c)
    }

    override fun onShow() {
        histBox.removeAllViews()
        val vid = ObdManager.vehicle.id
        val runs = try {
            val a = JSONArray(Prefs.perfRuns)
            (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optLong("vid") == vid }.reversed()
        } catch (_: Exception) { emptyList() }
        if (runs.isEmpty()) {
            histBox.addView(ui.tv("No runs yet for ${ObdManager.vehicle.name}.", 13f, Ui.DIM)); return
        }
        val best = HashMap<String, Double>()
        for (r in runs) {
            val sp = r.optJSONObject("splits") ?: continue
            sp.keys().forEach { k ->
                val v = sp.getDouble(k)
                val better = if (k.contains("distance")) v < (best[k] ?: 1e9) else v < (best[k] ?: 1e9)
                if (better) best[k] = v
            }
        }
        histBox.addView(ui.card("🏆 Best: " + best.entries.joinToString("   ") {
            if (it.key.contains("distance")) "${it.key.replace(" m", "")} %.1f m".format(it.value) else "${it.key} %.2f s".format(it.value)
        }, 13f, Ui.WHITE, Color.parseColor("#1F3A24")), ui.lp(4))
        for (r in runs.take(15)) {
            val sp = r.optJSONObject("splits") ?: continue
            val parts = mutableListOf<String>()
            sp.keys().forEach { k ->
                val v = sp.getDouble(k)
                parts += if (k.contains("distance")) "stop %.1f m".format(v) else "$k %.2f s".format(v)
            }
            val trap = r.optDouble("trap", 0.0)
            histBox.addView(ui.card("${ui.date(r.optLong("t"))} · ${r.optString("type")}\n" + parts.joinToString(" · ") +
                    (if (trap > 0) " · trap %.0f km/h".format(trap) else ""), 12f), ui.lp(4))
        }
    }

    private var wasRunning = false

    override fun refresh() {
        val pm = PerfMeter
        val running = pm.mode != PerfMeter.Mode.IDLE
        timer.text = "%.2f s".format(pm.liveT)
        timer.setTextColor(when (pm.mode) {
            PerfMeter.Mode.RUN_LAUNCH, PerfMeter.Mode.RUN_BRAKE -> Color.parseColor("#FFEA00")
            PerfMeter.Mode.ARMED_LAUNCH, PerfMeter.Mode.ARMED_BRAKE -> Color.parseColor("#00E676")
            else -> Color.parseColor("#00E5FF")
        })
        speed.text = "${ui.f(ObdManager.values[0x0D], 0, " km/h")} · ${ui.f(pm.liveDist, 0, " m")}"
        statusTv.text = if (ObdManager.state != ObdManager.State.CONNECTED) "Connect to the car first." else pm.status
        splitsTv.text = if (pm.splits.isEmpty()) "Splits appear here: 0-60, 0-100, 60-100, ¼ mile, 100-0." else
            pm.splits.entries.joinToString("\n") {
                if (it.key.contains("distance")) "%-20s %7.1f m".format(it.key, it.value) else "%-20s %7.2f s".format(it.key, it.value)
            } + if (pm.trap > 0) "\n%-20s %7.0f km/h".format("Trap speed", pm.trap) else ""
        if (wasRunning && !running) onShow()
        wasRunning = running
    }
}

/* ============================ Battery monitor ============================ */

@SuppressLint("SetTextI18n")
class BatteryPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var ring: RingGauge
    private lateinit var stateTv: TextView
    private lateinit var chart: LineChart
    private lateinit var crankTv: TextView

    override fun build(): View {
        val c = ui.col()
        c.addView(ui.section("🔋 Battery & charging"))
        ring = RingGauge(a, "Battery", "volts", 10f, 16f, 2) { v ->
            if (v < 11.8f || v > Prefs.chargeMaxV) 2 else if (v < 12.3f) 1 else 0
        }
        stateTv = ui.tv("", 15f, Ui.WHITE, true)
        val top = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ring, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(stateTv, LinearLayout.LayoutParams(0, Ui.WRAP, 1.4f).apply { leftMargin = ui.dp(12) })
        }
        c.addView(top, ui.lp())
        chart = LineChart(a, 170)
        c.addView(chart, ui.lp(10))
        crankTv = ui.mono("")
        c.addView(crankTv, ui.lp(10))
        c.addView(ui.tv("How cranking is measured: while the ignition is off or the engine is not yet running, the app samples the " +
                "adapter voltage several times per second and records the lowest value when the engine starts. Lead-acid: " +
                "healthy ≥ 9.6–10 V. LFP starter batteries normally stay above 11.5 V. Rest voltage for LFP is ≈13.2–13.4 V, " +
                "so set the parked-low and charging thresholds for your battery type in Settings.", 12f, Ui.DIM), ui.lp())
        return ui.scroll(c)
    }

    private fun describe(v: Double): String {
        val m = ObdManager
        val running = m.engineOn && (m.values[0x0C] ?: 0.0) > 450
        return if (running) when {
            v > Prefs.chargeMaxV -> "⛔ Over-voltage\nRegulator fault?"
            v >= 13.5 -> "✅ Charging normally"
            v >= Prefs.chargeMinV -> "⚠ Low charging\n(smart alternator may be in eco phase)"
            else -> "⛔ Not charging\nCheck alternator / belt"
        } else when {
            v >= 13.0 -> "Engine off\nSurface charge or LFP battery"
            v >= 12.6 -> "Engine off\n≈ 100 % (lead-acid)"
            v >= 12.4 -> "Engine off\n≈ 75 %"
            v >= 12.2 -> "Engine off\n≈ 50 % – charge soon"
            v >= 12.0 -> "Engine off\n≈ 25 % – low"
            else -> "⛔ Discharged\nCharge the battery"
        }
    }

    override fun onShow() {
        val sb = StringBuilder()
        ObdManager.lastCrank?.let { (mn, rest) -> sb.append("Last start: %.2f V (rested %.2f V, dip %.2f V)\n\n".format(mn, rest, rest - mn)) }
        try {
            val a = JSONArray(Prefs.crankHistory)
            val vid = ObdManager.vehicle.id
            val list = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optLong("vid", vid) == vid }.reversed()
            if (list.isEmpty()) sb.append("No cranking measurements yet.\nConnect with the ignition OFF, then start the engine.")
            else {
                sb.append("Cranking history (lowest voltage while starting):\n")
                for (o in list.take(15)) {
                    val mn = o.getDouble("min")
                    val flag = if (mn < Prefs.crankLowV) "  ⚠ weak" else ""
                    sb.append("%s   %.2f V  (rest %.2f)%s\n".format(ui.date(o.getLong("t"), "dd MMM HH:mm"), mn, o.getDouble("rest"), flag))
                }
            }
        } catch (_: Exception) {
        }
        crankTv.text = sb.toString().trimEnd()
    }

    override fun refresh() {
        val v = ObdManager.batteryV
        ring.value = v?.toFloat()
        stateTv.text = v?.let { describe(it) } ?: "No reading – connect the adapter."
        chart.set(ObdManager.battHist.values(), "Battery voltage", " V", Color.parseColor("#FFD54F"))
    }
}

/* ============================ Alerts ============================ */

class AlertsPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout
    private var filter = 0

    override fun build(): View {
        box = ui.col()
        return ui.scroll(box)
    }

    override fun onShow() {
        box.removeAllViews()
        box.addView(ui.section("🔔 Alert centre"))
        box.addView(ui.row(*listOf(0 to "All", 1 to "Warnings+", 2 to "Critical").map { (f, l) ->
            ui.btn(l, if (f == filter) Ui.ACC else Ui.CARD) { filter = f; onShow() }
        }.toTypedArray()), ui.lp(4))
        val list = Db.alerts().filter { it.level >= filter }
        if (list.isEmpty()) box.addView(ui.tv("No alerts. 👍", 14f, Ui.DIM), ui.lp(10))
        for (al in list.take(200)) {
            val row = LinearLayout(a).apply {
                background = ui.rounded(Ui.CARD, 8)
                val strip = View(a).apply { background = ui.rounded(AlertCenter.levelColor(al.level), 4) }
                addView(strip, LinearLayout.LayoutParams(ui.dp(5), Ui.MATCH))
                val txt = ui.vbox().apply {
                    setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                    addView(ui.tv("${AlertCenter.levelIcon(al.level)} ${al.title}", 14f, Ui.WHITE, true))
                    addView(ui.tv(al.text, 12f, Ui.WHITE))
                    addView(ui.tv(ui.date(al.t), 11f, Ui.DIM))
                }
                addView(txt, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            }
            box.addView(row, ui.lp(6))
        }
        box.addView(ui.row(
            ui.btn("Test notification", Ui.CARD) {
                AlertCenter.raise(a, "test${System.currentTimeMillis()}", AlertCenter.WARN, "Test alert",
                    "Notifications are working. ${AppInfo.credit}", 0L, "This is a test alert")
                onShow()
            },
            ui.btn("Clear all", Ui.BAD) { Db.clearAlerts(); onShow() }
        ), ui.lp(14))
        box.addView(ui.tv("Thresholds and notification options: More → Settings → Alerts.", 12f, Ui.DIM), ui.lp())
    }
}

/* ============================ About ============================ */

class AboutPage(a: MainActivity) : Page(a) {
    override val parent = "More"

    override fun build(): View {
        val c = ui.col()
        val logo = LogoView(a)
        c.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(ui.dp(130), ui.dp(130)))
        }, ui.lp(16))
        c.addView(ui.tv(AppInfo.NAME, 26f, Ui.WHITE, true).apply { gravity = Gravity.CENTER }, ui.lp(10))
        c.addView(ui.tv("Version ${AppInfo.VERSION} (build ${AppInfo.VERSION_CODE}) · ${AppInfo.BUILD_DATE}", 14f, Ui.DIM).apply {
            gravity = Gravity.CENTER
        }, ui.lp(2))
        c.addView(ui.tv("Software developed by", 13f, Ui.DIM).apply { gravity = Gravity.CENTER }, ui.lp(16))
        c.addView(ui.tv(AppInfo.DEVELOPER, 22f, Color.parseColor("#00E5FF"), true).apply { gravity = Gravity.CENTER }, ui.lp(2))
        c.addView(ui.card("Universal ELM327 OBD-II app for any car (petrol ~2001+, diesel ~2004+, all CAN cars). " +
                "Bluetooth Classic, BLE 4.0 and WiFi adapters. Live gauges, trip computer, mileage, maintenance, " +
                "driving-behaviour analysis, alerts, performance and battery tests, fault codes with UDS deep scan.", 13f), ui.lp(16))
        c.addView(ui.section("What's new"))
        c.addView(ui.mono(AppInfo.changelog, 12f), ui.lp())
        c.addView(ui.btn("Share app info", Ui.CARD) {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${AppInfo.credit}\nUniversal ELM327 OBD-II car diagnostics & trip logger.")
            }
            a.startActivity(Intent.createChooser(i, "Share"))
        }, ui.lp(12))
        c.addView(ui.tv("Do not operate the app while driving. Diagnostic information is a guide only – " +
                "confirm faults with a qualified mechanic.\n© 2026 ${AppInfo.DEVELOPER}. All rights reserved.", 11f, Ui.DIM).apply {
            gravity = Gravity.CENTER
        }, ui.lp(14))
        return ui.scroll(c)
    }
}

/** Opens the saved parking position in the phone's maps app. */
fun Page.openParking() {
    val lat = Prefs.parkLat
    val lon = Prefs.parkLon
    if (Prefs.parkT == 0L || (lat == 0.0 && lon == 0.0)) {
        ui.toast("No parking location yet – it is saved automatically when a trip ends (GPS must be on)."); return
    }
    try {
        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(My car)")))
    } catch (_: Exception) {
        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lon")))
    }
}
