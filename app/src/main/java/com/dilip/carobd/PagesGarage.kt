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
        h.subtitle = "${v.fuel} · ${v.dispL} L\nOdometer ${ui.f(v.odoKm, 0, " km")}" + (if (v.vin.isNotEmpty()) "\nVIN ${v.vin}" else "")
        h.linkIc = when (Prefs.lastType) { 1 -> Ic.BLE; 2 -> Ic.WIFI; else -> Ic.BLUETOOTH }
        box.addView(h, ui.lp(4))

        val due = Maint.dueList(v).filter { it.overdue || it.soon }
        if (due.isNotEmpty()) {
            box.addView(ui.iconCard(Ic.WRENCH, if (due.any { it.overdue }) Ui.BAD else Ui.WARN, "Maintenance attention",
                due.take(4).joinToString("\n") { "${it.item.name}: ${it.text}" },
                if (due.any { it.overdue }) Color.parseColor("#3A1A1D") else Color.parseColor("#3A2C0A")) { a.show("Maint") }, ui.lp(10))
        }

        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
        val trips = Db.trips(v.id, cal.timeInMillis)
        val km = trips.sumOf { it.distanceKm }
        val fuel = trips.sumOf { it.fuel }
        val spend = Db.fuelLogs(v.id).filter { it.t >= cal.timeInMillis }.sumOf { it.cost }
        box.addView(ui.section("This month", Ic.CALENDAR))
        val (s1, t1) = ui.stat(Ic.ROUTE, "Distance", Ui.GOOD); t1.text = ui.f(km, 0, " km")
        val (s2, t2) = ui.stat(Ic.CAR, "Trips", Ui.ACC); t2.text = trips.size.toString()
        val (s3, t3) = ui.stat(Ic.LEAF, "Economy", Ui.GOOD); t3.text = ui.econ(if (fuel > 0) km / fuel else null)
        val (s4, t4) = ui.stat(Ic.FUEL, "Fuel used", Ui.ORANGE); t4.text = ui.f(fuel, 1, " ${v.spec.unit}")
        val (s5, t5) = ui.stat(Ic.DATABASE, "Fuel spend", Ui.VIOLET); t5.text = ui.money(spend)
        val (s6, t6) = ui.stat(Ic.PULSE, "Avg score", Ui.CYAN); t6.text = if (trips.isNotEmpty()) trips.map { it.score }.average().toInt().toString() else "--"
        box.addView(ui.row(s1, s2, s3))
        box.addView(ui.row(s4, s5, s6), ui.lp(8))

        val alertN = try { Db.alertCountSince(System.currentTimeMillis() - 7 * 86_400_000L) } catch (_: Exception) { 0 }
        val nVeh = Db.vehicles().size
        box.addView(ui.section("Tools", Ic.GRID))
        box.addView(ui.grid(
            ui.tile(Ic.CAR, "Garage", "$nVeh vehicle(s)", Ui.ACC) { a.show("Garage") },
            ui.tile(Ic.FUEL, "Fuel log", "Real mileage & cost", Ui.ORANGE) { a.show("Fuel") }
        ), ui.lp(0))
        box.addView(ui.grid(
            ui.tile(Ic.WRENCH, "Maintenance", if (due.isEmpty()) "All up to date" else "${due.size} need attention", if (due.isEmpty()) Ui.GOOD else Ui.WARN) { a.show("Maint") },
            ui.tile(Ic.CHART, "Analysis", "Trends & driving tips", Ui.VIOLET) { a.show("Analysis") }
        ), ui.lp(8))
        box.addView(ui.grid(
            ui.tile(Ic.BELL, "Alerts", if (alertN > 0) "$alertN this week" else "No alerts", if (alertN > 0) Ui.PINK else Ui.DIM) { a.show("Alerts") },
            ui.tile(Ic.TIMER, "Performance", "0-100 · ¼ mile · braking", Ui.CYAN) { a.show("Perf") }
        ), ui.lp(8))
        box.addView(ui.grid(
            ui.tile(Ic.BATTERY, "Battery", "Voltage & cranking", Ui.GOOD) { a.show("Battery") },
            ui.tile(Ic.PIN, "Where's my car", if (Prefs.parkT > 0) "Parked " + ui.date(Prefs.parkT, "dd MMM HH:mm") else "Saved when a trip ends", Ui.ORANGE) { openParking() }
        ), ui.lp(8))
        box.addView(ui.section("Nearby", Ic.PIN))
        box.addView(ui.grid(
            ui.tile(Ic.FUEL, "Petrol pump", "Find nearest", Ui.ORANGE) { openNearby("petrol pump") },
            ui.tile(Ic.WRENCH, "Service centre", "Garages near you", Ui.ACC) { openNearby("car service center") },
            ui.tile(Ic.PIN, "Parking", "Find parking", Ui.CYAN) { openNearby("parking") }
        ), ui.lp(8))
        box.addView(ui.grid(
            ui.tile(Ic.BOLT, "EV / CNG", "Charging & CNG", Ui.GOOD) { openNearby("EV charging station") },
            ui.tile(Ic.WIND, "Tyre shop", "Air & puncture", Ui.VIOLET) { openNearby("tyre shop") },
            ui.tile(Ic.DROP, "Car wash", "Detailing", Ui.PINK) { openNearby("car wash") }
        ), ui.lp(8))
        box.addView(ui.section("More tools", Ic.GEAR))
        box.addView(ui.grid(
            ui.tile(Ic.SHIELD, "Car health", "Status of every system", Ui.GOOD) { a.show("Health") },
            ui.tile(Ic.MONITOR, "HUD mode", "Windscreen mirror", Ui.ACC) { a.show("HUD") },
            ui.tile(Ic.GEAR, "Settings", "Connection & options", Color.parseColor("#90A4AE")) { a.show("Settings") }
        ), ui.lp(8))
        box.addView(ui.tile(Ic.INFO, "About ${AppInfo.NAME}", "Version ${AppInfo.VERSION} · developed by ${AppInfo.DEVELOPER}", Ui.VIOLET) { a.show("About") }, ui.lp(8))
        box.addView(ui.tv("${AppInfo.NAME} v${AppInfo.VERSION}\nSoftware developed by ${AppInfo.DEVELOPER}", 12f, Ui.DIM).apply {
            gravity = android.view.Gravity.CENTER
        }, ui.lp(22))
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
            box.addView(ui.card((if (v.id == active) "✔ " else "") + "${v.title}\n${v.fuel} · ${v.dispL} L · odo ${ui.f(v.odoKm, 0, " km")}" +
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
        val tank = ui.edit("litres / kg", v.tankL.toString(), true)
        val ve = ui.edit("0.80–0.90", v.ve.toString(), true)
        val price = ui.edit("₹ per unit", v.fuelPrice.toString(), true)
        val corr = ui.edit("1.00", "%.3f".format(Locale.US, v.fuelCorr), true)
        val odo = ui.edit("km", "%.0f".format(Locale.US, v.odoKm), true)
        val spd = ui.edit("km/h", v.speedLimit.toString(), true)
        val rpm = ui.edit("rpm", v.rpmLimit.toString(), true)
        formDialog(if (v.id == 0L) "Add vehicle" else "Edit ${v.name}", listOf(
            "Name" to name, "Make" to make, "Model" to model, "Year" to year, "Fuel type" to fuel,
            "Engine displacement (L)" to disp, "Tank capacity" to tank, "Volumetric efficiency" to ve,
            "Fuel price" to price, "Fuel correction factor" to corr, "Odometer (km)" to odo,
            "Overspeed alert (km/h)" to spd, "Over-rev alert (rpm)" to rpm
        )) {
            val isNew = v.id == 0L
            v.name = name.text.toString().ifBlank { "My car" }
            v.make = make.text.toString().trim(); v.model = model.text.toString().trim()
            v.year = year.num()?.toInt() ?: 0
            v.fuel = Fuels.all[fuel.selectedItemPosition.coerceAtLeast(0)].name
            disp.num()?.let { v.dispL = it }; tank.num()?.let { v.tankL = it }; ve.num()?.let { v.ve = it }
            price.num()?.let { v.fuelPrice = it }; corr.num()?.let { v.fuelCorr = it }; odo.num()?.let { v.odoKm = it }
            spd.num()?.let { v.speedLimit = it.toInt() }; rpm.num()?.let { v.rpmLimit = it.toInt() }
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

/* ============================ Fuel log & real mileage ============================ */

class FuelPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun build(): View {
        box = ui.col()
        return ui.scroll(box)
    }

    /** Economy for each full fill = distance since previous full fill / fuel added since then. */
    private fun fullToFull(logs: List<FuelLog>): List<Triple<FuelLog, FuelLog, Double>> {
        val asc = logs.sortedBy { it.odo }
        val out = mutableListOf<Triple<FuelLog, FuelLog, Double>>()
        var prevFull: FuelLog? = null
        var qty = 0.0
        for (l in asc) {
            if (prevFull != null) qty += l.qty
            if (l.full) {
                val p = prevFull
                if (p != null && l.odo > p.odo && qty > 0) out.add(Triple(p, l, (l.odo - p.odo) / qty))
                prevFull = l; qty = 0.0
            }
        }
        return out
    }

    override fun onShow() {
        box.removeAllViews()
        val v = ObdManager.vehicle
        val u = v.spec.unit
        val logs = Db.fuelLogs(v.id)

        box.addView(ui.section("Add refuel – ${v.name}"))
        val date = ui.edit("yyyy-MM-dd HH:mm", fmt.format(System.currentTimeMillis()))
        val odo = ui.edit("km", "%.0f".format(Locale.US, v.odoKm), true)
        val qty = ui.edit(u, "", true)
        val cost = ui.edit("total ₹", "", true)
        val full = ui.check("Full tank (needed for real mileage)", true)
        val note = ui.edit("station / note", "")
        box.addView(ui.row(ui.labeled("Date & time", date), ui.labeled("Odometer km", odo)), ui.lp())
        box.addView(ui.row(ui.labeled("Quantity ($u)", qty), ui.labeled("Total cost ₹ (blank = qty × price)", cost)), ui.lp())
        box.addView(full, ui.lp())
        box.addView(note, ui.lp(4))
        box.addView(ui.btn("Save refuel", Ui.GOOD) {
            val q = qty.num()
            val o = odo.num()
            if (q == null || q <= 0 || o == null) { ui.toast("Enter odometer and quantity"); return@btn }
            val c = cost.num() ?: (q * v.fuelPrice)
            val t = try { fmt.parse(date.text.toString())?.time } catch (_: Exception) { null } ?: System.currentTimeMillis()
            Db.saveFuel(FuelLog(0, v.id, t, o, q, c, full.isChecked, note.text.toString()))
            if (c > 0) v.fuelPrice = c / q
            if (o > v.odoKm) v.odoKm = o
            Db.saveVehicle(v); ObdManager.reloadVehicle()
            ui.toast("Saved")
            onShow()
        }, ui.lp(8))

        val ff = fullToFull(logs)
        box.addView(ui.section("Real mileage (full-to-full)"))
        if (ff.isEmpty()) box.addView(ui.tv("Log at least two full-tank fills to compute real mileage.", 13f, Ui.DIM))
        else {
            val econ = ff.map { it.third }
            val asc = logs.sortedBy { it.odo }
            val dist = asc.last().odo - asc.first().odo
            val totalCost = logs.sumOf { it.cost }
            val costPerKm = if (dist > 0) (totalCost - asc.last().cost) / dist else null
            box.addView(ui.mono(
                "Last      ${ui.econ(econ.last(), u)}\nAverage   ${ui.econ(econ.average(), u)}\n" +
                        "Best      ${ui.econ(econ.max(), u)}\nWorst     ${ui.econ(econ.min(), u)}\n" +
                        "Cost/km   ${ui.f(costPerKm, 2, " ₹")}\nTotal     ${ui.money(totalCost)} over ${ui.f(dist, 0, " km")}"
            ), ui.lp())
            val chart = LineChart(a, 140)
            chart.set(econ.map { it.toFloat() }.toFloatArray(), "km/$u per fill")
            box.addView(chart, ui.lp(8))

            // Calibration: app-measured fuel vs real fuel for the last full-to-full interval
            val (p, l, _) = ff.last()
            val real = logs.filter { it.odo > p.odo && it.odo <= l.odo }.sumOf { it.qty }
            val appFuel = Db.trips(v.id, p.t).filter { it.start < l.t }.sumOf { it.fuel }
            if (appFuel > 0.5 && real > 0) {
                val factor = (real / appFuel * v.fuelCorr).coerceIn(0.3, 3.0)
                box.addView(ui.card("Calibration: app measured ${ui.f(appFuel, 2, " $u")} vs real ${ui.f(real, 2, " $u")} " +
                        "since last full fill.\nSuggested fuel correction ×%.3f (now ×%.3f)".format(factor, v.fuelCorr)), ui.lp(8))
                box.addView(ui.btn("Apply calibration") {
                    v.fuelCorr = factor; Db.saveVehicle(v); ObdManager.reloadVehicle(); ui.toast("Applied"); onShow()
                }, ui.lp(4))
            }
        }

        box.addView(ui.section("Refuel history (long-press to delete)"))
        if (logs.isEmpty()) box.addView(ui.tv("No refuels yet.", 13f, Ui.DIM))
        for (l in logs) {
            box.addView(ui.card("${ui.date(l.t)} · ${ui.f(l.odo, 0, " km")}\n${ui.f(l.qty, 2, " $u")} · ${ui.money(l.cost)} · " +
                    "${ui.f(if (l.qty > 0) l.cost / l.qty else null, 2, " ₹/$u")}${if (l.full) " · FULL" else ""}" +
                    (if (l.note.isNotEmpty()) "\n${l.note}" else ""), 12f).apply {
                setOnLongClickListener {
                    AlertDialog.Builder(a).setTitle("Delete this refuel?")
                        .setPositiveButton("Delete") { _, _ -> Db.deleteFuel(l.id); onShow() }
                        .setNegativeButton("Cancel", null).show(); true
                }
            }, ui.lp(4))
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
            box.addView(ui.card("${item.name}\n${d.text}\n$interval · last ${ui.date(item.lastT, "dd MMM yy")} @ ${ui.f(item.lastKm, 0, " km")}", 13f, Ui.WHITE, bg).apply {
                setOnClickListener { itemMenu(item) }
            }, ui.lp(5))
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
        if (recs.isEmpty()) box.addView(ui.tv("No service records yet.", 13f, Ui.DIM))
        for (r in recs) {
            box.addView(ui.card("${ui.date(r.t, "dd MMM yyyy")} · ${ui.f(r.odo, 0, " km")} · ${ui.money(r.cost)}\n${r.name}" +
                    (if (r.note.isNotEmpty()) "\n${r.note}" else ""), 12f).apply {
                setOnLongClickListener {
                    AlertDialog.Builder(a).setTitle("Delete this record?")
                        .setPositiveButton("Delete") { _, _ -> Db.deleteRecord(r.id); onShow() }
                        .setNegativeButton("Cancel", null).show(); true
                }
            }, ui.lp(4))
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
