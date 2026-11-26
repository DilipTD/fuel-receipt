package com.dilip.carobd

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/** Collects findings for every simulation run into app/build/sim-report.md (one file, appended per test). */
object Report {
    val lines = ArrayList<String>()
    var problems = 0
    @Synchronized fun ok(s: String) { lines += "- ✅ $s" }
    @Synchronized fun info(s: String) { lines += "- ℹ️ $s" }
    @Synchronized fun bad(s: String, e: Throwable? = null) {
        problems++
        lines += "- ❌ $s" + (e?.let { "\n\n```\n" + stack(it) + "\n```\n" } ?: "")
    }
    @Synchronized fun head(s: String) { lines += "\n## $s\n" }
    fun stack(e: Throwable): String {
        var c: Throwable? = e; val sb = StringBuilder()
        var depth = 0
        while (c != null && depth < 3) {
            sb.append(c.javaClass.name).append(": ").append(c.message).append('\n')
            c.stackTrace.filter { it.className.startsWith("com.dilip") }.take(8).forEach { sb.append("    at ").append(it).append('\n') }
            c = c.cause; depth++
        }
        return sb.toString()
    }
    @Synchronized fun flush(name: String) {
        val f = File("build/sim-report.md")
        f.parentFile?.mkdirs()
        f.appendText("\n# $name\n" + lines.joinToString("\n") + "\n\nProblems in this run: $problems\n")
        lines.clear(); problems = 0
    }
}

abstract class SimBase {
    lateinit var ctl: ActivityController<MainActivity>
    val act: MainActivity get() = ctl.get()
    val workerErrors = ArrayList<Throwable>()

    fun idle(ms: Long = 50) {
        try { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
        catch (e: Throwable) { Report.bad("Main-thread exception while idling", e) }
    }

    fun prep() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.init(app)
        Prefs.consentVersion = LegalText.VERSION
        Prefs.autoConnect = false
        Prefs.roadAssist = false
        Thread.setDefaultUncaughtExceptionHandler { t, e -> synchronized(workerErrors) { workerErrors += e }; Report.bad("Uncaught on thread ${t.name}", e) }
    }

    fun launch() {
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle(2500)
    }

    fun relaunch() {
        try { ctl.pause().stop().destroy() } catch (_: Throwable) {}
        launch()
    }

    fun dismissDialogs() {
        try { ShadowDialog.getShownDialogs().toList().forEach { try { it.dismiss() } catch (_: Throwable) {} } } catch (_: Throwable) {}
        idle(20)
    }

    fun showPage(k: String): Boolean = try { act.show(k); idle(600); true } catch (e: Throwable) { Report.bad("Opening page '$k' crashed", e); false }

