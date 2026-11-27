package com.dilip.carobd

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Live data analysis: typical-range specification per reading, context-aware status (idle / cruise / warming up),
 * your-car baseline learning, per-drive min/avg/max, an out-of-spec log and rule-based AI suggestions.
 * The ranges are typical values for a healthy car, not factory data of one model.
 */

/** Snapshot of what the car is doing right now; decides which range applies. */
class SpecCtx(val v: Map<Int, Double>, val veh: Vehicle, val engineOn: Boolean, val battV: Double?) {
    val rpm: Double = v[0x0C] ?: 0.0
    val spd: Double = v[0x0D] ?: 0.0
    val cool: Double? = v[0x05]
    val runS: Double = v[0x1F] ?: 0.0
    val diesel: Boolean = veh.fuel.contains("Diesel", true)
    val running: Boolean = rpm > 400
    val idle: Boolean = running && spd < 2.0 && rpm < 1300
    val warm: Boolean = (cool ?: 0.0) >= 80.0
    val closedLoop: Boolean = (v[0x03] ?: 0.0).toInt() == 2
}

/** Allowed band: [lo, hi] is normal, [wLo, wHi] is "watch"; outside that is out of spec. Bar* is the drawn range. */
class Spec(val lo: Double, val hi: Double, val wLo: Double, val wHi: Double, val barMin: Double, val barMax: Double, val note: String = "")

/** level 0 ok, 1 watch, 2 out of spec; dir -1 low, +1 high. */
class Status(val level: Int, val dir: Int, val label: String, val spec: Spec?)

class Info(val what: String, val low: String, val high: String, val check: String)

class Sug(val sev: Int, val title: String, val why: String, val fix: String, val pids: List<Int>)

class Ev(val t: Long, val pid: Int, val name: String, val level: Int, val text: String)

class Base(var n: Int = 0, var mean: Double = 0.0, var m2: Double = 0.0) {
    val sd: Double get() = if (n > 1) sqrt(m2 / (n - 1)) else 0.0
}

object SpecEngine {
    const val BATT = -1   // pseudo-id of the pin-16 battery voltage
    const val BASE_READY = 150
    @Volatile var learnGapMs = 2000L

    private val stats = HashMap<Int, DoubleArray>()      // n, min, max, sum
    private val ema = HashMap<Int, Double>()
    private val lastLevel = HashMap<Int, Int>()
    private val streak = HashMap<Int, Int>()
    private val lastBaseAt = HashMap<String, Long>()
    val events = ArrayList<Ev>()
    private var baseVid = -2L
    private var bases = HashMap<String, Base>()
    private var dirty = false
    private var savedAt = 0L
    private var cache: SpecCtx? = null
    private var cacheAt = 0L

    @Volatile var recording = false
    private val recRows = ArrayList<String>()
    private var recLast = 0L
    private var recStart = 0L

    fun ctx(force: Boolean = false): SpecCtx {
        val now = System.currentTimeMillis()
        val c = cache
        if (!force && c != null && now - cacheAt < 700) return c
        val m = ObdManager
        val n = SpecCtx(HashMap(m.values), m.vehicle, m.engineOn, m.batteryV)
        cache = n; cacheAt = now
        return n
    }

    @Synchronized
    fun reset() {
        stats.clear(); ema.clear(); lastLevel.clear(); streak.clear(); events.clear(); cache = null
    }

    /* ---------------------------- ranges ---------------------------- */

