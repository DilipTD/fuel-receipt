package com.dilip.carobd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/* ============================ Connect ============================ */

@SuppressLint("MissingPermission", "SetTextI18n")
class ConnectPage(a: MainActivity) : Page(a) {
    private lateinit var hero: CarHeroView
    private lateinit var deviceBox: LinearLayout
    private lateinit var wifiBox: LinearLayout
    private lateinit var hostEt: EditText
    private lateinit var portEt: EditText
    private lateinit var logTv: TextView
    private lateinit var termBody: LinearLayout
    private lateinit var termArrow: ImageView
    private val typeCards = ArrayList<LinearLayout>()
    private val info = LinkedHashMap<String, Pair<LinearLayout, TextView>>()
    private var type = Prefs.lastType
    private var selected: BluetoothDevice? = null
    private val rows = HashMap<String, LinearLayout>()
    private var scanning = false
    private val h = Handler(Looper.getMainLooper())

    private val typeInfo = listOf(
        Triple(Ic.BLUETOOTH, "Bluetooth", "Classic SPP"),
        Triple(Ic.BLE, "BLE 4.0", "Low energy"),
        Triple(Ic.WIFI, "WiFi", "TCP socket")
    )

    override fun build(): View {
        val c = ui.col()
        hero = CarHeroView(a)
        c.addView(hero, ui.lp(4))
        c.addView(ui.row(
            ui.btn("Connect", Ui.GOOD) { doConnect() },
            ui.btn("Disconnect", Ui.BAD) { ObdManager.disconnect() }
        ), ui.lp(12))

        c.addView(ui.section("Adapter type", Ic.PLUG))
        val tc = LinearLayout(a).apply { isBaselineAligned = false }
        typeInfo.forEachIndexed { i, (ic, name, sub) ->
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(6), ui.dp(14), ui.dp(6), ui.dp(12))
                addView(iconView(a, ic, Ui.WHITE, 34))
                addView(ui.tv(name, 14f, Ui.WHITE, true).apply { gravity = Gravity.CENTER }, ui.lp(8))
                addView(ui.tv(sub, 11f, Ui.DIM).apply { gravity = Gravity.CENTER })
                setOnClickListener { onType(i) }
            }
            typeCards.add(card)
            tc.addView(card, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { if (i > 0) leftMargin = ui.dp(8) })
        }
        c.addView(tc)

        c.addView(ui.section("Device", Ic.SEARCH))
        deviceBox = ui.vbox()
        c.addView(deviceBox)
        c.addView(ui.row(
            ui.btn("Refresh / scan") { refreshDevices() },
            ui.btn("Bluetooth settings", Ui.CARD) { a.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        ), ui.lp(8))

        hostEt = ui.edit("IP address", Prefs.wifiHost)
        portEt = ui.edit("Port", Prefs.wifiPort.toString(), true)
        wifiBox = ui.vbox().apply {
            addView(ui.iconCard(Ic.WIFI, Ui.CYAN, "Join the adapter's WiFi first",
                "Phone settings → WiFi → WiFi_OBDII / V-LINK. Common address 192.168.0.10 : 35000 (or 192.168.0.123)."))
            addView(ui.row(ui.labeled("IP address", hostEt), ui.labeled("Port", portEt)), ui.lp(10))
        }
        c.addView(wifiBox, ui.lp())

        c.addView(ui.section("Connection details", Ic.INFO))
        val box = ui.vbox().apply {
            background = ui.rounded(Ui.CARD, 14)
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
        }
        fun infoRow(key: String, ic: Ic, label: String) {
            val v = ui.tv("", 13f, Ui.WHITE, true).apply { gravity = Gravity.END; setTextIsSelectable(true) }
            val r = LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(8), 0, ui.dp(8))
                addView(iconView(a, ic, Ui.DIM, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(10) })
                addView(ui.tv(label, 13f, Ui.DIM), LinearLayout.LayoutParams(0, Ui.WRAP, 0.8f))
                addView(v, LinearLayout.LayoutParams(0, Ui.WRAP, 1.4f))
            }
            info[key] = r to v
            box.addView(r)
        }
        infoRow("veh", Ic.CAR, "Vehicle")
        infoRow("st", Ic.PULSE, "Status")
        infoRow("elm", Ic.PLUG, "Adapter")
        infoRow("pro", Ic.CODE, "Protocol")
        infoRow("vin", Ic.DOC, "VIN")
        infoRow("pid", Ic.CHART, "Supported PIDs")
        infoRow("gps", Ic.PIN, "GPS")
        infoRow("csv", Ic.SAVE, "CSV log")
        c.addView(box)

        // collapsible terminal
        termArrow = iconView(a, Ic.CHEV, Ui.DIM, 18)
        val head = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(Ui.CARD, 14)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(14), ui.dp(12))
            addView(ui.badge(Ic.TERMINAL, Ui.VIOLET, 36), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { rightMargin = ui.dp(12) })
            addView(ui.vbox().apply {
                addView(ui.tv("Terminal & log", 14.5f, Ui.WHITE, true))
                addView(ui.tv("Raw ELM327 / OBD commands and the link log", 12f, Ui.DIM))
            }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(termArrow)
        }
        c.addView(head, ui.lp(18))
        termBody = ui.vbox().apply { visibility = View.GONE }
        val termEt = ui.edit("e.g. ATRV, 010C, 0902, ATSH7E1", "")
        termBody.addView(LinearLayout(a).apply {
            addView(termEt, LinearLayout.LayoutParams(0, Ui.WRAP, 2f))
            addView(ui.btn("Send") { ObdManager.terminal(termEt.text.toString()); termEt.setText("") },
                LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = ui.dp(8) })
        }, ui.lp(8))
        logTv = ui.tv("", 11f, Ui.DIM).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            background = ui.rounded(Color.parseColor("#0C1218"), 12); setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        }
        termBody.addView(logTv, ui.lp(8))
        c.addView(termBody)
        head.setOnClickListener {
            val open = termBody.visibility != View.VISIBLE
            termBody.visibility = if (open) View.VISIBLE else View.GONE
            termArrow.rotation = if (open) 90f else 0f
        }
        paintTypes()
        wifiBox.visibility = if (type == 2) View.VISIBLE else View.GONE
        refreshDevices()
        return ui.scroll(c)
    }

    private fun paintTypes() {
        typeCards.forEachIndexed { i, card ->
            card.background = if (i == type) ui.outlined(Ui.SEL, Ui.CYAN, 14, 2) else ui.rounded(Ui.CARD, 14)
        }
        hero.linkIc = typeInfo[type].first
        hero.invalidate()
    }

    private fun onType(t: Int) {
        type = t
        Prefs.lastType = t
        paintTypes()
        wifiBox.visibility = if (t == 2) View.VISIBLE else View.GONE
        deviceBox.visibility = if (t == 2) View.GONE else View.VISIBLE
        refreshDevices()
    }

    override fun onShow() {
        type = Prefs.lastType
        if (::hero.isInitialized) {
            paintTypes()
            wifiBox.visibility = if (type == 2) View.VISIBLE else View.GONE
            deviceBox.visibility = if (type == 2) View.GONE else View.VISIBLE
        }
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
        val t = type
        if (t == 2) return
        if (!a.hasBtPerms()) {
            deviceBox.addView(ui.iconCard(Ic.WARN, Ui.WARN, "Bluetooth permission needed", "Tap to grant 'Nearby devices'") { a.requestPerms() })
            return
        }
        val ad = a.btAdapter
        if (ad == null) {
            deviceBox.addView(ui.iconCard(Ic.WARN, Ui.BAD, "No Bluetooth", "This phone has no Bluetooth.")); return
        }
        if (!ad.isEnabled) {
            deviceBox.addView(ui.iconCard(Ic.BLUETOOTH, Ui.WARN, "Bluetooth is off", "Tap to turn it on") {
                try { a.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
            })
            return
        }
        if (t == 0) {
            val list = ad.bondedDevices.sortedByDescending { looksObd(it.name) }
            if (list.isEmpty()) deviceBox.addView(ui.iconCard(Ic.INFO, Ui.WARN, "No paired devices",
                "Pair the ELM327 in Bluetooth settings first (PIN 1234, 0000 or 6789)."))
            list.forEach { addDevice(it) }
        } else startScan(ad)
    }

    private fun addDevice(d: BluetoothDevice) {
        if (rows.containsKey(d.address)) return
        if (type == 1 && d.name == null) return
        val obd = looksObd(d.name)
        val r = ui.iconCard(if (type == 1) Ic.BLE else Ic.BLUETOOTH, if (obd) Ui.CYAN else Ui.ACC,
            d.name ?: "(unnamed)", d.address + if (obd) "  ·  OBD adapter" else "") { select(d) }
        rows[d.address] = r
        deviceBox.addView(r, ui.lp(6))
        if (d.address == Prefs.lastAddr || (selected == null && obd)) select(d)
    }

    private fun select(d: BluetoothDevice) {
        selected = d
        rows.forEach { (addr, v) -> v.background = if (addr == d.address) ui.outlined(Ui.SEL, Ui.CYAN, 14, 2) else ui.rounded(Ui.CARD, 14) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            a.runOnUiThread { addDevice(result.device) }
        }
    }

    private fun startScan(ad: BluetoothAdapter) {
        val s = ad.bluetoothLeScanner ?: return
        deviceBox.addView(ui.iconCard(Ic.SEARCH, Ui.ACC, "Scanning for BLE adapters…", "10 seconds. Location must be ON on older phones."))
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

    private fun doConnect() {
        if (ObdManager.state != ObdManager.State.DISCONNECTED) {
            ui.toast("Already connected / connecting"); return
        }
        Prefs.lastType = type
        if (type == 2) {
            val host = hostEt.text.toString().trim()
            val port = portEt.text.toString().toIntOrNull() ?: 35000
            Prefs.wifiHost = host; Prefs.wifiPort = port
            val fac = AutoStart.wifiFactory(a)
            ObdManager.connect(a) { WifiTransport(host, port, fac) }
            return
        }
        val d = selected ?: run { ui.toast("Select your OBD adapter first"); return }
        stopScan()
        Prefs.lastAddr = d.address
        val ad = a.btAdapter
        val app = a.applicationContext
        if (type == 0) ObdManager.connect(a) { ClassicBtTransport(d, ad) }
        else ObdManager.connect(a) { BleTransport(app, d) }
    }

    /** Connects to the last used adapter without user interaction. */
    fun autoConnect() {
        if (a.hasBtPerms()) AutoStart.connectLast(a)
    }

    override fun refresh() {
        val m = ObdManager
        hero.title = m.vehicle.title
        hero.subtitle = when (m.state) {
            ObdManager.State.CONNECTED -> if (m.engineOn) "Connected · ${m.deviceName}" else "Ignition off · battery monitor"
            ObdManager.State.CONNECTING -> m.status
            else -> if (m.status == "Disconnected" || m.status == "Not connected") "Not connected – pick an adapter below" else m.status
        }
        hero.state = when (m.state) {
            ObdManager.State.CONNECTED -> if (m.engineOn) 2 else 3
            ObdManager.State.CONNECTING -> 1
            else -> 0
        }
        hero.speed = m.values[0x0D] ?: 0.0
        hero.chips = buildList {
            m.batteryV?.let { add(Ic.BATTERY to "%.1f V".format(it)) }
            m.values[0x05]?.let { add(Ic.THERM to "%.0f°C".format(it)) }
            if (m.supported.isNotEmpty()) add(Ic.CHART to "${m.supported.size} PIDs")
        }
        hero.invalidate()
        fun set(k: String, v: String) {
            val p = info[k] ?: return
            p.second.text = v
            p.first.visibility = if (v.isEmpty()) View.GONE else View.VISIBLE
        }
        set("veh", m.vehicle.title)
        set("st", m.status)
        set("elm", m.elmVersion)
        set("pro", m.protocol)
        set("vin", m.vin)
        set("pid", if (m.supported.isNotEmpty()) m.supported.size.toString() else "")
        set("gps", if (Gps.running) (if (Gps.fresh() != null) "fix ✓" else "searching…") else "")
        set("csv", m.logFile)
        if (termBody.visibility == View.VISIBLE) logTv.text = m.logText().lines().takeLast(60).joinToString("\n")
    }
}