    fun allViews(v: View, out: MutableList<View> = ArrayList()): MutableList<View> {
        if (v.visibility != View.VISIBLE) return out
        out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) allViews(v.getChildAt(i), out)
        return out
    }

    fun label(v: View): String = when (v) {
        is TextView -> v.text.toString()
        is ViewGroup -> allViews(v).filterIsInstance<TextView>().joinToString(" ") { it.text.toString() }.take(60)
        else -> v.javaClass.simpleName
    }

    private val skip = Regex("(?i)delete|remove|clear all|erase|reset|restore|disconnect|wipe|exit|uninstall|clear data|factory")

    /** Clicks every clickable control on a page, filling and confirming any form dialog that opens. */
    fun monkey(k: String, maxClicks: Int = 45): Int {
        if (!showPage(k)) return 0
        val root = act.pages[k]!!.view
        val n = allViews(root).count { it.hasOnClickListeners() && it !is Spinner && it !is EditText }
        var clicked = 0
        for (i in 0 until minOf(n, maxClicks)) {
            if (act.current != k) { if (!showPage(k)) return clicked }
            val list = allViews(act.pages[k]!!.view).filter { it.hasOnClickListeners() && it !is Spinner && it !is EditText }
            val v = list.getOrNull(i) ?: break
            val lbl = label(v)
            if (skip.containsMatchIn(lbl)) continue
            try {
                v.performClick(); clicked++
                idle(250)
                handleDialog("$k → '$lbl'")
            } catch (e: Throwable) {
                Report.bad("Page '$k': tapping '$lbl' crashed", e)
            }
            dismissDialogs()
        }
        return clicked
    }

    private fun handleDialog(where: String) {
        val d = try { ShadowDialog.getLatestDialog() } catch (_: Throwable) { null } ?: return
        if (!d.isShowing) return
        val title = try { shadowOf(d as android.app.Dialog).title?.toString() } catch (_: Throwable) { null } ?: ""
        if (d is AlertDialog && !skip.containsMatchIn(title)) {
            val pos = d.getButton(DialogInterface.BUTTON_POSITIVE)
            if (pos != null && pos.visibility == View.VISIBLE && !skip.containsMatchIn(pos.text.toString())) {
                try { pos.performClick(); idle(300) } catch (e: Throwable) { Report.bad("$where: confirming dialog '$title' crashed", e) }
            }
        }
    }

    fun refreshAll(tag: String) {
        for (k in act.pages.keys.toList()) {
            if (!showPage(k)) continue
            try { repeat(3) { act.pages[k]!!.refresh(); idle(520) } } catch (e: Throwable) { Report.bad("[$tag] page '$k' refresh crashed", e) }
        }
    }

    fun setState(s: ObdManager.State) {
        val f = ObdManager::class.java.getDeclaredField("state"); f.isAccessible = true; f.set(ObdManager, s)
    }

    fun waitFor(maxMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(200); idle(200) }
        return cond()
    }

    fun disconnectAndWait() {
        ObdManager.disconnect()
        if (!waitFor(25_000) { ObdManager.state == ObdManager.State.DISCONNECTED }) Report.bad("OBD worker did not stop within 25 s after disconnect")
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class SimulationTest : SimBase() {

    private val petrolPids = setOf(0x01, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x1C, 0x1F, 0x20, 0x21, 0x2F,
        0x30, 0x31, 0x33, 0x3C, 0x40, 0x42, 0x46, 0x4E)

    /** 0-5 s idle, rapid acceleration to 105 (over-rev blip), over-speed cruise, sudden brake, cruise, hard brake, idle. */
    private fun drive(t: Double): EmuTransport.Car = when {
        t < 5 -> EmuTransport.Car(820.0, 0.0, coolant = 55.0)
        t < 12 -> { val s = (t - 5) * 15; EmuTransport.Car(if (t in 8.0..9.5) 6400.0 else 1500 + s * 30, s, throttle = 90.0, load = 85.0, maf = 40.0) }
        t < 24 -> EmuTransport.Car(2700.0, 108.0, throttle = 40.0, load = 50.0, maf = 22.0)
        t < 27 -> { val s = 108 - (t - 24) * 28; EmuTransport.Car(1800.0, s.coerceAtLeast(20.0), throttle = 0.0, load = 10.0, maf = 3.0) }
        t < 34 -> EmuTransport.Car(1600.0, 40.0, throttle = 18.0)
        t < 37 -> { val s = 40 - (t - 34) * 13.3; EmuTransport.Car(1000.0, s.coerceAtLeast(0.0), throttle = 0.0) }
        t < 48 -> EmuTransport.Car(800.0, 0.0)
        else -> EmuTransport.Car(null, 0.0, volt = 12.5)   // ignition off
    }

    @Test
    fun fullApplication() {
        prep()
        Report.head("1. Start-up and every page offline")
        try { launch(); Report.ok("App starts (${act.pages.size} pages registered)") } catch (e: Throwable) { Report.bad("App start-up crashed", e); Report.flush("Full application"); return }
        refreshAll("offline")
        Report.ok("All pages opened and refreshed offline")

        Report.head("2. Every button on every page (offline)")
        var total = 0
        for (k in act.pages.keys.toList()) total += monkey(k)
        Report.ok("Tapped $total controls across all pages")
        disconnectAndWait()

        Report.head("3. Live drive with emulated ELM327 car (petrol, CAN)")
        Prefs.distMethod = "ECU"; Prefs.voltMethod = "ADAPTER"; Prefs.speedCorrPct = 0.0
        val v = Db.activeVehicle(); v.fuel = "Petrol"; v.speedLimit = 90; v.rpmLimit = 6000; Db.saveVehicle(v); ObdManager.reloadVehicle()
        val tripsBefore = Db.trips(v.id).size
        lateinit var emu: EmuTransport
        ObdManager.connect(act) { EmuTransport(::drive, petrolPids).also { emu = it } }
        if (!waitFor(20_000) { ObdManager.state == ObdManager.State.CONNECTED }) {
            Report.bad("Did not connect to the emulator (status: ${ObdManager.status})")
            Report.info("Log: " + synchronized(emu.log) { emu.log.takeLast(25).joinToString(" ¦ ") })
        } else Report.ok("Connected: ELM ${ObdManager.elmVersion}, protocol '${ObdManager.protocol}', ${ObdManager.supported.size} PIDs, VIN '${ObdManager.vin}'")
        val keys = listOf("Home", "Dash", "Live", "HUD", "Car", "Health", "Battery", "Perf", "Trips", "Diag", "Alerts", "Analysis", "Road", "Nav")
        var ki = 0
        val start = System.currentTimeMillis()
        var maxSpd = 0.0; var sawTrip = false
        while (System.currentTimeMillis() - start < 46_000) {
            val k = keys[ki++ % keys.size]
            showPage(k)
            try { act.pages[k]!!.refresh() } catch (e: Throwable) { Report.bad("[live] '$k' refresh crashed", e) }
            maxSpd = maxOf(maxSpd, ObdManager.values[0x0D] ?: 0.0)
            if (ObdManager.engine.current != null) sawTrip = true
            Thread.sleep(1500)
        }
        val m = ObdManager
        if (sawTrip) Report.ok("Trip started automatically") else Report.bad("No trip was started while driving")
        if (maxSpd >= 100) Report.ok("Speed decoded (max %.0f km/h)".format(maxSpd)) else Report.bad("Max speed only %.0f km/h".format(maxSpd))
        val cur = m.engine.current
        if (cur != null) {
            Report.info("Live trip: %.2f km, fuel %.3f L, max %.0f km/h, max %.0f rpm, events: acc %d, brake %d, sudden %d, overspeed %d (%.0f s), over-rev %d, cold-rev %d".format(
                cur.distanceKm, cur.fuel, cur.maxSpeed, cur.maxRpm, cur.rapidAcc, cur.hardBrake, cur.suddenBrake, cur.overspeed, cur.overspeedS, cur.overRev, cur.coldRev))
            val expectKm = (7 * 52.5 + 12 * 108 + 3 * 66 + 7 * 40 + 3 * 20) / 3600.0
            if (cur.distanceKm in expectKm * 0.7..expectKm * 1.3) Report.ok("Distance %.2f km ≈ expected %.2f km".format(cur.distanceKm, expectKm))
            else Report.bad("Distance %.2f km, expected about %.2f km".format(cur.distanceKm, expectKm))
            if (cur.rapidAcc >= 1) Report.ok("Rapid acceleration detected") else Report.bad("Rapid acceleration (0→105 km/h in 7 s) was not detected")
            if (cur.suddenBrake + cur.hardBrake >= 1) Report.ok("Braking events detected (${cur.hardBrake} hard, ${cur.suddenBrake} sudden)") else Report.bad("No braking event for 108→24 km/h in 3 s")
            if (cur.overspeedS > 5) Report.ok("Over-speed time counted") else Report.bad("Over-speed above 90 km/h not counted")
            if (cur.overRev >= 1) Report.ok("Over-rev detected") else Report.bad("Over-rev (6400 rpm) not detected")
            if (cur.fuel > 0.005) Report.ok("Fuel use computed from MAF") else Report.bad("Fuel use is zero")
        }
        if (m.batteryV != null) Report.ok("Battery voltage %.1f V".format(m.batteryV)) else Report.bad("No battery voltage read")
        if (m.monitorRaw != null) Report.ok("Monitor status read") else Report.bad("PID 01 monitor status never read")
        val dtcs = Db.dtcHistory(v.id).map { it.code }.distinct()
        if (dtcs.containsAll(listOf("P0420", "P0301"))) Report.ok("Auto DTC scan stored ${dtcs.joinToString()}") else Report.bad("Auto DTC scan stored only $dtcs")
        val em = EmissionAi.analyse()
        Report.info("Emission AI: level ${em.level}, '${em.headline}', ${em.monitors.size} monitors")
        if (em.level == 2) Report.ok("Emission readiness reports 'not ready' while the MIL is on") else Report.bad("Emission readiness should be 'not ready' with MIL on, got level ${em.level}")
        val hs = HealthScore.compute()
        Report.info("Health score ${hs.score}: " + hs.items.joinToString { "${it.name}=${it.status}" })

        Report.head("4. Diagnostics with the car connected")
        showPage("Diag")
        monkey("Diag", 30)
        if (!waitFor(15_000) { !ObdManager.busy }) Report.bad("Diagnostic job still busy after 15 s")
        Report.info("Last diagnostic output: " + ObdManager.diagText.take(160).replace("\n", " ⏎ "))
        val clr = ObdManager.runDiag("Clear") { e -> if (e.clearDtcs()) "cleared" else "fail" }
        waitFor(8000) { !ObdManager.busy }
        if (ObdManager.diagText == "cleared") Report.ok("Clear codes accepted") else Report.bad("Clear codes returned '${ObdManager.diagText}'")
        ObdManager.runDiag("Readiness") { e -> Health.readiness(e.pid01(0x01)) }
        waitFor(8000) { !ObdManager.busy }
        val em2 = EmissionAi.analyse()
        if (em2.level == 1 && em2.incomplete >= 3) Report.ok("After clearing, readiness shows ${em2.incomplete} monitors pending") else Report.bad("After clearing expected pending monitors, got level ${em2.level} '${em2.headline}'")
        for (k in listOf("Dash", "Health", "Live", "Car", "HUD", "Battery", "Perf")) monkey(k, 25)

        Report.head("5. Ignition off and trip end")
        if (waitFor(40_000) { !ObdManager.engineOn }) Report.ok("Ignition-off detected (engine off)") else Report.bad("Engine still 'on' 40 s after the ECU stopped answering")
        waitFor(5000) { ObdManager.engine.current == null }
        val trips = Db.trips(v.id)
        if (trips.size > tripsBefore) {
            val t = trips.first()
            Report.ok("Trip saved: %.2f km, %d events, score %d".format(t.distanceKm, t.events, t.score))
            val ev = Db.events(t.id)
            Report.info("Saved events: " + ev.groupBy { it.type }.map { "${it.key}×${it.value.size}" }.joinToString())
            val track = Db.track(t.id)
            if (track.size > 5) Report.ok("${track.size} track points saved") else Report.bad("Only ${track.size} track points saved")
            act.selectedTripId = t.id
            for (k in listOf("TripDetail", "TripReplay")) { monkey(k, 20); try { act.pages[k]!!.refresh() } catch (e: Throwable) { Report.bad("'$k' refresh crashed", e) } }
        } else Report.bad("No trip saved after the drive")
        disconnectAndWait()
        Report.ok("Disconnected cleanly")

        Report.head("6. Reports")
        val rs = listOf<Pair<String, () -> String?>>(
            "Seller's" to { Reports.seller(act) },
            "Custom" to { Reports.custom(act, Reports.Custom(0, Long.MAX_VALUE, true, true, true, true, true, true, "All time")) },
            "Cost of ownership" to { Prefs.purchasePrice = 900000.0; Prefs.currentValue = 700000.0; Reports.cost(act) },
            "Trip CSV" to { Reports.tripCsv(act, 0, Long.MAX_VALUE) },
            "Trip PDF" to { Reports.tripLogPdf(act, 0, Long.MAX_VALUE, Reports.tripCols.take(7), "All time") },
            "Annual" to { Reports.annual(act, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) },
            "Fuel efficiency" to { Reports.fuelEfficiency(act) },
            "Maintenance" to { Reports.maintenance(act) })
        for ((n, f) in rs) try { val p = f(); if (p != null) Report.ok("$n report → $p") else Report.info("$n report returned no path (file storage not available in the simulator)") }
            catch (e: Throwable) { Report.bad("$n report crashed", e) }

        Report.head("7. Units, Hindi, light theme, 12-hour clock")
        Prefs.unitDist = "mi"; Prefs.unitTemp = "°F"; Prefs.unitPress = "psi"; Prefs.unitTorque = "lb-ft"; Prefs.unitFuel = "Gallon (US)"
        Prefs.economyUnit = "MPG (US)"; Prefs.currency = "$"; Prefs.time24 = false; Prefs.language = "hi"; Prefs.theme = "light"
        try { relaunch(); Report.ok("Restarted in light theme / Hindi") } catch (e: Throwable) { Report.bad("Restart with new display settings crashed", e) }
        if (Units.econ(15.0) == "35.3 MPG") Report.ok("15 km/L = ${Units.econ(15.0)}") else Report.bad("15 km/L shown as ${Units.econ(15.0)}, expected 35.3 MPG")
        if (Units.spd(100.0).toInt() == 62) Report.ok("100 km/h = 62 mph") else Report.bad("100 km/h -> ${Units.spd(100.0)}")
        refreshAll("imperial/hi/light")
        for (k in act.pages.keys.toList()) monkey(k, 20)
        Prefs.unitDist = "km"; Prefs.unitTemp = "°C"; Prefs.unitPress = "kPa"; Prefs.unitTorque = "Nm"; Prefs.unitFuel = "Litre (L)"
        Prefs.economyUnit = "km/L"; Prefs.currency = "₹"; Prefs.time24 = true; Prefs.language = "en"; Prefs.theme = "dark"
        relaunch()

        Report.head("8. Fuel types")
        for (fuel in Fuels.all.map { it.name }) {
            val vv = Db.activeVehicle(); vv.fuel = fuel; Db.saveVehicle(vv); ObdManager.reloadVehicle()
            try {
                for (k in listOf("Home", "Dash", "Health", "Garage", "Calc", "Fuel", "Battery", "Car", "HUD", "Maint")) { showPage(k); act.pages[k]!!.refresh(); idle(300) }
                val d = Maint.defaultsFor(fuel).map { it.first }
                val txt = EmissionAi.text()
                Report.ok("$fuel: pages OK, ${d.size} service items, emission: ${EmissionAi.analyse().headline}")
                if (fuel == "Diesel" && d.any { it.startsWith("Spark") }) Report.bad("Diesel schedule contains spark plugs")
                if (fuel.startsWith("Electric") && d.any { it.startsWith("Engine oil") }) Report.bad("EV schedule contains engine oil")
                if (txt.isBlank()) Report.bad("$fuel: empty emission text")
            } catch (e: Throwable) { Report.bad("$fuel: crashed", e) }
        }
        val vv = Db.activeVehicle(); vv.fuel = "Petrol"; Db.saveVehicle(vv); ObdManager.reloadVehicle()
        Report.flush("Full application (phone portrait)")
    }

    @Test
    fun electricCarDrive() {
        prep()
        launch()
        Report.head("EV drive (no RPM PID)")
        val v = Db.activeVehicle(); v.fuel = "Electric (EV)"; v.tankL = 40.0; Db.saveVehicle(v); ObdManager.reloadVehicle()
        val pids = setOf(0x01, 0x0D, 0x20, 0x2F, 0x40, 0x42, 0x46)
        ObdManager.connect(act) { EmuTransport({ t -> EmuTransport.Car(null, if (t < 8) 0.7 else if (t < 20) 40.0 else 0.0, fuelPct = 80 - t / 10) }, pids, monitor = intArrayOf(0, 0, 0, 0)) }
        val ok = waitFor(20_000) { ObdManager.state == ObdManager.State.CONNECTED }
        if (ok) Report.ok("EV connected") else Report.bad("EV did not connect: ${ObdManager.status}")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 25_000) {
            for (k in listOf("Home", "Dash", "Car", "Battery", "Health")) { showPage(k); try { act.pages[k]!!.refresh() } catch (e: Throwable) { Report.bad("EV page $k crashed", e) } }
            Thread.sleep(800)
        }
        if (ObdManager.state == ObdManager.State.CONNECTED && ObdManager.status.startsWith("Connected")) Report.ok("EV link stayed up while creeping at 0.7 km/h")
        else Report.bad("EV link dropped: ${ObdManager.status}")
        if (workerErrors.isEmpty()) Report.ok("No worker-thread crashes")
        disconnectAndWait()
        Report.flush("Electric car")
    }
}

