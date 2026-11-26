package com.dilip.carobd

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** One finding: what is wrong, how sure, and what to do about it. sev 0 = info, 1 = fix soon, 2 = fix now. */
class Finding(val sev: Int, val title: String, val detail: String, val fix: String, val stop: Boolean = false)

class TestResult(val id: String, val name: String, val ic: Ic) {
    /** -1 not available, 0 healthy, 1 needs attention, 2 problem */
    var status = -1
    var summary = ""
    val lines = ArrayList<String>()
    val findings = ArrayList<Finding>()
    fun ran() { status = maxOf(status, 0) }
    fun note(f: Finding) { findings.add(f); status = maxOf(status, f.sev) }
}

class Summary(val level: Int, val headline: String, val verdict: String, val stop: Boolean,
              val actions: List<Triple<Int, String, String>>, val links: List<String>)

/** Full-vehicle test suite with an on-device expert summary (rules + cross-test correlation). */
object DiagSuite {
    class T(val id: String, val name: String, val ic: Ic, val blurb: String)

    val tests = listOf(
        T("codes", "Fault codes", Ic.WARN, "Engine, gearbox and OBD codes with causes"),
        T("ready", "Emission readiness", Ic.LEAF, "Monitors, MIL and PUC readiness"),
        T("fuel", "Fuel trims", Ic.FUEL, "Lean / rich running, fuel system"),
        T("cool", "Cooling system", Ic.THERM, "Overheating, thermostat"),
        T("air", "Air & ignition", Ic.WIND, "Intake, load, throttle, timing"),
        T("o2", "O2 & catalyst", Ic.PULSE, "Oxygen sensors, catalyst check"),
        T("batt", "Battery & charging", Ic.BATTERY, "12 V level, alternator"),
        T("idle", "Idle quality", Ic.GAUGE, "Rough idle, misfire feel"),
        T("info", "Vehicle info", Ic.INFO, "VIN, ECU, adapter, PIDs")
    )

    @Volatile var results: List<TestResult> = emptyList()
    @Volatile var version = 0
    @Volatile var time = 0L

    private fun sample(e: Elm327, pid: Int): Double? =
        try { e.pid01(pid)?.let { Pids.byId[pid]?.decode?.invoke(it) } } catch (_: Exception) { null }

    private fun f1(v: Double?) = if (v == null) "—" else "%.1f".format(Locale.US, v)

    fun run(e: Elm327, ids: List<String>, progress: (String) -> Unit): List<TestResult> {
        val out = ArrayList<TestResult>()
        for (id in ids) {
            val t = tests.first { it.id == id }
            progress("Testing ${t.name}…")
            val r = try {
                when (id) {
                    "codes" -> codes(e); "ready" -> ready(e); "fuel" -> fuel(e); "cool" -> cool(e)
                    "air" -> air(e); "o2" -> o2(e); "batt" -> batt(e); "idle" -> idle(e); else -> info(e)
                }
            } catch (ex: Exception) {
                TestResult(t.id, t.name, t.ic).apply { summary = "Test failed: ${ex.message}" }
            }
            out.add(r)
        }
        val merged = LinkedHashMap<String, TestResult>()
        results.forEach { merged[it.id] = it }
        out.forEach { merged[it.id] = it }
        results = tests.mapNotNull { merged[it.id] }
        time = System.currentTimeMillis()
        version++
        try { Prefs.lastDiagReport = toText() } catch (_: Exception) {}
        return out
    }

    private fun mk(id: String) = tests.first { it.id == id }.let { TestResult(it.id, it.name, it.ic) }

    /* ---------------- individual tests ---------------- */

