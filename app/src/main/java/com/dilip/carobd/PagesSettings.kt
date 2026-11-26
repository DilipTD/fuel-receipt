package com.dilip.carobd

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import java.io.File

/**
 * All configuration in one place, grouped by function:
 * Connection · Trips · Alerts · Driving · Auto · PIDs · Data · About.
 * Edits are applied with "Save settings" (and automatically when you switch group).
 */
@SuppressLint("MissingPermission")
class SettingsPage(a: MainActivity) : Page(a) {
    override val parent = "More"

    companion object {
        const val REQ_RESTORE = 77
    }

    private lateinit var tabsRow: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var saveBar: LinearLayout
    private lateinit var customBox: LinearLayout
    private val saves = ArrayList<() -> Unit>()
    private var tab = 0

    private val tabDefs = listOf(
        Ic.PLUG to "Connection", Ic.ROUTE to "Trips", Ic.BELL to "Alerts", Ic.PULSE to "Driving",
        Ic.BOLT to "Auto", Ic.SLIDERS to "Custom PIDs", Ic.DATABASE to "Data", Ic.INFO to "About"
    )

    override fun build(): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        tabsRow = LinearLayout(a).apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(6)) }
        root.addView(HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabsRow)
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        body = ui.col(0)
        root.addView(ui.scroll(body), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        saveBar = LinearLayout(a).apply {
            setBackgroundColor(Color.parseColor("#0F161D"))
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            addView(ui.btn("Save settings", Ui.GOOD) { applySaves(); ui.toast("Settings saved") },
                LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        root.addView(saveBar)
        return root
    }

    override fun onShow() = populate()

    private fun applySaves() {
        try { saves.forEach { it() } } catch (e: Exception) { ui.toast("Could not save: ${e.message}") }
    }

    private fun populate() {
        tabsRow.removeAllViews()
        tabDefs.forEachIndexed { i, (ic, name) ->
            val on = i == tab
            tabsRow.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = if (on) ui.outlined(Ui.SEL, Ui.CYAN, 20, 1) else ui.rounded(Ui.CARD, 20)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(14), ui.dp(8))
                addView(ui.iconView(ic, if (on) Ui.CYAN else Ui.DIM, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(6) })
                addView(ui.tv(name, 13f, if (on) Ui.WHITE else Ui.DIM, on))
                setOnClickListener { if (tab != i) { applySaves(); tab = i; populate() } }
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
        }
        body.removeAllViews()
        saves.clear()
        when (tab) {
            0 -> connection()
            1 -> trips()
            2 -> alerts()
            3 -> driving()
            4 -> automation()
            5 -> pids()
            6 -> data()
            else -> about()
        }
        saveBar.visibility = if (tab in listOf(0, 1, 2, 3, 4)) View.VISIBLE else View.GONE
    }

    /* ---------- field helpers ---------- */

    private fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit): Switch {
        val sw = ui.check(label, get())
        saves += { set(sw.isChecked) }
        body.addView(sw)
        return sw
    }

    private fun num(label: String, value: String, onSave: (Double) -> Unit): View {
        val et = ui.edit("", value, true)
        saves += { et.num()?.let(onSave) }
        return ui.labeled(label, et)
    }

    private fun spinner(label: String, items: List<String>, sel: Int, onSave: (Int) -> Unit): View {
        val sp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(sel.coerceIn(0, items.size - 1))
            background = ui.rounded(Ui.CARD, 10)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        saves += { onSave(sp.selectedItemPosition) }
        return ui.labeled(label, sp)
    }

    private fun hint(t: String) = body.addView(ui.tv(t, 12f, Ui.DIM).apply { setPadding(ui.dp(2), ui.dp(6), ui.dp(2), 0) })

    /* ---------- groups ---------- */

    private fun connection() {
        body.addView(ui.section("Adapter type", Ic.PLUG))
        val types = listOf(Triple(Ic.BLUETOOTH, "Bluetooth", "Classic"), Triple(Ic.BLE, "BLE 4.0", "Low energy"), Triple(Ic.WIFI, "WiFi", "TCP"))
        val cards = ArrayList<LinearLayout>()
        val row = LinearLayout(a)
        fun paint() = cards.forEachIndexed { i, c ->
            c.background = if (i == Prefs.lastType) ui.outlined(Ui.SEL, Ui.CYAN, 14, 2) else ui.rounded(Ui.CARD, 14)
        }
        types.forEachIndexed { i, (ic, n, sub) ->
            val c = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(10))
                addView(ui.iconView(ic, Ui.WHITE, 30))
                addView(ui.tv(n, 13.5f, Ui.WHITE, true), ui.lp(6))
                addView(ui.tv(sub, 11f, Ui.DIM))
                setOnClickListener { Prefs.lastType = i; paint() }
            }
            cards.add(c)
            row.addView(c, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { if (i > 0) leftMargin = ui.dp(8) })
        }
        paint()
        body.addView(row)

        val saved = if (Prefs.lastAddr.isEmpty()) "No adapter chosen yet – pick one on the Connect screen"
        else Prefs.lastAddr
        body.addView(ui.iconCard(Ic.BLUETOOTH, Ui.ACC, "Saved Bluetooth adapter", saved) { a.show("Connect") }, ui.lp(10))

        body.addView(ui.section("WiFi adapter address", Ic.WIFI))
        val host = ui.edit("192.168.0.10", Prefs.wifiHost)
        val port = ui.edit("35000", Prefs.wifiPort.toString(), true)
        saves += {
            Prefs.wifiHost = host.text.toString().trim().ifEmpty { "192.168.0.10" }
            Prefs.wifiPort = port.text.toString().toIntOrNull() ?: 35000
        }
        body.addView(ui.row(ui.labeled("IP address", host), ui.labeled("Port", port)))
        hint("Common: 192.168.0.10 : 35000, 192.168.0.123 : 35000, 192.168.4.1 : 35000. Join the adapter's WiFi first.")

        body.addView(ui.section("Protocol & speed", Ic.CODE))
        val protos = listOf("0" to "Auto", "6" to "6 · CAN 11-bit 500k", "8" to "8 · CAN 11-bit 250k", "7" to "7 · CAN 29-bit 500k",
            "9" to "9 · CAN 29-bit 250k", "5" to "5 · KWP fast", "4" to "4 · KWP 5-baud", "3" to "3 · ISO 9141-2", "1" to "1 · J1850 PWM", "2" to "2 · J1850 VPW")
        val pr = spinner("OBD protocol", protos.map { it.second }, protos.indexOfFirst { it.first == Prefs.protocol }.coerceAtLeast(0)) {
            Prefs.protocol = protos[it].first
        }
        val pd = num("Extra poll delay (ms)", Prefs.pollDelayMs.toString()) { Prefs.pollDelayMs = it.toInt().coerceIn(0, 2000) }
        body.addView(ui.row(pr, pd))
        hint("Auto works for nearly every car. A poll delay helps slow clone adapters.")

        body.addView(ui.section("Behaviour", Ic.GEAR))
        toggle("Auto-connect to the last adapter when the app opens", { Prefs.autoConnect }) { Prefs.autoConnect = it }
        toggle("Auto-reconnect if the link drops", { Prefs.autoReconnect }) { Prefs.autoReconnect = it }
        toggle("Keep screen on while connected", { Prefs.keepScreenOn }) { Prefs.keepScreenOn = it }
        hint("Protocol and logging changes apply the next time you connect.")
    }

    private fun trips() {
        body.addView(ui.section("Trip logging", Ic.ROUTE))
        toggle("Auto start/stop trips with the engine", { Prefs.autoTrip }) { Prefs.autoTrip = it }
        toggle("GPS route tracking & cornering detection", { Prefs.gpsEnabled }) { Prefs.gpsEnabled = it }
        toggle("Notification with trip summary", { Prefs.tripNotify }) { Prefs.tripNotify = it }
        toggle("Scan fault codes automatically on connect", { Prefs.autoDtc }) { Prefs.autoDtc = it }
        toggle("Log live data to CSV (Downloads/CarOBD)", { Prefs.csvLogging }) { Prefs.csvLogging = it }
        body.addView(ui.row(
            num("Merge stops shorter than (min)", Prefs.tripMergeMin.toString()) { Prefs.tripMergeMin = it.toInt().coerceIn(0, 60) },
            spinner("Economy unit", listOf("km/L", "L/100km"), if (Prefs.economyUnit == "L/100km") 1 else 0) {
                Prefs.economyUnit = if (it == 1) "L/100km" else "km/L"
            }
        ), ui.lp(10))
        hint("Stops shorter than the merge time (fuel stop, traffic light) continue the same trip.")
    }

    private fun alerts() {
        body.addView(ui.section("Notifications & voice", Ic.BELL))
        toggle("Show alert notifications (overheat, charging, low fuel, check-engine…)", { Prefs.alertNotify }) { Prefs.alertNotify = it }
        toggle("Driving alerts (overspeed, over-rev, braking, faults)", { Prefs.eventAlerts }) { Prefs.eventAlerts = it }
        toggle("Voice alerts (text-to-speech)", { Prefs.voiceAlerts }) { Prefs.voiceAlerts = it }
        toggle("Beep when voice is off", { Prefs.beepAlerts }) { Prefs.beepAlerts = it }
        toggle("Also notify every driving event (hard braking, overspeed…)", { Prefs.notifyDrivingEvents }) { Prefs.notifyDrivingEvents = it }

        body.addView(ui.section("Alert limits", Ic.SLIDERS))
        body.addView(ui.row(
            num("Overheat at (°C)", Prefs.coolantMax.toString()) { Prefs.coolantMax = it.coerceIn(85.0, 130.0) },
            num("Low fuel below (%)", Prefs.lowFuelPct.toString()) { Prefs.lowFuelPct = it.coerceIn(0.0, 50.0) }
        ))
        body.addView(ui.row(
            num("Charging min (V)", Prefs.chargeMinV.toString()) { Prefs.chargeMinV = it.coerceIn(11.5, 14.5) },
            num("Charging max (V)", Prefs.chargeMaxV.toString()) { Prefs.chargeMaxV = it.coerceIn(14.0, 16.5) }
        ), ui.lp(10))
        body.addView(ui.row(
            num("Parked battery low (V)", Prefs.parkLowV.toString()) { Prefs.parkLowV = it.coerceIn(10.5, 13.5) },
            num("Weak crank below (V)", Prefs.crankLowV.toString()) { Prefs.crankLowV = it.coerceIn(7.0, 12.5) }
        ), ui.lp(10))
        hint("Lead-acid defaults: 13.0 / 15.2 / 12.2 / 9.6 V. For an LFP starter battery try 13.4 / 14.8 / 13.0 / 11.0 V.")
        body.addView(ui.btn("Test notification", Ui.CARD) {
            AlertCenter.raise(a, "test${System.currentTimeMillis()}", AlertCenter.WARN, "Test alert",
                "Notifications are working. ${AppInfo.credit}", 0L, "This is a test alert")
        }, ui.lp(12))
    }

    private fun driving() {
        body.addView(ui.section("Driving-event thresholds", Ic.PULSE))
        body.addView(ui.row(
            num("Rapid acceleration (g)", Prefs.accelG.toString()) { Prefs.accelG = it.coerceIn(0.1, 1.0) },
            num("Hard braking (g)", Prefs.brakeG.toString()) { Prefs.brakeG = it.coerceIn(0.1, 1.0) }
        ))
        body.addView(ui.row(
            num("Sudden braking (g)", Prefs.suddenG.toString()) { Prefs.suddenG = it.coerceIn(0.2, 1.2) },
            num("Harsh cornering (g)", Prefs.cornerG.toString()) { Prefs.cornerG = it.coerceIn(0.1, 1.0) }
        ), ui.lp(10))
        body.addView(num("Long idle (seconds)", Prefs.longIdleS.toString()) { Prefs.longIdleS = it.toInt().coerceAtLeast(30) }, ui.lp(10))
        hint("Defaults 0.22 g and 0.24 g follow published telematics calibration (about 7.8 and 8.5 km/h per second).")
        val v = ObdManager.vehicle
        body.addView(ui.section("Vehicle limits", Ic.CAR))
        body.addView(ui.iconCard(Ic.GAUGE, Ui.ORANGE, "${v.title}", "Speed limit ${v.speedLimit} km/h · RPM limit ${v.rpmLimit}") { a.show("Garage") })
        hint("Overspeed and over-rev alerts use the limits set for each vehicle in the Garage.")
    }

    private fun automation() {
        body.addView(ui.section("Automatic trip logging", Ic.BOLT))
        body.addView(ui.iconCard(Ic.CAR, Ui.CYAN, "How it works",
            "Car on → phone joins the car's Bluetooth → app connects to the OBD adapter in the background → trip starts with the engine → trip saved after it stops."))
        val carBt = toggle("Auto-start when the phone connects to the car's Bluetooth", { Prefs.autoStartCarBt }) { }
        val btList = mutableListOf("" to "– select car stereo / hands-free –")
        try {
            a.btAdapter?.bondedDevices?.forEach { d -> btList += d.address to (d.name ?: d.address) }
        } catch (_: Exception) {
        }
        val sp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, btList.map { it.second })
            setSelection(btList.indexOfFirst { it.first == Prefs.carBtAddr }.coerceAtLeast(0))
            background = ui.rounded(Ui.CARD, 10)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        body.addView(ui.labeled("Car Bluetooth device (not the OBD adapter)", sp), ui.lp(8))
        saves.removeAt(saves.size - 1)   // replace the generic toggle save with a combined one
        saves += {
            val sel = btList.getOrNull(sp.selectedItemPosition) ?: ("" to "")
            Prefs.carBtAddr = sel.first
            Prefs.carBtName = if (sel.first.isEmpty()) "" else sel.second
            Prefs.autoStartCarBt = carBt.isChecked && sel.first.isNotEmpty()
            if (carBt.isChecked && sel.first.isEmpty()) ui.toast("Select the car's Bluetooth device to enable auto-start")
        }
        hint("Keep 'Auto-reconnect' on and allow the app to run without battery restrictions.")
        body.addView(ui.btn("Allow background running (battery optimisation)", Ui.CARD) {
            try {
                @SuppressLint("BatteryLife")
                val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
                a.startActivity(i)
            } catch (_: Exception) {
                try { a.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
            }
        }, ui.lp(10))
    }

    private fun pids() {
        body.addView(ui.section("Custom PIDs (mode 22 / manufacturer)", Ic.SLIDERS))
        body.addView(ui.tv("Header = module to ask (7E0 engine, 7E1 gearbox; blank = all). Request = hex, e.g. 221234. " +
                "Formula uses reply bytes A, B, C… after the echoed request, e.g. (A*256+B)/10-40.", 12f, Ui.DIM))
        customBox = ui.vbox()
        body.addView(customBox, ui.lp())
        val cName = ui.edit("Name (e.g. Gearbox oil temp)", "")
        val cHead = ui.edit("Header (7E1)", "")
        val cReq = ui.edit("Request (22xxxx)", "")
        val cFormula = ui.edit("Formula (A-40)", "")
        val cUnit = ui.edit("Unit (°C)", "")
        body.addView(cName, ui.lp())
        body.addView(ui.row(cHead, cReq), ui.lp(6))
        body.addView(ui.row(cFormula, cUnit), ui.lp(6))
        body.addView(ui.btn("Add custom PID") {
            val name = cName.text.toString().trim()
            val req = cReq.text.toString().replace(" ", "").uppercase()
            val formula = cFormula.text.toString().trim().ifEmpty { "A" }
            if (name.isEmpty() || req.length < 2 || req.length % 2 != 0 || !req.all { it in "0123456789ABCDEF" }) {
                ui.toast("Enter a name and a valid hex request"); return@btn
            }
            try {
                Expr(formula, Expr.varsFrom(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8))).eval()
            } catch (ex: Exception) {
                ui.toast("Formula error: ${ex.message}"); return@btn
            }
            Prefs.customPids = Prefs.customPids.filter { it.name != name } +
                    CustomPid(name, cHead.text.toString().trim().uppercase(), req, formula, cUnit.text.toString().trim())
            cName.setText(""); cReq.setText(""); cFormula.setText(""); cUnit.setText("")
            rebuildCustom()
        }, ui.lp(8))
        rebuildCustom()
    }

    private fun rebuildCustom() {
        if (!::customBox.isInitialized) return
        customBox.removeAllViews()
        val list = Prefs.customPids
        if (list.isEmpty()) customBox.addView(ui.tv("No custom PIDs yet.", 13f, Ui.DIM))
        for (cp in list) {
            val r = LinearLayout(a).apply {
                background = ui.rounded(Ui.CARD, 12)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(8), ui.dp(8))
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.vbox().apply {
                    addView(ui.tv(cp.name, 14f, Ui.WHITE, true))
                    addView(ui.tv("${cp.header.ifEmpty { "all" }} · ${cp.request} · ${cp.formula} ${cp.unit}", 11.5f, Ui.DIM))
                }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                addView(ui.btn("Delete", Ui.BAD) {
                    Prefs.customPids = Prefs.customPids.filter { it.name != cp.name }; rebuildCustom()
                })
            }
            customBox.addView(r, ui.lp(4))
        }
    }

    private fun data() {
        body.addView(ui.section("Backup & restore", Ic.DATABASE))
        val vCount = Db.vehicles().size
        val sizeKb = try { Db.file(a).length() / 1024 } catch (_: Exception) { 0L }
        body.addView(ui.iconCard(Ic.DATABASE, Ui.VIOLET, "Local database", "$vCount vehicle(s) · $sizeKb KB · trips, routes, fuel, service, alerts"))
        body.addView(ui.row(
            ui.btn("Backup data") { backup() },
            ui.btn("Restore", Ui.CARD) {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
                @Suppress("DEPRECATION")
                a.startActivityForResult(i, REQ_RESTORE)
            }
        ), ui.lp(10))
        hint("Backups are saved to Downloads/CarOBD. Restore replaces everything on this phone.")
        body.addView(ui.section("Clear", Ic.TRASH))
        body.addView(ui.btn("Clear alert log", Ui.BAD) { Db.clearAlerts(); ui.toast("Alert log cleared") })
    }

    private fun about() {
        val logo = LogoView(a)
        body.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(ui.dp(110), ui.dp(110)))
        }, ui.lp(14))
        body.addView(ui.tv(AppInfo.NAME, 24f, Ui.WHITE, true).apply { gravity = Gravity.CENTER }, ui.lp(8))
        body.addView(ui.tv("Version ${AppInfo.VERSION} · ${AppInfo.BUILD_DATE}", 13f, Ui.DIM).apply { gravity = Gravity.CENTER })
        body.addView(ui.tv("Software developed by ${AppInfo.DEVELOPER}", 15f, Ui.CYAN, true).apply { gravity = Gravity.CENTER }, ui.lp(8))
        body.addView(ui.card("Universal ELM327 app (Bluetooth / BLE / WiFi). Works with OBD-II/EOBD petrol cars (~2001+), diesel (~2004+) " +
                "and all CAN cars (~2008+). Live gauges, trip computer, real mileage, maintenance reminders, driving-behaviour " +
                "analysis, alerts, performance and battery tests, DTC read/clear (OBD + UDS), GPS routes, HUD.\n" +
                "Do not operate the app while driving."), ui.lp(14))
        body.addView(ui.btn("About & what's new", Ui.CARD) { a.show("About") }, ui.lp(10))
    }

    private fun backup() {
        try {
            Db.close()
            val f = Db.file(a)
            val os = Exporter.open(a, "carobd_backup_${stamp()}.db", "application/octet-stream")
            if (os == null) ui.toast("Backup failed")
            else {
                os.use { o -> f.inputStream().use { it.copyTo(o) } }
                ui.toast("Saved to Downloads/CarOBD")
            }
        } catch (e: Exception) {
            ui.toast("Backup failed: ${e.message}")
        } finally {
            Db.reopen(a)
        }
    }

    fun restore(uri: Uri) {
        if (ObdManager.state != ObdManager.State.DISCONNECTED) {
            ui.toast("Disconnect first, then restore."); return
        }
        AlertDialog.Builder(a).setTitle("Replace all current data with this backup?")
            .setPositiveButton("Restore") { _, _ ->
                try {
                    Db.close()
                    val f = Db.file(a)
                    val tmp = File(f.parentFile, "restore.tmp")
                    a.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    val head = tmp.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
                    if (!String(head, Charsets.US_ASCII).startsWith("SQLite format 3")) {
                        tmp.delete(); ui.toast("Not a Car OBD backup file")
                    } else {
                        File(f.path + "-wal").delete(); File(f.path + "-shm").delete()
                        tmp.copyTo(f, overwrite = true); tmp.delete()
                        ui.toast("Restored")
                    }
                } catch (e: Exception) {
                    ui.toast("Restore failed: ${e.message}")
                } finally {
                    Db.reopen(a)
                    ObdManager.reloadVehicle()
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
