package com.dilip.carobd

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

/** Fixed-size history buffer for live graphs. */
class Ring(private val size: Int = 240) {
    private val buf = FloatArray(size)
    private var n = 0
    private var head = 0

    @Synchronized
    fun add(v: Float) {
        buf[head] = v; head = (head + 1) % size; if (n < size) n++
    }

    @Synchronized
    fun values(): FloatArray = FloatArray(n) { buf[(head - n + it + size) % size] }
}

object ObdManager {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    @Volatile var state = State.DISCONNECTED
        private set
    @Volatile var status = "Not connected"
    @Volatile var deviceName = ""
    val values = ConcurrentHashMap<Int, Double>()
    val texts = ConcurrentHashMap<Int, String>()
    val customValues = ConcurrentHashMap<String, String>()
    val history = ConcurrentHashMap<Int, Ring>()
    @Volatile var monitorRaw: IntArray? = null
    @Volatile var supported: Set<Int> = emptySet()
    @Volatile var batteryV: Double? = null
    @Volatile var elmVersion = ""
    @Volatile var protocol = ""
    @Volatile var vin = ""
    @Volatile var diagText = "Connect to the car, then choose an action above."
    @Volatile var busy = false
    @Volatile var cycleMs = 0L
    @Volatile var logFile = ""
    @Volatile var engineOn = false
        set(v) { field = v; try { ObdService.refreshWake() } catch (_: Exception) {} }
    /** True while the app is on screen; live screens (gauges, drive mode…) want fast data, everything else can poll slowly. */
    @Volatile var appVisible = true
    @Volatile var livePage = true
    @Volatile var wakeUntil = 0L
    private var lastPrio = -1
    @Volatile var ecuReady = false
    @Volatile var lastEvent: DriveEvent? = null
    @Volatile var lastEventAt = 0L
    @Volatile var vehicle: Vehicle = Vehicle()
    val engine = TripEngine()
    val battHist = Ring(900)
    @Volatile var perfMode = false
    @Volatile var lastCrank: Pair<Double, Double>? = null   // (min during crank, resting before)
    private val vSamples = ArrayDeque<Pair<Long, Double>>()
    @Volatile private var preCrankUntil = 0L
    @Volatile private var preCrank = false
    private var fuelChecked = false
    private var ltKmpl: Double? = null
    private var ltKmplAt = 0L

    private val logLines = ArrayDeque<String>()
    private val jobs = LinkedBlockingQueue<(Elm327) -> Unit>()
    private val idleRpm = ArrayDeque<Double>()
    @Volatile private var transport: ObdTransport? = null
    @Volatile private var userStopped = false
    @Volatile private var odoFromEcu = false
    lateinit var app: Context
        private set