    private fun codes(e: Elm327): TestResult {
        val r = mk("codes"); val vid = ObdManager.vehicle.id
        val mil = e.pid01(0x01)
        val lamp = mil != null && mil[0] and 0x80 != 0
        if (mil != null) r.lines.add("Check-engine lamp: " + if (lamp) "ON" else "off")
        val found = LinkedHashSet<String>()
        for ((mode, kind) in listOf("03" to "Stored", "07" to "Pending", "0A" to "Permanent")) {
            val l = e.readDtcs(mode, kind)
            l.forEach { Db.insertDtc(vid, it); if (found.add(it.code)) r.lines.add("${it.code} · ${DtcDb.describe(it.code)} ($kind)") }
        }
        for ((h, label) in listOf("7E0" to "Engine ECU", "7E1" to "Gearbox (TCU)")) {
            try {
                e.readUdsDtcs(h, label).first.forEach { Db.insertDtc(vid, it); if (found.add(it.code)) r.lines.add("${it.code} · ${DtcDb.describe(it.code)} ($label)") }
            } catch (_: Exception) {
            }
        }
        e.setHeader(null)
        SmartAi.lastScan = found.toList(); SmartAi.scanId++
        r.ran()
        if (found.isEmpty()) {
            r.summary = "No fault codes"
            if (lamp) r.note(Finding(1, "Check-engine lamp is on but no code was read", "The lamp can stay on briefly after a repair or when a module outside the engine ECU holds the fault.",
                "Scan again after a short drive; if it stays on, use a brand scan tool for ABS/airbag/body modules."))
            return r
        }
        r.summary = "${found.size} code(s) found"
        for (rep in SmartAi.analyze(found)) {
            val top = rep.causes.firstOrNull()
            r.note(Finding(when (rep.urgency) { 0 -> 0; 1 -> 1; else -> 2 }, "${rep.code} · ${rep.desc}",
                if (top == null) "" else "Most likely: ${top.name} (${top.prob}%)" + (top.why?.let { " – evidence: $it" } ?: ""),
                top?.steps?.take(3)?.joinToString("; ") ?: "Have it checked at a workshop.", stop = rep.urgency >= 3))
        }
        try {
            val trig = e.freeze(0x02)
            if (trig != null && trig.size >= 2 && !(trig[0] == 0 && trig[1] == 0)) {
                val spd = e.freeze(0x0D)?.let { Pids.byId[0x0D]?.decode?.invoke(it) }
                val rpm = e.freeze(0x0C)?.let { Pids.byId[0x0C]?.decode?.invoke(it) }
                val ect = e.freeze(0x05)?.let { Pids.byId[0x05]?.decode?.invoke(it) }
                r.lines.add("Freeze frame for ${DtcDb.decode(trig[0], trig[1])}: speed ${f1(spd)} km/h · ${f1(rpm)} rpm · coolant ${f1(ect)} °C")
            }
        } catch (_: Exception) {
        }
        return r
    }

    private fun ready(e: Elm327): TestResult {
        val r = mk("ready")
        val d = e.pid01(0x01)
        if (d == null || d.size < 4) { r.summary = "Not reported by this car"; return r }
        ObdManager.monitorRaw = d
        val rep = EmissionAi.analyse()
        r.ran()
        r.lines.add(rep.headline)
        if (rep.detail.isNotBlank()) r.lines.add(rep.detail)
        rep.monitors.forEach { r.lines.add((if (it.ready) "✅ " else "⏳ ") + it.name) }
        r.summary = if (!rep.applicable) "Not applicable (EV)" else "${rep.readyPct}% monitors ready"
        if (rep.applicable && rep.level == 2)
            r.note(Finding(2, "Will fail an emission (PUC) test", rep.detail.ifBlank { "Check-engine lamp or fault codes present." }, "Fix the fault codes first, clear them, then complete a drive cycle."))
        else if (rep.applicable && rep.level == 1)
            r.note(Finding(1, "Monitors not complete yet", "${rep.incomplete} monitor(s) still pending.", rep.tips.take(2).joinToString(" ").ifBlank { "Drive 20–30 min mixed city/highway with a warm engine, then re-test." }))
        return r
    }

    private fun trimNote(r: TestResult, bank: String, lt: Double, st: Double?) {
        val tot = lt + (st ?: 0.0)
        val a = abs(lt)
        if (a < 10 && abs(tot) < 20) return
        val lean = lt > 0
        val sev = if (a >= 20 || abs(tot) >= 30) 2 else 1
        r.note(Finding(sev, "Fuel trim ${bank} ${if (lean) "lean" else "rich"} (long-term ${f1(lt)} %)",
            if (lean) "The ECU is adding fuel to compensate for extra air or too little fuel." else "The ECU is removing fuel – too much fuel or false 'lean' signal.",
            if (lean) "Check for intake/vacuum leaks (smoke test, hoses, intake gasket), clean or replace the MAF sensor, check fuel pressure and fuel filter, then injectors."
            else "Check the purge (EVAP) valve stuck open, leaking injectors, fuel-pressure regulator, dirty air filter and the coolant-temperature sensor."))
    }

