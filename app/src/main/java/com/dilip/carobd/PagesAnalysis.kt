package com.dilip.carobd

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import java.io.File
import java.util.Calendar

/* ============================ Analysis ============================ */

class AnalysisPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout
    private var days = 30

    override fun build(): View {
        box = ui.col()
        return ui.scroll(box)
    }

    override fun onShow() {
        box.removeAllViews()
        val v = ObdManager.vehicle
        val u = v.spec.unit
        box.addView(ui.row(
            *listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 0 to "All").map { (d, label) ->
                ui.btn(label, if (d == days) Ui.ACC else Ui.CARD) { days = d; onShow() }
            }.toTypedArray()
        ), ui.lp(6))

        val since = if (days == 0) 0L else System.currentTimeMillis() - days * 86_400_000L
        val trips = Db.trips(v.id, since).sortedBy { it.start }
        box.addView(ui.section("${v.title} – ${if (days == 0) "all time" else "last $days days"}"))
        if (trips.isEmpty()) {
            box.addView(ui.tv("No trips in this period yet.", 13f, Ui.DIM)); return
        }
        val km = trips.sumOf { it.distanceKm }
        val fuel = trips.sumOf { it.fuel }
        val cost = trips.sumOf { it.cost }
        val hours = trips.sumOf { it.durationS } / 3600.0
        val idle = trips.sumOf { it.idleS } / 3600.0
        val idleFuel = trips.sumOf { it.idleFuel }
        val co2 = trips.sumOf { it.co2Kg }
        val per100 = 100.0 / maxOf(km, 1.0)
        val score = trips.map { it.score.toDouble() * it.distanceKm }.sum() / maxOf(km, 0.1)
        val b100 = trips.map { it.best0100 }.filter { it > 0 }.minOrNull()
        val b60 = trips.map { it.best060 }.filter { it > 0 }.minOrNull()
        val obd = trips.sumOf { it.sumObdSpd }
        val gps = trips.sumOf { it.sumGpsSpd }

        box.addView(ui.mono(buildString {
            append("Trips          ${trips.size}\n")
            append("Distance       ${ui.f(km, 1, " km")}\n")
            append("Driving time   ${ui.f(hours, 1, " h")}  (idle ${ui.f(idle / maxOf(hours, 0.01) * 100, 0, "%")})\n")
            append("Fuel used      ${ui.f(fuel, 1, " $u")} (idle ${ui.f(idleFuel, 2, " $u")})\n")
            append("Economy        ${ui.econ(if (fuel > 0) km / fuel else null, u)}\n")
            append("Fuel cost      ${ui.money(cost)}  (${ui.f(if (km > 0) cost / km else null, 2, " ₹/km")})\n")
            append("CO2            ${ui.f(co2, 1, " kg")}\n")
            append("Avg speed      ${ui.f(km / maxOf(hours, 0.01), 1, " km/h")}\n")
            append("Max speed      ${ui.f(trips.maxOf { it.maxSpeed }, 0, " km/h")}\n")
            append("Driver score   ${score.toInt()}/100 (distance-weighted)\n")
            if (b60 != null) append("Best 0-60      ${ui.f(b60, 1, " s")}\n")
            if (b100 != null) append("Best 0-100     ${ui.f(b100, 1, " s")}\n")
            if (gps > 1000) append("Speedometer    %+.1f%% vs GPS\n".format((obd / gps - 1) * 100))
        }), ui.lp())

        // km per day, last 14 days
        val cal = Calendar.getInstance()
        val labels = mutableListOf<String>()
        val kmDay = mutableListOf<Float>()
        for (i in 13 downTo 0) {
            val c = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis; add(Calendar.DAY_OF_YEAR, -i)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val s = c.timeInMillis
            val e = s + 86_400_000L
            labels += c.get(Calendar.DAY_OF_MONTH).toString()
            kmDay += Db.trips(v.id, s).filter { it.start < e }.sumOf { it.distanceKm }.toFloat()
        }
        val bars = BarChart(a)
        bars.set(labels, kmDay, "Distance per day (last 14 days, km)")
        box.addView(bars, ui.lp(10))

        val econ = trips.mapNotNull { it.kmpl }.map { it.toFloat() }.toFloatArray()
        val ec = LineChart(a, 140)
        ec.set(econ, "Economy per trip", " km/$u", Color.parseColor("#66BB6A"))
        box.addView(ec, ui.lp(8))
        val sc = LineChart(a, 140)
        sc.set(trips.map { it.score.toFloat() }.toFloatArray(), "Driver score per trip", "", Color.parseColor("#FFB300"))
        box.addView(sc, ui.lp(8))

        val ra = trips.sumOf { it.rapidAcc }; val hb = trips.sumOf { it.hardBrake }; val sb = trips.sumOf { it.suddenBrake }
        val co = trips.sumOf { it.corner }; val os = trips.sumOf { it.overspeed }; val osS = trips.sumOf { it.overspeedS }
        val orv = trips.sumOf { it.overRev }; val cr = trips.sumOf { it.coldRev }; val li = trips.sumOf { it.longIdle }
        box.addView(ui.section("Driving behaviour (per 100 km)"))
        box.addView(ui.mono(
            "Rapid acceleration  %3d  (%.1f)\nHard braking        %3d  (%.1f)\nSudden braking      %3d  (%.1f)\n".format(ra, ra * per100, hb, hb * per100, sb, sb * per100) +
                    "Harsh cornering     %3d  (%.1f)\nOverspeed           %3d  (%s total)\nOver-rev            %3d\nCold high-RPM       %3d\nLong idle           %3d".format(
                        co, co * per100, os, ui.hms(osS), orv, cr, li)
        ), ui.lp())

        // Suggestions
        val tips = mutableListOf<String>()
        if (idle / maxOf(hours, 0.01) > 0.15) tips += "Idle time is ${ui.f(idle / hours * 100, 0, "%")} of driving – switch off during long waits (saved ≈ ${ui.f(idleFuel, 1, " $u")})."
        if ((hb + sb) * per100 > 3) tips += "Frequent hard braking – keep a larger following distance and anticipate signals."
        if (ra * per100 > 3) tips += "Frequent rapid acceleration – smoother throttle can improve economy 10–20 %."
        if (osS > 600) tips += "Spent ${ui.hms(osS)} above ${v.speedLimit} km/h – fuel use rises sharply above ~90 km/h."
        if (cr > 0) tips += "High RPM with a cold engine $cr time(s) – let the engine warm up gently."
        if (co * per100 > 2) tips += "Harsh cornering – slow down before bends."
        if (gps > 1000 && (obd / gps - 1) * 100 > 4) tips += "Speedometer reads %.0f %% high vs GPS – real distance is lower than shown.".format((obd / gps - 1) * 100)
        val due = Maint.dueList(v).filter { it.overdue }
        if (due.isNotEmpty()) tips += "Overdue service: " + due.joinToString { it.item.name }
        if (tips.isEmpty()) tips += "Great driving – no issues found in this period. 👍"
        box.addView(ui.section("Suggestions"))
        box.addView(ui.card(tips.joinToString("\n\n") { "• $it" }), ui.lp())

        // Fuel log comparison
        val logs = Db.fuelLogs(v.id).filter { it.t >= since }
        if (logs.isNotEmpty()) {
            box.addView(ui.section("Fuel purchases"))
            box.addView(ui.card("${logs.size} refuels · ${ui.f(logs.sumOf { it.qty }, 1, " $u")} · ${ui.money(logs.sumOf { it.cost })}\n" +
                    "Avg price ${ui.f(logs.sumOf { it.cost } / maxOf(logs.sumOf { it.qty }, 0.01), 2, " ₹/$u")}"), ui.lp())
        }
    }
}