    fun specFor(pid: Int, c: SpecCtx): Spec? {
        val veh = c.veh
        val d = veh.dispL.coerceAtLeast(0.6)
        return when (pid) {
            0x05 -> Spec(80.0, minOf(102.0, Prefs.coolantMax - 3), 65.0, Prefs.coolantMax, 40.0, 125.0, "warm engine")
            0x5C -> Spec(70.0, 115.0, 40.0, 130.0, 20.0, 150.0, "warm engine")
            0x0F -> Spec(-10.0, 55.0, -30.0, 70.0, -20.0, 90.0)
            0x0C -> when {
                !c.running -> null
                c.idle && c.warm -> if (c.diesel) Spec(700.0, 950.0, 550.0, 1100.0, 0.0, 1500.0, "warm idle") else Spec(650.0, 950.0, 550.0, 1100.0, 0.0, 1500.0, "warm idle")
                c.idle -> Spec(700.0, 1500.0, 500.0, 1800.0, 0.0, 2000.0, "cold idle")
                else -> Spec(0.0, veh.rpmLimit * 0.9, 0.0, veh.rpmLimit.toDouble(), 0.0, veh.rpmLimit * 1.1, "driving")
            }
            0x0D -> Spec(0.0, veh.speedLimit.toDouble(), 0.0, veh.speedLimit * 1.1, 0.0, maxOf(160.0, veh.speedLimit * 1.3), "your speed limit")
            0x04 -> if (!c.running) null else if (c.idle) Spec(12.0, 40.0, 5.0, 55.0, 0.0, 100.0, "idle") else Spec(5.0, 85.0, 0.0, 95.0, 0.0, 100.0, "driving")
            0x11 -> if (!c.running) null else if (c.idle) Spec(0.0, 22.0, -1.0, 32.0, 0.0, 100.0, "idle") else Spec(0.0, 85.0, -1.0, 98.0, 0.0, 100.0, "driving")
            0x06, 0x08 -> if (c.diesel || !c.closedLoop) null else Spec(-10.0, 10.0, -20.0, 20.0, -30.0, 30.0, "closed loop")
            0x07, 0x09 -> if (c.diesel || !c.running) null else Spec(-10.0, 10.0, -20.0, 20.0, -30.0, 30.0)
            0x0B -> when {
                !c.running -> Spec(85.0, 105.0, 60.0, 110.0, 0.0, 120.0, "engine off")
                c.diesel -> if (c.idle) Spec(90.0, 115.0, 75.0, 130.0, 0.0, 300.0, "idle") else null
                c.idle && c.warm -> Spec(25.0, 48.0, 18.0, 58.0, 0.0, 120.0, "warm idle")
                c.idle -> null
                else -> Spec(15.0, 230.0, 10.0, 280.0, 0.0, 300.0, "driving")
            }
            0x10 -> if (c.idle && c.warm && !c.diesel) Spec(1.3 * d, 4.5 * d, 0.8 * d, 6.5 * d, 0.0, 10.0 * d, "warm idle, ${"%.1f".format(d)} L") else null
            0x0E -> if (!c.running) null else if (c.idle) Spec(5.0, 22.0, 0.0, 30.0, -10.0, 60.0, "idle") else Spec(-5.0, 45.0, -10.0, 55.0, -10.0, 60.0, "driving")
            0x42 -> voltSpec(c)
            BATT -> voltSpec(c)
            0x2F, 0x5B -> Spec(25.0, 100.0, Prefs.lowFuelPct, 100.0, 0.0, 100.0)
            0x33 -> Spec(85.0, 105.0, 70.0, 110.0, 60.0, 110.0, "altitude dependent")
            0x3C, 0x3D, 0x3E -> if (c.warm) Spec(250.0, 850.0, 150.0, 950.0, 0.0, 1000.0, "warm engine") else null
            0x15 -> if (c.warm && c.closedLoop && !c.diesel) Spec(0.40, 0.80, 0.20, 0.90, 0.0, 1.0, "after catalyst") else null
            0x24, 0x34 -> if (c.warm && c.closedLoop && !c.diesel) Spec(0.97, 1.03, 0.90, 1.10, 0.7, 1.3, "closed loop") else null
            0x2D -> Spec(-10.0, 10.0, -20.0, 20.0, -30.0, 30.0)
            0x21, 0x4D -> Spec(0.0, 0.0, 0.0, 100.0, 0.0, 100.0, "MIL off")
            else -> null
        }
    }

    private fun voltSpec(c: SpecCtx): Spec =
        if (c.running) Spec(Prefs.chargeMinV, Prefs.chargeMaxV, 12.6, Prefs.chargeMaxV + 0.5, 10.0, 16.0, "engine running")
        else Spec(12.4, 13.3, 11.9, 14.8, 10.0, 16.0, "engine off")