/** Landscape layout checks for Drive mode and HUD on common phone sizes. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w740dp-h360dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class DriveModeLayoutTest : SimBase() {
    @Test fun layout() = check("740×360 dp landscape")
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h320dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class DriveModeSmallLayoutTest : SimBase() {
    @Test fun layout() = check("640×320 dp landscape (small phone)")
}

fun SimBase.check(name: String) {
    prep(); launch()
    Report.head("Drive mode layout – $name")
    setState(ObdManager.State.CONNECTED)
    ObdManager.values[0x0C] = 2500.0; ObdManager.values[0x0D] = 88.0; ObdManager.values[0x05] = 90.0; ObdManager.values[0x2F] = 55.0
    ObdManager.values[0x04] = 40.0; ObdManager.values[0x0F] = 33.0
    for (k in listOf("Car", "HUD")) {
        showPage(k); act.pages[k]!!.refresh(); idle(800)
        val root = act.window.decorView
        val h = root.height; val w = root.width
        val dens = act.resources.displayMetrics.density
        val vs = allViews(act.pages[k]!!.view).filter { it.width > 0 }
        val clipped = vs.filter { v -> val p = IntArray(2); v.getLocationInWindow(p); (p[1] + v.height > h + 2 || p[0] + v.width > w + 2) && v !is ViewGroup }
        val text = vs.filterIsInstance<TextView>().filter { it.height in 1..((it.textSize * 0.9).toInt()) }
        val gauges = vs.filter { it is GaugeView || it is RingGauge }
        Report.info("$k: window ${(w / dens).toInt()}×${(h / dens).toInt()} dp; gauges: " +
            gauges.joinToString { "${it.javaClass.simpleName} ${(it.width / dens).toInt()}×${(it.height / dens).toInt()}dp" })
        val small = gauges.filter { it.height / dens < 90 }
        if (k == "Car" && small.isNotEmpty()) Report.bad("$k: ${small.size} gauge(s) squeezed under 90 dp tall") else if (k == "Car") Report.ok("$k: gauges have room")
        if (clipped.isEmpty()) Report.ok("$k: nothing drawn off-screen") else Report.bad("$k: ${clipped.size} view(s) cut off the screen: " + clipped.take(6).joinToString { label(it).ifBlank { it.javaClass.simpleName } })
        if (text.isNotEmpty()) Report.bad("$k: ${text.size} text(s) squashed vertically: " + text.take(5).joinToString { it.text.toString() })
        val st = act.window.decorView.systemUiVisibility
        if (st and View.SYSTEM_UI_FLAG_FULLSCREEN != 0 && st and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0) Report.ok("$k: full-screen flags set") else Report.bad("$k: not full screen")
    }
    act.show("Home"); idle(300)
    if (act.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) Report.ok("Leaving Drive mode restores the status bar") else Report.bad("Status bar still hidden after leaving Drive mode")
    setState(ObdManager.State.DISCONNECTED)
    Report.flush("Layout $name")
}
