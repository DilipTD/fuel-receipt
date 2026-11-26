package com.dilip.carobd

import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Shows a simple form dialog. */
fun Page.formDialog(title: String, fields: List<Pair<String, View>>, okText: String = "Save", onOk: () -> Unit) {
    val box = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
    for ((label, v) in fields) box.addView(ui.labeled(label, v), ui.lp(6))
    AlertDialog.Builder(a).setTitle(title).setView(ui.scroll(box))
        .setPositiveButton(okText) { _, _ -> onOk() }
        .setNegativeButton("Cancel", null).show()
}

fun EditText.num(): Double? = text.toString().trim().replace(",", ".").toDoubleOrNull()

/* ============================ More (hub) ============================ */

class MorePage(a: MainActivity) : Page(a) {
    private lateinit var box: LinearLayout
    private var hero: CarHeroView? = null

    override fun build(): View {
        box = ui.col()
        return ui.scroll(box)
    }

    override fun onShow() {
        box.removeAllViews()
        val v = ObdManager.vehicle
        val h = CarHeroView(a, true)
        hero = h
        h.title = v.title
        h.subtitle = "${v.fuel} · ${v.dispL} L\nOdometer ${ui.dist(v.odoKm, 0)}" + (if (v.regNo.isNotEmpty()) "\nReg. ${v.regNo}" else "") + (if (v.vin.isNotEmpty()) "\nVIN ${v.vin}" else "")
        h.linkIc = when (Prefs.lastType) { 1 -> Ic.BLE; 2 -> Ic.WIFI; else -> Ic.BLUETOOTH }
        box.addView(h, ui.lp(4))

        val due = Maint.dueList(v).filter { it.overdue || it.soon }
        if (due.isNotEmpty()) {
            box.addView(ui.iconCard(Ic.WRENCH, if (due.any { it.overdue }) Ui.BAD else Ui.WARN, "Maintenance attention",
                due.take(4).joinToString("\n") { "${it.item.name}: ${it.text}" },
                if (due.any { it.overdue }) Color.parseColor("#3A1A1D") else Color.parseColor("#3A2C0A")) { a.show("Maint") }, ui.lp(10))
        }

        val dueDocs = Docs.due(v.id)
        if (dueDocs.isNotEmpty()) {
            val bad = dueDocs.any { (Docs.days(it) ?: 0) < 0 }
            box.addView(ui.iconCard(Ic.DOC, if (bad) Ui.BAD else Ui.WARN, "Documents need renewal",
                dueDocs.take(4).joinToString("\n") { "${it.title.ifBlank { it.type }}: ${Docs.status(it).first}" },
                if (bad) Color.parseColor("#3A1A1D") else Color.parseColor("#3A2C0A")) { a.show("Docs") }, ui.lp(10))
        }

        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
        val trips = Db.trips(v.id, cal.timeInMillis)
        val km = trips.sumOf { it.distanceKm }
        val fuel = trips.sumOf { it.fuel }
        val spend = Db.fuelLogs(v.id).filter { it.t >= cal.timeInMillis }.sumOf { it.cost }
        box.addView(ui.section("This month", Ic.CALENDAR))
        val (s1, t1) = ui.stat(Ic.ROUTE, "Distance", Ui.GOOD); t1.text = ui.dist(km, 0)
        val (s2, t2) = ui.stat(Ic.CAR, "Trips", Ui.ACC); t2.text = trips.size.toString()
        val (s3, t3) = ui.stat(Ic.LEAF, "Economy", Ui.GOOD); t3.text = ui.econ(if (fuel > 0) km / fuel else null)
        val (s4, t4) = ui.stat(Ic.FUEL, "Fuel used", Ui.ORANGE); t4.text = ui.f(fuel, 1, " ${v.spec.unit}")
        val (s5, t5) = ui.stat(Ic.DATABASE, "Fuel spend", Ui.VIOLET); t5.text = ui.money(spend)
        val (s6, t6) = ui.stat(Ic.PULSE, "Avg score", Ui.CYAN); t6.text = if (trips.isNotEmpty()) trips.map { it.score }.average().toInt().toString() else "--"
        box.addView(ui.row(s1, s2, s3))
        box.addView(ui.row(s4, s5, s6), ui.lp(8))

        val alertN = try { Db.alertCountSince(System.currentTimeMillis() - 7 * 86_400_000L) } catch (_: Exception) { 0 }
        fun menu(title: String, ic: Ic, vararg items: Triple<String, Int, () -> Unit>) {
            box.addView(ui.section(title, ic))
            items.toList().chunked(3).forEach { row ->
                val views = row.map { (l, col, act) -> ui.menuTile(iconOf(l), l, col, act) }.toMutableList<View>()
                while (views.size < 3) views.add(View(a))
                box.addView(ui.grid(*views.toTypedArray()), ui.lp(8))
            }
        }
        menu("Tools", Ic.GRID,
            Triple("Live data", Ui.ACC) { a.show("Live") },
            Triple("Road assistant", Ui.GOOD) { a.show("Road") },
            Triple("Documents", Ui.CYAN) { a.show("Docs") },
            Triple("Backup & cloud", Ui.VIOLET) { a.show("Backup") },
            Triple("Reports", Ui.VIOLET) { a.show("Reports") },
            Triple("Diagnosis", Ui.PINK) { a.show("Diag") },
            Triple("Drive mode", Ui.CYAN) { a.show("Car") },
            Triple("Car health", Ui.GOOD) { a.show("Health") },
            Triple("Driving style", Ui.VIOLET) { a.show("Analysis") },
            Triple("Maintenance", Ui.WARN) { a.show("Maint") },
            Triple("Garage", Ui.ACC) { a.show("Garage") },
            Triple(if (alertN > 0) "Alerts ($alertN)" else "Alerts", Ui.PINK) { a.show("Alerts") },
            Triple("Performance", Ui.CYAN) { a.show("Perf") },
            Triple("Battery", Ui.GOOD) { a.show("Battery") },
            Triple("HUD", Ui.ACC) { a.show("HUD") },
            Triple("Parking", Ui.ORANGE) { openParking() },
            Triple("Connect", Ui.GOOD) { a.show("Connect") },
            Triple("Calculator", Ui.GOOD) { a.show("Calc") },
            Triple("Help & FAQ", Ui.CYAN) { a.show("Faq") },
            Triple("Settings", Color.parseColor("#90A4AE")) { a.show("Settings") },
            Triple("Permissions", Ui.ORANGE) { a.show("Perms") },
            Triple("Privacy & legal", Ui.GOOD) { a.show("Legal") },
            Triple("About", Ui.VIOLET) { a.show("About") }
        )
        box.addView(ui.tv("${AppInfo.NAME} v${AppInfo.VERSION}\nSoftware developed by ${AppInfo.DEVELOPER}", 12f, Ui.DIM).apply {
            gravity = android.view.Gravity.CENTER
        }, ui.lp(22))
    }