    private val FAST = listOf(0x0C, 0x0D, 0x10, 0x0B, 0x11, 0x04, 0x5E, 0x44, 0x05)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        Prefs.init(app)
        Db.init(app)
        Alerts.init(app)
        vehicle = Db.activeVehicle()
        engine.onEvent = { e -> onDriveEvent(e) }
    }

    fun reloadVehicle() {
        vehicle = Db.activeVehicle()
    }

    fun log(s: String) {
        synchronized(logLines) {
            logLines.addLast(s)
            while (logLines.size > 200) logLines.removeFirst()
        }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    /* ---------------- connection ---------------- */

    fun connect(ctx: Context, factory: () -> ObdTransport) {
        init(ctx)
        if (state != State.DISCONNECTED) return
        userStopped = false
        userDisconnected = false
        state = State.CONNECTING
        status = "Connecting…"
        values.clear(); texts.clear(); customValues.clear(); history.clear(); batteryV = null
        SpecEngine.reset()
        AlertCenter.resetSession()
        jobs.clear()
        reloadVehicle()
        try {
            val i = Intent(app, ObdService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (e: Exception) {
            log("Service start failed: ${e.message}")
        }
        Thread({ runLoop(factory) }, "obd-worker").start()
    }

    @Volatile var userDisconnected = false

    fun disconnect() {
        userDisconnected = true
        userStopped = true
        transport?.close()
    }

    private val vinRe = Regex("[A-HJ-NPR-Z0-9]{17}")

    /** Looks for the 17-character VIN: Mode 09 PID 02, then (deep) other ECU addresses and UDS F190. */
    fun readVin(e: Elm327, deep: Boolean): String? {
        fun pick(s: String) = vinRe.find(s.replace(" ", "").uppercase())?.value
        try { pick(e.info09(0x02))?.let { return it } } catch (_: Exception) {}
        if (!deep) return null
        try {
            if (e.isCan) {
                for (h in listOf("7E0", "7E1", "7E2", "7E3")) try { pick(e.info09(0x02, h))?.let { return it } } catch (_: Exception) {}
                for (h in listOf("7E0", "7E1", "7E2", "7E3", "7E4")) try { pick(e.udsVin(h))?.let { return it } } catch (_: Exception) {}
            } else try { pick(e.info09(0x02))?.let { return it } } catch (_: Exception) {}
        } finally { try { e.setHeader(null) } catch (_: Exception) {} }
        return null
    }

    /** Searches the car for its VIN and stores it on the active vehicle. Callback runs on the main thread. */
    fun fetchVin(cb: (String?, String) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (state != State.CONNECTED) { cb(null, "Connect to the car first (ignition ON), then try again."); return }
        jobs.add { e ->
            val v = readVin(e, deep = true)
            if (v != null) {
                vin = v; vehicle.vin = v; Db.saveVehicle(vehicle)
                main.post { cb(v, "VIN read from the car's ECU") }
            } else main.post { cb(null, "This car did not share its VIN over OBD (common on some models). Enter it from the RC book or the windscreen plate.") }
        }
    }

    fun runDiag(title: String, block: (Elm327) -> String) {
        if (state != State.CONNECTED) {
            diagText = "Not connected."
            return
        }
        busy = true
        diagText = "$title…"
        jobs.add { e ->
            diagText = try {
                block(e)
            } catch (ex: Exception) {
                "Error: ${ex.message}"
            }
            busy = false
        }
    }

    fun terminal(cmd: String) {
        if (state != State.CONNECTED) return
        jobs.add { e -> e.raw(cmd.trim().uppercase(), 5000) }
    }

    /** Delay between poll cycles: adapts to what the user is looking at and whether the car is standing still. */
    private fun adaptiveDelay(): Int {
        val base = Prefs.pollDelayMs
        if (Prefs.powerMode == 0 || perfMode) return base
        val eco = Prefs.powerMode == 2
        val still = (values[0x0D] ?: 0.0) < 1.0
        return when {
            appVisible && livePage -> base
            appVisible -> if (eco) 700 else 300
            else -> (if (eco) 1800 else 700) + (if (still) (if (eco) 1200 else 500) else 0)
        }
    }

    /** True when nobody needs the full live data set (screen off or a non-live page). */
    private fun lightPolling() = Prefs.powerMode > 0 && !(appVisible && livePage) && !perfMode

    private fun applyBlePriority(parked: Boolean) {
        val want = when {
            Prefs.powerMode == 0 -> 1
            parked -> 2
            appVisible && livePage -> 1
            Prefs.powerMode == 2 || !appVisible -> 2
            else -> 0
        }
        if (want == lastPrio) return
        lastPrio = want
        (transport as? BleTransport)?.setPriority(want)
    }

    private fun sleepCheck(ms: Long, breakOnJob: Boolean = false) {
        var left = ms
        while (left > 0 && !userStopped && !(breakOnJob && (jobs.isNotEmpty() || (appVisible && livePage)))) {
            Thread.sleep(minOf(250L, left)); left -= 250
        }
    }

    private fun runLoop(factory: () -> ObdTransport) {
        var attempt = 0
        try {
            while (!userStopped) {
                val ok = try {
                    session(factory) { attempt = 0 }
                    true
                } catch (e: Exception) {
                    status = "Error: ${e.message}"
                    log("ERROR: ${e.message}")
                    if (!userStopped && engineOn && state == State.CONNECTED)
                        AlertCenter.raise(app, "link", AlertCenter.WARN, "OBD adapter disconnected",
                            "Lost connection to ${deviceName.ifEmpty { "the adapter" }} while the engine was running" +
                                    (if (Prefs.autoReconnect) " – reconnecting automatically." else "."), 5 * 60_000L)
                    false
                } finally {
                    try { transport?.close() } catch (_: Exception) {}
                    transport = null
                }
                if (userStopped || !Prefs.autoReconnect) break
                attempt++
                if (attempt > 40) break
                state = State.CONNECTING
                status = (if (ok) "Link closed" else status) + " – reconnecting in 10 s (try $attempt)"
                // back off when the car/adapter stays away: 10 s, then 30 s, then 60 s (saves phone battery)
                sleepCheck(if (Prefs.powerMode == 0) 10_000L else when { attempt <= 5 -> 10_000L; attempt <= 15 -> 30_000L; else -> 60_000L })
            }
        } finally {
            stopTrip()
            if (!Prefs.roadAssist) Gps.stop()
            if (!Prefs.roadAssist && (!Prefs.backgroundWatch || userDisconnected)) try { app.stopService(Intent(app, ObdService::class.java)) } catch (_: Exception) {}
            else if (!Prefs.roadAssist) ObdService.update(app, AppInfo.NAME, "Waiting for the car – will connect automatically")
            if (!status.startsWith("Error")) status = "Disconnected"
            busy = false
            engineOn = false
            state = State.DISCONNECTED
            CarWidget.update(app)
        }
    }

    /** Initialises the ECU side; returns false if the ECU is asleep (ignition off). */
    private fun prepareEcu(elm: Elm327): Boolean {
        try {
            elm.init()
        } catch (e: Exception) {
            if (!transportUp()) throw e
            log("ECU not ready: ${e.message}")
            return false
        }
        elmVersion = elm.version
        protocol = elm.protocol
        supported = elm.supportedPids()
        try {
            val found = readVin(elm, deep = false)
            vin = found ?: ""
            if (found != null && vehicle.vin.isEmpty()) {
                vehicle.vin = found; Db.saveVehicle(vehicle)
            }
        } catch (_: Exception) {
        }
        if (0xA6 in supported) {
            elm.pid01(0xA6)?.let { d ->
                Pids.byId[0xA6]?.decode?.invoke(d)?.let { odo ->
                    if (odo > 0) {
                        vehicle.odoKm = odo; odoFromEcu = true; Db.saveVehicle(vehicle)
                    }
                }
            }
        }
        ecuReady = true
        engineOn = true
        CarWidget.update(app)
        if (Prefs.autoDtc) autoDtcScan(elm)
        return true
    }

    private fun transportUp() = transport?.connected == true

    private fun session(factory: () -> ObdTransport, onConnected: () -> Unit) {
        val t = factory()
        transport = t
        deviceName = t.name
        status = "Opening ${t.name}…"
        t.open()
        val elm = Elm327(t, ::log, Prefs.protocol)
        status = "Initialising ELM327…"
        ecuReady = false
        odoFromEcu = false
        prepareEcu(elm)
        state = State.CONNECTED
        onConnected()
        Gps.start(app)
        var csv: Writer? = if (Prefs.csvLogging) openCsv() else null

        val slow = supported.filter { !Pids.isBitmap(it) && it !in FAST }.toMutableList()
        if (0x01 !in slow) slow.add(0, 0x01)
        if (Prefs.voltMethod == "ECU" && 0x42 in supported && 0x42 !in slow) slow.add(0x42)
        var slowIdx = 0
        var customIdx = 0
        var cycle = 0
        var lastCsv = 0L
        var lastTrack = 0L
        var lastSave = 0L
        var lastOk = System.currentTimeMillis()
        var zeroRpmSince = 0L
        var waitTick = 0
        var lastNotif = 0L
        var parkedSince = 0L
        var gpsOn = true
        lastPrio = -1

        try {
            while (!userStopped) {
                while (true) {
                    val j = jobs.poll() ?: break
                    j(elm)
                }
                if (!transportUp()) throw java.io.IOException("Connection lost")
                val now0 = System.currentTimeMillis()

                if (!engineOn) {
                    // Ignition off: sample battery quickly (parked monitor + crank capture); a jump above
                    // 13.1 V means the alternator is charging, i.e. the engine has just been started.
                    if (parkedSince == 0L) parkedSince = now0
                    val eco = Prefs.powerMode == 2
                    // Balanced/Eco: after the first minutes parked, check the battery only now and then instead of continuously.
                    val slowPark = Prefs.powerMode > 0 && !(appVisible && livePage) && now0 - parkedSince > (if (eco) 20_000L else 120_000L) && now0 > wakeUntil
                    status = if (slowPark) "Ignition off – low-power parked monitor" else "Ignition off – parked battery monitor"
                    applyBlePriority(slowPark)
                    waitTick++
                    var samples = 0
                    var charging = false
                    if (slowPark) {
                        if (gpsOn && !Prefs.roadAssist) { Gps.stop(); gpsOn = false }
                        elm.batteryVoltage()?.let { bv -> samples = 1; recordVolt(bv); if (bv > 13.1) charging = true }
                        if (!charging) sleepCheck(if (eco) 15_000 else 8_000, breakOnJob = true)
                    } else {
                        val until = System.currentTimeMillis() + 5000
                        while (!userStopped && System.currentTimeMillis() < until && jobs.isEmpty()) {
                            val bv = elm.batteryVoltage() ?: break
                            samples++
                            recordVolt(bv)
                            if (bv > 13.1) { charging = true; break }
                            Thread.sleep(150)
                        }
                        if (samples == 0) sleepCheck(5000)
                    }
                    AlertCenter.check(app)
                    if (waitTick % 3 == 0) updateNotification()
                    val woke = if (!ecuReady) prepareEcu(elm)
                    else (elm.pid01(0x0C) ?: (if (vehicle.isEv) elm.pid01(0x0D) else null)) != null || ((charging || waitTick % 6 == 0) && prepareEcu(elm))
                    if (woke) {
                        parkedSince = 0L
                        if (!gpsOn) { Gps.start(app); gpsOn = true }
                        engineOn = true; lastOk = System.currentTimeMillis(); status = "Connected"
                        preCrank = true; preCrankUntil = System.currentTimeMillis() + 45_000
                        if (charging) analyseCrank()
                        if (slow.size <= 1) {
                            slow.clear()
                            slow.addAll(supported.filter { !Pids.isBitmap(it) && it !in FAST })
                            if (0x01 !in slow) slow.add(0, 0x01)
                            if (Prefs.voltMethod == "ECU" && 0x42 in supported && 0x42 !in slow) slow.add(0x42)
                        }
                    }
                    if (!engineOn) continue
                }
                status = "Connected"

                var rpmOk = false
                val crankWatch = preCrank && now0 < preCrankUntil
                if (perfMode || crankWatch) {
                    // fast path: speed + RPM only (performance timing / crank-voltage capture)
                    val okR = readPid(elm, 0x0C)
                    val okS = readPid(elm, 0x0D)
                    rpmOk = okR || okS
                    if (crankWatch) {
                        elm.batteryVoltage()?.let { recordVolt(it) }
                        if ((values[0x0C] ?: 0.0) > 400) {
                            analyseCrank(); preCrank = false
                        }
                    }
                } else {
                    for (p in FAST) if (p in supported) {
                        val ok = readPid(elm, p)
                        if (p == 0x0C && ok) rpmOk = true
                    }
                    if (0x0C !in supported && 0x0D in supported) rpmOk = true
                    val light = lightPolling()
                    repeat(if (light) 1 else 3) {
                        if (slow.isNotEmpty()) {
                            readPid(elm, slow[slowIdx % slow.size]); slowIdx++
                        }
                    }
                    val customs = Prefs.customPids
                    if (customs.isNotEmpty() && !light) {
                        readCustom(elm, customs[customIdx % customs.size]); customIdx++
                    }
                    if (cycle % 10 == 0) elm.batteryVoltage()?.let { recordVolt(it) }
                    if (preCrank && (values[0x0C] ?: 0.0) > 400) preCrank = false
                }
                cycle++

                val now = System.currentTimeMillis()
                if (rpmOk) lastOk = now
                else if (now - lastOk > 20_000) {
                    log("No ECU response for 20 s – ignition off")
                    parkedSince = 0L
                    stopTrip()
                    engineOn = false
                    values.remove(0x0C); values.remove(0x0D)
                    continue
                }

                CarWidget.maybeUpdate(app)
                val rpm = values[0x0C]
                val spd = values[0x0D]
                val running = if (vehicle.isEv) (spd ?: 0.0) > 0.5 else (rpm ?: 0.0) > 400
                if (vehicle.isEv) engine.current?.let { tc ->
                    val soc = values[0x2F]
                    if (soc != null && tc.fuelStartPct >= 0 && soc <= tc.fuelStartPct) tc.fuel = (tc.fuelStartPct - soc) / 100.0 * vehicle.tankL
                }
                if (spd != null && spd < 1 && running) {
                    synchronized(idleRpm) {
                        rpm?.let { idleRpm.addLast(it) }
                        while (idleRpm.size > 120) idleRpm.removeFirst()
                    }
                }
                if (Prefs.autoTrip && engine.current == null && running) startTrip()
                if (engine.current != null && Prefs.autoTrip) {
                    if (!running) {
                        if (zeroRpmSince == 0L) zeroRpmSince = now
                        if (now - zeroRpmSince > 90_000) stopTrip()
                    } else zeroRpmSince = 0L
                }
                engine.update(spd, rpm, fuelRate(), values[0x05])
                AlertCenter.check(app)
                if (now - lastNotif >= 5000) { lastNotif = now; updateNotification() }
                val tr0 = engine.current
                if (tr0 != null && !fuelChecked && now - tr0.start > 90_000) {
                    fuelChecked = true
                    val f = values[0x2F]
                    if (f != null) {
                        val prev = Db.trips(vehicle.id, 0, 3).firstOrNull { it.id != tr0.id && it.end > 0 }
                        if (prev != null) AlertCenter.checkFuelJump(app, prev.fuelEndPct, f, vehicle.tankL, vehicle.spec.unit)
                    }
                }

                val tr = engine.current
                if (tr != null && now - lastTrack >= 2000) {
                    lastTrack = now
                    val g = Gps.fresh()
                    try {
                        Db.insertTrack(tr.id, TrackPoint(now, g?.latitude, g?.longitude, spd ?: 0.0, rpm ?: 0.0,
                            g?.let { if (it.hasSpeed()) it.speed * 3.6 else null }))
                    } catch (_: Exception) {
                    }
                }
                if (tr != null && now - lastSave >= 30_000) {
                    lastSave = now
                    try { Db.updateTrip(tr) } catch (_: Exception) {}
                }
                if (csv != null && now - lastCsv >= 1000) {
                    lastCsv = now
                    writeCsvRow(csv)
                }
                cycleMs = now - now0
                applyBlePriority(false)
                val d = adaptiveDelay()
                if (d > 0) Thread.sleep(d.toLong())
            }
        } finally {
            try { csv?.close() } catch (_: Exception) {}
            csv = null
        }
    }

    /* ---------------- trips & events ---------------- */

    /** Something happened that may mean the car just woke (e.g. car Bluetooth connected): poll normally for a while. */
    fun wakeNow(ms: Long = 3 * 60_000L) {
        wakeUntil = System.currentTimeMillis() + ms
        try { ObdService.refreshWake() } catch (_: Exception) {}
    }

    @Synchronized
    fun startTrip() {
        if (engine.current != null) return
        fuelChecked = false
        val merge = Prefs.tripMergeMin
        if (merge > 0) {
            try {
                val last = Db.trips(vehicle.id, 0, 1).firstOrNull()
                if (last != null && last.end > 0 && System.currentTimeMillis() - last.end < merge * 60_000L) {
                    engine.resume(vehicle, last)
                    fuelChecked = true
                    log("Trip resumed (stop shorter than $merge min)")
                    return
                }
            } catch (_: Exception) {
            }
        }
        val t = engine.start(vehicle, values[0x2F])
        try { Db.insertTrip(t) } catch (e: Exception) { log("DB: ${e.message}") }
    }

    /** Range left from fuel level and recent economy (trip average once > 5 km, else 30-day average). */
    fun rangeKm(): Double? {
        val f = values[0x2F] ?: return null
        val t = engine.current
        val k = t?.kmpl?.takeIf { t.distanceKm > 5 } ?: longTermKmpl() ?: return null
        return f / 100.0 * vehicle.tankL * k
    }

    fun longTermKmpl(): Double? {
        val now = System.currentTimeMillis()
        if (now - ltKmplAt > 10 * 60_000L) {
            ltKmplAt = now
            ltKmpl = try {
                val tr = Db.trips(vehicle.id, now - 30 * 86_400_000L)
                val km = tr.sumOf { it.distanceKm }
                val fu = tr.sumOf { it.fuel }
                if (km > 10 && fu > 0.3) km / fu else null
            } catch (_: Exception) { null }
        }
        return ltKmpl
    }

    @Volatile private var ecuVoltAt = 0L
    private fun recordVolt(v: Double, fromEcu: Boolean = false) {
        if (fromEcu) ecuVoltAt = System.currentTimeMillis()
        else if (Prefs.voltMethod == "ECU" && System.currentTimeMillis() - ecuVoltAt < 15_000) return
        batteryV = v
        battHist.add(v.toFloat())
        val now = System.currentTimeMillis()
        synchronized(vSamples) {
            vSamples.addLast(now to v)
            while (vSamples.isNotEmpty() && now - vSamples.first().first > 25_000) vSamples.removeFirst()
        }
    }

    /** Engine has just started: the lowest voltage in the last seconds is the cranking dip. */
    private fun analyseCrank() {
        val list = synchronized(vSamples) { vSamples.toList() }
        if (list.size < 4) return
        val now = System.currentTimeMillis()
        val recent = list.filter { now - it.first < 10_000 }
        val minP = recent.minByOrNull { it.second } ?: return
        val before = list.filter { it.first < minP.first - 300 }
        val rest = (before.maxOfOrNull { it.second } ?: list.first().second).coerceAtMost(13.0)
        if (rest - minP.second < 0.4) return   // no visible dip – crank happened between samples
        lastCrank = minP.second to rest
        log("Crank: %.2f V (rest %.2f V)".format(minP.second, rest))
        try {
            val a = org.json.JSONArray(Prefs.crankHistory)
            a.put(org.json.JSONObject().put("t", now).put("min", minP.second).put("rest", rest).put("vid", vehicle.id))
            val out = org.json.JSONArray()
            for (i in maxOf(0, a.length() - 30) until a.length()) out.put(a.get(i))
            Prefs.crankHistory = out.toString()
        } catch (_: Exception) {
        }
        if (minP.second < Prefs.crankLowV)
            AlertCenter.raise(app, "crank", AlertCenter.WARN, "Weak battery – cranking %.1f V".format(minP.second),
                "Voltage dropped to %.1f V while starting (rested %.1f V). A healthy battery stays above about %.1f V. Get the battery tested.".format(minP.second, rest, Prefs.crankLowV),
                6 * 3600_000L, "Battery is weak")
    }

    private fun updateNotification() {
        if (!::app.isInitialized) return
        val t = engine.current
        val text = when {
            t != null -> "▶ Trip %.1f km · %s · %s · score %d".format(t.distanceKm, hmsS(t.durationS),
                t.kmpl?.let { "%.1f km/%s".format(it, vehicle.spec.unit) } ?: "--", t.score)
            engineOn -> "Engine on · ${values[0x0D]?.let { "%.0f km/h".format(it) } ?: ""} · ${batteryV?.let { "%.1f V".format(it) } ?: ""}"
            else -> "Ignition off · battery ${batteryV?.let { "%.2f V".format(it) } ?: "--"}"
        }
        ObdService.update(app, "${vehicle.name} – ${AppInfo.NAME}", text)
    }

    private fun hmsS(s: Double): String {
        val x = s.toLong()
        return "%d:%02d:%02d".format(x / 3600, (x % 3600) / 60, x % 60)
    }

    @Synchronized
    fun stopTrip() {
        val t = engine.stop(values[0x2F]) ?: return
        try {
            if (t.distanceKm > 0.05 || t.durationS > 60) {
                Db.updateTrip(t)
                if (!odoFromEcu) {
                    vehicle.odoKm += t.distanceKm - t.odoCounted
                    t.odoCounted = t.distanceKm
                    Db.updateTrip(t)
                    Db.saveVehicle(vehicle)
                }
                (Gps.fresh() ?: Gps.last)?.let { g ->
                    Prefs.parkLat = g.latitude; Prefs.parkLon = g.longitude; Prefs.parkT = System.currentTimeMillis()
                }
                if (Prefs.tripNotify && ::app.isInitialized) {
                    val k = t.kmpl
                    Alerts.notify(
                        app, 2000, "Trip saved – %.1f km".format(t.distanceKm),
                        "Economy: ${if (k != null) "%.1f km/${vehicle.spec.unit}".format(k) else "--"} · " +
                                "Score ${t.score} · ${t.events} events · cost %.0f".format(t.cost)
                    )
                    Maint.checkAndNotify(app)
                }
            } else Db.deleteTrip(t.id)
        } catch (e: Exception) {
            log("DB: ${e.message}")
        }
    }

    private fun onDriveEvent(e: DriveEvent) {
        Gps.fresh()?.let { e.lat = it.latitude; e.lon = it.longitude }
        lastEvent = e
        lastEventAt = System.currentTimeMillis()
        try { Db.insertEvent(e) } catch (_: Exception) {}
        if (e.type == DriveEvent.EV_BRAKE || e.type == DriveEvent.EV_ACC) Alerts.beepIfQuiet()
        if (::app.isInitialized)
            AlertCenter.raise(app, "ev_${e.type}", if (e.type == DriveEvent.EV_SUDDEN) AlertCenter.WARN else AlertCenter.INFO,
                e.label, "Driving event during trip on ${vehicle.name}.", 0L, notify = Prefs.notifyDrivingEvents)
    }

    fun onGps(l: Location) {
        engine.onGps(l, values[0x0D])
    }

    private fun autoDtcScan(elm: Elm327) {
        try {
            val all = elm.readDtcs("03", "Stored") + elm.readDtcs("07", "Pending")
            val fresh = all.filter { Db.insertDtc(vehicle.id, it) }
            if (fresh.isNotEmpty()) {
                val txt = fresh.joinToString("\n") { "${it.code} ${DtcDb.describe(it.code)}" }
                AlertCenter.raise(app, "dtc", AlertCenter.CRIT, "New fault code(s) – ${vehicle.name}", txt, 60_000L,
                    "Warning. New engine fault code detected.")
                diagText = "Auto-scan found new codes:\n$txt\n\nOpen 'Read DTCs' for details."
            }
        } catch (e: Exception) {
            log("Auto DTC scan failed: ${e.message}")
        }
    }

    /* ---------------- reading ---------------- */

    private fun readPid(elm: Elm327, p: Int): Boolean {
        val d = elm.pid01(p) ?: return false
        val def = Pids.byId[p]
        try {
            if (p == 0x01) monitorRaw = d
            if (def == null) {
                texts[p] = d.joinToString(" ") { "%02X".format(it) }
                return true
            }
            def.decode?.let {
                var v = it(d)
                if (p == 0x0D) v *= 1.0 + Prefs.speedCorrPct / 100.0
                if (p == 0x42 && Prefs.voltMethod == "ECU") recordVolt(v, true)
                values[p] = v
                if (p == 0x5B && vehicle.isEv) values[0x2F] = v
                history.getOrPut(p) { Ring() }.add(v.toFloat())
                SpecEngine.feed(p, v)
                if (p == 0x0D) PerfMeter.onSpeed(System.currentTimeMillis(), v)
            }
            def.text?.let { texts[p] = it(d) }
        } catch (_: Exception) {
        }
        return true
    }

    private fun readCustom(elm: Elm327, c: CustomPid) {
        try {
            val req = c.request.replace(" ", "").uppercase()
            val rs = elm.request(req, c.header.ifBlank { null }, 3000)
            if (c.header.isNotBlank()) elm.setHeader(null)
            val first = req.substring(0, 2).toInt(16)
            val r = rs.firstOrNull { it.data.isNotEmpty() && it.data[0] == first + 0x40 }
            if (r == null) {
                customValues[c.name] = "no data"; return
            }
            val skip = req.length / 2
            val data = if (r.data.size > skip) r.data.copyOfRange(skip, r.data.size) else IntArray(0)
            val v = Expr(c.formula.ifBlank { "A" }, Expr.varsFrom(data)).eval()
            customValues[c.name] = "%.2f %s".format(v, c.unit)
        } catch (e: Exception) {
            customValues[c.name] = "err: ${e.message}"
        }
    }

    /**
     * Fuel consumption per hour in the vehicle's fuel unit (L/h, or kg/h for CNG).
     * Uses PID 5E if available, else MAF, else speed-density (MAP/IAT/RPM).
     */
    fun fuelRate(): Double? {
        val v = vehicle
        val spec = v.spec
        if (v.isEv) return null
        val corr = v.fuelCorr
        values[0x5E]?.let { return it * corr }
        val rpm = values[0x0C] ?: return null
        if (rpm < 300) return 0.0
        val fs = values[0x03]?.toInt()
        val thr = values[0x11] ?: 100.0
        if (fs == 4 && thr < 20 && rpm > 1100) return 0.0      // deceleration fuel cut
        val lambda = (values[0x44] ?: values[0x24] ?: values[0x34] ?: spec.lambda).coerceIn(0.6, 4.0)
        val afr = spec.afr * lambda
        val maf = values[0x10] ?: run {
            val map = values[0x0B] ?: return null
            val iat = values[0x0F] ?: 30.0
            val imap = rpm * map / (iat + 273.15) / 2.0
            (imap / 60.0) * v.ve * v.dispL * 28.97 / 8.314
        }
        return maf * 3600.0 / (afr * spec.density) * corr
    }

    fun idleRpmStdDev(): Double? {
        val l = synchronized(idleRpm) { idleRpm.toList() }
        if (l.size < 20) return null
        val m = l.average()
        return sqrt(l.sumOf { (it - m) * (it - m) } / l.size)
    }

    private val CSV_COLS = listOf(0x0C, 0x0D, 0x05, 0x0F, 0x0B, 0x10, 0x04, 0x11, 0x0E, 0x06, 0x07, 0x14, 0x15, 0x2F, 0x42, 0x5C, 0x3C)

    private fun openCsv(): Writer? {
        val name = "carobd_log_${stamp()}.csv"
        val os = Exporter.open(app, name) ?: return null
        logFile = "Downloads/CarOBD/$name"
        val w = os.bufferedWriter()
        w.write("time_ms,lat,lon," + CSV_COLS.joinToString(",") { Pids.nameOf(it).replace(",", "") } +
                ",battery_v,fuel_rate,inst_economy,accel_g,trip_km,trip_fuel\n")
        return w
    }

    private fun writeCsvRow(w: Writer) {
        try {
            val t = engine.current
            val g = Gps.fresh()
            w.write(
                System.currentTimeMillis().toString() + "," + (g?.latitude?.toString() ?: "") + "," +
                        (g?.longitude?.toString() ?: "") + "," +
                        CSV_COLS.joinToString(",") { values[it]?.let { v -> "%.2f".format(v) } ?: "" } + "," +
                        (batteryV?.toString() ?: "") + "," +
                        (fuelRate()?.let { "%.3f".format(it) } ?: "") + "," +
                        (engine.instantKmpl?.let { "%.2f".format(it) } ?: "") + "," +
                        "%.3f".format(engine.liveAccelG) + "," +
                        (t?.let { "%.3f".format(it.distanceKm) } ?: "") + "," +
                        (t?.let { "%.3f".format(it.fuel) } ?: "") + "\n"
            )
            w.flush()
        } catch (_: Exception) {
        }
    }
}
