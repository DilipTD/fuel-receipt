package com.dilip.carobd

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Small multi-page A4 PDF writer: title block, headings, key/value rows and tables. */
class PdfBuilder(private val title: String, private val sub: String) {
    companion object {
        /** Simulation hook: draw pages on this canvas instead of a native PdfDocument. */
        @Volatile var testCanvas: (() -> android.graphics.Canvas)? = null
    }
    private val tc = testCanvas
    private val doc: PdfDocument? = if (tc == null) PdfDocument() else null
    val pages get() = n
    private var page: PdfDocument.Page? = null
    private var c: android.graphics.Canvas? = null
    private var y = 0f
    private var n = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val W = 595f; private val H = 842f; private val M = 32f
    private val ink = Color.parseColor("#0F1A24"); private val dim = Color.parseColor("#5B6B7A"); private val line = Color.parseColor("#D5DEE6")

    init { newPage(true) }

    private fun newPage(first: Boolean = false) {
        if (doc != null) {
            page?.let { footer(); doc.finishPage(it) }
            n++
            page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
            c = page!!.canvas
        } else {
            if (n > 0) footer()
            n++
            c = tc!!()
        }
        if (first) {
            p.color = Color.parseColor("#0F1A24"); c!!.drawRect(0f, 0f, W, 96f, p)
            p.color = Color.parseColor("#2FA8FF"); c!!.drawRect(0f, 92f, W, 96f, p)
            p.color = Color.WHITE; p.textSize = 22f; p.typeface = Typeface.DEFAULT_BOLD; c!!.drawText(title, M, 44f, p)
            p.textSize = 11.5f; p.typeface = Typeface.DEFAULT; p.color = Color.parseColor("#B8C6D4"); c!!.drawText(sub, M, 68f, p)
            y = 122f
        } else y = 48f
    }

    private fun footer() {
        p.color = Color.parseColor("#7F8D9B"); p.textSize = 9f; p.typeface = Typeface.DEFAULT
        c!!.drawText("${AppInfo.NAME} v${AppInfo.VERSION} · Software developed by ${AppInfo.DEVELOPER} · " +
            SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date()) + " · page $n", M, 826f, p)
    }

    private fun need(h: Float) { if (y + h > 806f) newPage() }

    fun heading(t: String) {
        need(40f); y += 12f
        p.color = ink; p.textSize = 14f; p.typeface = Typeface.DEFAULT_BOLD; c!!.drawText(t, M, y, p)
        p.color = Color.parseColor("#2FA8FF"); c!!.drawRect(M, y + 5f, M + 36f, y + 7f, p)
        y += 22f
    }

    fun kv(k: String, v: String) {
        need(18f)
        p.textSize = 11.5f; p.typeface = Typeface.DEFAULT; p.color = dim; c!!.drawText(k, M, y, p)
        p.color = ink; p.typeface = Typeface.DEFAULT_BOLD; c!!.drawText(v.take(60), 230f, y, p)
        p.color = line; c!!.drawRect(M, y + 5f, W - M, y + 5.6f, p)
        y += 18f
    }

    fun text(t: String) {
        need(16f); p.textSize = 11f; p.typeface = Typeface.DEFAULT; p.color = dim; c!!.drawText(t.take(95), M, y, p); y += 16f
    }

    /** Equal-weight columns scaled to the page; first column left, the rest right aligned. */
    fun table(headers: List<String>, rows: List<List<String>>, weights: List<Float> = headers.map { 1f }) {
        val total = weights.sum(); val usable = W - 2 * M
        val xs = ArrayList<Float>(); var acc = M
        for (w in weights) { xs += acc; acc += usable * w / total }
        fun cell(i: Int, t: String, bold: Boolean, col: Int) {
            p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; p.color = col; p.textSize = 10f
            val wpx = usable * weights[i] / total - 6f
            var s = t
            while (s.length > 1 && p.measureText(s) > wpx) s = s.dropLast(1)
            if (i == 0) { p.textAlign = Paint.Align.LEFT; c!!.drawText(s, xs[i], y, p) }
            else { p.textAlign = Paint.Align.RIGHT; c!!.drawText(s, xs[i] + usable * weights[i] / total - 4f, y, p) }
            p.textAlign = Paint.Align.LEFT
        }
        fun head() {
            need(24f)
            p.color = Color.parseColor("#EAF2F9"); c!!.drawRect(M, y - 12f, W - M, y + 6f, p)
            headers.forEachIndexed { i, h -> cell(i, h, true, ink) }
            y += 20f
        }
        head()
        for ((ri, r) in rows.withIndex()) {
            if (y + 18f > 806f) { newPage(); head() }
            if (ri % 2 == 1) { p.color = Color.parseColor("#F6F9FB"); c!!.drawRect(M, y - 11f, W - M, y + 5f, p) }
            r.forEachIndexed { i, t -> cell(i, t, false, ink) }
            y += 16f
        }
        y += 6f
    }

    fun save(ctx: Context, name: String): String? {
        footer()
        if (doc == null) return "simulated/$name ($n page${if (n == 1) "" else "s"})"
        doc.finishPage(page!!)
        val os = Exporter.open(ctx, name, "application/pdf")
        return try { if (os == null) null else { os.use { doc.writeTo(it) }; "Downloads/CarOBD/$name" } } catch (_: Exception) { null } finally { doc.close() }
    }
}