    /** Level / direction / label for one reading, or null when there is no specification for it. */
    fun status(pid: Int, value: Double, c: SpecCtx): Status? {
        when (pid) {
            0x01 -> return if (value >= 128) Status(2, 1, "MIL ON", null) else Status(0, 0, "OK", null)
            0x03 -> {
                val code = value.toInt()
                return when (code) {
                    8, 16 -> Status(2, 0, "Fuel system fault", null)
                    1 -> if (c.warm) Status(1, 0, "Still open loop", null) else Status(0, 0, "Warming up", null)
                    else -> Status(0, 0, "OK", null)
                }
            }
        }
        val s = specFor(pid, c) ?: return null
        val warming = !c.warm && (pid == 0x05 || pid == 0x5C || pid in 0x3C..0x3E)
        val st = when {
            value in s.lo..s.hi -> Status(0, 0, "OK", s)
            value < s.lo -> if (warming) Status(0, -1, "Warming up", s)
                else if (value < s.wLo) Status(2, -1, "Out of spec ↓", s) else Status(1, -1, "Low", s)
            else -> if (value > s.wHi) Status(2, 1, "Out of spec ↑", s) else Status(1, 1, "High", s)
        }
        if (st.level == 0 && st.label == "OK") {
            val dr = drift(pid, c)
            if (dr != null) return Status(1, dr.second, "Off your norm", s)
        }
        return st
    }

    /* ---------------------------- baseline ---------------------------- */

    private val baseMinDelta = mapOf("idle_rpm" to 60.0, "cool" to 6.0, "chg_v" to 0.35, "ltft" to 4.0, "idle_load" to 6.0,
        "idle_map" to 5.0, "idle_maf" to 0.8, "idle_adv" to 4.0, "idle_thr" to 4.0)

    private fun baseKey(pid: Int, c: SpecCtx): String? = when (pid) {
        0x0C -> if (c.idle && c.warm) "idle_rpm" else null
        0x05 -> if (c.running && c.warm && c.runS > 900) "cool" else null
        0x42 -> if (c.rpm > 600) "chg_v" else null
        0x07 -> if (c.running && c.warm && c.closedLoop && !c.diesel) "ltft" else null
        0x04 -> if (c.idle && c.warm) "idle_load" else null
        0x0B -> if (c.idle && c.warm && !c.diesel) "idle_map" else null
        0x10 -> if (c.idle && c.warm && !c.diesel) "idle_maf" else null
        0x0E -> if (c.idle && c.warm) "idle_adv" else null
        0x11 -> if (c.idle && c.warm) "idle_thr" else null
        else -> null
    }

    fun baseNames(): Map<String, String> = mapOf("idle_rpm" to "Warm idle RPM", "cool" to "Operating coolant temp", "chg_v" to "Charging voltage",
        "ltft" to "Long-term fuel trim", "idle_load" to "Idle load", "idle_map" to "Idle MAP", "idle_maf" to "Idle air flow",
        "idle_adv" to "Idle timing", "idle_thr" to "Idle throttle")

    private fun loadBases() {
        val vid = ObdManager.vehicle.id
        if (vid == baseVid) return
        saveBases()
        baseVid = vid; bases = HashMap()
        try {
            val o = JSONObject(Prefs.liveBaseline).optJSONObject(vid.toString()) ?: return
            for (k in o.keys()) { val a = o.getJSONArray(k); bases[k] = Base(a.getInt(0), a.getDouble(1), a.getDouble(2)) }
        } catch (_: Exception) {}
    }

    private fun saveBases() {
        if (!dirty || baseVid == -2L) return
        try {
            val root = try { JSONObject(Prefs.liveBaseline) } catch (_: Exception) { JSONObject() }
            val o = JSONObject()
            for ((k, b) in bases) o.put(k, org.json.JSONArray().put(b.n).put(b.mean).put(b.m2))
            root.put(baseVid.toString(), o)
            Prefs.liveBaseline = root.toString(); dirty = false
        } catch (_: Exception) {}
    }

    fun resetBaseline() { bases.clear(); dirty = true; saveBases() }

