package com.dilip.carobd

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import java.util.Calendar
import java.util.Locale

private const val MISSED = "[missed]"

/** Per-fill derived numbers used by the list, stats and charts. */
class FillInfo(val log: FuelLog, val sinceKm: Double, val econ: Double?, val tripCost: Double, val tripFuel: Double, val tripN: Int)

object FuelMath {
    fun analyse(vid: Long, logs: List<FuelLog>): List<FillInfo> {
        val asc = logs.sortedWith(compareBy({ it.t }, { it.odo }))
        val trips = Db.trips(vid)
        val out = ArrayList<FillInfo>()
        var prev: FuelLog? = null
        var prevFull: FuelLog? = null
        var qtySinceFull = 0.0
        for (l in asc) {
            var econ: Double? = null
            if (prevFull != null) qtySinceFull += l.qty
            if (l.full) {
                val p = prevFull
                if (p != null && !l.note.contains(MISSED) && l.odo > p.odo && qtySinceFull > 0) econ = (l.odo - p.odo) / qtySinceFull
                prevFull = l; qtySinceFull = 0.0
            }
            if (l.note.contains(MISSED)) { prevFull = if (l.full) l else null; qtySinceFull = 0.0 }
            val from = prev?.t ?: 0L
            val tr = if (prev == null) emptyList() else trips.filter { it.start >= from && it.start < l.t }
            out += FillInfo(l, if (prev != null && l.odo > prev.odo) l.odo - prev.odo else 0.0, econ,
                tr.sumOf { it.cost }, tr.sumOf { it.fuel }, tr.size)
            prev = l
        }
        return out.reversed()
    }

    /** Re-prices every trip with the price of the latest fill-up made before it started. */
    fun repriceTrips(vid: Long) {
        val fills = Db.fuelLogs(vid).filter { it.qty > 0 && it.cost > 0 }.sortedBy { it.t }
        if (fills.isEmpty()) return
        for (t in Db.trips(vid)) {
            val f = fills.lastOrNull { it.t <= t.start } ?: fills.first()
            val price = f.cost / f.qty
            if (kotlin.math.abs(price - t.fuelPrice) > 0.001) { t.fuelPrice = price; try { Db.updateTrip(t) } catch (_: Exception) {} }
        }
    }
}

/* ============================ Fill-ups list ============================ */

class FuelPage(a: MainActivity) : Page(a) {
    override val parent: String? = null
    private lateinit var box: LinearLayout
    private var mode = 0

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    override fun onShow() {
        box.removeAllViews()
        val v = ObdManager.vehicle
        val u = v.spec.unit
        val logs = Db.fuelLogs(v.id)
        val info = FuelMath.analyse(v.id, logs)

        // header
        val add = ui.iconView(Ic.PLUS, Ui.WHITE, 26).apply {
            background = ui.rounded(Ui.ACC, 26)
            setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
            setOnClickListener { a.selectedFuelId = 0; a.show("FuelAdd") }
            ui.pressAnim(this)
        }
        box.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.tv("Fill-ups", 30f, Ui.WHITE, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(add, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        })
        box.addView(ui.row(
            *listOf(Triple(0, "List", Ic.DOC), Triple(1, "Stats", Ic.CHART), Triple(2, "Charts", Ic.PULSE)).map { (m, l, ic) ->
                ui.btn(l, if (m == mode) Ui.ACC else Ui.CARD) { mode = m; onShow() }
            }.toTypedArray()), ui.lp(8))

        if (logs.isEmpty()) {
            box.addView(ui.iconCard(Ic.FUEL, Ui.ORANGE, "No fill-ups yet", "Tap + to add your first one") { a.selectedFuelId = 0; a.show("FuelAdd") }, ui.lp(14))
            return
        }
        when (mode) { 0 -> list(info, u); 1 -> stats(info, u, v); else -> charts(info, u) }
    }