    private fun fuel(e: Elm327): TestResult {
        val r = mk("fuel")
        val st1 = sample(e, 0x06); val lt1 = sample(e, 0x07); val st2 = sample(e, 0x08); val lt2 = sample(e, 0x09)
        if (st1 == null && lt1 == null) { r.summary = "Not reported (diesel/EV or unsupported)"; return r }
        r.ran()
        r.lines.add("Bank 1: short-term ${f1(st1)} % · long-term ${f1(lt1)} %")
        if (st2 != null || lt2 != null) r.lines.add("Bank 2: short-term ${f1(st2)} % · long-term ${f1(lt2)} %")
        e.pid01(0x03)?.let { Pids.byId[0x03]?.text?.invoke(it)?.let { s -> r.lines.add("Fuel system: $s") } }
        if (lt1 != null) trimNote(r, "bank 1", lt1, st1)
        if (lt2 != null) trimNote(r, "bank 2", lt2, st2)
        r.summary = if (r.findings.isEmpty()) "Within normal range (±10 %)" else r.findings.first().title
        return r
    }

    private fun cool(e: Elm327): TestResult {
        val r = mk("cool")
        val ect = sample(e, 0x05)
        if (ect == null) { r.summary = "Coolant temperature not available"; return r }
        val run = sample(e, 0x1F)
        r.ran()
        r.lines.add("Coolant: ${f1(ect)} °C" + (run?.let { " · engine running ${(it / 60).toInt()} min" } ?: ""))
        when {
            ect > 112 -> r.note(Finding(2, "Engine overheating (${f1(ect)} °C)", "Coolant is far above the normal 85–100 °C.",
                "Stop and switch off when safe, let it cool 30 min, check coolant level (cold only), radiator fan, thermostat, water pump and for leaks. Do not keep driving.", stop = true))
            ect > 105 -> r.note(Finding(1, "Running hot (${f1(ect)} °C)", "Higher than normal; may be traffic/heat or a cooling fault.",
                "Check coolant level, radiator fan operation, clogged radiator fins and the thermostat."))
            run != null && run > 600 && ect < 75 -> r.note(Finding(1, "Engine slow to warm up (${f1(ect)} °C after ${(run / 60).toInt()} min)",
                "Usually a thermostat stuck open or a faulty temperature sensor. Costs fuel and raises emissions.", "Replace the thermostat; check the coolant-temperature sensor."))
            run != null && run < 180 && ect < 70 -> r.lines.add("Engine still warming up – repeat this test after a 10-minute drive for a meaningful result.")
        }
        r.summary = if (r.findings.isEmpty()) "Normal" else r.findings.first().title
        return r
    }

