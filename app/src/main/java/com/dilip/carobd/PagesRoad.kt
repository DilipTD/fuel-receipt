package com.dilip.carobd

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Speed-limit sign: red ring, white disc, black number (grey when estimated). */
class RoadSignView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    var limit: Int? = null
        set(v) { field = v; invalidate() }
    var estimated = false
    var over = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    override fun onMeasure(w: Int, h: Int) { val s = (92 * d).toInt(); setMeasuredDimension(s, s) }
    override fun onDraw(c: Canvas) {
        val r = width / 2f; val cx = r; val cy = r
        p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawCircle(cx, cy, r - 2 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.2f; p.color = if (over) Color.parseColor("#FF1F3D") else Color.parseColor("#D7263D")
        c.drawCircle(cx, cy, r - 2 * d - r * 0.1f, p)
        val s = limit?.toString() ?: "--"
        t.color = if (limit == null || estimated) Color.parseColor("#55606E") else Color.BLACK
        t.textSize = r * (if (s.length >= 3) 0.62f else 0.78f)
        c.drawText(s, cx, cy + t.textSize * 0.35f, t)
        if (estimated && limit != null) { t.textSize = r * 0.2f; t.color = Color.parseColor("#55606E"); c.drawText("est.", cx, cy + r * 0.62f, t) }
    }
}

/** Compass with an arrow pointing in the direction of travel. */
class CompassView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    var deg: Float? = null
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8896A5"); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    override fun onMeasure(w: Int, h: Int) { val s = (92 * d).toInt(); setMeasuredDimension(s, s) }
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = Color.parseColor("#2A3441"); c.drawCircle(r, r, r - 3 * d, p)
        t.textSize = 11 * d
        c.drawText("N", r, 15 * d, t); c.drawText("S", r, width - 7 * d, t); c.drawText("E", width - 9 * d, r + 4 * d, t); c.drawText("W", 9 * d, r + 4 * d, t)
        val a = deg ?: return
        c.save(); c.rotate(a, r, r)
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#2FA8FF")
        c.drawPath(Path().apply { moveTo(r, r - 26 * d); lineTo(r + 9 * d, r + 12 * d); lineTo(r, r + 6 * d); lineTo(r - 9 * d, r + 12 * d); close() }, p)
        c.restore()
    }
}