    private fun iconOf(l: String): Ic = when {
        l.startsWith("Live") -> Ic.CHART; l.startsWith("Road") -> Ic.NAV; l.startsWith("Backup") -> Ic.DATABASE; l.startsWith("Diag") -> Ic.PULSE; l.startsWith("Drive") -> Ic.GAUGE
        l.startsWith("Car health") -> Ic.SHIELD; l.startsWith("Driving") -> Ic.USER; l.startsWith("Maint") -> Ic.WRENCH
        l.startsWith("Garage") -> Ic.CAR; l.startsWith("Alerts") -> Ic.BELL; l.startsWith("Perf") -> Ic.TIMER
        l.startsWith("Battery") -> Ic.BATTERY; l.startsWith("HUD") -> Ic.MONITOR; l.startsWith("Parking") -> Ic.PIN
        l.startsWith("Connect") -> Ic.PLUG; l.startsWith("Settings") -> Ic.GEAR; l.startsWith("About") -> Ic.INFO; l.startsWith("Privacy") -> Ic.SHIELD; l.startsWith("Permissions") -> Ic.SLIDERS; l.startsWith("Home Assistant") -> Ic.HOME; l.startsWith("Documents") -> Ic.DOC
        l.startsWith("Reports") -> Ic.DOC; l.startsWith("Calc") -> Ic.CHART; l.startsWith("Help") -> Ic.INFO
        l.startsWith("Petrol") -> Ic.FUEL; l.startsWith("Service") -> Ic.WRENCH; l.startsWith("Find") -> Ic.PIN
        l.startsWith("EV") -> Ic.BOLT; l.startsWith("Tyre") -> Ic.WIND; l.startsWith("Car wash") -> Ic.DROP
        else -> Ic.GRID
    }