    private fun air(e: Elm327): TestResult {
        val r = mk("air")
        val rpm = sample(e, 0x0C)
        val load = sample(e, 0x04); val map = sample(e, 0x0B); val iat = sample(e, 0x0F)
        val maf = sample(e, 0x10); val thr = sample(e, 0x11); val adv = sample(e, 0x0E)
        if (rpm == null || rpm < 300) { r.summary = "Start the engine and idle, then test again"; return r }
        r.ran()
        r.lines.add("RPM ${f1(rpm)} · load ${f1(load)} % · throttle ${f1(thr)} %")
        r.lines.add("MAP ${f1(map)} kPa · MAF ${f1(maf)} g/s · intake air ${f1(iat)} °C · timing ${f1(adv)}°")
        val idleNow = rpm < 1200 && (thr == null || thr < 25)
        if (idleNow) {
            if (map != null && map > 60) r.note(Finding(1, "High manifold pressure at idle (${f1(map)} kPa)", "Idle intake pressure should be low (about 25–50 kPa). High readings point to a vacuum leak or restriction.",
                "Smoke-test intake hoses, check brake-booster hose, intake gasket and PCV hose."))
            if (load != null && load > 50) r.note(Finding(1, "High engine load at idle (${f1(load)} %)", "The engine is working harder than needed while standing.",
                "Clean the throttle body, check for air leaks and heavy loads (A/C on, bad alternator)."))
            if (thr != null && thr > 18) r.note(Finding(1, "Throttle open more than normal at idle (${f1(thr)} %)", "A dirty throttle body forces the ECU to open it further.",
                "Clean the throttle body and let the ECU relearn (ignition ON 30 s, then idle 10 min)."))
            if (maf != null && maf < 1.0) r.note(Finding(1, "Very low airflow reading (${f1(maf)} g/s)", "MAF sensor dirty/failing or air bypassing it.",
                "Clean the MAF sensor with MAF cleaner, check the air-filter box and hoses after the sensor."))
        }
        if (iat != null && iat > 80) r.note(Finding(1, "Intake air very hot (${f1(iat)} °C)", "Heat soak or hot-air intake problem reduces power and raises knock risk.",
            "Check air-intake ducting, intercooler (if turbo) and under-bonnet heat shields."))
        r.summary = if (r.findings.isEmpty()) "Normal" else r.findings.first().title
        return r
    }

    private fun o2(e: Elm327): TestResult {
        val r = mk("o2")
        val sup = ObdManager.supported
        if (0x15 !in sup && 0x14 !in sup) { r.summary = "Oxygen sensor readings not reported (modern cars use wideband PIDs)"; return r }
        val rpm = sample(e, 0x0C)
        if (rpm == null || rpm < 300) { r.summary = "Start the engine and warm it up, then test again"; return r }
        val ect = sample(e, 0x05)
        if (ect != null && ect < 70) { r.summary = "Engine not warm (${f1(ect)} °C) – test after warm-up"; return r }
        val up = ArrayList<Double>(); val down = ArrayList<Double>()
        repeat(12) {
            sample(e, 0x14)?.let { up.add(it) }; sample(e, 0x15)?.let { down.add(it) }
            Thread.sleep(200)
        }
        r.ran()
        fun rng(l: List<Double>) = if (l.isEmpty()) 0.0 else l.max() - l.min()
        r.lines.add("Upstream sensor: ${f1(up.minOrNull())}–${f1(up.maxOrNull())} V (swing ${f1(rng(up))} V)")
        r.lines.add("Downstream sensor: ${f1(down.minOrNull())}–${f1(down.maxOrNull())} V (swing ${f1(rng(down))} V)")
        if (down.size >= 8 && up.size >= 8 && rng(down) > 0.55 && rng(up) > 0.5)
            r.note(Finding(1, "Catalyst may be worn", "The rear sensor swings almost like the front one, so the catalytic converter is not storing oxygen well. (Indicative only – confirm with code P0420/P0430.)",
                "Check for misfires and rich running first (they kill catalysts); then have the catalyst efficiency tested."))
        r.summary = if (r.findings.isEmpty()) "Sensors respond normally" else r.findings.first().title
        return r
    }