    private fun list(info: List<FillInfo>, u: String) {
        val avg = info.mapNotNull { it.econ }.average().takeIf { !it.isNaN() }
        var month = ""
        for (f in info) {
            val l = f.log
            val m = ui.date(l.t, "MMMM yyyy")
            if (m != month) { month = m; box.addView(ui.tv(m, 16f, Ui.DIM, true), ui.lp(16)) }
            val ppu = if (l.qty > 0) l.cost / l.qty else 0.0
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.rounded(Ui.CARD, 22)
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(12))
                // top row
                addView(LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.badge(Ic.FUEL, Ui.VIOLET, 48), LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { rightMargin = ui.dp(12) })
                    addView(ui.vbox().apply {
                        addView(ui.tv(ui.date(l.t, "dd-MM-yyyy"), 17f, Ui.WHITE, true))
                        addView(ui.tv(ui.money(l.cost).let { "$it" }, 19f, Ui.WHITE, true))
                    }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                    addView(ui.vbox().apply {
                        gravity = Gravity.END
                        addView(ui.tv("%,.0f km".format(l.odo), 17f, Ui.WHITE, true).apply { gravity = Gravity.END })
                        if (f.sinceKm > 0) addView(ui.chip("%.0f km".format(f.sinceKm), Ui.VIOLET), ui.lp(4))
                    })
                })
                addView(View(a).apply { setBackgroundColor(Color.parseColor("#3A3A4C")) }, LinearLayout.LayoutParams(Ui.MATCH, 1).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(8) })
                addView(infoRow(Ic.DROP, Ui.VIOLET, "%.2f %s → ₹%.2f/%s%s".format(Locale.US, l.qty, u, ppu, u, if (l.full) "" else "  (partial)"), Ui.WHITE))
                val econCol = f.econ?.let { e -> if (avg != null && e < avg * 0.95) Ui.BAD else Ui.GOOD } ?: Ui.DIM
                addView(LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.iconView(Ic.CHART, Ui.VIOLET, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(10) })
                    addView(ui.tv(f.econ?.let { "%.2f km/%s".format(Locale.US, it, u) } ?: "-- km/$u", 15f, econCol, true))
                    addView(ui.iconView(Ic.DATABASE, Ui.VIOLET, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { leftMargin = ui.dp(16); rightMargin = ui.dp(8) })
                    addView(ui.tv(if (f.sinceKm > 0 && l.cost > 0) "₹%.3f/km".format(Locale.US, l.cost / f.sinceKm) else "--", 15f, Ui.DIM))
                }, ui.lp(8))
                if (l.station.isNotEmpty() || l.lat != null) addView(infoRow(Ic.PIN, Ui.ORANGE, l.station.ifEmpty { "Location saved" }, Ui.WHITE), ui.lp(8))
                if (l.receipt.isNotEmpty()) addView(LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.chip("Receipt attached", Ui.GOOD, Ic.DOC))
                }, ui.lp(8))
                if (f.tripN > 0) addView(infoRow(Ic.ROUTE, Ui.CYAN, "OBD trips ${f.tripN} · %.1f %s · ₹%.0f".format(Locale.US, f.tripFuel, u, f.tripCost), Ui.CYAN), ui.lp(8))
            }
            card.setOnClickListener { a.selectedFuelId = l.id; a.show("FuelAdd") }
            ui.pressAnim(card)
            box.addView(ui.swipeDelete(card, "Delete this fill-up?", "${ui.date(l.t, "dd MMM yyyy")} · ${ui.money(l.cost)}" + if (l.receipt.isNotEmpty()) " (the attached receipt is removed too)" else "") {
                Receipts.delete(a, l.receipt)
                Db.deleteFuel(l.id); FuelMath.repriceTrips(ObdManager.vehicle.id); onShow()
            }, ui.lp(8))
        }
    }

    private fun infoRow(ic: Ic, col: Int, text: String, tcol: Int) = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.iconView(ic, col, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(10) })
        addView(ui.tv(text, 15f, tcol))
    }

    private fun stats(info: List<FillInfo>, u: String, v: Vehicle) {
        val logs = info.map { it.log }
        val spend = logs.sumOf { it.cost }; val qty = logs.sumOf { it.qty }
        val asc = logs.sortedBy { it.odo }
        val dist = if (asc.size > 1) asc.last().odo - asc.first().odo else 0.0
        val costKm = if (dist > 0) (spend - asc.last().cost) / dist else null
        val econs = info.mapNotNull { it.econ }
        val trips = info.sumOf { it.tripCost }
        val (s1, t1) = ui.stat(Ic.DATABASE, "Total spent", Ui.ORANGE); t1.text = ui.money(spend)
        val (s2, t2) = ui.stat(Ic.FUEL, "Total $u", Ui.VIOLET); t2.text = "%.0f".format(qty)
        val (s3, t3) = ui.stat(Ic.ROUTE, "Distance", Ui.GOOD); t3.text = "%.0f km".format(dist)
        box.addView(ui.row(s1, s2, s3), ui.lp(12))
        val (s4, t4) = ui.stat(Ic.LEAF, "Avg economy", Ui.GOOD); t4.text = econs.takeIf { it.isNotEmpty() }?.average()?.let { "%.1f".format(it) } ?: "--"
        val (s5, t5) = ui.stat(Ic.BOLT, "Best", Ui.CYAN); t5.text = econs.maxOrNull()?.let { "%.1f".format(it) } ?: "--"
        val (s6, t6) = ui.stat(Ic.WARN, "Worst", Ui.BAD); t6.text = econs.minOrNull()?.let { "%.1f".format(it) } ?: "--"
        box.addView(ui.row(s4, s5, s6), ui.lp(8))
        box.addView(ui.section("Costs", Ic.DATABASE))
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Ui.CARD, 18); setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            addView(ui.dotRow("Cost per km", costKm?.let { "₹%.2f".format(it) } ?: "--"))
            addView(ui.dotRow("Avg price / $u", if (qty > 0) "₹%.2f".format(spend / qty) else "--"))
            addView(ui.dotRow("Fill-ups", "${logs.size}"))
            addView(ui.dotRow("OBD trip cost", ui.money(trips), Ic.ROUTE, Ui.CYAN))
        }, ui.lp(6))

        // calibration (app measured vs real)
        val ffs = info.filter { it.econ != null }
        if (ffs.size >= 1) {
            val l = ffs.first().log
            val idx = info.indexOfFirst { it.log.id == l.id }
            val pf = info.drop(idx + 1).firstOrNull { it.log.full }?.log
            if (pf != null) {
                val real = logs.filter { it.t > pf.t && it.t <= l.t }.sumOf { it.qty }
                val appFuel = Db.trips(v.id, pf.t).filter { it.start < l.t }.sumOf { it.fuel }
                if (appFuel > 0.5 && real > 0) {
                    val factor = (real / appFuel * v.fuelCorr).coerceIn(0.3, 3.0)
                    box.addView(ui.section("Calibration", Ic.SLIDERS))
                    box.addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        background = ui.rounded(Ui.CARD, 18); setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(10))
                        addView(ui.dotRow("App measured", "%.2f $u".format(appFuel)))
                        addView(ui.dotRow("Real (pump)", "%.2f $u".format(real)))
                        addView(ui.dotRow("Suggested ×", "%.3f".format(factor), null, Ui.GOOD))
                        addView(ui.btn("Apply calibration") { v.fuelCorr = factor; Db.saveVehicle(v); ObdManager.reloadVehicle(); ui.toast("Applied"); onShow() }, ui.lp(6))
                    }, ui.lp(6))
                }
            }
        }
    }

    private fun charts(info: List<FillInfo>, u: String) {
        val asc = info.reversed()
        val econ = asc.mapNotNull { it.econ }
        if (econ.size >= 2) { val c = LineChart(a, 150); c.set(econ.map { it.toFloat() }.toFloatArray(), "Economy per fill", " km/$u", Color.parseColor("#3DDC97")); box.addView(c, ui.lp(12)) }
        val price = asc.filter { it.log.qty > 0 }.map { (it.log.cost / it.log.qty).toFloat() }
        if (price.size >= 2) { val c = LineChart(a, 150); c.set(price.toFloatArray(), "Price per $u", " ₹", Color.parseColor("#FFB300")); box.addView(c, ui.lp(8)) }
        val labels = asc.takeLast(8).map { ui.date(it.log.t, "dd/MM") }
        if (labels.size >= 2) { val b = BarChart(a); b.set(labels, asc.takeLast(8).map { it.log.cost.toFloat() }, "Spend per fill (₹)"); box.addView(b, ui.lp(8)) }
        val dn = DonutView(a)
        val spendTrips = info.sumOf { it.tripCost }
        val spend = info.sumOf { it.log.cost }
        dn.set(listOf(Triple("OBD trips", spendTrips.toFloat(), Ui.CYAN), Triple("Other", (spend - spendTrips).coerceAtLeast(0.0).toFloat(), Ui.VIOLET)), ui.money(spend), "spent")
        box.addView(dn, ui.lp(10))
        if (econ.size < 2 && price.size < 2) box.addView(ui.tv("Add two or more full-tank fill-ups to see charts.", 13f, Ui.DIM), ui.lp(12))
    }
}