@SuppressLint("SetTextI18n")
class RoadPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout
    private lateinit var sign: RoadSignView
    private lateinit var compass: CompassView
    private lateinit var spdTv: TextView
    private lateinit var roadTv: TextView
    private lateinit var subTv: TextView
    private lateinit var aiTv: TextView
    private lateinit var hazBox: LinearLayout
    private lateinit var logBox: LinearLayout
    private lateinit var headTv: TextView
    private lateinit var mapTv: TextView
    private var logSig = ""

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    override fun onShow() {
        box.removeAllViews()
        sign = RoadSignView(a); compass = CompassView(a)
        spdTv = ui.tv("0", 38f, Ui.WHITE, true); headTv = ui.tv("—", 15f, Ui.CYAN, true)
        roadTv = ui.tv("Finding road…", 16f, Ui.WHITE, true); subTv = ui.tv("", 12f, Ui.DIM); aiTv = ui.tv("", 12.5f, Ui.WHITE)
        box.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(Ui.CARD, 22); setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            addView(sign)
            addView(ui.vbox().apply {
                gravity = Gravity.CENTER
                addView(spdTv.apply { gravity = Gravity.CENTER })
                addView(ui.tv(Units.spdLabel, 12f, Ui.DIM).apply { gravity = Gravity.CENTER })
            }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(ui.vbox().apply {
                gravity = Gravity.CENTER
                addView(compass)
                addView(headTv.apply { gravity = Gravity.CENTER })
            })
        }, ui.lp(6))
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Ui.CARD, 18); setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.badge(Ic.ROUTE, Ui.ACC, 36), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { rightMargin = ui.dp(10) })
                addView(ui.vbox().apply { addView(roadTv); addView(subTv) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            })
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.rounded(Ui.alpha(Ui.VIOLET, 40), 12); setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                addView(ui.chip("AI", Ui.VIOLET, Ic.SHIELD), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
                addView(aiTv)
            }, ui.lp(10))
        }, ui.lp(10))

        box.addView(ui.section("Ahead", Ic.WARN))
        hazBox = ui.vbox(); box.addView(hazBox)
        box.addView(ui.row(
            ui.btn("Correct this limit", Ui.CARD) { correctLimit() },
            ui.btn("Report hazard here", Ui.CARD) { reportHazard() }), ui.lp(8))
        box.addView(ui.section("Offline map", Ic.DATABASE))
        mapTv = ui.tv(RoadAssist.savedInfo(), 12.5f, Ui.DIM)
        box.addView(mapTv, ui.lp(4))
        box.addView(ui.row(
            ui.btn("Download this area", Ui.ACC) {
                ui.toast("Downloading… this can take up to a minute")
                RoadAssist.downloadArea { m -> ui.toast(m); mapTv.text = RoadAssist.savedInfo() }
            },
            ui.btn("Clear saved maps", Ui.CARD) {
                AlertDialog.Builder(a).setTitle("Clear saved maps?").setMessage("Road data will be downloaded again when needed.")
                    .setPositiveButton("Clear") { _, _ -> RoadAssist.clearSaved(); mapTv.text = RoadAssist.savedInfo() }.setNegativeButton("Cancel", null).show()
            }), ui.lp(8))
        box.addView(ui.section("Announcements", Ic.VOICE))
        logBox = ui.vbox(); box.addView(logBox)

        box.addView(ui.section("Settings", Ic.SLIDERS))
        fun sw(label: String, v: Boolean, set: (Boolean) -> Unit) = ui.check(label, v).apply {
            setOnCheckedChangeListener { _, on -> set(on); if (label.startsWith("Road assistant")) {
                if (on) { Gps.start(a); ObdService.start(a) } else if (!Prefs.backgroundWatch && ObdManager.state == ObdManager.State.DISCONNECTED) a.stopService(android.content.Intent(a, ObdService::class.java))
            } }
        }
        box.addView(sw("Road assistant (works in background)", Prefs.roadAssist) { Prefs.roadAssist = it }, ui.lp(4))
        box.addView(sw("Voice announcements", Prefs.roadAnnounce) { Prefs.roadAnnounce = it }, ui.lp(4))
        box.addView(sw("Speed breaker, camera & school alerts", Prefs.roadHazards) { Prefs.roadHazards = it }, ui.lp(4))
        box.addView(sw("Warn on estimated limits too", Prefs.roadEstWarn) { Prefs.roadEstWarn = it }, ui.lp(4))
        val marginBtn = ui.btn("Over-speed warning at limit + ${Units.sStr(Prefs.speedMargin.toDouble())}", Ui.CARD) {}
        marginBtn.setOnClickListener {
            Prefs.speedMargin = when (Prefs.speedMargin) { 0 -> 5; 5 -> 10; 10 -> 15; else -> 0 }
            marginBtn.text = "Over-speed warning at limit + ${Units.sStr(Prefs.speedMargin.toDouble())}"
        }
        box.addView(marginBtn, ui.lp(8))
        box.addView(ui.tv("Limits and hazards come from OpenStreetMap and can be missing or out of date – estimated limits (grey, \"est.\") are guesses from the road type. Always follow the road signs. GPS is needed; internet is only needed the first time in an area (use Download this area before a trip).", 11.5f, Ui.DIM), ui.lp(10))
        logSig = ""
        refresh()
    }

    private fun correctLimit() {
        val i = RoadAssist.info
        if (i == null || i.wayId == 0L) { ui.toast("No road matched yet"); return }
        val opts = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
        val items = opts.map { Units.sStr(it.toDouble()) }.toMutableList()
        if (i.source == "you") items.add("Remove my correction")
        AlertDialog.Builder(a).setTitle("Real speed limit on ${i.road.ifEmpty { "this road" }}").setItems(items.toTypedArray()) { _, k ->
            if (k < opts.size) { RoadAssist.setLimitFix(i.wayId, opts[k]); ui.toast("Saved – I will use ${Units.sStr(opts[k].toDouble())} here") }
            else { RoadAssist.setLimitFix(i.wayId, null); ui.toast("Correction removed") }
        }.show()
    }

    private fun reportHazard() {
        val kinds = listOf("bump", "pothole", "curve", "camera", "school", "rail")
        val items = kinds.map { RoadAssist.labelFor(it) }.toMutableList()
        items.add("Remove my hazards within 80 m")
        AlertDialog.Builder(a).setTitle("Report at your position").setItems(items.toTypedArray()) { _, k ->
            if (k < kinds.size) ui.toast(if (RoadAssist.reportHazard(kinds[k])) "Saved – I will warn you here next time" else "No GPS position yet")
            else ui.toast("Removed ${RoadAssist.removeMyHazardsHere()} hazard(s)")
        }.show()
    }

    override fun refresh() {
        if (!::sign.isInitialized) return
        val r = RoadAssist
        val i = r.info
        val spd = r.speedKmh
        sign.limit = i?.limit; sign.estimated = i?.estimated == true
        sign.over = i?.limit != null && spd > i.limit + Prefs.speedMargin
        spdTv.text = "%.0f".format(Units.spd(spd)); spdTv.setTextColor(if (sign.over) Ui.BAD else Ui.WHITE)
        compass.deg = r.heading
        headTv.text = if (r.heading != null) "${RoadAssist.compass(r.heading)} · ${r.heading!!.toInt()}°" else "—"
        roadTv.text = when {
            !Prefs.roadAssist -> "Road assistant is off"
            i == null -> if (r.lastFix == 0L) Gps.statusText(a) else r.status
            else -> listOf(i.ref, i.road).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { i.highway.replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }
        }
        subTv.text = if (i == null) "" else i.highway.replace('_', ' ') + " · " + if (i.limit == null) "no limit data" else if (i.estimated) "limit ${Units.sStr(i.limit!!.toDouble())} (estimated)" else if (i.source == "you") "limit ${Units.sStr(i.limit!!.toDouble())} (set by you)" else "limit ${Units.sStr(i.limit!!.toDouble())}"
        aiTv.text = when {
            i == null -> "Drive with GPS on – I will find the road, its limit and hazards."
            sign.over -> "You are ${Units.sStr(spd - (i.limit ?: 0))} over the limit – ease off."
            i.limit != null -> "Within the limit. Heading ${RoadAssist.compass(r.heading)}."
            else -> "No limit data for this road – use the road signs."
        }
        hazBox.removeAllViews()
        val ahead = r.ahead()
        if (ahead.isEmpty()) hazBox.addView(ui.iconCard(Ic.CHECK, Ui.GOOD, "Nothing ahead", "No speed breakers, cameras or school zones in your direction"))
        else for ((z, d) in ahead.take(4)) hazBox.addView(ui.iconCard(when (z.kind) { "camera" -> Ic.SCAN; "school", "hospital" -> Ic.USER; else -> Ic.WARN }, Ui.WARN, z.label + if (z.mine) " (yours)" else "", "$d m ahead"), ui.lp(6))
        val sig = synchronized(r.log) { r.log.size.toString() + (r.log.firstOrNull()?.first ?: 0) }
        if (sig != logSig) {
            logSig = sig; logBox.removeAllViews()
            val l = synchronized(r.log) { r.log.toList() }
            if (l.isEmpty()) logBox.addView(ui.tv("Nothing announced yet.", 12.5f, Ui.DIM))
            l.take(12).forEach { (t, s) -> logBox.addView(ui.dotRow(s, ui.date(t, "HH:mm:ss"), Ic.VOICE), ui.lp(0)) }
        }
    }
}
