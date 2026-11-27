package com.dilip.carobd

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thin bar showing where a reading sits inside its normal band (green), watch band (amber) and out-of-spec zones (red). */
class SpecBar(ctx: Context) : View(ctx) {
    private var spec: Spec? = null
    private var value: Double? = null
    private var level = 0
    var tall = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = ctx.resources.displayMetrics.density

    fun set(s: Spec?, v: Double?, lvl: Int) {
        spec = s; value = v; level = lvl
        visibility = if (s == null) GONE else VISIBLE
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), ((if (tall) 14 else 8) * d).toInt())

    override fun onDraw(c: Canvas) {
        val s = spec ?: return
        val span = (s.barMax - s.barMin).takeIf { it > 0 } ?: return
        val th = (if (tall) 8 else 5) * d
        val top = (height - th) / 2f
        val w = width.toFloat()
        fun x(v: Double) = (((v - s.barMin) / span).coerceIn(0.0, 1.0) * w).toFloat()
        val zones = listOf(
            Triple(s.barMin, s.wLo, Ui.alpha(Ui.BAD, 150)), Triple(s.wLo, s.lo, Ui.alpha(Ui.WARN, 150)),
            Triple(s.lo, s.hi, Ui.alpha(Ui.GOOD, 190)), Triple(s.hi, s.wHi, Ui.alpha(Ui.WARN, 150)), Triple(s.wHi, s.barMax, Ui.alpha(Ui.BAD, 150))
        )
        val clip = android.graphics.Path().apply { addRoundRect(RectF(0f, top, w, top + th), th / 2, th / 2, android.graphics.Path.Direction.CW) }
        c.save(); c.clipPath(clip)
        for ((a, b, col) in zones) {
            val x1 = x(a); val x2 = x(b)
            if (x2 - x1 < 0.5f && s.lo == s.hi && col == zones[2].third) {
                // a zero-width normal band (e.g. "must be 0"): draw a small tick
                p.color = col; c.drawRect(x1 - 2 * d, top, x1 + 2 * d, top + th, p)
            } else if (x2 > x1) { p.color = col; r.set(x1, top, x2, top + th); c.drawRect(r, p) }
        }
        c.restore()
        val v = value ?: return
        val mx = x(v)
        p.color = Color.WHITE; c.drawCircle(mx, height / 2f, (if (tall) 6f else 4.5f) * d, p)
        p.color = when (level) { 0 -> Ui.GOOD; 1 -> Ui.WARN; else -> Ui.BAD }
        c.drawCircle(mx, height / 2f, (if (tall) 3.6f else 2.6f) * d, p)
    }
}

/** Live data: range check for every reading, AI summary and suggestions, grouped signals, info sheet, recording. */
@SuppressLint("SetTextI18n")
class LivePage(a: MainActivity) : Page(a) {
    override val parent = "More"

    private class Entry(val key: String, val name: String, val unit: String, val cat: String, val pid: Int?) {
        val specPid: Int? get() = pid ?: if (key == "calc:batt") SpecEngine.BATT else null
    }

    private class Cell(val card: LinearLayout, val value: TextView, val trend: TextView, val spark: SparkView,
                       val bar: SpecBar, val st: TextView, val star: TextView)

    private lateinit var head: TextView
    private lateinit var summaryBox: LinearLayout
    private lateinit var sugBox: LinearLayout
    private lateinit var attnBox: LinearLayout
    private lateinit var catRow: LinearLayout
    private lateinit var insightBox: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var chart1: LineChart
    private lateinit var chart2: LineChart
    private lateinit var statsBox: LinearLayout
    private lateinit var recBtn: android.widget.Button
    private lateinit var evBox: LinearLayout
    private val cells = HashMap<String, Cell>()
    private val groupDots = LinkedHashMap<String, View>()
    private val groupEntries = HashMap<String, List<Entry>>()
    private val calcHist = HashMap<String, Ring>()
    private var entries: List<Entry> = emptyList()
    private var sig = ""
    private var cat = "All"
    private var pid1 = "p${0x0C}"
    private var pid2: String? = null
    private var lastSlow = 0L
    private var lastIssueN = -1
    private var lastInsight = 0L
    private val cats = listOf("Issues", "Pinned", "All", "Engine", "Fuel", "Air", "Temp", "Electrical", "Emission", "Custom")
    private val groups = listOf("Engine", "Fuel", "Air", "Temp", "Electrical", "Emission", "Custom")
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun lc(l: Int) = when (l) { 0 -> Ui.GOOD; 1 -> Ui.WARN; else -> Ui.BAD }