    override fun refresh() {
        val m = ObdManager
        hero?.state = when (m.state) {
            ObdManager.State.CONNECTED -> if (m.engineOn) 2 else 3
            ObdManager.State.CONNECTING -> 1
            else -> 0
        }
        hero?.speed = m.values[0x0D] ?: 0.0
    }
}

/* ============================ Garage ============================ */

class GaragePage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout

    override fun build(): View {
        box = ui.col()
        return ui.scroll(box)
    }

    override fun onShow() {
        box.removeAllViews()
        box.addView(ui.section("Vehicles"))
        val active = ObdManager.vehicle.id
        for (v in Db.vehicles()) {
            box.addView(ui.card((if (v.id == active) "✔ " else "") + "${v.title}\n${v.fuel} · ${v.dispL} L · odo ${ui.dist(v.odoKm, 0)}" +
                    (if (v.year > 0) " · ${v.year}" else ""), 14f, Ui.WHITE, if (v.id == active) Ui.SEL else Ui.CARD), ui.lp(8))
            box.addView(ui.row(
                ui.btn("Use", Ui.GOOD) {
                    if (ObdManager.engine.current != null) ObdManager.stopTrip()
                    Prefs.activeVehicle = v.id; ObdManager.reloadVehicle(); onShow()
                },
                ui.btn("Edit") { edit(v) },
                ui.btn("Delete", Ui.BAD) {
                    AlertDialog.Builder(a).setTitle("Delete ${v.name} and all its trips, fuel and service history?")
                        .setPositiveButton("Delete") { _, _ ->
                            Docs.purge(a, v.id)
                            Db.deleteVehicle(v.id)
                            if (v.id == Prefs.activeVehicle) { Prefs.activeVehicle = 0; ObdManager.reloadVehicle() }
                            onShow()
                        }.setNegativeButton("Cancel", null).show()
                }
            ), ui.lp(4))
        }
        box.addView(ui.btn("+ Add vehicle", Ui.GOOD) { edit(Vehicle()) }, ui.lp(14))
        box.addView(ui.tv("Tip: speed limit drives overspeed alerts; RPM limit drives over-rev alerts. " +
                "Fuel correction is set automatically from the Fuel log (full-to-full fills).", 12f, Ui.DIM), ui.lp())
    }

    private fun edit(v: Vehicle) {
        val name = ui.edit("My car", v.name)
        val make = ui.edit("e.g. Tata, Maruti, Hyundai", v.make)
        val model = ui.edit("e.g. Nexon, Swift, Creta", v.model)
        val year = ui.edit("2022", if (v.year > 0) v.year.toString() else "", true)
        val fuel = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, Fuels.all.map { it.name })
            setSelection(Fuels.all.indexOfFirst { it.name == v.fuel }.coerceAtLeast(0))
        }
        val disp = ui.edit("litres", v.dispL.toString(), true)
        val tank = ui.edit("litres / kg / kWh", v.tankL.toString(), true)
        val ve = ui.edit("0.80–0.90", v.ve.toString(), true)
        val price = ui.edit("₹ per unit", v.fuelPrice.toString(), true)
        val corr = ui.edit("1.00", "%.3f".format(Locale.US, v.fuelCorr), true)
        val odo = ui.edit("km", "%.0f".format(Locale.US, v.odoKm), true)
        val spd = ui.edit("km/h", v.speedLimit.toString(), true)
        val rpm = ui.edit("rpm", v.rpmLimit.toString(), true)
        val vinE = ui.edit("17 characters (or Documents → Fetch VIN)", v.vin)
        val regE = ui.edit("e.g. KA01AB1234", v.regNo)
        formDialog(if (v.id == 0L) "Add vehicle" else "Edit ${v.name}", listOf(
            "Name" to name, "Make" to make, "Model" to model, "Year" to year, "Fuel type" to fuel,
            "Engine displacement (L)" to disp, "Tank (L / kg) or battery (kWh)" to tank, "Volumetric efficiency" to ve,
            "Fuel price" to price, "Fuel correction factor" to corr, "Odometer (km)" to odo,
            "Overspeed alert (km/h)" to spd, "Over-rev alert (rpm)" to rpm,
            "VIN" to vinE, "Registration number" to regE
        )) {
            val isNew = v.id == 0L
            v.name = name.text.toString().ifBlank { "My car" }
            v.make = make.text.toString().trim(); v.model = model.text.toString().trim()
            v.year = year.num()?.toInt() ?: 0
            v.fuel = Fuels.all[fuel.selectedItemPosition.coerceAtLeast(0)].name
            disp.num()?.let { v.dispL = it }; tank.num()?.let { v.tankL = it }; ve.num()?.let { v.ve = it }
            price.num()?.let { v.fuelPrice = it }; corr.num()?.let { v.fuelCorr = it }; odo.num()?.let { v.odoKm = it }
            spd.num()?.let { v.speedLimit = it.toInt() }; rpm.num()?.let { v.rpmLimit = it.toInt() }
            v.vin = vinE.text.toString().trim().uppercase().replace(" ", ""); v.regNo = regE.text.toString().trim().uppercase()
            Db.saveVehicle(v)
            if (isNew) {
                Maint.loadDefaults(v)
                Prefs.activeVehicle = v.id
            }
            ObdManager.reloadVehicle()
            onShow()
        }
    }
}