/* ============================ Settings ============================ */

class SettingsPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var customBox: LinearLayout

    companion object {
        const val REQ_RESTORE = 77
    }

    override fun build(): View {
        val c = ui.col()
        c.addView(ui.section("Connection"))
        val autoConn = ui.check("Auto-connect to last adapter when app opens", Prefs.autoConnect)
        val autoRe = ui.check("Auto-reconnect if the link drops", Prefs.autoReconnect)
        val protos = listOf("0" to "Auto", "6" to "6 CAN 11-bit 500k", "8" to "8 CAN 11-bit 250k", "7" to "7 CAN 29-bit 500k",
            "9" to "9 CAN 29-bit 250k", "5" to "5 KWP fast", "4" to "4 KWP 5-baud", "3" to "3 ISO 9141-2", "1" to "1 J1850 PWM", "2" to "2 J1850 VPW")
        val proto = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, protos.map { it.second })
            setSelection(protos.indexOfFirst { it.first == Prefs.protocol }.coerceAtLeast(0))
        }
        val delay = ui.edit("ms", Prefs.pollDelayMs.toString(), true)
        c.addView(autoConn); c.addView(autoRe)
        c.addView(ui.row(ui.labeled("OBD protocol", proto), ui.labeled("Extra poll delay ms", delay)), ui.lp())

        c.addView(ui.section("Trips, GPS & logging"))
        val autoTrip = ui.check("Auto start/stop trips with the engine", Prefs.autoTrip)
        val gps = ui.check("GPS route tracking & cornering detection", Prefs.gpsEnabled)
        val csv = ui.check("Log live data to CSV (Downloads/CarOBD)", Prefs.csvLogging)
        val autoDtc = ui.check("Scan fault codes automatically on connect", Prefs.autoDtc)
        val tripN = ui.check("Notification with trip summary", Prefs.tripNotify)
        val screen = ui.check("Keep screen on while connected", Prefs.keepScreenOn)
        val econ = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, listOf("km/L", "L/100km"))
            setSelection(if (Prefs.economyUnit == "L/100km") 1 else 0)
        }
        listOf(autoTrip, gps, csv, autoDtc, tripN, screen).forEach { c.addView(it) }
        c.addView(ui.labeled("Economy unit", econ), ui.lp())

        c.addView(ui.section("Alerts & driving-event thresholds"))
        val events = ui.check("Driving alerts (overspeed, over-rev, braking, faults)", Prefs.eventAlerts)
        val voice = ui.check("Voice alerts (text-to-speech)", Prefs.voiceAlerts)
        val beep = ui.check("Beep when voice is off", Prefs.beepAlerts)
        listOf(events, voice, beep).forEach { c.addView(it) }
        val acc = ui.edit("g", Prefs.accelG.toString(), true)
        val brk = ui.edit("g", Prefs.brakeG.toString(), true)
        val sud = ui.edit("g", Prefs.suddenG.toString(), true)
        val cor = ui.edit("g", Prefs.cornerG.toString(), true)
        val idle = ui.edit("s", Prefs.longIdleS.toString(), true)
        c.addView(ui.row(ui.labeled("Rapid accel (g)", acc), ui.labeled("Hard brake (g)", brk)), ui.lp())
        c.addView(ui.row(ui.labeled("Sudden brake (g)", sud), ui.labeled("Cornering (g)", cor)), ui.lp())
        c.addView(ui.labeled("Long idle (seconds)", idle), ui.lp())
        c.addView(ui.tv("Defaults 0.22 g / 0.24 g follow published telematics calibration (≈ 7.8 and 8.5 km/h per second). " +
                "Speed and RPM limits are set per vehicle in Garage.", 12f, Ui.DIM), ui.lp())

        c.addView(ui.btn("Save settings", Ui.GOOD) {
            Prefs.autoConnect = autoConn.isChecked; Prefs.autoReconnect = autoRe.isChecked
            Prefs.protocol = protos[proto.selectedItemPosition.coerceAtLeast(0)].first
            delay.num()?.let { Prefs.pollDelayMs = it.toInt() }
            Prefs.autoTrip = autoTrip.isChecked; Prefs.gpsEnabled = gps.isChecked; Prefs.csvLogging = csv.isChecked
            Prefs.autoDtc = autoDtc.isChecked; Prefs.tripNotify = tripN.isChecked; Prefs.keepScreenOn = screen.isChecked
            Prefs.economyUnit = if (econ.selectedItemPosition == 1) "L/100km" else "km/L"
            Prefs.eventAlerts = events.isChecked; Prefs.voiceAlerts = voice.isChecked; Prefs.beepAlerts = beep.isChecked
            acc.num()?.let { Prefs.accelG = it.coerceIn(0.1, 1.0) }
            brk.num()?.let { Prefs.brakeG = it.coerceIn(0.1, 1.0) }
            sud.num()?.let { Prefs.suddenG = it.coerceIn(0.2, 1.2) }
            cor.num()?.let { Prefs.cornerG = it.coerceIn(0.1, 1.0) }
            idle.num()?.let { Prefs.longIdleS = it.toInt().coerceAtLeast(30) }
            ui.toast("Saved. Protocol / CSV changes apply on the next connection.")
        }, ui.lp(12))

        c.addView(ui.section("Custom PIDs (mode 22 / manufacturer)"))
        c.addView(ui.tv("Header = module to ask (7E0 engine, 7E1 gearbox; blank = all). Request = hex, e.g. 221234. " +
                "Formula uses reply bytes A, B, C… after the echoed request, e.g. (A*256+B)/10-40.", 12f, Ui.DIM))
        customBox = ui.vbox()
        c.addView(customBox, ui.lp())
        val cName = ui.edit("Name (e.g. Gearbox oil temp)", "")
        val cHead = ui.edit("Header (7E1)", "")
        val cReq = ui.edit("Request (22xxxx)", "")
        val cFormula = ui.edit("Formula (A-40)", "")
        val cUnit = ui.edit("Unit (°C)", "")
        c.addView(cName, ui.lp())
        c.addView(ui.row(cHead, cReq), ui.lp(6))
        c.addView(ui.row(cFormula, cUnit), ui.lp(6))
        c.addView(ui.btn("Add custom PID") {
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

        c.addView(ui.section("Backup"))
        c.addView(ui.row(
            ui.btn("Backup data") { backup() },
            ui.btn("Restore", Ui.CARD) {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
                @Suppress("DEPRECATION")
                a.startActivityForResult(i, REQ_RESTORE)
            }
        ), ui.lp())
        c.addView(ui.tv("Backup saves all vehicles, trips, routes, fuel and service history to Downloads/CarOBD.", 12f, Ui.DIM), ui.lp())

        c.addView(ui.section("About"))
        c.addView(ui.tv(
            "Car OBD 2.0 — universal ELM327 app (Bluetooth / BLE / WiFi). Works with OBD-II/EOBD petrol cars (~2001+), " +
                    "diesel (~2004+) and all CAN cars (~2008+). Live data & graphs, trip computer, real mileage, " +
                    "maintenance reminders, driving-behaviour analysis, DTC read/clear (OBD + UDS), GPS routes, HUD.\n" +
                    "Do not operate the app while driving.", 12f, Ui.DIM
        ), ui.lp())
        return ui.scroll(c)
    }

    override fun onShow() = rebuildCustom()

    private fun rebuildCustom() {
        if (!::customBox.isInitialized) return
        customBox.removeAllViews()
        val list = Prefs.customPids
        if (list.isEmpty()) customBox.addView(ui.tv("No custom PIDs.", 13f, Ui.DIM))
        for (cp in list) {
            val r = LinearLayout(a).apply {
                background = ui.rounded(Ui.CARD, 6)
                setPadding(ui.dp(10), ui.dp(6), ui.dp(6), ui.dp(6))
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(ui.tv("${cp.name}\n${cp.header.ifEmpty { "all" }} · ${cp.request} · ${cp.formula} ${cp.unit}", 12f),
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                addView(ui.btn("Delete", Ui.BAD) {
                    Prefs.customPids = Prefs.customPids.filter { it.name != cp.name }; rebuildCustom()
                })
            }
            customBox.addView(r, ui.lp(4))
        }
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