    private fun pins(): Set<String> = Prefs.livePins.split(",").filter { it.isNotBlank() }.toSet()
    private fun setPin(k: String, on: Boolean) {
        val s = pins().toMutableSet(); if (on) s.add(k) else s.remove(k)
        Prefs.livePins = s.joinToString(","); sig = ""
    }

    override fun build(): View {
        val c = ui.col()
        head = ui.tv("", 12f, Ui.DIM)
        c.addView(head, ui.lp(4))
        summaryBox = ui.vbox()
        c.addView(summaryBox, ui.lp(4))

        c.addView(ui.section("AI suggestions", Ic.SHIELD))
        sugBox = ui.vbox()
        c.addView(sugBox)
        attnBox = ui.vbox()
        c.addView(attnBox, ui.lp(4))

        c.addView(ui.section("Readings", Ic.GRID))
        catRow = LinearLayout(a)
        c.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(catRow) }, ui.lp(4))
        c.addView(ui.tv("Tap a card to graph it · ⓘ or long-press for range, history and explanation", 11f, Ui.DIM), ui.lp(6))
        grid = ui.vbox()
        c.addView(grid, ui.lp(6))

        c.addView(ui.section("Graph", Ic.CHART))
        statsBox = ui.vbox()
        c.addView(statsBox)
        chart1 = LineChart(a, 150); chart2 = LineChart(a, 120)
        c.addView(chart1, ui.lp(8))
        c.addView(chart2, ui.lp(8))

        c.addView(ui.section("More analysis", Ic.PULSE))
        insightBox = ui.vbox()
        c.addView(insightBox)

        c.addView(ui.section("Out-of-spec log", Ic.FLAG))
        evBox = ui.vbox()
        c.addView(evBox)

        c.addView(ui.section("Record & share", Ic.DOWNLOAD))
        recBtn = ui.btn("Record session", Ui.BAD) { toggleRecord() }
        c.addView(recBtn, ui.lp(4))
        c.addView(ui.row(
            ui.btn("Snapshot CSV", Ui.CARD) { snapshot() },
            ui.btn("Report PDF", Ui.CARD) { report() }
        ), ui.lp(8))
        c.addView(ui.tv("Ranges are typical values for a healthy car, not factory data for one model. Treat them as a guide.", 11f, Ui.DIM), ui.lp(10))
        return ui.scroll(c)
    }

    override fun onShow() { sig = ""; lastSlow = 0L; paintCats() }

    private fun paintCats() {
        catRow.removeAllViews()
        val n = if (lastIssueN > 0) lastIssueN else 0
        for (cn in cats) {
            val on = cn == cat
            val label = if (cn == "Issues" && n > 0) "Issues $n" else cn
            val col = if (cn == "Issues" && n > 0) Ui.BAD else Ui.ACC
            catRow.addView(ui.tv(label, 13f, if (on) Ui.WHITE else Ui.DIM, on).apply {
                background = ui.rounded(if (on) col else Ui.CARD, 18)
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                setOnClickListener { cat = cn; sig = ""; paintCats() }
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
        }
        val sortOn = Prefs.liveSortIssues
        catRow.addView(ui.tv(if (sortOn) "Sort: issues first" else "Sort: default", 12f, Ui.DIM).apply {
            background = ui.outlined(Ui.BG, Ui.STROKE, 18, 1)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            setOnClickListener { Prefs.liveSortIssues = !Prefs.liveSortIssues; sig = ""; paintCats() }
        })
    }

    private fun category(p: Int): String = when (p) {
        0x05, 0x5C, 0x3C, 0x3D, 0x3E, 0x3F -> "Temp"
        0x0F, 0x10, 0x0B, 0x33, 0x46, 0x68, 0x6B -> "Air"
        0x42, 0x43, 0x4D, 0x4E -> "Electrical"
        0x03, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x22, 0x23, 0x2F, 0x51, 0x52, 0x5E, 0x2D -> "Fuel"
        in 0x13..0x1B, in 0x24..0x2B, in 0x34..0x3B, 0x2C, 0x2E, 0x78, 0x79 -> "Emission"
        else -> "Engine"
    }

    private fun catColor(c: String) = when (c) {
        "Fuel" -> Ui.ORANGE; "Air" -> Ui.CYAN; "Temp" -> Ui.BAD; "Electrical" -> Ui.WARN; "Emission" -> Ui.GOOD; "Custom" -> Ui.PINK; else -> Ui.ACC
    }

    private fun catIcon(c: String) = when (c) {
        "Fuel" -> Ic.FUEL; "Air" -> Ic.WIND; "Temp" -> Ic.THERM; "Electrical" -> Ic.BATTERY; "Emission" -> Ic.LEAF; "Custom" -> Ic.CODE; "Pinned" -> Ic.PIN; else -> Ic.ENGINE
    }

    private fun ring(key: String) = calcHist.getOrPut(key) { Ring(120) }

    private fun series(e: Entry): FloatArray {
        val m = ObdManager
        return if (e.pid != null) m.history[e.pid]?.values() ?: FloatArray(0) else ring(e.key).values()
    }

    private fun rawOf(e: Entry): Double? = when {
        e.pid != null -> ObdManager.values[e.pid]
        e.key == "calc:batt" -> ObdManager.batteryV
        else -> null
    }

    private fun stOf(e: Entry, c: SpecCtx): Status? {
        val sp = e.specPid ?: return null
        val v = rawOf(e) ?: return null
        return SpecEngine.status(sp, v, c)
    }

    private fun textOf(e: Entry): String {
        val m = ObdManager
        if (e.pid != null) {
            val def = Pids.byId[e.pid]
            return m.texts[e.pid] ?: m.values[e.pid]?.let { val (cv, _) = Units.conv(e.unit, it); "%.${def?.decimals ?: 1}f".format(cv) } ?: "…"
        }
        return when (e.key) {
            "calc:fuel" -> m.fuelRate()?.let { "%.2f".format(Units.vol(it, "L")) } ?: "--"
            "calc:econ" -> m.engine.instantKmpl?.let { "%.1f".format(it) } ?: "--"
            "calc:acc" -> "%+.2f".format(m.engine.liveAccelG)
            "calc:batt" -> ui.f(m.batteryV, 2)
            else -> m.customValues[e.key.removePrefix("c:")] ?: "…"
        }
    }

    private fun allEntries(): List<Entry> {
        val m = ObdManager
        val u = m.vehicle.spec.unit
        val list = mutableListOf<Entry>()
        list += Entry("calc:fuel", "Fuel rate", "$u/h", "Fuel", null)
        list += Entry("calc:econ", "Instant economy", "km/$u", "Fuel", null)
        list += Entry("calc:acc", "Acceleration", "g", "Engine", null)
        list += Entry("calc:batt", "Battery (pin 16)", "V", "Electrical", null)
        for (p in m.supported.filter { !Pids.isBitmap(it) }) { val d = Pids.byId[p]; list += Entry("p$p", d?.name ?: Pids.nameOf(p), d?.unit ?: "", category(p), p) }
        for (cp in Prefs.customPids) list += Entry("c:${cp.name}", cp.name, "", "Custom", null)
        return list
    }

    private fun rebuild() {
        val c = SpecEngine.ctx(true)
        val all = allEntries()
        val pn = pins()
        var list = when (cat) {
            "Issues" -> all.filter { (stOf(it, c)?.level ?: 0) >= 1 }
            "Pinned" -> all.filter { it.key in pn }
            "All" -> all
            else -> all.filter { it.cat == cat }
        }
        if (Prefs.liveSortIssues) list = list.sortedByDescending { stOf(it, c)?.level ?: 0 }
        entries = list
        grid.removeAllViews(); cells.clear(); groupDots.clear(); groupEntries.clear()
        if (list.isEmpty()) {
            grid.addView(ui.tv(when (cat) {
                "Issues" -> "No readings outside the typical range. 👍"
                "Pinned" -> "Nothing pinned yet. Open a reading's ⓘ sheet and tap Pin."
                else -> "No readings in this group yet."
            }, 13f, Ui.DIM).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
            return
        }
        if (cat == "All") {
            val pinned = list.filter { it.key in pn }
            if (pinned.isNotEmpty()) addGroup("Pinned", pinned)
            for (g in groups) addGroup(g, list.filter { it.cat == g && it.key !in pn })
        } else addCells(list)
    }

    private fun addGroup(name: String, list: List<Entry>) {
        if (list.isEmpty()) return
        val dot = View(a).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.GOOD) } }
        groupDots[name] = dot; groupEntries[name] = list
        grid.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.iconView(catIcon(name), catColor(name), 16), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { rightMargin = ui.dp(8) })
            addView(ui.tv(name + "  ·  ${list.size}", 13f, Ui.DIM, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(dot, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)))
        }, ui.lp(14))
        addCells(list)
    }

    private fun addCells(list: List<Entry>) {
        list.chunked(2).forEach { pair ->
            val views = pair.map { e -> makeCell(e) }.toMutableList<View>()
            if (views.size < 2) views.add(View(a))
            grid.addView(ui.grid(*views.toTypedArray()), ui.lp(8))
        }
    }

    private fun makeCell(e: Entry): View {
        val value = ui.tv("…", 21f, Ui.WHITE, true)
        val unit = ui.tv(Units.label(e.unit), 11.5f, Ui.DIM)
        val trend = ui.tv("", 14f, Ui.DIM, true)
        val spark = SparkView(a).apply { color = catColor(e.cat) }
        val bar = SpecBar(a).apply { visibility = View.GONE }
        val st = ui.tv("", 10.5f, Ui.DIM, true)
        val star = ui.tv("★", 12f, Ui.WARN, true).apply { visibility = if (e.key in pins()) View.VISIBLE else View.GONE }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(8))
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.tv(e.name, 11.5f, Ui.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                addView(star, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = ui.dp(4) })
                if (e.specPid != null || e.pid != null) addView(ui.iconView(Ic.INFO, Ui.DIM, 15).apply { setOnClickListener { showInfo(e) } },
                    LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)).apply { leftMargin = ui.dp(6) })
            })
            addView(LinearLayout(a).apply {
                gravity = Gravity.BOTTOM
                addView(value); addView(unit, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = ui.dp(4); bottomMargin = ui.dp(3) })
                addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)); addView(trend)
            }, ui.lp(2))
            addView(bar, ui.lp(4))
            addView(st, ui.lp(0))
            addView(spark, ui.lp(2))
            if (e.pid != null || e.key.startsWith("calc")) {
                setOnClickListener { pid1 = e.key; if (pid2 == e.key) pid2 = null }
                setOnLongClickListener { showInfo(e); true }
            }
        }
        cells[e.key] = Cell(card, value, trend, spark, bar, st, star)
        return card
    }

    /* ---------------------------- info sheet ---------------------------- */

    private fun showInfo(e: Entry) {
        val c = SpecEngine.ctx(true)
        val sp = e.specPid
        val raw = rawOf(e)
        val st = stOf(e, c)
        val l = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(4)) }
        l.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.tv(e.name, 17f, Ui.WHITE, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            if (st != null) addView(ui.chip(st.label, lc(st.level)))
        })
        l.addView(LinearLayout(a).apply {
            gravity = Gravity.BOTTOM
            addView(ui.tv(textOf(e), 32f, Ui.WHITE, true))
            addView(ui.tv(Units.label(e.unit), 14f, Ui.DIM), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = ui.dp(6); bottomMargin = ui.dp(6) })
        }, ui.lp(6))
        val spec = if (sp != null) SpecEngine.specFor(sp, c) else null
        if (spec != null) {
            l.addView(SpecBar(a).apply { tall = true; set(spec, raw, st?.level ?: 0) }, ui.lp(4))
            l.addView(ui.tv("Normal range: " + SpecEngine.rangeText(sp!!, spec), 12.5f, Color.parseColor("#D5DEE8")), ui.lp(6))
        } else if (sp != null) l.addView(ui.tv("No fixed range applies in the current situation (for example the engine is off or not warm).", 12f, Ui.DIM), ui.lp(6))
        else l.addView(ui.tv("No specification for this signal.", 12f, Ui.DIM), ui.lp(6))

        // this-drive statistics
        val dec = e.pid?.let { Pids.byId[it]?.decimals } ?: 1
        val tri: Triple<Double, Double, Double>? = if (e.pid != null) SpecEngine.statOf(e.pid)?.let { Triple(Units.conv(e.unit, it.first).first, Units.conv(e.unit, it.second).first, Units.conv(e.unit, it.third).first) }
        else series(e).let { s -> if (s.isEmpty()) null else Triple(s.min().toDouble(), s.average(), s.max().toDouble()) }
        if (tri != null) {
            val (x1, t1) = ui.stat(Ic.DOWNLOAD, "Min", Ui.CYAN); t1.text = "%.${dec}f".format(tri.first)
            val (x2, t2) = ui.stat(Ic.GAUGE, "Average", Ui.ACC); t2.text = "%.${dec}f".format(tri.second)
            val (x3, t3) = ui.stat(Ic.BOLT, "Max", Ui.ORANGE); t3.text = "%.${dec}f".format(tri.third)
            l.addView(ui.tv("THIS DRIVE", 11f, Ui.DIM), ui.lp(14))
            l.addView(ui.row(x1, x2, x3), ui.lp(4))
        }
        val ser = series(e)
        if (ser.size >= 3) {
            val ch = LineChart(a, 110); ch.set(ser, e.name, if (e.unit.isNotEmpty()) " ${Units.label(e.unit)}" else "", catColor(e.cat))
            l.addView(ch, ui.lp(8))
        }

        // own-car baseline
        if (e.pid != null && SpecEngine.learnable(e.pid)) {
            val b = SpecEngine.baseline(e.pid, c)
            val txt = if (b != null) "Your car's usual: %s (±%s) from %d samples".format(SpecEngine.fmt(e.pid, b.mean), SpecEngine.fmt(e.pid, b.sd), b.n)
            else "Learning your car's normal for this reading. It needs about ${SpecEngine.BASE_READY} samples in the right conditions (warm engine, idle or steady driving)."
            l.addView(ui.tv("YOUR CAR", 11f, Ui.DIM), ui.lp(14))
            l.addView(ui.tv(txt, 12.5f, Color.parseColor("#D5DEE8")), ui.lp(4))
        }

        // explanation
        val inf = if (sp != null) SpecEngine.infoFor(sp) else null
        l.addView(ui.tv("WHAT IS THIS?", 11f, Ui.DIM), ui.lp(14))
        if (inf != null) {
            l.addView(ui.tv(inf.what, 12.5f, Color.parseColor("#D5DEE8")), ui.lp(4))
            if (inf.low.isNotBlank()) l.addView(ui.tv("Low: ${inf.low}", 12.5f, Color.parseColor("#D5DEE8")), ui.lp(6))
            if (inf.high.isNotBlank()) l.addView(ui.tv("High: ${inf.high}", 12.5f, Color.parseColor("#D5DEE8")), ui.lp(6))
            if (inf.check.isNotBlank()) l.addView(ui.tv("What to check: ${inf.check}", 12.5f, Ui.VIOLET), ui.lp(6))
        } else l.addView(ui.tv(if (e.pid != null) "Standard OBD-II reading reported by the engine computer." else "Value calculated by the app from other readings.", 12.5f, Color.parseColor("#D5DEE8")), ui.lp(4))
        val cyc = ObdManager.cycleMs
        l.addView(ui.tv(
            (if (e.pid != null) "PID 0x%02X · Mode 01 · direct ECU value".format(e.pid) else if (e.key.startsWith("c:")) "Custom PID" else "Calculated by the app") +
                    (if (cyc > 0) " · refreshed about every %.1f s".format(cyc / 1000.0) else ""), 11f, Ui.DIM), ui.lp(10))

        val pinned = e.key in pins()
        val dlg = AlertDialog.Builder(a).setView(ScrollView(a).apply { addView(l) }).setPositiveButton("Close", null).create()
        l.addView(ui.row(
            ui.btn(if (pinned) "Unpin" else "Pin", Ui.CARD) { setPin(e.key, !pinned); dlg.dismiss() },
            ui.btn("Graph", Ui.CARD) { pid1 = e.key; if (pid2 == e.key) pid2 = null; dlg.dismiss() },
            ui.btn("Compare", Ui.CARD) { pid2 = e.key; dlg.dismiss() }
        ), ui.lp(12))
        dlg.show()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Ui.BG))
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Ui.ACC)
    }

    /* ---------------------------- summary, suggestions, log ---------------------------- */

    private fun contextLabel(c: SpecCtx): String = when {
        !c.running -> "Engine off"
        c.idle && !c.warm -> "Idling, warming up"
        c.idle -> "Idling, warm"
        !c.warm -> "Driving, warming up"
        else -> "Driving, warm"
    }

    private fun slow() {
        val m = ObdManager
        val c = SpecEngine.ctx(true)
        // summary
        summaryBox.removeAllViews()
        if (m.state != ObdManager.State.CONNECTED || c.v.isEmpty()) {
            summaryBox.addView(ui.iconCard(Ic.PLUG, Ui.DIM, "Waiting for live data", "Connect to the car to see readings checked against their normal range"))
        } else {
            val h = SpecEngine.health(c)
            val lvl = if (h.out > 0) 2 else if (h.watch > 0) 1 else 0
            val col = lc(lvl)
            val title = when {
                h.out > 0 -> "${h.out} reading${if (h.out == 1) "" else "s"} out of spec"
                h.watch > 0 -> "${h.watch} reading${if (h.watch == 1) "" else "s"} to watch"
                else -> "All readings in range"
            }
            val box = ui.vbox().apply {
                background = ui.outlined(Ui.CARD, Ui.alpha(col, 160), 18, 2)
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                addView(LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.badge(if (lvl == 0) Ic.CHECK else Ic.WARN, col, 38), LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).apply { rightMargin = ui.dp(12) })
                    addView(ui.vbox().apply {
                        addView(ui.tv(title, 16f, Ui.WHITE, true))
                        addView(ui.tv("${contextLabel(c)} · ${h.total} readings checked", 12f, Ui.DIM))
                    }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                })
                if (h.total > 0) addView(LinearLayout(a).apply {
                    if (h.ok > 0) addView(View(a).apply { setBackgroundColor(Ui.GOOD) }, LinearLayout.LayoutParams(0, ui.dp(5), h.ok.toFloat()))
                    if (h.watch > 0) addView(View(a).apply { setBackgroundColor(Ui.WARN) }, LinearLayout.LayoutParams(0, ui.dp(5), h.watch.toFloat()))
                    if (h.out > 0) addView(View(a).apply { setBackgroundColor(Ui.BAD) }, LinearLayout.LayoutParams(0, ui.dp(5), h.out.toFloat()))
                }, ui.lp(10))
                addView(LinearLayout(a).apply {
                    addView(ui.chip("${h.ok} OK", Ui.GOOD), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
                    addView(ui.chip("${h.watch} watch", Ui.WARN), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
                    addView(ui.chip("${h.out} out", Ui.BAD))
                }, ui.lp(10))
                val prog = SpecEngine.baselineProgress()
                addView(ui.tv(if (prog >= 100) "Your car's norms learned (${SpecEngine.baselineCount()} readings)"
                else "Learning your car's normal: $prog %" + if (SpecEngine.baselineCount() > 0) " · ${SpecEngine.baselineCount()} ready" else "", 11.5f, Ui.DIM), ui.lp(8))
                if (h.out + h.watch > 0) setOnClickListener { cat = "Issues"; sig = ""; paintCats() }
            }
            summaryBox.addView(box)
        }

        // suggestions
        sugBox.removeAllViews()
        if (m.state == ObdManager.State.CONNECTED && c.v.isNotEmpty()) {
            val sg = SpecEngine.suggestions(c)
            if (sg.isEmpty()) sugBox.addView(ui.iconCard(Ic.CHECK, Ui.GOOD, "Nothing to fix", "Every reading is inside its normal range for what the car is doing."), ui.lp(0))
            else for (s in sg.take(5)) {
                val ent = s.pids.firstNotNullOfOrNull { p -> allEntries().firstOrNull { it.specPid == p } }
                sugBox.addView(ui.iconCard(if (s.sev >= 2) Ic.WARN else Ic.INFO, lc(if (s.sev >= 2) 2 else 1), s.title,
                    s.why + "\nWhat to do: " + s.fix, Ui.CARD, if (ent != null) ({ showInfo(ent) }) else null), ui.lp(6))
            }
        } else sugBox.addView(ui.tv("Suggestions appear once the engine is running.", 12f, Ui.DIM))

        // issues count for chip
        val issues = if (c.v.isEmpty()) 0 else SpecEngine.findings(c).size
        if (issues != lastIssueN) { lastIssueN = issues; paintCats() }

        // group dots
        for ((g, dot) in groupDots) {
            val worst = groupEntries[g]?.maxOfOrNull { stOf(it, c)?.takeIf { s -> s.label != "Warming up" }?.level ?: 0 } ?: 0
            dot.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(lc(worst)) }
        }

        // out-of-spec log
        evBox.removeAllViews()
        val evs = SpecEngine.eventList().takeLast(15).reversed()
        if (evs.isEmpty()) evBox.addView(ui.tv("Nothing outside the range this drive.", 12f, Ui.DIM))
        else for (ev in evs) evBox.addView(ui.tv("${hms.format(Date(ev.t))}  ${ev.name}: ${ev.text}", 12f, if (ev.level >= 2) Ui.BAD else Ui.WARN).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)) })

        // more analysis
        insightBox.removeAllViews()
        val ins = insights()
        if (ins.isEmpty()) insightBox.addView(ui.tv("No extra analysis yet.", 12f, Ui.DIM))
        else for (i in ins) insightBox.addView(ui.iconCard(i.ic, lc(i.status), i.title, i.body), ui.lp(6))

        recBtn.text = if (SpecEngine.recording) "Stop & save (${SpecEngine.recordingSeconds()} s)" else "Record session"
    }

    private class Insight(val ic: Ic, val status: Int, val title: String, val body: String)

    private fun insights(): List<Insight> {
        val m = ObdManager; val v = m.values; val veh = m.vehicle
        val out = mutableListOf<Insight>()
        m.idleRpmStdDev()?.let { sd ->
            out += Insight(Ic.ENGINE, if (sd < 40) 0 else if (sd < 90) 1 else 2, "Idle stability ±%.0f rpm".format(sd),
                if (sd < 40) "Smooth idle" else "Unsteady idle – check plugs, throttle body, vacuum leaks")
        }
        val rpm = v[0x0C]; val maf = v[0x10]
        if (rpm != null && rpm > 500 && maf != null && veh.dispL > 0) {
            val ve = maf * 120.0 / rpm / (veh.dispL * 1.184) * 100
            out += Insight(Ic.WIND, 0, "Volumetric efficiency ≈ %.0f %%".format(ve), "Air %.1f g/s at %.0f rpm".format(maf, rpm))
        }
        val iat = v[0x0F]; val amb = v[0x46]
        if (iat != null && amb != null) out += Insight(Ic.WIND, 0, "Intake air %s".format(Units.tStr(iat)),
            "%.0f° above ambient".format(if (Units.tempF) (iat - amb) * 1.8 else iat - amb))
        val load = v[0x04]; val thr = v[0x11]
        if (load != null && thr != null) out += Insight(Ic.GAUGE, 0, "Load %.0f %% · throttle %.0f %%".format(load, thr), "Engine demand")
        out += Insight(Ic.TIMER, if (m.cycleMs in 1..400) 0 else 1, "Refresh %d ms".format(m.cycleMs),
            "%.1f updates/s · ${m.supported.count { !Pids.isBitmap(it) }} signals".format(if (m.cycleMs > 0) 1000.0 / m.cycleMs else 0.0))
        return out
    }

    /* ---------------------------- refresh ---------------------------- */

    override fun refresh() {
        val m = ObdManager
        val pids = m.supported.filter { !Pids.isBitmap(it) }
        val c = SpecEngine.ctx()
        val issueKey = if (cat == "Issues") allEntries().filter { (stOf(it, c)?.level ?: 0) >= 1 }.joinToString(",") { it.key } else ""
        val s = pids.joinToString(",") + "|" + Prefs.customPids.joinToString(",") { it.name } + "|" + cat + "|" + Prefs.liveSortIssues + "|" + Prefs.livePins + "|" + issueKey
        if (s != sig) { sig = s; rebuild() }
        head.text = "${pids.size} signals · ${Prefs.customPids.size} custom · ${m.cycleMs} ms cycle"

        m.fuelRate()?.let { ring("calc:fuel").add(it.toFloat()) }
        m.engine.instantKmpl?.let { ring("calc:econ").add(it.toFloat()) }
        ring("calc:acc").add(m.engine.liveAccelG.toFloat())
        m.batteryV?.let { ring("calc:batt").add(it.toFloat()) }

        val pn = pins()
        for (e in entries) {
            val cl = cells[e.key] ?: continue
            cl.value.text = textOf(e)
            val d = series(e)
            cl.spark.set(d)
            if (d.size >= 12) {
                val n = d.size
                val recent = d.takeLast(6).average(); val prev = d.drop(n - 12).take(6).average()
                val rng = (d.max() - d.min()).coerceAtLeast(1e-6f)
                val dlt = (recent - prev) / rng
                cl.trend.text = if (dlt > 0.08) "▲" else if (dlt < -0.08) "▼" else "▬"
                cl.trend.setTextColor(if (dlt > 0.08) Ui.WARN else if (dlt < -0.08) Ui.CYAN else Ui.DIM)
            }
            val st = stOf(e, c)
            val sp = e.specPid
            val spec = if (sp != null) SpecEngine.specFor(sp, c) else null
            cl.bar.set(spec, rawOf(e), st?.level ?: 0)
            if (st != null) { cl.st.text = st.label; cl.st.setTextColor(if (st.label == "Warming up") Ui.CYAN else lc(st.level)) } else cl.st.text = ""
            cl.value.setTextColor(if (st != null && st.level >= 1 && st.label != "Warming up") lc(st.level).let { Ui.lighten(it, 1.2f) } else Ui.WHITE)
            cl.star.visibility = if (e.key in pn) View.VISIBLE else View.GONE
            val sel = e.key == pid1; val cmp = e.key == pid2
            val lv = st?.level ?: 0
            cl.card.background = if (sel) ui.outlined(Ui.SEL, Ui.CYAN, 16, 2) else if (cmp) ui.outlined(Ui.SEL, Ui.PINK, 16, 2)
            else if (lv >= 1 && st?.label != "Warming up") ui.outlined(Ui.CARD, Ui.alpha(lc(lv), 200), 16, 2) else ui.rounded(Ui.CARD, 16)
        }

        // graphs
        val e1 = entries.firstOrNull { it.key == pid1 } ?: allEntry(pid1)
        if (e1 != null) {
            val d1 = series(e1)
            chart1.set(d1, e1.name, if (e1.unit.isNotEmpty()) " ${e1.unit}" else "", Color.parseColor("#4FC3F7"))
            statsBox.removeAllViews()
            if (d1.isNotEmpty()) {
                val avg = d1.average(); val sd = Math.sqrt(d1.map { (it - avg) * (it - avg) }.average())
                val (x1, t1) = ui.stat(Ic.DOWNLOAD, "Min", Ui.CYAN); t1.text = "%.1f".format(d1.min())
                val (x2, t2) = ui.stat(Ic.GAUGE, "Avg", Ui.ACC); t2.text = "%.1f".format(avg)
                val (x3, t3) = ui.stat(Ic.BOLT, "Max", Ui.ORANGE); t3.text = "%.1f".format(d1.max())
                val (x4, t4) = ui.stat(Ic.PULSE, "σ", Ui.VIOLET); t4.text = "%.1f".format(sd)
                statsBox.addView(ui.row(x1, x2, x3, x4), ui.lp(2))
            }
        }
        val e2 = pid2?.let { k -> entries.firstOrNull { it.key == k } ?: allEntry(k) }
        if (e2 != null) { chart2.visibility = View.VISIBLE; chart2.set(series(e2), e2.name, if (e2.unit.isNotEmpty()) " ${e2.unit}" else "", Color.parseColor("#FF80AB")) }
        else chart2.visibility = View.GONE

        val now = System.currentTimeMillis()
        if (now - lastSlow > 1500) { lastSlow = now; slow() }
    }

    private fun allEntry(k: String): Entry? {
        val p = k.removePrefix("p").toIntOrNull()
        return if (k.startsWith("p") && p != null) Entry(k, Pids.byId[p]?.name ?: Pids.nameOf(p), Pids.byId[p]?.unit ?: "", category(p), p)
        else if (k.startsWith("calc:")) Entry(k, k.removePrefix("calc:"), "", "Engine", null) else null
    }

    /* ---------------------------- record / export ---------------------------- */

    private fun toggleRecord() {
        if (!SpecEngine.recording) {
            if (ObdManager.state != ObdManager.State.CONNECTED) { ui.toast("Connect to the car first"); return }
            SpecEngine.startRecording(); ui.toast("Recording one row per second. Tap again to stop and save.")
        } else {
            val csv = SpecEngine.stopRecording()
            if (csv == null) ui.toast("Nothing was recorded")
            else {
                val path = Exporter.writeText(a, "carobd_live_${stamp()}.csv", "text/csv", csv)
                ui.toast(if (path != null) "Saved to $path" else "Export failed – check storage permission")
            }
        }
        lastSlow = 0L
    }

    private fun snapshot() {
        val m = ObdManager
        val c = SpecEngine.ctx(true)
        val sb = StringBuilder("pid,name,value,unit,status,normal range\n")
        for ((p, v) in m.values.toSortedMap()) {
            val d = Pids.byId[p]
            val st = SpecEngine.status(p, v, c)
            val rg = SpecEngine.specFor(p, c)?.let { SpecEngine.rangeText(p, it) } ?: ""
            sb.append("%02X,\"%s\",%.3f,%s,%s,\"%s\"\n".format(p, d?.name ?: Pids.nameOf(p), v, d?.unit ?: "", st?.label ?: "", rg))
        }
        for ((k, v) in m.customValues) sb.append("custom,\"$k\",$v,,,\n")
        sb.append("\n# ${AppInfo.credit}\n")
        val path = Exporter.writeText(a, "carobd_snapshot_${stamp()}.csv", "text/csv", sb.toString())
        ui.toast(if (path != null) "Saved to $path" else "Export failed")
    }

    private fun report() {
        val p = Reports.liveData(a)
        ui.toast(if (p == null) "Connect to the car first, or check storage permission" else "Saved to $p")
    }
}