    @Synchronized
    private fun learn(pid: Int, v: Double, c: SpecCtx, now: Long) {
        val k = baseKey(pid, c) ?: return
        loadBases()
        if (now - (lastBaseAt[k] ?: 0L) < learnGapMs) return
        lastBaseAt[k] = now
        val b = bases.getOrPut(k) { Base() }
        if (b.n < 5000) {
            b.n++; val dl = v - b.mean; b.mean += dl / b.n; b.m2 += dl * (v - b.mean)
        } else { b.mean += (v - b.mean) / 5000.0 }
        dirty = true
        if (now - savedAt > 90_000) { savedAt = now; saveBases() }
    }

    fun baseline(pid: Int, c: SpecCtx): Base? {
        val k = baseKey(pid, c) ?: return null
        loadBases()
        return bases[k]?.takeIf { it.n >= BASE_READY }
    }

    fun baselineProgress(): Int {
        loadBases()
        if (bases.isEmpty()) return 0
        return (bases.values.sumOf { minOf(it.n, BASE_READY) } * 100 / (BASE_READY * bases.size)).coerceIn(0, 100)
    }

    fun learnable(pid: Int) = pid in setOf(0x0C, 0x05, 0x42, 0x07, 0x04, 0x0B, 0x10, 0x0E, 0x11)

    fun rangeText(pid: Int, s: Spec): String {
        val unit = Pids.byId[pid]?.unit ?: if (pid == BATT) "V" else ""
        val (a, lb) = Units.conv(unit, s.lo); val (b, _) = Units.conv(unit, s.hi)
        val span = abs(b - a)
        val dec = if (span == 0.0) 0 else if (span < 3) 2 else if (span < 40) 1 else 0
        val core = if (s.lo == s.hi) "%.${dec}f %s".format(a, lb) else "%.${dec}f–%.${dec}f %s".format(a, b, lb)
        return core.trim() + if (s.note.isNotBlank()) " (${s.note})" else ""
    }

    fun baselineCount(): Int { loadBases(); return bases.values.count { it.n >= BASE_READY } }

    /** Smoothed value is far from your car's usual: returns (usual, direction). */
    fun drift(pid: Int, c: SpecCtx): Pair<Base, Int>? {
        val k = baseKey(pid, c) ?: return null
        val b = baseline(pid, c) ?: return null
        val cur = ema[pid] ?: return null
        val lim = maxOf(3.0 * b.sd, baseMinDelta[k] ?: 0.0)
        return if (abs(cur - b.mean) > lim) b to (if (cur > b.mean) 1 else -1) else null
    }

    /* ---------------------------- feed from the ECU thread ---------------------------- */

    fun feed(pid: Int, v: Double) {
        val now = System.currentTimeMillis()
        synchronized(stats) {
            val s = stats.getOrPut(pid) { doubleArrayOf(0.0, v, v, 0.0) }
            s[0] += 1.0; if (v < s[1]) s[1] = v; if (v > s[2]) s[2] = v; s[3] += v
        }
        ema[pid] = ema[pid]?.let { it + (v - it) * 0.08 } ?: v
        val c = ctx()
        learn(pid, v, c, now)
        val st = status(pid, v, c)
        if (st != null) {
            val lvl = st.level
            if (lvl == 2) {
                val k = (streak[pid] ?: 0) + 1; streak[pid] = k
                if (k == 4 && (lastLevel[pid] ?: 0) != 2) { log(pid, 2, v, st); lastLevel[pid] = 2 }
            } else { streak[pid] = 0; lastLevel[pid] = lvl }
        }
        if (recording && now - recLast >= 1000) record(now, c)
    }

    fun fmt(pid: Int, v: Double): String {
        val d = Pids.byId[pid]
        val (cv, lb) = Units.conv(d?.unit ?: "", v)
        return "%.${d?.decimals ?: 1}f %s".format(cv, lb).trim()
    }

    fun nameOf(pid: Int) = if (pid == BATT) "Battery voltage" else Pids.nameOf(pid)