    private fun batt(e: Elm327): TestResult {
        val r = mk("batt")
        val adapter = e.batteryVoltage()
        val ecu = sample(e, 0x42)
        val rpm = sample(e, 0x0C)
        val v = ecu ?: adapter
        if (v == null) { r.summary = "Voltage not available"; return r }
        val running = rpm != null && rpm > 400
        r.ran()
        r.lines.add("ECU: ${if (ecu != null) "%.2f V".format(Locale.US, ecu) else "—"} · adapter: ${if (adapter != null) "%.2f V".format(Locale.US, adapter) else "—"} · engine ${if (running) "running" else "off"}")
        if (running) when {
            v > 15.0 -> r.note(Finding(2, "Overcharging (${"%.1f".format(Locale.US, v)} V)", "Alternator regulator may be faulty; risk to the battery and electronics.",
                "Have the alternator/regulator tested soon; check battery terminal and earth connections.", stop = false))
            v < 12.4 -> r.note(Finding(2, "Battery is not charging (${"%.1f".format(Locale.US, v)} V)", "With the engine running the voltage should be 13.2–14.8 V.",
                "Check the alternator belt, alternator output, fuses and battery terminals. Avoid long drives – the battery will run flat."))
            v < 13.0 -> r.note(Finding(1, "Weak charging (${"%.1f".format(Locale.US, v)} V)", "Alternator output is on the low side.",
                "Test alternator output and belt tension; clean battery terminals and earth straps."))
        } else when {
            v < 11.8 -> r.note(Finding(2, "Battery nearly flat (${"%.1f".format(Locale.US, v)} V)", "Below about 12 V the car may not start and the battery sulphates.", "Charge it fully and load-test; check for parasitic drain."))
            v < 12.2 -> r.note(Finding(1, "Battery low (${"%.1f".format(Locale.US, v)} V)", "A rested healthy lead-acid battery reads about 12.5–12.8 V.", "Charge it and test; if it falls again quickly, replace it or find the drain."))
        }
        if (ecu != null && adapter != null && abs(ecu - adapter) > 0.6)
            r.note(Finding(0, "Adapter and ECU voltages differ by ${"%.1f".format(Locale.US, abs(ecu - adapter))} V", "Often a loose OBD adapter or poor port contact.", "Re-seat the adapter firmly."))
        r.summary = if (r.findings.none { it.sev > 0 }) "%.2f V – normal".format(Locale.US, v) else r.findings.first { it.sev > 0 }.title
        return r
    }

    private fun idle(e: Elm327): TestResult {
        val r = mk("idle")
        val s = ArrayList<Double>()
        repeat(12) { sample(e, 0x0C)?.let { s.add(it) }; Thread.sleep(250) }
        if (s.size < 6) { r.summary = "RPM not available"; return r }
        val mean = s.average()
        if (mean < 300) { r.summary = "Engine off – start it and test at idle"; return r }
        if (mean > 1300) { r.summary = "Engine not at idle (${mean.toInt()} rpm) – release the throttle"; return r }
        val sd = sqrt(s.sumOf { (it - mean) * (it - mean) } / s.size)
        val ect = sample(e, 0x05)
        r.ran()
        r.lines.add("Idle ${mean.toInt()} rpm · variation ±${sd.toInt()} rpm over ${s.size} readings")
        when {
            sd > 120 -> r.note(Finding(2, "Rough idle (±${sd.toInt()} rpm)", "Strong speed fluctuation – typical of misfire, vacuum leak or a bad engine mount.",
                "Check spark plugs and coils, intake leaks, throttle body and engine mounts."))
            sd > 50 -> r.note(Finding(1, "Slightly unstable idle (±${sd.toInt()} rpm)", "Early sign of dirty throttle body, worn plugs or small vacuum leak.", "Clean the throttle body, inspect spark plugs, check hoses."))
        }
        if (ect != null && ect > 75) {
            if (mean < 600 && !ObdManager.vehicle.isEv) r.note(Finding(1, "Low idle speed (${mean.toInt()} rpm)", "Idle is below the usual 650–900 rpm.", "Clean the throttle body / idle control; check for A/C or electrical load issues."))
            if (mean > 1100) r.note(Finding(1, "High idle speed (${mean.toInt()} rpm) when warm", "A leak or sticking throttle holds the idle up.", "Check intake leaks, throttle body, and coolant-temperature sensor."))
        }
        r.summary = if (r.findings.isEmpty()) "Smooth (${mean.toInt()} rpm)" else r.findings.first().title
        return r
    }

    private fun info(e: Elm327): TestResult {
        val r = mk("info"); val m = ObdManager
        r.ran()
        r.lines.add("VIN: ${e.info09(0x02).ifEmpty { "n/a" }}")
        r.lines.add("Calibration: ${e.info09(0x04).ifEmpty { "n/a" }} · ECU ${e.info09(0x0A).ifEmpty { "n/a" }}")
        r.lines.add("Adapter: ${m.elmVersion} · ${e.protocol}")
        r.lines.add("Supported PIDs: ${m.supported.size}")
        r.summary = "${m.supported.size} PIDs · ${e.protocol}"
        return r
    }