object Reports {
    private fun d(t: Long) = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(t))
    private fun dist(v: Double, dec: Int = 1) = "%.${dec}f %s".format(Units.km(v), Units.distLabel)
    private fun vol(v: Double, dec: Int, spec: String) = "%.${dec}f %s".format(Units.vol(v, spec), Units.volLabel(spec))
    private fun m(v: Double) = "${Prefs.currency}%,.0f".format(v)
    private fun vehLine() = ObdManager.vehicle.let { v -> v.title + (if (v.regNo.isNotBlank()) "  ·  ${v.regNo}" else "") }
    private fun nm(prefix: String, ext: String) = "CarOBD_${prefix}_${stamp()}.$ext"

    fun tripCsv(ctx: Context, from: Long, until: Long): String? {
        val v = ObdManager.vehicle
        val trips = Db.trips(v.id, from).filter { it.start < until }
        val sb = StringBuilder("start,end,duration_min,distance_km,fuel,economy_km_per_unit,cost,avg_kmh,max_kmh,max_rpm,idle_min,co2_kg,score,rapid_acc,hard_brake,sudden_brake,cornering,overspeed\n")
        for (t in trips) sb.append(listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t.start)), SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t.end)),
            "%.1f".format(Locale.US, t.durationS / 60), "%.2f".format(Locale.US, t.distanceKm), "%.3f".format(Locale.US, t.fuel),
            t.kmpl?.let { "%.2f".format(Locale.US, it) } ?: "", "%.2f".format(Locale.US, t.cost), "%.1f".format(Locale.US, t.avgSpeed),
            "%.0f".format(t.maxSpeed), "%.0f".format(t.maxRpm), "%.1f".format(Locale.US, t.idleS / 60), "%.2f".format(Locale.US, t.co2Kg),
            t.score, t.rapidAcc, t.hardBrake, t.suddenBrake, t.corner, t.overspeed).joinToString(",")).append("\n")
        return Exporter.writeText(ctx, nm("trip_log", "csv"), "text/csv", sb.toString())
    }

    fun seller(ctx: Context): String? {
        val v = ObdManager.vehicle; val u = v.spec.unit
        val b = PdfBuilder("Seller's Report", vehLine())
        b.heading("Vehicle")
        b.kv("Make / model", listOf(v.make, v.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { v.name })
        if (v.year > 0) b.kv("Year", v.year.toString())
        if (v.vin.isNotBlank()) b.kv("VIN", v.vin)
        if (v.regNo.isNotBlank()) b.kv("Registration", v.regNo)
        b.kv("Fuel", v.fuel)
        b.kv("Odometer", dist(v.odoKm, 0).replace(".0", ""))
        val trips = Db.trips(v.id); val km = trips.sumOf { it.distanceKm }; val fl = trips.sumOf { it.fuel }
        b.heading("Usage recorded by the app")
        b.kv("Trips logged", trips.size.toString())
        b.kv("Distance logged", dist(km, 0))
        if (fl > 0.5) b.kv("Average economy", Units.econ(km / fl, u))
        if (km > 1) b.kv("Average driver score", "%.0f / 100".format(trips.sumOf { it.score * it.distanceKm } / km))
        val dtc = Db.dtcHistory(v.id)
        b.kv("Fault codes seen", if (dtc.isEmpty()) "None recorded" else "${dtc.map { it.code }.distinct().size} distinct")
        val recs = Db.records(v.id).sortedByDescending { it.t }
        b.heading("Service history")
        if (recs.isEmpty()) b.text("No service records.")
        else {
            b.table(listOf("Date", "Service", "Odometer", "Cost"), recs.map { listOf(d(it.t), it.name, dist(it.odo, 0), m(it.cost)) }, listOf(1.1f, 2.4f, 1.2f, 1f))
            b.kv("Total spent on service", m(recs.sumOf { it.cost }))
        }
        val due = Maint.dueList(v)
        if (due.isNotEmpty()) { b.heading("Upcoming / overdue service"); due.forEach { b.kv(it.item.name, it.text) } }
        val docs = Db.docs(v.id)
        if (docs.isNotEmpty()) { b.heading("Documents on file"); docs.forEach { b.kv(it.type.ifBlank { "Document" }, it.title + if (it.expiry > 0) "  (expires ${d(it.expiry)})" else "") } }
        return b.save(ctx, nm("sellers_report", "pdf"))
    }

    class Custom(val from: Long, val until: Long, val trips: Boolean, val fuel: Boolean, val maint: Boolean, val events: Boolean, val faults: Boolean, val detailed: Boolean, val label: String)

    fun custom(ctx: Context, o: Custom): String? {
        val v = ObdManager.vehicle; val u = v.spec.unit
        val b = PdfBuilder("Custom Report", vehLine() + "  ·  " + o.label)
        val trips = Db.trips(v.id, o.from).filter { it.start < o.until }.sortedBy { it.start }
        if (o.trips) {
            b.heading("Trips")
            val km = trips.sumOf { it.distanceKm }; val fl = trips.sumOf { it.fuel }
            b.kv("Trips", trips.size.toString()); b.kv("Distance", dist(km, 0))
            b.kv("Fuel used", vol(fl, 1, u)); if (fl > 0.1) b.kv("Economy", Units.econ(km / fl, u))
            b.kv("Cost of fuel (trips)", m(trips.sumOf { it.cost }))
            if (o.detailed && trips.isNotEmpty()) b.table(listOf("Date", Units.distLabel, "Economy", "Max " + Units.spdLabel, "Score"),
                trips.map { listOf(d(it.start), "%.1f".format(Units.km(it.distanceKm)), it.kmpl?.let { k -> Units.econ(k, u) } ?: "--", "%.0f".format(Units.spd(it.maxSpeed)), it.score.toString()) }, listOf(1.3f, 1f, 1.5f, 1f, 0.8f))
        }
        if (o.fuel) {
            val logs = Db.fuelLogs(v.id).filter { it.t >= o.from && it.t < o.until }.sortedBy { it.t }
            b.heading("Fuel")
            b.kv("Fill-ups", logs.size.toString()); b.kv("Quantity", vol(logs.sumOf { it.qty }, 1, u)); b.kv("Spent", m(logs.sumOf { it.cost }))
            if (o.detailed && logs.isNotEmpty()) b.table(listOf("Date", "Odometer", "Qty", "Cost", "Station"),
                logs.map { listOf(d(it.t), dist(it.odo, 0), "%.1f".format(Units.vol(it.qty, u)), m(it.cost), it.station) }, listOf(1.2f, 1.2f, 0.8f, 1f, 1.8f))
        }
        if (o.maint) {
            val recs = Db.records(v.id).filter { it.t >= o.from && it.t < o.until }.sortedBy { it.t }
            b.heading("Maintenance")
            b.kv("Services", recs.size.toString()); b.kv("Spent", m(recs.sumOf { it.cost }))
            if (o.detailed && recs.isNotEmpty()) b.table(listOf("Date", "Service", "Odometer", "Cost"), recs.map { listOf(d(it.t), it.name, dist(it.odo, 0), m(it.cost)) }, listOf(1.1f, 2.4f, 1.2f, 1f))
        }
        if (o.events) {
            b.heading("Driving events")
            b.kv("Rapid acceleration", trips.sumOf { it.rapidAcc }.toString()); b.kv("Rapid deceleration (hard braking)", trips.sumOf { it.hardBrake }.toString())
            b.kv("Sudden braking", trips.sumOf { it.suddenBrake }.toString()); b.kv("Harsh cornering", trips.sumOf { it.corner }.toString())
            b.kv("Over-speed", trips.sumOf { it.overspeed }.toString()); b.kv("Over-revving", trips.sumOf { it.overRev }.toString()); b.kv("Long idle", trips.sumOf { it.longIdle }.toString())
        }
        if (o.faults) {
            val f = Db.dtcHistory(v.id).filter { it.t >= o.from && it.t < o.until }
            b.heading("Fault codes")
            if (f.isEmpty()) b.text("No fault codes recorded in this period.")
            else b.table(listOf("Date", "Code", "Module", "Type"), f.map { listOf(d(it.t), it.code, it.ecu, it.kind) }, listOf(1.2f, 1f, 1.5f, 1f))
        }
        return b.save(ctx, nm("custom_report", "pdf"))
    }

    fun cost(ctx: Context): String? {
        val v = ObdManager.vehicle
        val fuel = Db.fuelLogs(v.id); val recs = Db.records(v.id)
        val fuelCost = fuel.sumOf { it.cost }; val mCost = recs.sumOf { it.cost }
        val trips = Db.trips(v.id)
        val odos = fuel.map { it.odo }.filter { it > 0 }
        val km = maxOf(trips.sumOf { it.distanceKm }, (odos.maxOrNull() ?: 0.0) - (odos.minOrNull() ?: 0.0))
        val firstT = (fuel.map { it.t } + recs.map { it.t } + trips.map { it.start }).minOrNull() ?: System.currentTimeMillis()
        val months = ((System.currentTimeMillis() - firstT) / (30.4 * 86_400_000.0)).coerceAtLeast(1.0)
        val depr = (Prefs.purchasePrice - Prefs.currentValue).coerceAtLeast(0.0)
        val total = fuelCost + mCost + depr
        val b = PdfBuilder("Cost of Ownership", vehLine() + "  ·  since " + d(firstT))
        b.heading("Totals")
        b.kv("Fuel", m(fuelCost)); b.kv("Maintenance & service", m(mCost))
        if (Prefs.purchasePrice > 0) { b.kv("Purchase price", m(Prefs.purchasePrice)); b.kv("Current value", m(Prefs.currentValue)); b.kv("Depreciation", m(depr)) }
        b.kv("Total cost of ownership", m(total))
        b.heading("Cost efficiency")
        b.kv("Distance covered", dist(km, 0)); b.kv("Months tracked", "%.1f".format(months))
        if (km > 1) { b.kv("Fuel cost per ${Units.distLabel}", "${Prefs.currency}%.2f".format(fuelCost / Units.km(km))); b.kv("Total cost per ${Units.distLabel}", "${Prefs.currency}%.2f".format(total / Units.km(km))) }
        b.kv("Running cost per month (fuel + service)", m((fuelCost + mCost) / months))
        b.kv("Total cost per month", m(total / months))
        val byItem = recs.groupBy { it.name }.map { it.key to it.value.sumOf { r -> r.cost } }.sortedByDescending { it.second }.take(10)
        if (byItem.isNotEmpty()) { b.heading("Biggest service costs"); b.table(listOf("Item", "Total"), byItem.map { listOf(it.first, m(it.second)) }, listOf(3f, 1f)) }
        return b.save(ctx, nm("cost_of_ownership", "pdf"))
    }

    val tripCols = listOf("Date", "Start time", "Distance", "Duration", "Avg speed", "Max speed", "Fuel", "Economy", "Cost", "Idle", "Score", "Events")

    fun tripLogPdf(ctx: Context, from: Long, until: Long, cols: List<String>, label: String): String? {
        val v = ObdManager.vehicle; val u = v.spec.unit
        val trips = Db.trips(v.id, from).filter { it.start < until }.sortedBy { it.start }
        val b = PdfBuilder("Trip Log Report", vehLine() + "  ·  " + label)
        b.kv("Trips", trips.size.toString()); b.kv("Total distance", dist(trips.sumOf { it.distanceKm }, 1)); b.kv("Total fuel cost", m(trips.sumOf { it.cost }))
        b.heading("Trips")
        fun cellFor(t: Trip, c: String) = when (c) {
            "Date" -> d(t.start); "Start time" -> SimpleDateFormat(if (Prefs.time24) "HH:mm" else "hh:mm a", Locale.getDefault()).format(Date(t.start))
            "Distance" -> "%.1f %s".format(Units.km(t.distanceKm), Units.distLabel)
            "Duration" -> "%dh %02dm".format((t.durationS / 3600).toInt(), ((t.durationS % 3600) / 60).toInt())
            "Avg speed" -> "%.0f".format(Units.spd(t.avgSpeed)); "Max speed" -> "%.0f".format(Units.spd(t.maxSpeed))
            "Fuel" -> "%.1f".format(Units.vol(t.fuel, u)); "Economy" -> t.kmpl?.let { Units.econ(it, u) } ?: "--"
            "Cost" -> m(t.cost); "Idle" -> "%.0f min".format(t.idleS / 60); "Score" -> t.score.toString(); else -> t.events.toString()
        }
        val use = cols.ifEmpty { listOf("Date", "Distance", "Economy", "Cost") }
        b.table(use, trips.map { t -> use.map { cellFor(t, it) } }, use.map { if (it == "Economy" || it == "Duration") 1.4f else 1f })
        return b.save(ctx, nm("trip_log_report", "pdf"))
    }

    fun annual(ctx: Context, year: Int): String? {
        val v = ObdManager.vehicle; val u = v.spec.unit
        val cal = Calendar.getInstance()
        fun start(mo: Int) = cal.apply { set(year, mo, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val trips = Db.trips(v.id); val fuel = Db.fuelLogs(v.id); val recs = Db.records(v.id)
        val rows = ArrayList<List<String>>()
        var tk = 0.0; var tf = 0.0; var tc = 0.0; var tm = 0.0; var tn = 0
        for (mo in 0..11) {
            val a = start(mo); val e = if (mo == 11) start(0).let { cal.apply { set(year + 1, 0, 1, 0, 0, 0) }.timeInMillis } else start(mo + 1)
            val tr = trips.filter { it.start in a until e }
            val km = tr.sumOf { it.distanceKm }; val fl = fuel.filter { it.t in a until e }.sumOf { it.cost }; val ml = recs.filter { it.t in a until e }.sumOf { it.cost }
            val used = tr.sumOf { it.fuel }
            tk += km; tf += fl; tm += ml; tn += tr.size; tc += used
            rows += listOf(SimpleDateFormat("MMM", Locale.getDefault()).format(Date(a)), tr.size.toString(), "%.0f".format(Units.km(km)), if (used > 0.1) Units.econ(km / used, u) else "--", m(fl), m(ml))
        }
        val b = PdfBuilder("Annual Summary $year", vehLine())
        b.heading("Year at a glance")
        b.kv("Trips", tn.toString()); b.kv("Distance", dist(tk, 0)); if (tc > 0.5) b.kv("Average economy", Units.econ(tk / tc, u))
        b.kv("Fuel spend", m(tf)); b.kv("Service spend", m(tm)); b.kv("Total running cost", m(tf + tm))
        b.heading("Month by month")
        b.table(listOf("Month", "Trips", Units.distLabel.uppercase(), "Economy", "Fuel cost", "Service"), rows, listOf(0.9f, 0.8f, 1f, 1.5f, 1.3f, 1.2f))
        return b.save(ctx, nm("annual_$year", "pdf"))
    }

    fun fuelEfficiency(ctx: Context): String? {
        val v = ObdManager.vehicle; val u = v.spec.unit
        val logs = Db.fuelLogs(v.id).sortedBy { it.t }
        class Seg(val log: FuelLog, val kmpl: Double, val km: Double)
        val segs = ArrayList<Seg>()
        var prev: FuelLog? = null
        for (l in logs) {
            if (!l.full) continue
            val p = prev
            if (p != null && l.odo > p.odo && l.qty > 0) segs += Seg(l, (l.odo - p.odo) / l.qty, l.odo - p.odo)
            prev = l
        }
        val b = PdfBuilder("Fuel Efficiency", vehLine())
        b.heading("Summary")
        if (segs.isEmpty()) { b.text("Not enough full-tank fill-ups with odometer readings yet."); return b.save(ctx, nm("fuel_efficiency", "pdf")) }
        val avg = segs.sumOf { it.km } / segs.sumOf { it.log.qty }
        b.kv("Fill-ups analysed", segs.size.toString()); b.kv("Average economy", Units.econ(avg, u))
        val best = segs.maxByOrNull { it.kmpl }!!; val worst = segs.minByOrNull { it.kmpl }!!
        b.kv("Best fill-up", "${Units.econ(best.kmpl, u)}  (${d(best.log.t)})"); b.kv("Worst fill-up", "${Units.econ(worst.kmpl, u)}  (${d(worst.log.t)})")
        if (segs.size >= 4) {
            val h = segs.size / 2
            val a = segs.take(h).let { it.sumOf { s -> s.km } / it.sumOf { s -> s.log.qty } }; val c2 = segs.drop(h).let { it.sumOf { s -> s.km } / it.sumOf { s -> s.log.qty } }
            b.kv("Trend (recent vs earlier)", "%+.1f %%".format((c2 / a - 1.0) * 100.0))
        }
        b.kv("Fuel cost per ${Units.distLabel}", "${Prefs.currency}%.2f".format(segs.sumOf { it.log.cost } / Units.km(segs.sumOf { it.km })))
        b.heading("Every fill-up")
        b.table(listOf("Date", Units.distLabel.uppercase(), "Qty", "Economy", "Cost"), segs.sortedByDescending { it.log.t }.map {
            listOf(d(it.log.t), "%.0f".format(Units.km(it.km)), "%.1f".format(Units.vol(it.log.qty, u)), Units.econ(it.kmpl, u), m(it.log.cost)) }, listOf(1.2f, 0.8f, 0.8f, 1.5f, 1f))
        return b.save(ctx, nm("fuel_efficiency", "pdf"))
    }

    fun maintenance(ctx: Context): String? {
        val v = ObdManager.vehicle
        val recs = Db.records(v.id).sortedBy { it.t }
        val b = PdfBuilder("Maintenance History", vehLine())
        b.heading("Totals")
        b.kv("Service records", recs.size.toString()); b.kv("Total spent", m(recs.sumOf { it.cost }))
        if (recs.isNotEmpty()) b.kv("Last service", d(recs.last().t))
        b.heading("Service log")
        if (recs.isEmpty()) b.text("No service records.")
        else b.table(listOf("Date", "Service", "Odometer", "Cost"), recs.map { listOf(d(it.t), it.name + if (it.note.isNotBlank()) " – ${it.note}" else "", dist(it.odo, 0), m(it.cost)) }, listOf(1.1f, 2.6f, 1.2f, 1f))
        return b.save(ctx, nm("maintenance_history", "pdf"))
    }
}

/* ============================ Reports page ============================ */

class ReportsPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    private class R(val ic: Ic, val title: String, val body: String, val run: () -> Unit)

    override fun onShow() {
        box.removeAllViews()
        val rs = listOf(
            R(Ic.CAR, "Seller's Report", "Full vehicle history, ideal for selling your car.") { done(Reports.seller(a)) },
            R(Ic.SLIDERS, "Custom Report", "Choose dates, categories and the level of detail.") { customDialog() },
            R(Ic.CHART, "Cost of Ownership", "Total cost, depreciation and cost efficiency.") { costDialog() },
            R(Ic.GRID, "Trip Log", "All your trips as a spreadsheet (CSV).") { periodDialog("Trip Log (CSV)") { f, u, _ -> done(Reports.tripCsv(a, f, u)) } },
            R(Ic.ROUTE, "Trip Log Report", "Advanced PDF: pick columns and sections, print-ready for reimbursement or taxes.") { tripPdfDialog() },
            R(Ic.CALENDAR, "Annual Summary", "Year at a glance with month-by-month breakdown.") { yearDialog() },
            R(Ic.GAUGE, "Fuel Efficiency", "Consumption trends with best and worst fill-ups.") { done(Reports.fuelEfficiency(a)) },
            R(Ic.WRENCH, "Maintenance History", "Chronological service log with totals.") { done(Reports.maintenance(a)) }
        )
        box.addView(ui.tv("Reports are saved to Downloads/CarOBD as PDF or CSV and can be shared straight away.", 12f, Ui.DIM).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(4)) })
        val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = ui.rounded(Ui.CARD, 22); setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        rs.forEachIndexed { i, r ->
            if (i > 0) card.addView(View(a).apply { setBackgroundColor(Ui.STROKE) }, LinearLayout.LayoutParams(Ui.MATCH, 1).apply { leftMargin = ui.dp(72); rightMargin = ui.dp(10) })
            card.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(10), ui.dp(14), ui.dp(10), ui.dp(14))
                addView(ui.badge(r.ic, Ui.VIOLET, 46), LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)).apply { rightMargin = ui.dp(14) })
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ui.tv(r.title, 16f, Ui.WHITE, true)); addView(ui.tv(r.body, 12.5f, Ui.DIM))
                }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                addView(ui.iconView(Ic.CHEV, Ui.DIM, 18))
                setOnClickListener { r.run() }
                ui.pressAnim(this)
            })
        }
        box.addView(card, ui.lp(8))
    }

    private fun done(path: String?) {
        if (path == null) { ui.toast("Could not save – check storage permission"); return }
        ui.toast("Saved to $path")
    }

    private fun spinnerOf(items: List<String>, sel: Int = 0) = Spinner(a).apply {
        adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, items); setSelection(sel)
        background = ui.rounded(Ui.BG, 10); setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
    }

    private fun dialog(title: String, v: View, ok: String = "Create", onOk: () -> Unit) {
        AlertDialog.Builder(a).setTitle(title).setView(ScrollWrap(v)).setPositiveButton(ok) { _, _ -> onOk() }.setNegativeButton("Cancel", null).show()
    }

    private fun ScrollWrap(v: View) = android.widget.ScrollView(a).apply { addView(v) }

    private fun body() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(6)) }

    private fun periodDialog(title: String, run: (Long, Long, String) -> Unit) {
        val b = body(); val sp = spinnerOf(TripFilter.names)
        b.addView(ui.tv("Period", 12f, Ui.DIM)); b.addView(sp)
        dialog(title, b) { val i = sp.selectedItemPosition; val (f, u) = TripFilter.range(i, ObdManager.vehicle.id); run(f, u, TripFilter.names[i]) }
    }

    private fun customDialog() {
        val b = body(); val sp = spinnerOf(TripFilter.names)
        b.addView(ui.tv("Period", 12f, Ui.DIM)); b.addView(sp)
        fun cb(t: String) = CheckBox(a).apply { text = t; isChecked = true }
        val c1 = cb("Trips"); val c2 = cb("Fuel"); val c3 = cb("Maintenance"); val c4 = cb("Driving events"); val c5 = cb("Fault codes")
        b.addView(ui.tv("Categories", 12f, Ui.DIM), ui.lp(12)); for (c in listOf(c1, c2, c3, c4, c5)) b.addView(c)
        val lv = spinnerOf(listOf("Summary", "Detailed (with tables)"), 1)
        b.addView(ui.tv("Level of detail", 12f, Ui.DIM), ui.lp(12)); b.addView(lv)
        dialog("Custom Report", b) {
            val i = sp.selectedItemPosition; val (f, u) = TripFilter.range(i, ObdManager.vehicle.id)
            done(Reports.custom(a, Reports.Custom(f, u, c1.isChecked, c2.isChecked, c3.isChecked, c4.isChecked, c5.isChecked, lv.selectedItemPosition == 1, TripFilter.names[i])))
        }
    }

    private fun costDialog() {
        val b = body()
        val p = ui.edit("0", if (Prefs.purchasePrice > 0) "%.0f".format(Prefs.purchasePrice) else "", true)
        val c = ui.edit("0", if (Prefs.currentValue > 0) "%.0f".format(Prefs.currentValue) else "", true)
        b.addView(ui.labeled("Purchase price (${Prefs.currency}) – optional", p)); b.addView(ui.labeled("Current estimated value (${Prefs.currency})", c), ui.lp(10))
        b.addView(ui.tv("Used for depreciation. Leave empty to report running costs only.", 12f, Ui.DIM), ui.lp(8))
        dialog("Cost of Ownership", b) {
            Prefs.purchasePrice = p.text.toString().toDoubleOrNull() ?: 0.0; Prefs.currentValue = c.text.toString().toDoubleOrNull() ?: 0.0
            done(Reports.cost(a))
        }
    }

    private fun tripPdfDialog() {
        val b = body(); val sp = spinnerOf(TripFilter.names)
        b.addView(ui.tv("Period", 12f, Ui.DIM)); b.addView(sp)
        b.addView(ui.tv("Columns", 12f, Ui.DIM), ui.lp(12))
        val on = setOf("Date", "Distance", "Duration", "Economy", "Cost")
        val boxes = Reports.tripCols.map { n -> CheckBox(a).apply { text = n; isChecked = n in on }.also { b.addView(it) } }
        dialog("Trip Log Report", b) {
            val i = sp.selectedItemPosition; val (f, u) = TripFilter.range(i, ObdManager.vehicle.id)
            val cols = boxes.filter { it.isChecked }.map { it.text.toString() }.take(7)
            if (boxes.count { it.isChecked } > 7) ui.toast("First 7 columns used (page width)")
            done(Reports.tripLogPdf(a, f, u, cols, TripFilter.names[i]))
        }
    }

    private fun yearDialog() {
        val now = Calendar.getInstance().get(Calendar.YEAR)
        val years = (now downTo now - 8).map { it.toString() }
        val b = body(); val sp = spinnerOf(years)
        b.addView(ui.tv("Year", 12f, Ui.DIM)); b.addView(sp)
        dialog("Annual Summary", b) { done(Reports.annual(a, years[sp.selectedItemPosition].toInt())) }
    }
}