/* ============================ Add / edit fill-up ============================ */

class FuelAddPage(a: MainActivity) : Page(a) {
    override val parent = "Fuel"
    private lateinit var box: LinearLayout
    private var tripMeter = false
    private var fields: Array<Any>? = null
    private val cal: Calendar = Calendar.getInstance()
    private var station = ""; private var sLat: Double? = null; private var sLon: Double? = null; private var receipt = ""
    private lateinit var stationEt: EditText
    private lateinit var placeBox: LinearLayout
    private lateinit var receiptBox: LinearLayout
    private var camUri: android.net.Uri? = null
    companion object { const val REQ_CAM = 91; const val REQ_PICK = 92 }

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    override fun onShow() {
        box.removeAllViews()
        val v = ObdManager.vehicle
        val u = v.spec.unit
        val all = Db.fuelLogs(v.id)
        val existing = all.firstOrNull { it.id == a.selectedFuelId }
        val others = all.filter { it.id != existing?.id }
        val lastOdo = others.maxOfOrNull { it.odo } ?: v.odoKm
        cal.timeInMillis = existing?.t ?: System.currentTimeMillis()
        tripMeter = false
        station = existing?.station ?: ""; sLat = existing?.lat; sLon = existing?.lon; receipt = existing?.receipt ?: ""
        if (existing == null) Weather.here(a, false)?.let { sLat = it.first; sLon = it.second }

        box.addView(ui.row(
            ui.btn("Cancel", Ui.CARD) { if (receipt != existing?.receipt) Receipts.delete(a, receipt); a.show("Fuel") },
            ui.btn(if (existing == null) "Save" else "Update", Ui.GOOD) { save(existing, lastOdo) }
        ), ui.lp(2))
        box.addView(ui.tv(if (existing == null) "New fill-up" else "Edit fill-up", 26f, Ui.WHITE, true), ui.lp(10))

        // odometer / trip meter
        val seg0 = ui.btn("Odo counter", Ui.ACC) {}
        val seg1 = ui.btn("Trip meter", Ui.CARD) {}
        val odo = ui.edit("0", existing?.let { "%.0f".format(Locale.US, it.odo) } ?: "", true)
        val odoLabel = ui.tv("Odo counter (km)", 14f, Ui.WHITE)
        val last = ui.tv("Last value: %,.0f km".format(lastOdo), 12f, Ui.DIM)
        fun paint() {
            seg0.background = ui.rounded(if (!tripMeter) Ui.ACC else Ui.CARD, 12); seg1.background = ui.rounded(if (tripMeter) Ui.ACC else Ui.CARD, 12)
            odoLabel.text = if (tripMeter) "Trip meter (km since last fill-up)" else "Odo counter (km)"
        }
        seg0.setOnClickListener { tripMeter = false; paint() }
        seg1.setOnClickListener { tripMeter = true; paint() }
        val card1 = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = ui.rounded(Ui.CARD, 20); setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) }
        card1.addView(ui.row(seg0, seg1))
        card1.addView(odoLabel, ui.lp(12)); card1.addView(odo, ui.lp(4)); card1.addView(last, ui.lp(4))

        val qty = ui.edit("0", existing?.let { "%.2f".format(Locale.US, it.qty) } ?: "", true)
        val price = ui.edit("0", if (existing != null && existing.qty > 0) "%.2f".format(Locale.US, existing.cost / existing.qty) else "%.2f".format(Locale.US, v.fuelPrice), true)
        val cost = ui.edit("0", existing?.let { "%.2f".format(Locale.US, it.cost) } ?: "", true)
        card1.addView(ui.row(ui.labeled("Fuel ($u)", qty), ui.labeled("Fuel price (₹/$u)", price)), ui.lp(12))
        card1.addView(ui.labeled("Total cost (₹)", cost), ui.lp(8))
        card1.addView(ui.chip("Auto-calculates any 2 → 3rd", Ui.CYAN, Ic.BOLT), ui.lp(10))
        box.addView(card1, ui.lp(8))
        paint()

        // auto calculation: the field edited least recently is derived from the other two
        val order = ArrayList<EditText>()
        if (existing != null) { order += qty; order += price } else order += price
        var guard = false
        fun calc(changed: EditText) {
            order.remove(changed); order.add(changed)
            if (order.size < 2) return
            val target = listOf(qty, price, cost).firstOrNull { it !in order.takeLast(2) } ?: return
            val q = qty.num(); val p = price.num(); val c = cost.num()
            val res = when (target) {
                cost -> if (q != null && p != null) q * p else null
                price -> if (q != null && c != null && q > 0) c / q else null
                else -> if (c != null && p != null && p > 0) c / p else null
            } ?: return
            guard = true; target.setText("%.2f".format(Locale.US, res)); guard = false
        }
        for (f in listOf(qty, price, cost)) f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!guard && f.hasFocus()) calc(f) }
        })

        // date & time
        val dateBtn = ui.btn(ui.date(cal.timeInMillis, "dd MMM yyyy"), Ui.CARD) {}
        val timeBtn = ui.btn(ui.date(cal.timeInMillis, "h:mm a"), Ui.CARD) {}
        dateBtn.setOnClickListener {
            DatePickerDialog(a, { _, y, m, d -> cal.set(y, m, d); dateBtn.text = ui.date(cal.timeInMillis, "dd MMM yyyy") },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        timeBtn.setOnClickListener {
            TimePickerDialog(a, { _, h, mi -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, mi); timeBtn.text = ui.date(cal.timeInMillis, "h:mm a") },
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }
        box.addView(ui.section("Date", Ic.CALENDAR))
        box.addView(ui.row(dateBtn, timeBtn), ui.lp(2))

        val full = ui.check("Full tank", existing?.full ?: true)
        val missed = ui.check("Missed previous fill-up", existing?.note?.contains(MISSED) == true)
        val note = ui.edit("Note", existing?.note?.replace(MISSED, "")?.trim() ?: "")
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; background = ui.rounded(Ui.CARD, 20); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            addView(full); addView(missed, ui.lp(8)); addView(note, ui.lp(10))
        }, ui.lp(14))
        fields = arrayOf(odo, qty, price, cost, full, missed, note)
        stationUi(existing)
        receiptUi(existing)
        if (existing != null) box.addView(ui.btn("Delete fill-up", Ui.BAD) {
            AlertDialog.Builder(a).setTitle("Delete this fill-up?")
                .setPositiveButton("Delete") { _, _ -> Receipts.delete(a, existing.receipt); Db.deleteFuel(existing.id); FuelMath.repriceTrips(v.id); a.show("Fuel") }
                .setNegativeButton("Cancel", null).show()
        }, ui.lp(12))
    }

    /* ---------- station (GPS) ---------- */

    private fun stationUi(existing: FuelLog?) {
        stationEt = ui.edit("Petrol station name", station)
        placeBox = ui.vbox()
        val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = ui.rounded(Ui.CARD, 20); setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)) }
        card.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.badge(Ic.PIN, Ui.ORANGE, 32), LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { rightMargin = ui.dp(10) })
            addView(ui.tv("Petrol station", 16f, Ui.WHITE, true))
        })
        card.addView(stationEt, ui.lp(10))
        card.addView(ui.row(
            ui.btn("Use my location", Ui.ORANGE) { captureLocation(true) },
            ui.btn("Clear", Ui.CARD) { sLat = null; sLon = null; renderPlace() }
        ), ui.lp(8))
        card.addView(placeBox, ui.lp(8))
        box.addView(ui.section("Location", Ic.PIN))
        box.addView(card, ui.lp(2))
        renderPlace()
        if (existing == null && sLat != null && station.isEmpty()) lookupName()
    }

    private fun captureLocation(manual: Boolean) {
        val h = Weather.here(a, false)
        if (h == null) { ui.toast(if (Gps.hasPermission(a)) "No GPS fix yet – wait a few seconds outdoors" else "Allow location permission first"); return }
        sLat = h.first; sLon = h.second; renderPlace(); if (manual || stationEt.text.isEmpty()) lookupName()
    }

    private fun lookupName() {
        val la = sLat ?: return; val lo = sLon ?: return
        Receipts.stationNear(la, lo) { n -> if (n != null && stationEt.text.isEmpty() && sLat == la) stationEt.setText(n) }
    }

    private fun renderPlace() {
        placeBox.removeAllViews()
        val la = sLat; val lo = sLon
        if (la == null || lo == null) { placeBox.addView(ui.tv("No location attached – tap \"Use my location\" while at the station.", 12f, Ui.DIM)); return }
        val map = TileMapView(a); map.setPlace(la, lo, 16)
        placeBox.addView(map, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(140)))
        val addr = ui.tv("%.5f, %.5f".format(Locale.US, la, lo), 12.5f, Ui.WHITE)
        Geo.name(a, la, lo) { addr.text = it }
        placeBox.addView(addr, ui.lp(8))
        placeBox.addView(ui.btn("Navigate to this station", Ui.CARD) { Nav.drive(a, la, lo) }, ui.lp(6))
    }

    /* ---------- receipt photo ---------- */

    private fun receiptUi(existing: FuelLog?) {
        receiptBox = ui.vbox()
        val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = ui.rounded(Ui.CARD, 20); setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)) }
        card.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.badge(Ic.DOC, Ui.GOOD, 32), LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { rightMargin = ui.dp(10) })
            addView(ui.tv("Receipt", 16f, Ui.WHITE, true))
        })
        card.addView(ui.row(ui.btn("Take photo", Ui.GOOD) { takePhoto() }, ui.btn("Choose image", Ui.CARD) { pickImage() }), ui.lp(8))
        card.addView(receiptBox, ui.lp(8))
        box.addView(ui.section("Receipt", Ic.DOC))
        box.addView(card, ui.lp(2))
        renderReceipt()
    }

    private fun renderReceipt() {
        receiptBox.removeAllViews()
        if (receipt.isEmpty()) { receiptBox.addView(ui.tv("No receipt attached.", 12f, Ui.DIM)); return }
        val bmp = Receipts.bitmap(a, receipt, 700)
        if (bmp == null) { receiptBox.addView(ui.tv("Receipt file not found.", 12f, Ui.BAD)); return }
        receiptBox.addView(android.widget.ImageView(a).apply {
            setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; adjustViewBounds = false
            background = ui.rounded(Ui.SEL, 14); clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, ui.dp(14).toFloat()) } }
            setOnClickListener { showFull() }
        }, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(190)))
        receiptBox.addView(ui.row(
            ui.btn("View full size", Ui.CARD) { showFull() },
            ui.btn("Remove", Ui.BAD) {
                AlertDialog.Builder(a).setTitle("Remove this receipt?").setPositiveButton("Remove") { _, _ -> receipt = ""; renderReceipt() }.setNegativeButton("Cancel", null).show()
            }
        ), ui.lp(8))
    }

    private fun showFull() {
        val bmp = Receipts.bitmap(a, receipt, 1600) ?: return
        val iv = android.widget.ImageView(a).apply { setImageBitmap(bmp); adjustViewBounds = true; setBackgroundColor(Color.BLACK) }
        AlertDialog.Builder(a).setView(android.widget.ScrollView(a).apply { addView(iv) }).setPositiveButton("Close", null).show()
    }

    private fun takePhoto() {
        try {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "carobd_receipt_${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CarOBD")
            }
            camUri = a.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            val i = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, camUri)
            @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_CAM)
        } catch (e: Exception) { ui.toast("Camera not available – use Choose image") }
    }

    private fun pickImage() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "image/*" }
        @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_PICK)
    }

    fun photoResult(req: Int, ok: Boolean, uri: android.net.Uri?) {
        val src = if (req == REQ_CAM) camUri else uri
        if (!ok || src == null) { if (req == REQ_CAM) camUri?.let { try { a.contentResolver.delete(it, null, null) } catch (_: Exception) {} }; return }
        val name = Receipts.save(a, src)
        if (req == REQ_CAM) try { a.contentResolver.delete(src, null, null) } catch (_: Exception) {}
        if (name == null) { ui.toast("Could not read the image"); return }
        if (receipt.isNotEmpty() && receipt != (Db.fuelLogs(ObdManager.vehicle.id).firstOrNull { it.id == a.selectedFuelId }?.receipt ?: "")) Receipts.delete(a, receipt)
        receipt = name; renderReceipt()
    }

    private fun save(existing: FuelLog?, lastOdo: Double) {
        val f = fields ?: return
        val odoE = f[0] as EditText; val qtyE = f[1] as EditText; val priceE = f[2] as EditText; val costE = f[3] as EditText
        val full = (f[4] as android.widget.Switch).isChecked; val missed = (f[5] as android.widget.Switch).isChecked
        val note = (f[6] as EditText).text.toString().trim()
        val v = ObdManager.vehicle
        val q = qtyE.num(); val o0 = odoE.num()
        val p = priceE.num()
        val c = costE.num() ?: if (q != null && p != null) q * p else null
        if (q == null || q <= 0 || c == null) { ui.toast("Enter fuel quantity and price or cost"); return }
        val odo = if (tripMeter) lastOdo + (o0 ?: 0.0) else (o0 ?: v.odoKm)
        val log = FuelLog(existing?.id ?: 0, v.id, cal.timeInMillis, odo, q, c, full, (note + if (missed) " $MISSED" else "").trim(),
            stationEt.text.toString().trim(), sLat, sLon, receipt)
        if (existing != null && existing.receipt.isNotEmpty() && existing.receipt != receipt) Receipts.delete(a, existing.receipt)
        Db.saveFuel(log)
        if (c > 0) v.fuelPrice = c / q
        if (odo > v.odoKm) v.odoKm = odo
        Db.saveVehicle(v); ObdManager.reloadVehicle()
        FuelMath.repriceTrips(v.id)
        ui.toast("Saved · price ₹%.2f/%s applied to trips".format(Locale.US, c / q, v.spec.unit))
        a.show("Fuel")
    }

}