    @Synchronized
    private fun log(pid: Int, level: Int, v: Double, st: Status) {
        events.add(Ev(System.currentTimeMillis(), pid, nameOf(pid), level, "${fmt(pid, v)} · ${st.label}"))
        if (events.size > 200) events.removeAt(0)
    }

    @Synchronized fun eventList(): List<Ev> = ArrayList(events)

    fun statOf(pid: Int): Triple<Double, Double, Double>? = synchronized(stats) {
        val s = stats[pid] ?: return null
        if (s[0] < 1) null else Triple(s[1], s[3] / s[0], s[2])
    }

    fun smooth(pid: Int): Double? = ema[pid]

    /* ---------------------------- recording ---------------------------- */

    private val recPids = ArrayList<Int>()

    @Synchronized
    fun startRecording() {
        recRows.clear(); recPids.clear()
        recPids.addAll(ObdManager.supported.filter { !Pids.isBitmap(it) && Pids.byId[it]?.decode != null }.sorted())
        recStart = System.currentTimeMillis(); recLast = 0L; recording = true
    }

    fun recordedRows() = recRows.size
    fun recordingSeconds() = if (recording) (System.currentTimeMillis() - recStart) / 1000 else 0L

    @Synchronized
    private fun record(now: Long, c: SpecCtx) {
        recLast = now
        if (recRows.size > 20_000) return
        val sb = StringBuilder()
        sb.append("%.1f".format((now - recStart) / 1000.0))
        val flagged = ArrayList<String>()
        for (p in recPids) {
            val v = c.v[p]
            sb.append(',').append(if (v == null) "" else "%.3f".format(v))
            if (v != null) status(p, v, c)?.let { if (it.level >= 1) flagged.add("${nameOf(p)}:${it.label}") }
        }
        sb.append(",\"").append(flagged.joinToString("; ")).append('"')
        recRows.add(sb.toString())
    }

    /** Stops recording and returns the CSV text, or null when nothing was captured. */
    @Synchronized
    fun stopRecording(): String? {
        recording = false
        if (recRows.isEmpty()) return null
        val head = "seconds," + recPids.joinToString(",") { "\"${Pids.nameOf(it)}\"" } + ",flags"
        val csv = head + "\n" + recRows.joinToString("\n") + "\n\n# Flags = readings outside the typical range at that moment\n# ${AppInfo.credit}\n"
        recRows.clear()
        return csv
    }

    /* ---------------------------- explanations ---------------------------- */

