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

/* ============================ Connect ============================ */

@SuppressLint("MissingPermission", "SetTextI18n")
class ConnectPage(a: MainActivity) : Page(a) {
    private lateinit var typeGroup: RadioGroup
    private lateinit var deviceBox: LinearLayout
    private lateinit var wifiBox: LinearLayout
    private lateinit var hostEt: android.widget.EditText
    private lateinit var portEt: android.widget.EditText
    private lateinit var infoTv: TextView
    private lateinit var logTv: TextView
    private var selected: BluetoothDevice? = null
    private val rows = HashMap<String, TextView>()
    private var scanning = false
    private val h = Handler(Looper.getMainLooper())

    override fun build(): View {
        val c = ui.col()
        c.addView(ui.section("Adapter type"))
        typeGroup = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("Bluetooth", "BLE 4.0", "WiFi").forEachIndexed { i, s ->
            typeGroup.addView(RadioButton(a).apply { text = s; id = 100 + i; setTextColor(Ui.WHITE) })
        }
        typeGroup.check(100 + Prefs.lastType)
        typeGroup.setOnCheckedChangeListener { _, id -> onType(id - 100) }
        c.addView(typeGroup)

        c.addView(ui.section("Device"))
        deviceBox = ui.vbox()
        c.addView(deviceBox)
        c.addView(ui.row(
            ui.btn("Refresh / scan") { refreshDevices() },
            ui.btn("Bluetooth settings", Ui.CARD) { a.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        ), ui.lp())

        hostEt = ui.edit("IP address", Prefs.wifiHost)
        portEt = ui.edit("Port", Prefs.wifiPort.toString(), true)
        wifiBox = ui.vbox().apply {
            addView(ui.tv("Join the adapter's WiFi (e.g. WiFi_OBDII, V-LINK) in phone settings first. Common: 192.168.0.10:35000 or 192.168.0.123:35000.", 12f, Ui.DIM))
            addView(ui.row(ui.labeled("IP", hostEt), ui.labeled("Port", portEt)), ui.lp(6))
        }
        c.addView(wifiBox, ui.lp())

        c.addView(ui.row(
            ui.btn("Connect", Ui.GOOD) { doConnect() },
            ui.btn("Disconnect", Ui.BAD) { ObdManager.disconnect() }
        ), ui.lp(14))
        infoTv = ui.tv("", 13f, Ui.DIM).apply { setTextIsSelectable(true) }
        c.addView(infoTv, ui.lp())

        c.addView(ui.section("Terminal (raw ELM327 / OBD commands)"))
        val termEt = ui.edit("e.g. ATRV, 010C, 0902, ATSH7E1", "")
        val sendRow = LinearLayout(a).apply {
            addView(termEt, LinearLayout.LayoutParams(0, Ui.WRAP, 2f))
            addView(ui.btn("Send") {
                ObdManager.terminal(termEt.text.toString()); termEt.setText("")
            }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = ui.dp(8) })
        }
        c.addView(sendRow)
        logTv = ui.tv("", 11f, Ui.DIM).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        c.addView(logTv, ui.lp())
        onType(Prefs.lastType)
        return ui.scroll(c)
    }

    private fun onType(t: Int) {
        wifiBox.visibility = if (t == 2) View.VISIBLE else View.GONE
        deviceBox.visibility = if (t == 2) View.GONE else View.VISIBLE
        refreshDevices()
    }

