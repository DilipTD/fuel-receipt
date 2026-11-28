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
    private lateinit var recBtn: android.widget.Button
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
    private lateinit var dlBar: android.widget.ProgressBar
    private lateinit var dlTv: TextView
    private lateinit var basisTv: TextView
    private lateinit var speedBar: SpecBar
    private lateinit var nextBox: LinearLayout
    private lateinit var statsTv: TextView
    private var logSig = ""

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    override fun onShow() {
        box.removeAllViews()
        sign = RoadSignView(a); compass = CompassView(a)
        spdTv = ui.tv("0", 38f, Ui.WHITE, true); headTv = ui.tv("—", 15f, Ui.CYAN, true)
        roadTv = ui.tv("Finding road…", 16f, Ui.WHITE, true); subTv = ui.tv("", 12f, Ui.DIM); aiTv = ui.tv("", 12.5f, Ui.WHITE)
        sign.setOnClickListener { explainLimit() }
        speedBar = SpecBar(a).apply { tall = true; visibility = View.GONE }
        basisTv = ui.tv("", 11.5f, Ui.DIM)
        nextBox = ui.vbox(); statsTv = ui.tv("", 12.5f, Ui.WHITE)
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
        box.addView(speedBar, ui.lp(6))
        box.addView(nextBox, ui.lp(6))
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Ui.CARD, 18); setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.badge(Ic.ROUTE, Ui.ACC, 36), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { rightMargin = ui.dp(10) })
                addView(ui.vbox().apply { addView(roadTv); addView(subTv); addView(basisTv) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
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
        box.addView(ui.section("This drive", Ic.CHART))
        box.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(Ui.CARD, 14); setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            addView(statsTv, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(ui.tv("Reset", 12f, Ui.ACC, true).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6)); setOnClickListener { RoadAssist.resetStats(); refresh() } })
        }, ui.lp(2))
        box.addView(ui.section("Drive report", Ic.CHART))
        box.addView(ui.tv("Wrong limit or road? Record the drive, tap Mark problem where it happens, then Stop & save. The report shows GPS quality, the matched road and where each limit came from – send me the summary and I can fix it.", 12f, Ui.DIM))
        recBtn = ui.btn("Record this drive", Ui.BAD) { toggleDriveLog() }
        box.addView(ui.row(recBtn, ui.btn("Mark problem", Ui.CARD) { if (DriveLog.recording) { DriveLog.mark(); ui.toast("Marked") } else ui.toast("Start recording first") }), ui.lp(6))
        box.addView(ui.section("Offline map", Ic.DATABASE))
        mapTv = ui.tv(RoadAssist.savedInfo(), 12.5f, Ui.DIM)
        box.addView(mapTv, ui.lp(4))
        val radii = listOf(10, 25, 50, 100, 200)
        val sp = android.widget.Spinner(a).apply {
            adapter = android.widget.ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, radii.map { "Map radius $it km  (about ${RoadAssist.areasFor(it)} areas)" })
            setSelection(radii.indexOf(Prefs.mapRadiusKm).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(pr: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { Prefs.mapRadiusKm = radii[pos] }
                override fun onNothingSelected(pr: android.widget.AdapterView<*>?) {}
            }
        }
        box.addView(sp, ui.lp(4))
        dlBar = android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        box.addView(dlBar, ui.lp(4))
        dlTv = ui.tv("", 12.5f, Ui.WHITE, true).apply { visibility = View.GONE }
        box.addView(dlTv, ui.lp(2))
        box.addView(ui.row(
            ui.btn("Download map", Ui.ACC) {
                RoadAssist.downloadWide(Prefs.mapRadiusKm) { m -> ui.toast(m); mapTv.text = RoadAssist.savedInfo() }
                ui.toast("Downloading in the background – you can leave this screen")
                refresh()
            },
            ui.btn("Stop", Ui.CARD) { RoadAssist.cancelDownload() }), ui.lp(6))
        box.addView(ui.row(
            ui.btn("Town detail here", Ui.CARD) {
                ui.toast("Downloading town detail in the background")
                RoadAssist.downloadArea { m -> ui.toast(m); mapTv.text = RoadAssist.savedInfo() }
            },
            ui.btn("Clear saved maps", Ui.CARD) {
                AlertDialog.Builder(a).setTitle("Clear saved maps?").setMessage("Road data will be downloaded again when needed.")
                    .setPositiveButton("Clear") { _, _ -> RoadAssist.clearSaved(); mapTv.text = RoadAssist.savedInfo() }.setNegativeButton("Cancel", null).show()
            }), ui.lp(6))
        box.addView(ui.check("Download highway maps ahead automatically", Prefs.mapAuto).apply { setOnCheckedChangeListener { _, v -> Prefs.mapAuto = v } }, ui.lp(4))
        box.addView(ui.tv("Highways, ramps and hazards are saved for the whole radius, so the limit is known before you get there and without internet. Town streets are saved in detail around where you drive. A 200 km map has many areas and can take an hour or more; it carries on in the background and continues where it stopped if interrupted.", 11.5f, Ui.DIM), ui.lp(4))
        box.addView(ui.section("Announcements", Ic.VOICE))
        logBox = ui.vbox(); box.addView(logBox)

        box.addView(ui.section("Settings", Ic.SLIDERS))
        fun sw(label: String, v: Boolean, set: (Boolean) -> Unit) = ui.check(label, v).apply {
            setOnCheckedChangeListener { _, on -> set(on); if (label.startsWith("Road assistant")) {
                if (on) { Gps.start(a); ObdService.start(a) } else if (!Prefs.backgroundWatch && ObdManager.state == ObdManager.State.DISCONNECTED) a.stopService(android.content.Intent(a, ObdService::class.java))
            } }
        }
        box.addView(sw("Road assistant (works in background)", Prefs.roadAssist) { Prefs.roadAssist = it }, ui.lp(4))
        val srcNames = listOf("auto" to "Automatic – ELM327 when connected, else GPS", "gps" to "GPS only", "obd" to "ELM327 adapter (OBD speed)")
        val srcCard = ui.iconCard(Ic.PLUG, Ui.CYAN, "Speed source", "Using now: ${RoadAssist.speedSourceText()}")
        box.addView(srcCard, ui.lp(4))
        box.addView(ui.btn("Speed source: " + srcNames.first { it.first == Prefs.roadSpeedSrc }.second, Ui.CARD) {}.also { b ->
            b.setOnClickListener {
                android.app.AlertDialog.Builder(a).setTitle("Road assistant speed from")
                    .setSingleChoiceItems(srcNames.map { it.second }.toTypedArray(), srcNames.indexOfFirst { it.first == Prefs.roadSpeedSrc }) { d, i ->
                        Prefs.roadSpeedSrc = srcNames[i].first; b.text = "Speed source: " + srcNames[i].second; d.dismiss()
                        if (i != 1 && ObdManager.state != ObdManager.State.CONNECTED) ui.toast("Connect your ELM327 on the Connect page to use its speed")
                    }.setNegativeButton("Close", null).show()
            }
        }, ui.lp(4))
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

    private fun explainLimit() {
        val i = RoadAssist.info
        if (i == null) { ui.toast("No road matched yet"); return }
        val lim = i.limit
        val src = when (i.source) { "you" -> "Set by you"; "osm" -> "Marked on the map"; "cam" -> "Read by the camera"; else -> "Estimated" }
        val msg = StringBuilder()
        msg.append(if (lim != null) "${Units.sStr(lim.toDouble())} · $src · ${i.conf} % sure\n\n" else "No limit data for this road.\n\n")
        if (i.basis.isNotEmpty()) msg.append("Why: ${i.basis}.\n\n")
        msg.append("Road: ${i.highway.replace('_', ' ')}")
        if (i.lanes > 0) msg.append(" · ${i.lanes} lanes")
        if (i.oneway) msg.append(" · one-way")
        if (i.lit) msg.append(" · lit")
        if (i.urban) msg.append(" · built-up area")
        if (i.estimated) msg.append("\n\nEstimates come from nearby marked roads or typical limits for the road type. If you know the real limit, set it once and I will remember it for this road.")
        val b = AlertDialog.Builder(a).setTitle(if (lim != null) "Speed limit ${Units.sStr(lim.toDouble())}" else "Speed limit").setMessage(msg.toString())
            .setNegativeButton("Close", null).setNeutralButton("Correct") { _, _ -> correctLimit() }
        if (i.estimated && lim != null && i.wayId != 0L) b.setPositiveButton("Confirm ${Units.sStr(lim.toDouble())}") { _, _ ->
            RoadAssist.setLimitFix(i.wayId, lim); ui.toast("Saved – this road now uses ${Units.sStr(lim.toDouble())}")
        }
        b.show()
    }

    private fun correctLimit() {
        val i = RoadAssist.info
        if (i == null || i.wayId == 0L) { ui.toast("No road matched yet"); return }
        val opts = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
        val items = opts.map { Units.sStr(it.toDouble()) }.toMutableList()
        if (i.source == "you") items.add("Remove my correction")
        if (i.source == "cam") items.add("Forget the camera reading")
        AlertDialog.Builder(a).setTitle("Real speed limit on ${i.road.ifEmpty { "this road" }}").setItems(items.toTypedArray()) { _, k ->
            if (k < opts.size) { RoadAssist.setLimitFix(i.wayId, opts[k]); ui.toast("Saved – I will use ${Units.sStr(opts[k].toDouble())} here") }
            else if (i.source == "cam") { RoadAssist.forgetCamera(i.wayId); ui.toast("Camera reading forgotten") }
            else { RoadAssist.setLimitFix(i.wayId, null); ui.toast("Correction removed") }
        }.show()
    }

    private fun reportHazard() = HazardQuick.show(a)

    private fun toggleDriveLog() {
        if (!DriveLog.recording) {
            if (!Prefs.roadAssist) { ui.toast("Turn on the road assistant first"); return }
            DriveLog.start(); ui.toast("Recording. Keep the Road assistant on, drive, and tap Mark problem when something is wrong.")
        } else {
            val sum = DriveLog.summary()
            val txt = DriveLog.stop()
            if (txt == null) { ui.toast("No GPS points were recorded"); return }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
            val path = Exporter.writeText(a, "carobd_drive_$stamp.txt", "text/plain", txt)
            try { (a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Drive report", sum)) } catch (_: Throwable) {}
            ui.toast(if (path != null) "Saved to $path. Summary copied – paste it in the chat." else "Summary copied. File export failed – check storage permission.")
        }
        refresh()
    }

    override fun refresh() {
        if (!::sign.isInitialized) return
        val r = RoadAssist
        if (::recBtn.isInitialized) recBtn.text = if (DriveLog.recording) "Stop & save (${DriveLog.seconds() / 60}:${"%02d".format(DriveLog.seconds() % 60)})" else "Record this drive"
        if (::dlBar.isInitialized) {
            if (r.dlBusy || r.status.startsWith("Downloading")) {
                dlBar.visibility = View.VISIBLE; dlTv.visibility = View.VISIBLE
                dlBar.progress = r.dlPct; dlTv.text = r.dlText.ifEmpty { r.status }
            } else { dlBar.visibility = View.GONE; dlTv.visibility = View.GONE }
        }
        val i = r.info
        val spd = r.speedKmh
        sign.limit = i?.limit; sign.estimated = i?.estimated == true
        sign.over = i?.limit != null && spd > i.limit + Prefs.speedMargin
        spdTv.text = "%.0f".format(Units.spd(spd)); spdTv.setTextColor(if (sign.over) Ui.BAD else Ui.WHITE)
        val lm = i?.limit
        if (lm != null) {
            val mg = Prefs.speedMargin
            speedBar.set(Spec(0.0, lm + mg.toDouble(), 0.0, lm + mg + 10.0, 0.0, maxOf(120.0, lm * 1.6)), spd, if (spd <= lm + mg) 0 else if (spd <= lm + mg + 10) 1 else 2)
        } else speedBar.set(null, null, 0)
        basisTv.text = if (i == null) "" else (if (i.limit != null) "${i.conf} % sure · " else "") + i.basis + "  (tap the sign for details)"
        nextBox.removeAllViews()
        r.next?.let { n -> nextBox.addView(ui.iconCard(Ic.FLAG, Ui.CYAN, "Limit changes to ${Units.sStr(n.limit.toDouble())}", "in about ${n.dist} m" + (if (n.road.isNotEmpty()) " · ${n.road}" else "") + if (n.estimated) " · estimated" else "")) }
        val mins = r.statSec / 60.0
        statsTv.text = if (r.statSec < 10) "Moving time, over-limit share and alerts appear while you drive." else
            "%.0f min moving · top speed %s\nOver the limit %s of the time%s · %d hazard alerts".format(mins, Units.sStr(r.topSpd),
                if (r.limSec > 5) "%.0f %%".format(r.overSec * 100 / r.limSec) else "–", if (r.maxOver > 0) " · worst +${Units.sStr(r.maxOver)}" else "", r.alertsN)
        compass.deg = r.heading
        headTv.text = if (r.heading != null) "${RoadAssist.compass(r.heading)} · ${r.heading!!.toInt()}°" else "—"
        roadTv.text = when {
            !Prefs.roadAssist -> "Road assistant is off"
            i == null -> if (r.lastFix == 0L) Gps.statusText(a) else r.status
            else -> listOf(i.ref, i.road).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { i.highway.replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }
        }
        subTv.text = if (i == null) "" else i.highway.replace('_', ' ') + (if (i.lanes > 0) " · ${i.lanes} lanes" else "") + (if (i.oneway) " · one-way" else "") + " · " + if (i.limit == null) "no limit data" else if (i.estimated) "limit ${Units.sStr(i.limit!!.toDouble())} (estimated)" else if (i.source == "you") "limit ${Units.sStr(i.limit!!.toDouble())} (set by you)" else if (i.source == "cam") "limit ${Units.sStr(i.limit!!.toDouble())} (read from a sign)" else "limit ${Units.sStr(i.limit!!.toDouble())}"
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