    /* ---------------- AI summary ---------------- */

    private fun has(id: String, key: String) = results.firstOrNull { it.id == id }?.findings?.any { it.title.contains(key, true) } == true

    fun summarize(): Summary {
        val ran = results.filter { it.status >= 0 }
        val level = ran.maxOfOrNull { it.status } ?: -1
        val all = results.flatMap { t -> t.findings.map { t to it } }
        val stop = all.any { it.second.stop }
        val actions = all.filter { it.second.sev >= 1 }
            .sortedWith(compareByDescending<Pair<TestResult, Finding>> { it.second.stop }.thenByDescending { it.second.sev })
            .map { Triple(it.second.sev, "${it.first.name}: ${it.second.title}", it.second.fix) }
        val codes = SmartAi.lastScan.toSet()
        val links = ArrayList<String>()
        val lean = has("fuel", "lean")
        if (lean && (codes.contains("P0171") || codes.contains("P0174") || has("air", "manifold") || has("air", "airflow")))
            links.add("Lean fuel trim together with ${if (codes.any { it == "P0171" || it == "P0174" }) "the lean code" else "the intake readings"} points to one cause: an air/vacuum leak or dirty MAF. Fix that first – the other items may clear by themselves.")
        if (has("batt", "charging") && (has("idle", "idle") || has("idle", "unstable")))
            links.add("Weak charging and an unstable idle usually go together: low system voltage upsets the ECU and ignition. Test the alternator and battery before replacing engine parts.")
        if (codes.any { it.startsWith("P030") } && (has("idle", "Rough") || has("idle", "unstable")))
            links.add("Misfire codes and the rough idle agree. Start with spark plugs and ignition coils (swap a coil to see if the misfire follows).")
        if (has("cool", "slow to warm") && has("fuel", "rich"))
            links.add("The engine is running cold, so the ECU enriches the fuel (rich trim). Replace the thermostat first; the trim usually normalises afterwards.")
        if (has("ready", "emission") && codes.isNotEmpty())
            links.add("The emission-test failure is caused by the stored fault codes. Repair the cause, clear the codes and complete a drive cycle.")
        val headline = when (level) { 2 -> "Problems found – action needed"; 1 -> "Some items need attention"; 0 -> "All tested systems look healthy"; else -> "No test has run yet" }
        val verdict = when {
            stop -> "Do not drive – get it checked first"
            level == 2 -> "Drive gently to a workshop soon"
            level == 1 -> "Safe to drive – book a check within a few weeks"
            level == 0 -> "Safe to drive"
            else -> "Run a scan to get a verdict"
        }
        return Summary(level, headline, verdict, stop, actions, links)
    }

    private fun icon(s: Int) = when (s) { 2 -> "🔴"; 1 -> "🟠"; 0 -> "✅"; else -> "⚪" }

    fun toText(): String {
        val v = ObdManager.vehicle
        val s = summarize()
        val sb = StringBuilder("${AppInfo.NAME} – AI diagnostic report\n${v.title}")
        if (v.regNo.isNotBlank()) sb.append(" · ${v.regNo}")
        sb.append("\n${SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(if (time > 0) time else System.currentTimeMillis()))}\n\n")
        sb.append("VERDICT: ${s.verdict}\n${s.headline}\n")
        if (s.actions.isNotEmpty()) {
            sb.append("\nWHAT TO DO\n")
            s.actions.forEachIndexed { i, a -> sb.append("${i + 1}. ${if (a.first >= 2) "NOW" else "SOON"} – ${a.second}\n    Solution: ${a.third}\n") }
        }
        if (s.links.isNotEmpty()) { sb.append("\nHOW THE FINDINGS CONNECT\n"); s.links.forEach { sb.append("• $it\n") } }
        sb.append("\nTEST RESULTS\n")
        for (t in results) {
            sb.append("${icon(t.status)} ${t.name}: ${t.summary}\n")
            t.lines.forEach { sb.append("    $it\n") }
        }
        sb.append("\nGenerated by on-device rules and statistics from live ECU data. It is a guide: confirm with tests before replacing parts.")
        sb.append(AppInfo.footer)
        return sb.toString()
    }
}