    private fun looksObd(n: String?): Boolean {
        val u = n?.uppercase() ?: return false
        return listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "KONNWEI", "VEEPEAK", "CAN", "CARISTA", "LELINK", "VIECAR", "KIWI")
            .any { u.contains(it) }
    }

    fun refreshDevices() {
        if (!::deviceBox.isInitialized) return
        stopScan()
        deviceBox.removeAllViews()
        rows.clear()
        selected = null
        val t = typeGroup.checkedRadioButtonId - 100
        if (t == 2) return
        if (!a.hasBtPerms()) {
            deviceBox.addView(ui.tv("Bluetooth permission not granted — tap here to grant.", 13f, Ui.WARN).apply {
                setOnClickListener { a.requestPerms() }
            })
            return
        }
        val ad = a.btAdapter
        if (ad == null) {
            deviceBox.addView(ui.tv("This phone has no Bluetooth.", 13f, Ui.BAD)); return
        }
        if (!ad.isEnabled) {
            deviceBox.addView(ui.tv("Bluetooth is off — tap here to turn it on.", 13f, Ui.WARN).apply {
                setOnClickListener {
                    try { a.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
                }
            })
            return
        }
        if (t == 0) {
            val list = ad.bondedDevices.sortedByDescending { looksObd(it.name) }
            if (list.isEmpty()) deviceBox.addView(ui.tv("No paired devices. Pair the ELM327 in Bluetooth settings first (PIN 1234, 0000 or 6789).", 13f, Ui.WARN))
            list.forEach { addDevice(it) }
        } else startScan(ad)
    }

    private fun addDevice(d: BluetoothDevice) {
        if (rows.containsKey(d.address)) return
        val isBle = typeGroup.checkedRadioButtonId - 100 == 1
        if (isBle && d.name == null) return
        val r = ui.card("${d.name ?: "(unnamed)"}\n${d.address}" + if (looksObd(d.name)) "   · OBD adapter" else "", 14f).apply {
            setOnClickListener { select(d) }
        }
        rows[d.address] = r
        deviceBox.addView(r, ui.lp(6))
        if (d.address == Prefs.lastAddr || (selected == null && looksObd(d.name))) select(d)
    }

    private fun select(d: BluetoothDevice) {
        selected = d
        rows.forEach { (addr, v) -> v.background = ui.rounded(if (addr == d.address) Ui.SEL else Ui.CARD, 8) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            a.runOnUiThread { addDevice(result.device) }
        }
    }

    private fun startScan(ad: BluetoothAdapter) {
        val s = ad.bluetoothLeScanner ?: return
        deviceBox.addView(ui.tv("Scanning for BLE adapters (10 s)… Location must be ON on older phones.", 12f, Ui.DIM))
        try {
            s.startScan(scanCb)
            scanning = true
            h.postDelayed({ stopScan() }, 10_000)
        } catch (e: Exception) {
            ui.toast("BLE scan failed: ${e.message}")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { a.btAdapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private fun wifiFactory(): SocketFactory? {
        val cm = a.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n.socketFactory
        }
        return null
    }

    private fun doConnect() {
        if (ObdManager.state != ObdManager.State.DISCONNECTED) {
            ui.toast("Already connected / connecting"); return
        }
        val t = typeGroup.checkedRadioButtonId - 100
        Prefs.lastType = t
        if (t == 2) {
            val host = hostEt.text.toString().trim()
            val port = portEt.text.toString().toIntOrNull() ?: 35000
            Prefs.wifiHost = host; Prefs.wifiPort = port
            val fac = wifiFactory()
            ObdManager.connect(a) { WifiTransport(host, port, fac) }
            return
        }
        val d = selected ?: run { ui.toast("Select your OBD adapter first"); return }
        stopScan()
        Prefs.lastAddr = d.address
        connectBt(t, d)
    }

    private fun connectBt(t: Int, d: BluetoothDevice) {
        val ad = a.btAdapter
        val app = a.applicationContext
        if (t == 0) ObdManager.connect(a) { ClassicBtTransport(d, ad) }
        else ObdManager.connect(a) { BleTransport(app, d) }
    }

    /** Connects to the last used adapter without user interaction. */
    fun autoConnect() {
        if (a.hasBtPerms()) AutoStart.connectLast(a)
    }

    override fun refresh() {
        val m = ObdManager
        infoTv.text = buildString {
            append("Vehicle: ${m.vehicle.title}\n")
            append("Status: ${m.status}\n")
            if (m.elmVersion.isNotEmpty()) append("Adapter: ${m.elmVersion}\n")
            if (m.protocol.isNotEmpty()) append("Protocol: ${m.protocol}\n")
            if (m.vin.isNotEmpty()) append("VIN: ${m.vin}\n")
            if (m.supported.isNotEmpty()) append("Supported PIDs: ${m.supported.size}\n")
            if (Gps.running) append("GPS: ${if (Gps.fresh() != null) "fix ✓" else "searching…"}\n")
            if (m.logFile.isNotEmpty()) append("CSV log: ${m.logFile}\n")
        }
        logTv.text = m.logText().lines().takeLast(60).joinToString("\n")
    }
}

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
    private val vals = LinkedHashMap<String, TextView>()
    private val keys = listOf(
        "Load", "Boost", "MAP", "MAF", "Intake air", "Oil temp",
        "Timing", "STFT", "LTFT", "Ambient", "Fuel rate", "Trip km"
    )

    override fun build(): View {
        val c = ui.col()
        gRpm = GaugeView(a, "Engine", "rpm", 8000f, 6500f, 1000f)
        gSpd = GaugeView(a, "Speed", "km/h", 220f, 120f)
        c.addView(ui.row(gRpm, gSpd), ui.lp(4))

        eventTv = ui.card("", 15f).apply { visibility = View.GONE; gravity = Gravity.CENTER }
        c.addView(eventTv, ui.lp(4))
        alertTv = ui.card("", 13f).apply { visibility = View.GONE; setOnClickListener { a.show("Alerts") } }
        c.addView(alertTv, ui.lp(4))

        rCool = RingGauge(a, "Coolant", "°C", 40f, 120f) { v -> if (v >= Prefs.coolantMax) 2 else if (v >= Prefs.coolantMax - 5) 1 else 0 }
        rFuel = RingGauge(a, "Fuel", "%", 0f, 100f) { v -> if (v <= Prefs.lowFuelPct) 2 else if (v <= 25) 1 else 0 }
        rBatt = RingGauge(a, "Battery", "V", 10f, 16f, 1) { v -> if (v < 11.8f || v > Prefs.chargeMaxV) 2 else if (v < 12.3f) 1 else 0 }
        rThr = RingGauge(a, "Throttle", "%", 0f, 100f) { v -> if (v > 85) 1 else 0 }
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
                val v = ui.tv("--", 16f, Ui.WHITE, true)
                vals[key] = v
                ui.vbox().apply {
                    background = ui.rounded(Ui.CARD)
                    setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                    addView(ui.tv(key, 11f, Ui.DIM))
                    addView(v)
                }
            }
            c.addView(ui.row(*tiles.toTypedArray()), ui.lp(8))
        }
        c.addView(ui.row(
            ui.btn("HUD") { a.show("HUD") },
            ui.btn("Performance", Ui.CARD) { a.show("Perf") },
            ui.btn("New trip", Ui.CARD) { ObdManager.stopTrip(); ObdManager.startTrip() }
        ), ui.lp(12))
        c.addView(ui.tv("Long-press a gauge or the G-meter to reset its peak.", 11f, Ui.DIM), ui.lp(6))
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