    private val info = mapOf(
        0x05 to Info("Engine coolant temperature measured at the thermostat housing. The ECU uses it for fuelling, fan control and warm-up.",
            "Thermostat stuck open or a faulty sensor: slow warm-up, poor economy, weak cabin heat.",
            "Overheating: low coolant, failed fan, blocked radiator, weak water pump or thermostat stuck closed.",
            "Check coolant level and fan operation; stop driving if it keeps climbing."),
        0x5C to Info("Engine oil temperature, usually calculated or read from a sensor in the oil circuit.",
            "Engine not fully warm, short trips.", "Heavy load or low oil level, cooling problem.", "Check the oil level and avoid hard driving until it is back in range."),
        0x0F to Info("Temperature of the air going into the engine.", "Very cold air, or a sensor reading low.", "Heat soak in traffic, or hot air being drawn in: less power and economy.",
            "Check the intake ducting and air box; it falls again once you are moving."),
        0x0C to Info("Engine speed. Idle should be steady once the engine is warm.",
            "Rough or low idle: dirty throttle body, weak spark, vacuum leak or low fuel pressure.",
            "High idle: vacuum leak, throttle body problem, or the ECU holding revs for a cold start or cat heat-up.",
            "Clean the throttle body, check for intake air leaks and the spark plugs."),
        0x0D to Info("Vehicle speed from the ECU.", "", "Above the speed limit you set for this car.", "Slow down."),
        0x04 to Info("Calculated engine load: how much of the maximum air the engine is using.",
            "Very low load at idle can mean a sensor fault.", "High load at idle means the engine works hard for no reason: air leak, dragging accessory or clogged exhaust.",
            "Compare with the MAP and MAF readings."),
        0x11 to Info("Throttle plate opening.", "", "High throttle at idle: dirty or sticking throttle body.", "Clean the throttle body and run a throttle relearn."),
        0x06 to Info("Short-term fuel trim: the quick correction the ECU adds or removes to keep the mixture at the ideal ratio.",
            "Negative: the ECU is removing fuel because the mixture is rich.", "Positive: the ECU is adding fuel because the mixture is lean (extra air or too little fuel).",
            "Large swings together with a high long-term trim point to an air leak or a fuel delivery problem."),
        0x07 to Info("Long-term fuel trim: the learned correction the ECU keeps over time.",
            "Negative: running rich: leaking injector, stuck purge valve or a faulty oxygen sensor.",
            "Positive: running lean: vacuum or intake leak, dirty MAF sensor or weak fuel pump.", "Check for air leaks first, then the MAF sensor and fuel pressure."),
        0x0B to Info("Absolute pressure in the intake manifold. Low means high vacuum.",
            "Lower than normal at idle: engine is sealed well, or a sensor reads low.", "High at idle: vacuum leak, late valve timing or a leaking gasket.",
            "Spray-test or smoke-test the intake for leaks."),
        0x10 to Info("Mass of air entering the engine.", "Low for the engine size: dirty or failing MAF sensor, intake restriction.",
            "High at idle: air leak after the sensor or throttle stuck open.", "Clean the MAF sensor with proper cleaner and check the air filter."),
        0x0E to Info("Ignition timing relative to top dead centre.", "Retarded timing: knock control, heat, poor fuel or a sensor fault.", "Too much advance is rare at idle; check codes.",
            "Use good fuel and look for knock-related codes."),
        0x42 to Info("Supply voltage seen by the engine computer.",
            "Low while running: weak alternator, slipping belt or poor connection. With engine off: discharged battery.",
            "High: faulty voltage regulator, or the charge limiter is set too high.", "Test the alternator output and the battery."),
        0x2F to Info("Fuel tank level (or battery state of charge on EVs).", "Low: refuel soon.", "", "Refuel."),
        0x33 to Info("Atmospheric pressure at the sensor. Falls with altitude.", "Normal in the mountains.", "Unusually high is a sensor fault.", "Compare with the weather pressure at sea level."),
        0x3C to Info("Catalytic converter temperature.", "Cold: engine not warm, or a misfire cooled the exhaust.", "Very hot: misfire or rich mixture burning in the catalyst.", "Check for misfire codes."),
        0x15 to Info("Oxygen sensor after the catalyst. A healthy converter keeps it steady.", "Very low and steady: lean exhaust or a dead sensor.", "Switching like the front sensor: the catalyst is worn.", "Test the catalyst efficiency."),
        0x24 to Info("Air-fuel ratio in lambda; 1.00 is the ideal ratio.", "Rich (below 1): too much fuel.", "Lean (above 1): too much air.", "Check trims and air leaks."),
        0x2D to Info("Difference between the commanded and measured EGR flow.", "", "Large error: clogged or sticking EGR valve.", "Clean the EGR valve and passages."),
        0x21 to Info("Distance driven with the check-engine lamp on.", "", "Above zero: the lamp has been on.", "Read the fault codes in Diagnostics."),
        0x01 to Info("Check-engine lamp state and the number of stored codes.", "", "MIL ON: a fault is stored.", "Read the fault codes in Diagnostics."),
        0x03 to Info("Fuel system loop status. Closed loop uses the oxygen sensor to adjust fuel.", "", "Staying open loop when warm points to an oxygen sensor or coolant sensor fault.", "Check the sensors.")
    )

    fun infoFor(pid: Int): Info? = info[pid] ?: when (pid) {
        0x08 -> info[0x06]; 0x09 -> info[0x07]; BATT -> info[0x42]; 0x5B -> info[0x2F]
        0x3D, 0x3E -> info[0x3C]; 0x34 -> info[0x24]; 0x4D -> info[0x21]; 0x43 -> info[0x04]
        else -> null
    }

    /** Readings that are not in the normal range right now, worst first. */
    class Finding(val pid: Int, val name: String, val st: Status, val value: Double)