/* ============================ Maintenance ============================ */

class MaintPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout

    override fun build(): View {
        box = ui.col()
        return ui.scroll(box)
    }

    override fun onShow() {
        box.removeAllViews()
        val v = ObdManager.vehicle
        box.addView(ui.section("Maintenance – ${v.name}"))
        val odo = ui.edit("km", "%.0f".format(Locale.US, v.odoKm), true)
        box.addView(ui.row(ui.labeled("Current odometer (km)", odo), ui.btn("Update odometer") {
            odo.num()?.let { v.odoKm = it; Db.saveVehicle(v); ObdManager.reloadVehicle(); ui.toast("Odometer updated"); onShow() }
        }), ui.lp())

        val due = Maint.dueList(v)
        if (due.isEmpty()) {
            box.addView(ui.tv("No service items.", 13f, Ui.DIM), ui.lp())
            box.addView(ui.btn("Load default service schedule") { Maint.loadDefaults(v); onShow() }, ui.lp())
        }
        box.addView(ui.tv("Tap an item: mark done / edit interval / delete.", 12f, Ui.DIM), ui.lp())
        for (d in due) {
            val bg = when {
                d.overdue -> Ui.BAD
                d.soon -> 0xFF8A6D00.toInt()
                else -> Ui.CARD
            }
            val item = d.item
            val interval = listOfNotNull(
                if (item.intervalKm > 0) "every ${ui.f(item.intervalKm, 0)} km" else null,
                if (item.intervalDays > 0) "every ${item.intervalDays} days" else null
            ).joinToString(" / ")
            val (mic, mcol) = Maint.iconFor(item.name)
            box.addView(ui.iconCard(mic, if (d.overdue) Color.WHITE else mcol, item.name,
                "${d.text}\n$interval · last ${ui.date(item.lastT, "dd MMM yy")} @ ${ui.dist(item.lastKm, 0)}", bg) { itemMenu(item) }, ui.lp(5))
        }

        box.addView(ui.section("Add custom item"))
        val n = ui.edit("e.g. Clutch oil, FASTag recharge", "")
        val km = ui.edit("km (0 = none)", "", true)
        val days = ui.edit("days (0 = none)", "", true)
        box.addView(n, ui.lp())
        box.addView(ui.row(km, days), ui.lp(6))
        box.addView(ui.btn("Add item", Ui.GOOD) {
            if (n.text.isBlank()) { ui.toast("Enter a name"); return@btn }
            Db.saveMaint(MaintItem(0, v.id, n.text.toString().trim(), km.num() ?: 0.0, days.num()?.toInt() ?: 0, v.odoKm, System.currentTimeMillis()))
            onShow()
        }, ui.lp(6))

        val recs = Db.records(v.id)
        box.addView(ui.section("Service history · total ${ui.money(recs.sumOf { it.cost })}"))
        if (recs.isNotEmpty()) box.addView(ui.tv("Swipe a record to the left to delete it", 11.5f, Ui.DIM))
        if (recs.isEmpty()) box.addView(ui.tv("No service records yet.", 13f, Ui.DIM))
        for (r in recs) {
            val (ric, rcol) = Maint.iconFor(r.name)
            box.addView(ui.swipeDelete(ui.iconCard(ric, rcol, r.name, "${ui.date(r.t, "dd MMM yyyy")} · ${ui.dist(r.odo, 0)} · ${ui.money(r.cost)}" +
                    (if (r.note.isNotEmpty()) "\n${r.note}" else ""), Ui.CARD),
                "Delete this service record?", r.name) { Db.deleteRecord(r.id); onShow() }, ui.lp(4))
        }
        box.addView(ui.btn("Export service history CSV", Ui.CARD) {
            val sb = StringBuilder("date,odometer_km,item,cost,note\n")
            recs.forEach { sb.append("${ui.date(it.t, "yyyy-MM-dd")},${"%.0f".format(it.odo)},\"${it.name}\",${"%.2f".format(it.cost)},\"${it.note.replace("\"", "'")}\"\n") }
            val p = Exporter.writeText(a, "carobd_service_${stamp()}.csv", "text/csv", sb.toString())
            ui.toast(if (p != null) "Saved to $p" else "Export failed")
        }, ui.lp(10))
    }

    private fun itemMenu(item: MaintItem) {
        AlertDialog.Builder(a).setTitle(item.name)
            .setItems(arrayOf("Mark as done", "Edit interval", "Delete item")) { _, which ->
                when (which) {
                    0 -> markDone(item)
                    1 -> editItem(item)
                    2 -> { Db.deleteMaint(item.id); onShow() }
                }
            }.show()
    }

    private fun markDone(item: MaintItem) {
        val v = ObdManager.vehicle
        val odo = ui.edit("km", "%.0f".format(Locale.US, v.odoKm), true)
        val cost = ui.edit("₹", "", true)
        val note = ui.edit("workshop / parts / note", "")
        formDialog("Done: ${item.name}", listOf("Odometer" to odo, "Cost" to cost, "Note" to note)) {
            val o = odo.num() ?: v.odoKm
            item.lastKm = o; item.lastT = System.currentTimeMillis()
            Db.saveMaint(item)
            Db.insertRecord(MaintRecord(0, v.id, item.id, item.name, System.currentTimeMillis(), o, cost.num() ?: 0.0, note.text.toString()))
            if (o > v.odoKm) { v.odoKm = o; Db.saveVehicle(v); ObdManager.reloadVehicle() }
            onShow()
        }
    }

    private fun editItem(item: MaintItem) {
        val name = ui.edit("name", item.name)
        val km = ui.edit("km", "%.0f".format(Locale.US, item.intervalKm), true)
        val days = ui.edit("days", item.intervalDays.toString(), true)
        val lastKm = ui.edit("km", "%.0f".format(Locale.US, item.lastKm), true)
        val lastDate = ui.edit("yyyy-MM-dd", ui.date(item.lastT, "yyyy-MM-dd"))
        formDialog("Edit ${item.name}", listOf(
            "Name" to name, "Interval km (0 = none)" to km, "Interval days (0 = none)" to days,
            "Last done at km" to lastKm, "Last done on (yyyy-MM-dd)" to lastDate
        )) {
            item.name = name.text.toString().ifBlank { item.name }
            item.intervalKm = km.num() ?: item.intervalKm
            item.intervalDays = days.num()?.toInt() ?: item.intervalDays
            item.lastKm = lastKm.num() ?: item.lastKm
            try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(lastDate.text.toString())?.let { item.lastT = it.time }
            } catch (_: Exception) {
            }
            Db.saveMaint(item)
            onShow()
        }
    }
}
