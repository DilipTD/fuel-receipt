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
        PdfBuilder.testCanvas = { android.graphics.Canvas(android.graphics.Bitmap.createBitmap(595, 842, android.graphics.Bitmap.Config.ARGB_8888)) }
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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
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
        else -> EmuTransport.Car(1800.0, 50.0, throttle = 15.0)   // steady cruise until the test switches the ignition off
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
        val keys = listOf("Home", "Dash", "Live", "HUD", "Car", "Health", "Battery", "Perf", "Trips", "Diag", "Alerts", "Analysis", "Road")
        var ki = 0
        val start = System.currentTimeMillis()
        var maxSpd = 0.0; var sawTrip = false; var runV = 0.0
        while (System.currentTimeMillis() - start < 46_000) {
            val k = keys[ki++ % keys.size]
            showPage(k)
            try { act.pages[k]!!.refresh() } catch (e: Throwable) { Report.bad("[live] '$k' refresh crashed", e) }
            maxSpd = maxOf(maxSpd, ObdManager.values[0x0D] ?: 0.0)
            ObdManager.batteryV?.let { runV = maxOf(runV, it) }
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
        if (runV in 13.8..14.4) Report.ok("Charging voltage read from adapter while driving: %.1f V".format(runV)) else Report.bad("Charging voltage while driving was %.2f V (expected 14.1)".format(runV))
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
        ObdManager.runDiag("Clear") { e -> if (e.clearDtcs()) "cleared" else "fail" }
        waitFor(8000) { !ObdManager.busy }
        if (ObdManager.diagText == "cleared") Report.ok("Clear codes accepted") else Report.bad("Clear codes returned '${ObdManager.diagText}'")
        ObdManager.runDiag("Readiness") { e -> Health.readiness(e.pid01(0x01)) }
        waitFor(8000) { !ObdManager.busy }
        val em2 = EmissionAi.analyse()
        if (em2.level == 1 && em2.incomplete >= 3) Report.ok("After clearing, readiness shows ${em2.incomplete} monitors pending") else Report.bad("After clearing expected pending monitors, got level ${em2.level} '${em2.headline}'")
        ObdManager.runDiag("Full AI scan") { e -> DiagSuite.run(e, DiagSuite.tests.map { it.id }) { }; "scan done" }
        if (!waitFor(60_000) { !ObdManager.busy }) Report.bad("Full AI scan still busy after 60 s")
        val ds = DiagSuite.results
        if (ds.size == DiagSuite.tests.size) Report.ok("Full AI scan produced ${ds.size} test results") else Report.bad("Full AI scan produced ${ds.size} results")
        Report.info("AI scan: " + ds.joinToString { "${it.id}=${it.status}" })
        val sm = DiagSuite.summarize()
        if (sm.verdict.isNotBlank() && DiagSuite.toText().contains("VERDICT")) Report.ok("AI summary: ${sm.verdict}; ${sm.actions.size} action(s), ${sm.links.size} link(s)") else Report.bad("AI summary empty")
        monkey("Diag", 20)
        if (Reports.diagnosis(act) != null) Report.ok("AI diagnostic PDF created") else Report.bad("AI diagnostic PDF not created")
        for (k in listOf("Dash", "Health", "Live", "Car", "HUD", "Battery", "Perf")) monkey(k, 25)

        Report.head("4b. Corrections while cruising at 50 km/h")
        Prefs.speedCorrPct = 10.0
        waitFor(4000) { (ObdManager.values[0x0D] ?: 0.0) > 54 }
        val cs = ObdManager.values[0x0D] ?: 0.0
        if (cs in 54.5..55.5) Report.ok("Speed correction +10 %%: 50 → %.1f km/h".format(cs)) else Report.bad("Speed correction +10 %% gave %.1f km/h (expected 55)".format(cs))
        Prefs.speedCorrPct = 0.0
        Prefs.voltMethod = "ECU"
        Thread.sleep(9000); idle(100)
        val ev = ObdManager.batteryV ?: 0.0
        if (ev in 13.9..14.0) Report.ok("Voltage source ECU (PID 42): %.2f V".format(ev)) else Report.bad("Voltage source ECU gave %.2f V (expected 13.98 from PID 42)".format(ev))
        Prefs.voltMethod = "ADAPTER"
        emu.ignition = false

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
        Report.head("9. Car health is remembered after a restart")
        Prefs.healthSnap = ""
        setState(ObdManager.State.CONNECTED); ObdManager.engineOn = true
        ObdManager.values[0x05] = 112.0; ObdManager.values[0x2F] = 7.0; ObdManager.values[0x0F] = 40.0; ObdManager.batteryV = 12.0
        val live = HealthScore.compute()
        Report.info("Live: score ${live.score}, " + live.items.joinToString { "${it.name}=${it.status}" })
        setState(ObdManager.State.DISCONNECTED); ObdManager.engineOn = false
        ObdManager.values.clear(); ObdManager.batteryV = null; ObdManager.monitorRaw = null
        relaunch()
        val hs2 = HealthScore.compute()
        val kept = hs2.items.filter { it.detail.endsWith("last reading") }
        if (kept.any { it.name == "Cooling" && it.status == 2 } && hs2.score == live.score) Report.ok("Restored after restart: " + kept.joinToString { "${it.name} (${it.detail})" } + ", score ${hs2.score}")
        else Report.bad("Health after restart differs: score ${hs2.score} vs ${live.score}; " + hs2.items.joinToString { "${it.name}=${it.status}" })
        showPage("Health"); monkey("Health", 10)
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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class DriveModeLayoutTest : SimBase() {
    @Test fun layout() = check("740×360 dp landscape")
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class HudPortraitLayoutTest : SimBase() {
    @Test fun layout() = check("411×891 dp portrait")
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h320dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class DriveModeSmallLayoutTest : SimBase() {
    @Test fun layout() = check("640×320 dp landscape (small phone)")
}

fun SimBase.check(name: String) {
    prep(); launch()
    Report.head("Drive mode layout – $name")
    setState(ObdManager.State.CONNECTED)
    ObdManager.values[0x0C] = 2500.0; ObdManager.values[0x0D] = 88.0; ObdManager.values[0x05] = 90.0; ObdManager.values[0x2F] = 55.0
    ObdManager.values[0x04] = 40.0; ObdManager.values[0x0F] = 33.0
    for (k in if (name.contains("portrait")) listOf("HUD") else listOf("Car", "HUD")) {
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


/** Road AI: heading-aware road matching, saved maps, limit correction and own hazards. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RoadAiTest : SimBase() {
    private fun fix(la: Double, lo: Double, bearing: Float, kmh: Double) = android.location.Location("gps").apply {
        latitude = la; longitude = lo; this.bearing = bearing; speed = (kmh / 3.6).toFloat(); accuracy = 5f; time = System.currentTimeMillis()
    }

    @Test
    fun roadAi() {
        prep()
        launch()
        Report.head("Road AI")
        Prefs.roadAssist = true
        val t = RoadAssist.Tile(0.5010, -30.0000, 1500.0, System.currentTimeMillis(), listOf(
            RoadAssist.Way(1, "Main Road", "", "primary", 60, false, doubleArrayOf(0.5000, -30.0010, 0.5000, -29.9980)),
            RoadAssist.Way(2, "Flyover", "", "motorway_link", 40, true, doubleArrayOf(0.50025, -30.0010, 0.50025, -29.9980))
        ), listOf(RoadAssist.Hazard("bump", 0.50015, -29.9988, "Speed breaker")))
        RoadAssist.installTile(t)
        RoadAssist.onLocation(fix(0.50022, -30.0000, 90f, 30.0))
        val east = RoadAssist.info
        if (east?.road == "Flyover") Report.ok("Heading east on the one-way flyover → matched the flyover (limit ${east.limit})") else Report.bad("East match wrong: ${east?.road}")
        RoadAssist.onLocation(fix(0.50022, -30.0000, 270f, 30.0))
        RoadAssist.onLocation(fix(0.50022, -30.0000, 270f, 30.0))
        val west = RoadAssist.info
        if (west?.road == "Main Road") Report.ok("Heading west → matched Main Road, not the one-way flyover") else Report.bad("West match wrong: ${west?.road}")
        RoadAssist.setLimitFix(1, 45)
        RoadAssist.onLocation(fix(0.50022, -30.0000, 270f, 30.0))
        val fx = RoadAssist.info
        if (fx?.limit == 45 && fx.source == "you" && !fx.estimated) Report.ok("User limit correction applied (45)") else Report.bad("Correction not applied: ${fx?.limit} ${fx?.source}")
        RoadAssist.setLimitFix(1, null)
        if (RoadAssist.reportHazard("pothole")) Report.ok("Own hazard saved") else Report.bad("Own hazard not saved")
        RoadAssist.onLocation(fix(0.50022, -29.9998, 90f, 30.0))
        Thread.sleep(3200)
        RoadAssist.onLocation(fix(0.50022, -29.9998, 90f, 30.0))
        val hz = RoadAssist.hazards
        if (hz.any { it.kind == "bump" } && hz.any { it.mine }) Report.ok("Hazards include the map speed breaker and my pothole (${hz.size})") else Report.bad("Hazards missing: ${hz.map { it.kind }}")
        val ahead = RoadAssist.ahead()
        if (ahead.any { it.first.kind == "bump" }) Report.ok("Speed breaker detected ahead at ${ahead.first { it.first.kind == "bump" }.second} m") else Report.bad("Speed breaker not ahead")
        if (RoadAssist.savedInfo().contains("saved area")) Report.ok("Saved map persisted: ${RoadAssist.savedInfo()}") else Report.bad("Saved map not persisted: ${RoadAssist.savedInfo()}")
        showPage("Road")
        try { act.pages["Road"]!!.refresh(); Report.ok("Road page renders") } catch (e: Throwable) { Report.bad("Road page crashed", e) }
        RoadAssist.removeMyHazardsHere()
        RoadAssist.clearSaved()
        Prefs.roadAssist = false
        Report.flush("Road AI")
    }
}


/** Live data analysis: context-aware ranges, suggestions, own-car baseline, recording and the redesigned page. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class LiveSpecTest : SimBase() {
    private fun ctx(vararg kv: Pair<Int, Double>) = SpecCtx(mapOf(*kv), ObdManager.vehicle, true, null)

    @Test
    fun liveSpec() {
        prep()
        launch()
        Report.head("Live data analysis")
        val base = arrayOf(0x0C to 800.0, 0x0D to 0.0, 0x05 to 90.0, 0x1F to 1200.0, 0x03 to 2.0)
        fun c(vararg extra: Pair<Int, Double>) = ctx(*(base.filter { b -> extra.none { it.first == b.first } } + extra).toTypedArray())

        var s = SpecEngine.status(0x05, 90.0, c())
        if (s?.level == 0) Report.ok("Coolant 90 °C is in spec") else Report.bad("Coolant 90 → ${s?.label}")
        s = SpecEngine.status(0x05, 118.0, c(0x05 to 118.0))
        if (s?.level == 2) Report.ok("Coolant 118 °C is out of spec (${s.label})") else Report.bad("Coolant 118 → ${s?.label}")
        s = SpecEngine.status(0x05, 50.0, c(0x05 to 50.0, 0x1F to 100.0))
        if (s?.label == "Warming up") Report.ok("Cold coolant right after start = Warming up, not a fault") else Report.bad("Cold coolant → ${s?.label}")
        s = SpecEngine.status(0x0C, 800.0, c())
        if (s?.level == 0) Report.ok("Warm idle 800 rpm in spec") else Report.bad("Idle 800 → ${s?.label}")
        s = SpecEngine.status(0x0C, 1250.0, c(0x0C to 1250.0))
        if (s != null && s.level >= 1 && s.dir > 0) Report.ok("Warm idle 1250 rpm flagged high (${s.label})") else Report.bad("Idle 1250 → ${s?.label}")
        val sCruise = SpecEngine.status(0x0C, 2500.0, c(0x0C to 2500.0, 0x0D to 80.0))
        if (sCruise?.level == 0) Report.ok("2500 rpm at 80 km/h is normal while driving (same rpm would be wrong at idle)") else Report.bad("Cruise 2500 → ${sCruise?.label}")
        s = SpecEngine.status(0x0B, 70.0, c(0x0B to 70.0))
        if (s != null && s.level >= 1) Report.ok("MAP 70 kPa at warm idle flagged (${s.label})") else Report.bad("MAP idle 70 → ${s?.label}")
        s = SpecEngine.status(0x0B, 70.0, c(0x0B to 70.0, 0x0D to 60.0, 0x0C to 2000.0))
        if (s?.level == 0) Report.ok("MAP 70 kPa while driving is fine") else Report.bad("MAP driving → ${s?.label}")
        s = SpecEngine.status(0x07, 25.0, c(0x07 to 25.0))
        if (s?.level == 2) Report.ok("LTFT +25 % out of spec") else Report.bad("LTFT 25 → ${s?.label}")
        s = SpecEngine.status(0x01, 130.0, c())
        if (s?.level == 2) Report.ok("MIL bit → out of spec") else Report.bad("MIL → ${s?.label}")
        s = SpecEngine.status(0x42, 12.5, c(0x42 to 12.5))
        if (s != null && s.level >= 1) Report.ok("12.5 V while running = low charging (${s.label})") else Report.bad("Charging 12.5 → ${s?.label}")

        val sg = SpecEngine.suggestions(c(0x07 to 16.0, 0x06 to 9.0, 0x0B to 70.0))
        if (sg.any { it.title.contains("air leak") }) Report.ok("AI: LTFT +16 with high idle MAP → air-leak suggestion") else Report.bad("No air-leak suggestion: ${sg.map { it.title }}")
        val sg2 = SpecEngine.suggestions(c(0x05 to 60.0, 0x1F to 1500.0))
        if (sg2.any { it.title.contains("operating temperature") }) Report.ok("AI: thermostat suggestion after 25 min at 60 °C") else Report.bad("No thermostat suggestion: ${sg2.map { it.title }}")
        val h = SpecEngine.health(c(0x07 to 25.0))
        if (h.out >= 1 && h.ok >= 1) Report.ok("Health summary ${h.ok} ok / ${h.watch} watch / ${h.out} out") else Report.bad("Health ${h.ok}/${h.watch}/${h.out}")

        // baseline
        SpecEngine.learnGapMs = 0
        SpecEngine.resetBaseline()
        ObdManager.values.clear(); for ((k, v) in base) ObdManager.values[k] = v
        for (i in 0 until 200) { ObdManager.values[0x0C] = 800.0 + (i % 5); SpecEngine.ctx(true); SpecEngine.feed(0x0C, 800.0 + (i % 5)) }
        val b = SpecEngine.baseline(0x0C, c())
        if (b != null && Math.abs(b.mean - 802.0) < 3) Report.ok("Learned warm idle ${"%.0f".format(b.mean)} rpm from ${b.n} samples") else Report.bad("Baseline not learned: ${b?.n} ${b?.mean}")
        for (i in 0 until 120) { ObdManager.values[0x0C] = 920.0; SpecEngine.ctx(true); SpecEngine.feed(0x0C, 920.0) }
        val ds = SpecEngine.status(0x0C, 920.0, c(0x0C to 920.0))
        if (ds?.label == "Off your norm") Report.ok("920 rpm is inside the generic range but flagged: off your car's norm") else Report.bad("Drift not flagged: ${ds?.label}")
        SpecEngine.resetBaseline(); SpecEngine.learnGapMs = 2000

        // recording
        SpecEngine.reset()
        ObdManager.supported = setOf(0x05, 0x0C, 0x0D)
        SpecEngine.startRecording()
        ObdManager.values[0x05] = 118.0
        SpecEngine.ctx(true); SpecEngine.feed(0x05, 118.0)
        val csv = SpecEngine.stopRecording()
        if (csv != null && csv.startsWith("seconds,") && csv.contains("Coolant")) Report.ok("Recording CSV written with flags column") else Report.bad("Recording CSV wrong: ${csv?.take(80)}")

        // page
        ObdManager.supported = setOf(0x05, 0x0C, 0x0D, 0x04, 0x07, 0x0B)
        for ((k, v) in mapOf(0x05 to 118.0, 0x0C to 800.0, 0x0D to 0.0, 0x04 to 20.0, 0x07 to 22.0, 0x0B to 70.0, 0x1F to 1200.0, 0x03 to 2.0)) ObdManager.values[k] = v
        showPage("Live")
        try { act.pages["Live"]!!.refresh(); idle(100); act.pages["Live"]!!.refresh(); Report.ok("Live data page renders with range bars and suggestions") } catch (e: Throwable) { Report.bad("Live page crashed", e) }
        try { val pdf = Reports.liveData(act); if (pdf != null) Report.ok("Live data PDF report built") else Report.bad("Live PDF null") } catch (e: Throwable) { Report.bad("Live PDF crashed", e) }
        showPage("Dash")
        try { act.pages["Dash"]!!.refresh(); Report.ok("Gauges page colours readings by spec") } catch (e: Throwable) { Report.bad("Dash crashed", e) }
        ObdManager.values.clear(); ObdManager.supported = emptySet()
        Report.flush("Live data analysis")
    }
}


/** Road AI v2: estimated limits (same road, regional default), limit change ahead, sharp curves, drive stats. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RoadEstimateTest : SimBase() {
    private fun fix(la: Double, lo: Double, bearing: Float, kmh: Double) = android.location.Location("gps").apply {
        latitude = la; longitude = lo; this.bearing = bearing; speed = (kmh / 3.6).toFloat(); accuracy = 5f; time = System.currentTimeMillis()
    }

    @Test
    fun roadEstimates() {
        prep()
        launch()
        Report.head("Road AI estimates")
        Prefs.roadAssist = true
        RoadAssist.clearSaved()
        // 1. untagged section of a road whose neighbour section is marked 80
        RoadAssist.installTile(RoadAssist.Tile(0.5000, -30.0000, 1500.0, System.currentTimeMillis(), listOf(
            RoadAssist.Way(1, "Ring Road", "NH1", "primary", 80, false, doubleArrayOf(0.5, -30.0100, 0.5, -30.0050)),
            RoadAssist.Way(2, "Ring Road", "NH1", "primary", -1, false, doubleArrayOf(0.5, -30.0050, 0.5, -29.9980))
        ), emptyList()))
        RoadAssist.onLocation(fix(0.5, -30.0000, 90f, 40.0))
        val a = RoadAssist.info
        if (a?.limit == 80 && a.estimated && a.conf == 70 && a.basis.contains("Same road")) Report.ok("Untagged section inherits 80 from the same road (${a.basis})") else Report.bad("Inherit failed: ${a?.limit} ${a?.basis}")
        // 2. regional default in India, rural secondary road
        RoadAssist.installTile(RoadAssist.Tile(20.0000, 78.0000, 1500.0, System.currentTimeMillis(), listOf(
            RoadAssist.Way(3, "", "SH5", "secondary", -1, false, doubleArrayOf(20.0, 77.9990, 20.0, 78.0010))
        ), emptyList()))
        RoadAssist.onLocation(fix(20.0, 78.0000, 90f, 40.0))
        val b = RoadAssist.info
        if (b?.limit == 60 && b.estimated && b.basis.contains("India")) Report.ok("India default for a rural secondary road: 60 (${b.basis})") else Report.bad("India default failed: ${b?.limit} ${b?.basis}")
        // 3. sharp curve
        RoadAssist.installTile(RoadAssist.Tile(0.7000, -30.0000, 1500.0, System.currentTimeMillis(), listOf(
            RoadAssist.Way(4, "Hill Road", "", "tertiary", 40, false, doubleArrayOf(0.7, -30.0030, 0.7, -29.9990, 0.70045, -29.9990))
        ), emptyList()))
        RoadAssist.onLocation(fix(0.7, -30.0020, 90f, 40.0))
        val cv = RoadAssist.curve
        if (cv != null && cv.first.kind == "curve" && cv.first.adv == 30) Report.ok("Sharp 90° curve detected ${cv.second} m ahead, advice ease to ${cv.first.adv}") else Report.bad("Curve not detected: ${cv?.second}")
        if (RoadAssist.ahead().any { it.first.kind == "curve" }) Report.ok("Curve is listed in the hazards ahead") else Report.bad("Curve missing from ahead()")
        // 4. limit change ahead
        RoadAssist.installTile(RoadAssist.Tile(0.9000, -30.0000, 1500.0, System.currentTimeMillis(), listOf(
            RoadAssist.Way(5, "Main St", "", "secondary", 60, false, doubleArrayOf(0.9, -30.0040, 0.9, -30.0020)),
            RoadAssist.Way(6, "Main St", "", "secondary", 40, false, doubleArrayOf(0.9, -30.0020, 0.9, -29.9990))
        ), emptyList()))
        RoadAssist.onLocation(fix(0.9, -30.0030, 90f, 70.0))
        val nx = RoadAssist.next
        if (nx != null && nx.limit == 40) Report.ok("Limit change ahead detected: 40 in ${nx.dist} m") else Report.bad("Next limit not detected: ${nx?.limit}")
        // 5. stats
        RoadAssist.resetStats()
        RoadAssist.onLocation(fix(0.9, -30.0030, 90f, 70.0))
        Thread.sleep(1100)
        RoadAssist.onLocation(fix(0.9, -30.0029, 90f, 70.0))
        if (RoadAssist.statSec > 0.5 && RoadAssist.overSec > 0.5 && RoadAssist.maxOver >= 9) Report.ok("Drive stats: ${"%.1f".format(RoadAssist.statSec)} s moving, ${"%.1f".format(RoadAssist.overSec)} s over, worst +${"%.0f".format(RoadAssist.maxOver)}") else Report.bad("Stats wrong: ${RoadAssist.statSec} ${RoadAssist.overSec} ${RoadAssist.maxOver}")
        showPage("Road")
        try { act.pages["Road"]!!.refresh(); Report.ok("Road page renders (speed bar, next limit, drive stats)") } catch (e: Throwable) { Report.bad("Road page crashed", e) }
        RoadAssist.clearSaved()
        Prefs.roadAssist = false
        Report.flush("Road AI estimates")
    }
}