    fun findings(c: SpecCtx): List<Finding> {
        val out = ArrayList<Finding>()
        for ((p, v) in c.v) {
            if (Pids.isBitmap(p)) continue
            val s = status(p, v, c) ?: continue
            if (s.level >= 1) out.add(Finding(p, nameOf(p), s, v))
        }
        c.battV?.let { b -> status(BATT, b, c)?.let { if (it.level >= 1 && !c.v.containsKey(0x42)) out.add(Finding(BATT, nameOf(BATT), it, b)) } }
        return out.sortedByDescending { it.st.level }
    }

    class Health(val ok: Int, val watch: Int, val out: Int) { val total get() = ok + watch + out }

    fun health(c: SpecCtx): Health {
        var ok = 0; var w = 0; var o = 0
        for ((p, v) in c.v) {
            if (Pids.isBitmap(p)) continue
            val s = status(p, v, c) ?: continue
            if (s.label == "Warming up") continue
            when (s.level) { 0 -> ok++; 1 -> w++; else -> o++ }
        }
        return Health(ok, w, o)
    }

    /* ---------------------------- AI suggestions ---------------------------- */

    fun suggestions(c: SpecCtx): List<Sug> {
        val out = ArrayList<Sug>()
        val v = c.v
        val st = v[0x06]; val lt = v[0x07]; val map = v[0x0B]; val maf = v[0x10]; val rpm = c.rpm
        val ltLvl = lt?.let { status(0x07, it, c) }?.level ?: 0
        val mapHigh = c.idle && c.warm && !c.diesel && map != null && (status(0x0B, map, c)?.dir ?: 0) > 0
        if (lt != null && lt > 10 && ltLvl >= 1) {
            if (mapHigh || (st != null && st > 5)) {
                val extra = if (mapHigh) "high idle manifold pressure" else "short-term trim %+.1f %%".format(st ?: 0.0)
                out.add(Sug(if (lt > 20) 2 else 1, "Likely air leak after the MAF sensor",
                    "Long-term trim %+.1f %% (the ECU adds fuel) together with %s.".format(lt, extra),
                    "Smoke-test the intake and hoses; check the PCV hose and the intake gasket.", listOf(0x07, 0x0B)))
            }
            else out.add(Sug(if (lt > 20) 2 else 1, "Mixture is running lean",
                "Long-term trim %+.1f %%.".format(lt) , "Clean the MAF sensor, check the fuel pressure and the air filter.", listOf(0x07, 0x10)))
        } else if (lt != null && lt < -10 && ltLvl >= 1) {
            out.add(Sug(if (lt < -20) 2 else 1, "Mixture is running rich", "Long-term trim %+.1f %% (the ECU removes fuel).".format(lt),
                "Check the purge valve, injectors and the front oxygen sensor; a dirty air filter also does this.", listOf(0x07)))
        }
        c.cool?.let { cool ->
            if (c.runS > 900 && cool < 70 && c.running) out.add(Sug(1, "Engine is not reaching operating temperature",
                "Coolant %s after %d min running.".format(Units.tStr(cool), (c.runS / 60).toInt()), "Thermostat probably stuck open; replace it. Economy and heater suffer meanwhile.", listOf(0x05)))
            if (cool >= Prefs.coolantMax - 3 && c.running) out.add(Sug(if (cool >= Prefs.coolantMax) 2 else 1, "Engine running hot",
                "Coolant %s with %.0f %% load.".format(Units.tStr(cool), v[0x04] ?: 0.0),
                "Check coolant level and that the cooling fan runs. Pull over if it keeps rising.", listOf(0x05)))
        }
        val bv = c.battV ?: v[0x42]
        if (bv != null && c.running) {
            if (bv < Prefs.chargeMinV - 0.4) out.add(Sug(2, "Charging voltage is low", "%.2f V while the engine runs; expected %.1f–%.1f V.".format(bv, Prefs.chargeMinV, Prefs.chargeMaxV),
                "Test the alternator, belt and battery terminals. Do this soon so the battery does not drain.", listOf(0x42)))
            else if (bv > Prefs.chargeMaxV + 0.3) out.add(Sug(2, "Charging voltage is too high", "%.2f V while running.".format(bv),
                "Faulty regulator or wrong charge-limit setting; a high voltage can damage the battery and electronics.", listOf(0x42)))
        } else if (bv != null && !c.running) {
            if (bv < 11.9) out.add(Sug(2, "Battery is nearly flat", "%.2f V with the engine off.".format(bv), "Charge the battery and then test it; it may not start the car again.", listOf(0x42)))
            else if (bv < 12.3) out.add(Sug(1, "Battery is low", "%.2f V at rest.".format(bv), "Take a longer drive or charge it; test it if this repeats.", listOf(0x42)))
        }
        if (c.idle && c.warm) {
            val ir = status(0x0C, rpm, c)
            if (ir != null && ir.level >= 1 && ir.dir > 0) out.add(Sug(1, "Idle speed is high", "%.0f rpm with a warm engine.".format(rpm),
                if (mapHigh) "High idle plus high manifold pressure: look for a vacuum leak." else "Clean the throttle body; a stuck idle valve or a vacuum leak also raises idle.", listOf(0x0C)))
            if (ir != null && ir.level >= 1 && ir.dir < 0) out.add(Sug(1, "Idle speed is low", "%.0f rpm with a warm engine.".format(rpm),
                "Clean the throttle body and check the spark plugs and the MAF sensor.", listOf(0x0C)))
            ObdManager.idleRpmStdDev()?.let { sd -> if (sd > 90) out.add(Sug(1, "Idle is hunting", "RPM varies by ±%.0f.".format(sd),
                "Check spark plugs, coils and vacuum leaks.", listOf(0x0C))) }
        }
        val iat = v[0x0F]
        if (iat != null && iat > 65) out.add(Sug(1, "Intake air is hot", "%s at the intake.".format(Units.tStr(iat)), "Heat soak lowers power and economy; it clears once you move. Check the air box if it stays high.", listOf(0x0F)))
        val ct = v[0x3C]
        if (ct != null && ct > 950) out.add(Sug(2, "Catalytic converter is overheating", "%s at the catalyst.".format(Units.tStr(ct)), "Possible misfire or rich mixture. Avoid hard driving and read the fault codes.", listOf(0x3C)))
        if (maf != null && rpm > 1500 && c.spd > 20 && c.veh.dispL > 0 && !c.diesel) {
            val ve = maf * 120.0 / rpm / (c.veh.dispL * 1.184) * 100
            val load = v[0x04]
            if (load != null && load > 55 && ve < 35) out.add(Sug(1, "Low air flow for this load", "Airflow equals %.0f %% volumetric efficiency at %.0f %% load.".format(ve, load),
                "A dirty MAF sensor or a clogged air filter; check both.", listOf(0x10, 0x04)))
        }
        for (f in findings(c)) {
            if (out.any { it.pids.contains(f.pid) }) continue
            if (f.pid == 0x0D || f.pid == 0x0C && !c.idle) continue
            val inf = infoFor(f.pid) ?: continue
            val txt = if (f.st.dir < 0) inf.low else inf.high
            if (txt.isBlank()) continue
            out.add(Sug(f.st.level, "${f.name}: ${f.st.label.lowercase().replaceFirstChar { it.uppercase() }}", "%s: %s".format(fmt(f.pid, f.value), txt), inf.check, listOf(f.pid)))
        }
        for (k in listOf(0x0C, 0x05, 0x42, 0x07, 0x04, 0x0B, 0x10)) {
            val d = drift(k, c) ?: continue
            if (out.any { it.pids.contains(k) }) continue
            val key = baseKey(k, c) ?: continue
            out.add(Sug(1, "${baseNames()[key] ?: Pids.nameOf(k)} differs from your car's usual",
                "Now %s, your car normally runs %s (±%s).".format(fmt(k, ema[k] ?: 0.0), fmt(k, d.first.mean), fmt(k, d.first.sd)),
                "Still inside the general range, so it is only a hint. Watch whether it keeps drifting.", listOf(k)))
        }
        return out.sortedByDescending { it.sev }
    }
}
