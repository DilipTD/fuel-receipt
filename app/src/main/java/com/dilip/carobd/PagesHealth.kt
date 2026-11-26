package com.dilip.carobd

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import kotlin.math.*

private fun Context.px(v: Float) = v * resources.displayMetrics.density

/* ======================= Health model ======================= */

object HealthScore {
    class Item(val name: String, val ic: Ic, val status: Int, val detail: String)   // status: -1 unknown, 0 ok, 1 warn, 2 bad

    class Result(val items: List<Item>, val score: Int)

    /** Live result merged with the last known readings, so the score survives restarts until a new reading clears a problem. */
    fun compute(): Result {
        val live = computeLive()
        val snap = try { org.json.JSONObject(Prefs.healthSnap) } catch (_: Exception) { org.json.JSONObject() }
        val out = ArrayList<Item>()
        val nsnap = org.json.JSONObject()
        for (i in live.items) {
            if (i.status >= 0) {
                out += i
                nsnap.put(i.name, org.json.JSONObject().put("s", i.status).put("d", i.detail))
            } else {
                val o = snap.optJSONObject(i.name)
                if (o != null) {
                    out += Item(i.name, i.ic, o.optInt("s", -1), o.optString("d") + " · last reading")
                    nsnap.put(i.name, o)
                } else out += i
            }
        }
        val s = nsnap.toString()
        if (s != Prefs.healthSnap) Prefs.healthSnap = s
        val known = out.count { it.status >= 0 }
        var pen = 0
        for (it in out) pen += when (it.status) { 2 -> 22; 1 -> 9; else -> 0 }
        return Result(out, if (known == 0) 0 else (100 - pen).coerceIn(5, 100))
    }

    private fun computeLive(): Result {
        val m = ObdManager
        val v = m.values
        val veh = m.vehicle
        val items = mutableListOf<Item>()

        val cool = v[0x05]
        items += when {
            cool == null -> Item("Cooling", Ic.THERM, -1, "No live data")
            cool >= Prefs.coolantMax -> Item("Cooling", Ic.THERM, 2, "%.0f °C – overheating".format(cool))
            cool >= Prefs.coolantMax - 8 -> Item("Cooling", Ic.THERM, 1, "%.0f °C – running hot".format(cool))
            else -> Item("Cooling", Ic.THERM, 0, "%.0f °C".format(cool))
        }

        val bv = m.batteryV ?: v[0x42]
        items += when {
            bv == null -> Item("Battery", Ic.BATTERY, -1, "No live data")
            m.engineOn && bv < Prefs.chargeMinV -> Item("Battery", Ic.BATTERY, 2, "%.1f V – not charging".format(bv))
            m.engineOn && bv > Prefs.chargeMaxV -> Item("Battery", Ic.BATTERY, 2, "%.1f V – overcharging".format(bv))
            !m.engineOn && bv < 11.8 -> Item("Battery", Ic.BATTERY, 2, "%.1f V – very low".format(bv))
            !m.engineOn && bv < 12.3 -> Item("Battery", Ic.BATTERY, 1, "%.1f V – low".format(bv))
            else -> Item("Battery", Ic.BATTERY, 0, "%.1f V".format(bv))
        }

        val fuel = v[0x2F]
        val fn = if (veh.isEv) "Fuel" else "Fuel"
        val low = if (veh.isEv) "charge soon" else "refuel now"
        items += when {
            fuel == null -> Item(fn, if (veh.isEv) Ic.BATTERY else Ic.FUEL, -1, if (veh.isEv) "No SoC data (set a custom PID)" else "No live data")
            fuel <= Prefs.lowFuelPct -> Item(fn, Ic.FUEL, 2, "%.0f %% – $low".format(fuel))
            fuel <= 25 -> Item(fn, Ic.FUEL, 1, "%.0f %% – getting low".format(fuel))
            else -> Item(fn, Ic.FUEL, 0, "%.0f %%".format(fuel))
        }

        val cat = v[0x3C]
        items += when {
            cat == null -> Item("Catalyst", Ic.WIND, -1, "No live data")
            cat > 950 -> Item("Catalyst", Ic.WIND, 2, "%.0f °C – too hot".format(cat))
            cat > 850 -> Item("Catalyst", Ic.WIND, 1, "%.0f °C – hot".format(cat))
            else -> Item("Catalyst", Ic.WIND, 0, "%.0f °C".format(cat))
        }

        val raw = m.monitorRaw
        val mil = raw != null && raw.isNotEmpty() && (raw[0] and 0x80) != 0
        val nDtc = if (raw != null && raw.isNotEmpty()) raw[0] and 0x7F else 0
        val recent = Db.dtcHistory(veh.id).filter { it.t > System.currentTimeMillis() - 30 * 86_400_000L }
            .map { it.code }.distinct().size
        items += when {
            mil -> Item("Fault codes", Ic.WARN, 2, "Check-engine light on · $nDtc code(s)")
            recent > 0 -> Item("Fault codes", Ic.WARN, 1, "$recent code(s) seen in last 30 days")
            raw == null -> Item("Fault codes", Ic.WARN, -1, "Not scanned yet")
            else -> Item("Fault codes", Ic.WARN, 0, "No active codes")
        }

        val due = Maint.dueList(veh)
        val over = due.count { it.overdue }; val soon = due.count { it.soon }
        items += when {
            over > 0 -> Item("Service", Ic.WRENCH, 2, "$over overdue")
            soon > 0 -> Item("Service", Ic.WRENCH, 1, "$soon due soon")
            else -> Item("Service", Ic.WRENCH, 0, "All up to date")
        }

        val em = EmissionAi.analyse()
        if (em.applicable) items += when {
            !em.known -> Item("Emissions", Ic.LEAF, -1, "Not read yet")
            em.level == 2 -> Item("Emissions", Ic.LEAF, 2, "Not ready – check-engine light on")
            em.level == 1 -> Item("Emissions", Ic.LEAF, 1, "${em.incomplete} monitor(s) pending")
            else -> Item("Emissions", Ic.LEAF, 0, "Ready for test")
        }

        val intake = v[0x0F]
        items += when {
            intake == null -> Item("Intake air", Ic.WIND, -1, "No live data")
            intake > 65 -> Item("Intake air", Ic.WIND, 1, "%.0f °C – heat soak".format(intake))
            else -> Item("Intake air", Ic.WIND, 0, "%.0f °C".format(intake))
        }

        val week = Db.trips(veh.id, System.currentTimeMillis() - 7 * 86_400_000L)
        val km = week.sumOf { it.distanceKm }
        val sc = if (km > 1) week.sumOf { it.score * it.distanceKm } / km else null
        items += when {
            sc == null -> Item("Driving", Ic.CAR, -1, "No trips this week")
            sc < 55 -> Item("Driving", Ic.CAR, 2, "Score %.0f – rough".format(sc))
            sc < 75 -> Item("Driving", Ic.CAR, 1, "Score %.0f".format(sc))
            else -> Item("Driving", Ic.CAR, 0, "Score %.0f – smooth".format(sc))
        }

        val known = items.count { it.status >= 0 }
        val pen = items.sumOf { when (it.status) { 2 -> 22; 1 -> 9; else -> 0 }.toInt() }
        val score = if (known == 0) 0 else (100 - pen).coerceIn(5, 100)
        return Result(items, score)
    }
}

/* ======================= Top-view car diagram ======================= */

class CarHealthView(ctx: Context) : View(ctx) {
    private var items: List<HealthScore.Item> = emptyList()
    private var score = 0
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.px(1.2f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C8D3DE"); textSize = ctx.px(11f) }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7F8D9B"); textSize = ctx.px(10f) }
    private val rect = RectF()

    fun set(i: List<HealthScore.Item>, s: Int) { items = i; score = s; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val wd = MeasureSpec.getSize(w)
        setMeasuredDimension(wd, (wd * 1.3f).toInt())
    }

    private fun col(s: Int) = when (s) {
        0 -> Color.parseColor("#3DDC97"); 1 -> Color.parseColor("#FFC247"); 2 -> Color.parseColor("#FF4D4D")
        else -> Color.parseColor("#5C6B7A")
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // background
        body.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#17212B"), Color.parseColor("#0E151C"), Shader.TileMode.CLAMP)
        rect.set(0f, 0f, w, h); c.drawRoundRect(rect, context.px(20f), context.px(20f), body); body.shader = null

        val cx = w / 2f
        val cw = w * 0.31f           // half-width of the SUV body
        val top = h * 0.06f; val bot = h * 0.94f
        val H = bot - top
        fun y(f: Float) = top + H * f
        fun px(v: Float) = context.px(v)
        // ground shadow
        body.color = Color.argb(70, 0, 0, 0)
        rect.set(cx - cw * 1.25f, y(0.02f), cx + cw * 1.25f, y(1.0f) + px(6f)); c.drawRoundRect(rect, px(30f), px(30f), body)
        // tyres (big SUV tyres with tread lines) – drawn first so the body overlaps the inner half
        for (wy in listOf(y(0.17f), y(0.80f))) for (side in listOf(-1, 1)) {
            val wx = cx + side * (cw + px(1f))
            body.color = Color.parseColor("#06090C")
            rect.set(wx - px(10f), wy - px(30f), wx + px(10f), wy + px(30f)); c.drawRoundRect(rect, px(7f), px(7f), body)
            line.color = Color.parseColor("#2A3541"); line.strokeWidth = px(1f)
            var ty = wy - px(26f)
            while (ty < wy + px(27f)) { c.drawLine(wx - px(8f), ty, wx + px(8f), ty + px(3f), line); ty += px(6f) }
        }
        // body shell – boxy SUV: short overhangs, flat sides, upright tail
        val p = Path()
        p.moveTo(cx - cw * 0.78f, y(0.015f))
        p.quadTo(cx, y(-0.008f), cx + cw * 0.78f, y(0.015f))
        p.cubicTo(cx + cw * 0.98f, y(0.03f), cx + cw * 1.0f, y(0.07f), cx + cw, y(0.13f))
        p.lineTo(cx + cw, y(0.90f))
        p.cubicTo(cx + cw, y(0.96f), cx + cw * 0.95f, y(0.985f), cx + cw * 0.80f, y(0.99f))
        p.lineTo(cx - cw * 0.80f, y(0.99f))
        p.cubicTo(cx - cw * 0.95f, y(0.985f), cx - cw, y(0.96f), cx - cw, y(0.90f))
        p.lineTo(cx - cw, y(0.13f))
        p.cubicTo(cx - cw * 1.0f, y(0.07f), cx - cw * 0.98f, y(0.03f), cx - cw * 0.78f, y(0.015f))
        p.close()
        body.shader = LinearGradient(cx - cw, 0f, cx + cw, 0f,
            intArrayOf(Color.parseColor("#22344A"), Color.parseColor("#3F5B79"), Color.parseColor("#22344A")), null, Shader.TileMode.CLAMP)
        c.drawPath(p, body); body.shader = null
        line.color = Color.parseColor("#6B8CAB"); line.strokeWidth = px(1.8f); c.drawPath(p, line)
        // wheel-arch cut-outs
        body.color = Color.parseColor("#0C131A")
        for (wy in listOf(y(0.17f), y(0.80f))) for (side in listOf(-1, 1)) {
            val ax = cx + side * cw
            rect.set(ax - px(if (side < 0) -1f else 14f), wy - px(28f), ax + px(if (side < 0) -14f else 1f), wy + px(28f))
            c.drawRoundRect(rect, px(9f), px(9f), body)
        }
        // bonnet: centre crease + power bulge + grille slot
        body.color = Color.parseColor("#2E4660")
        rect.set(cx - cw * 0.80f, y(0.045f), cx + cw * 0.80f, y(0.28f)); c.drawRoundRect(rect, px(12f), px(12f), body)
        line.color = Color.parseColor("#5B7B99"); line.strokeWidth = px(1f)
        c.drawRoundRect(rect, px(12f), px(12f), line)
        c.drawLine(cx - cw * 0.22f, y(0.06f), cx - cw * 0.30f, y(0.27f), line)
        c.drawLine(cx + cw * 0.22f, y(0.06f), cx + cw * 0.30f, y(0.27f), line)
        body.color = Color.parseColor("#0B1117")
        rect.set(cx - cw * 0.40f, y(0.012f), cx + cw * 0.40f, y(0.03f)); c.drawRoundRect(rect, px(4f), px(4f), body)
        // head-lights (LED strips) and tail-lights
        body.color = Color.parseColor("#E8F6FF")
        for (side in listOf(-1, 1)) {
            val hx = cx + side * cw * 0.66f
            rect.set(hx - cw * 0.20f, y(0.018f), hx + cw * 0.20f, y(0.040f)); c.drawRoundRect(rect, px(4f), px(4f), body)
        }
        body.color = Color.parseColor("#E5354A")
        for (side in listOf(-1, 1)) {
            val tx = cx + side * cw * 0.72f
            rect.set(tx - cw * 0.17f, y(0.955f), tx + cw * 0.17f, y(0.985f)); c.drawRoundRect(rect, px(4f), px(4f), body)
        }
        // windscreen
        body.color = Color.parseColor("#0D1722")
        val ws = Path().apply {
            moveTo(cx - cw * 0.82f, y(0.40f)); lineTo(cx - cw * 0.68f, y(0.285f))
            lineTo(cx + cw * 0.68f, y(0.285f)); lineTo(cx + cw * 0.82f, y(0.40f)); close()
        }
        c.drawPath(ws, body)
        body.color = Color.argb(40, 120, 200, 255)
        c.drawLine(cx - cw * 0.40f, y(0.30f), cx - cw * 0.55f, y(0.385f), line.apply { color = Color.argb(60, 160, 220, 255) })
        // roof (long, flat) with roof rails and sunroof
        rect.set(cx - cw * 0.84f, y(0.40f), cx + cw * 0.84f, y(0.74f))
        body.color = Color.parseColor("#34506C"); c.drawRoundRect(rect, px(10f), px(10f), body)
        line.color = Color.parseColor("#7FA3C4"); line.strokeWidth = px(1f); c.drawRoundRect(rect, px(10f), px(10f), line)
        // roof rails (silver) + cross bars
        line.color = Color.parseColor("#B8C7D6"); line.strokeWidth = px(3f)
        c.drawLine(cx - cw * 0.76f, y(0.43f), cx - cw * 0.76f, y(0.71f), line)
        c.drawLine(cx + cw * 0.76f, y(0.43f), cx + cw * 0.76f, y(0.71f), line)
        line.strokeWidth = px(1.6f)
        c.drawLine(cx - cw * 0.76f, y(0.47f), cx + cw * 0.76f, y(0.47f), line)
        c.drawLine(cx - cw * 0.76f, y(0.67f), cx + cw * 0.76f, y(0.67f), line)
        // rear glass + tailgate
        body.color = Color.parseColor("#0D1722")
        val rg = Path().apply {
            moveTo(cx - cw * 0.82f, y(0.74f)); lineTo(cx + cw * 0.82f, y(0.74f))
            lineTo(cx + cw * 0.70f, y(0.83f)); lineTo(cx - cw * 0.70f, y(0.83f)); close()
        }
        c.drawPath(rg, body)
        body.color = Color.parseColor("#2B4259")
        rect.set(cx - cw * 0.80f, y(0.845f), cx + cw * 0.80f, y(0.945f)); c.drawRoundRect(rect, px(8f), px(8f), body)
        line.color = Color.parseColor("#5B7B99"); line.strokeWidth = px(1f); c.drawRoundRect(rect, px(8f), px(8f), line)
        // door lines
        line.color = Color.argb(110, 12, 19, 26); line.strokeWidth = px(1f)
        c.drawLine(cx - cw, y(0.40f), cx - cw * 0.84f, y(0.40f), line); c.drawLine(cx + cw * 0.84f, y(0.40f), cx + cw, y(0.40f), line)
        c.drawLine(cx - cw, y(0.58f), cx - cw * 0.84f, y(0.58f), line); c.drawLine(cx + cw * 0.84f, y(0.58f), cx + cw, y(0.58f), line)
        c.drawLine(cx - cw, y(0.74f), cx - cw * 0.84f, y(0.74f), line); c.drawLine(cx + cw * 0.84f, y(0.74f), cx + cw, y(0.74f), line)
        // side mirrors
        body.color = Color.parseColor("#2E4660")
        for (side in listOf(-1, 1)) {
            val mx = cx + side * (cw + px(7f))
            rect.set(mx - px(8f), y(0.335f), mx + px(8f), y(0.375f)); c.drawRoundRect(rect, px(5f), px(5f), body)
            line.color = Color.parseColor("#6B8CAB"); c.drawRoundRect(rect, px(5f), px(5f), line)
        }
        // roof area used for the health ring
        rect.set(cx - cw * 0.66f, y(0.49f), cx + cw * 0.66f, y(0.65f))

        // overall score in the roof
        val sc = if (items.any { it.status >= 0 }) score else -1
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.px(7f); strokeCap = Paint.Cap.ROUND }
        val rr = cw * 0.36f; val ry = (rect.top + rect.bottom) / 2f
        ring.color = Color.parseColor("#1B2B3B"); c.drawCircle(cx, ry, rr, ring)
        if (sc >= 0) {
            ring.color = if (sc >= 80) col(0) else if (sc >= 55) col(1) else col(2)
            c.drawArc(cx - rr, ry - rr, cx + rr, ry + rr, -90f, 360f * sc / 100f, false, ring)
        }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = context.px(24f); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        c.drawText(if (sc >= 0) "$sc" else "--", cx, ry + context.px(6f), big)
        val s2 = Paint(sub).apply { textAlign = Paint.Align.CENTER }
        c.drawText("HEALTH", cx, ry + context.px(20f), s2)

        // component dots + callouts  (name -> (x frac, y frac), label side)
        fun place(name: String, fx: Float, fy: Float, left: Boolean) {
            val it = items.firstOrNull { x -> x.name == name } ?: return
            val x = cx + fx * cw; val y = top + (bot - top) * fy
            val tx = if (left) context.px(10f) else w - context.px(10f)
            val pt = Paint(txt).apply { textAlign = if (left) Paint.Align.LEFT else Paint.Align.RIGHT }
            val ps = Paint(sub).apply { textAlign = if (left) Paint.Align.LEFT else Paint.Align.RIGHT }
            line.color = Color.argb(120, Color.red(col(it.status)), Color.green(col(it.status)), Color.blue(col(it.status)))
            line.strokeWidth = context.px(1f)
            val ex = if (left) context.px(10f) + max(pt.measureText(it.name), ps.measureText(it.detail.take(26))) + context.px(4f)
                     else w - context.px(10f) - max(pt.measureText(it.name), ps.measureText(it.detail.take(26))) - context.px(4f)
            c.drawLine(ex, y, x, y, line)
            c.drawText(it.name, tx, y - context.px(3f), pt)
            c.drawText(it.detail.take(26), tx, y + context.px(10f), ps)
            // pulsing halo for warnings
            val pulse = if (it.status >= 1) 0.5f + 0.5f * sin(System.currentTimeMillis() / (if (it.status == 2) 180.0 else 350.0)).toFloat() else 0f
            dot.color = Color.argb((70 + 90 * pulse).toInt(), Color.red(col(it.status)), Color.green(col(it.status)), Color.blue(col(it.status)))
            c.drawCircle(x, y, context.px(10f + 4f * pulse), dot)
            dot.color = col(it.status); c.drawCircle(x, y, context.px(6f), dot)
            dot.color = Color.WHITE; c.drawCircle(x, y, context.px(2f), dot)
        }
        place("Cooling", -0.50f, 0.075f + 0.06f, true)
        place("Battery", 0.50f, 0.075f + 0.06f, false)
        place("Intake air", -0.25f, 0.20f, true)
        place("Fuel", 0.0f, 0.89f, false)
        place("Catalyst", -0.35f, 0.80f, true)
        place("Fault codes", 0.30f, 0.50f - 0.14f, false)
        place("Service", -0.55f, 0.55f, true)
        place("Driving", 0.55f, 0.66f, false)

        if (items.any { it.status >= 1 }) postInvalidateOnAnimation()
    }
}

/* ======================= Health page ======================= */

class HealthPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    override fun onShow() {
        box.removeAllViews()
        val r = HealthScore.compute()
        box.addView(ui.section("Car health", Ic.SHIELD))
        val cv = CarHealthView(a); cv.set(r.items, r.score)
        box.addView(cv, ui.lp(6))
        val worst = r.items.filter { it.status >= 1 }.sortedByDescending { it.status }
        if (worst.isEmpty() && r.items.any { it.status >= 0 })
            box.addView(ui.iconCard(Ic.CHECK, Ui.GOOD, "Everything looks healthy", "No warnings from live data, fault codes or service schedule."), ui.lp(10))
        for (w in worst) {
            val col = if (w.status == 2) Ui.BAD else Ui.WARN
            box.addView(ui.iconCard(w.ic, col, w.name, w.detail) {
                a.show(when (w.name) { "Service" -> "Maint"; "Battery" -> "Battery"; "Driving" -> "Analysis"; else -> "Diag" })
            }, ui.lp(8))
        }
        if (r.items.all { it.status < 0 })
            box.addView(ui.card("Connect to the car to fill in live readings. Service and fault history are used even when offline."), ui.lp(10))
        box.addView(ui.section("All systems", Ic.GRID))
        for (i in r.items) {
            val col = when (i.status) { 0 -> Ui.GOOD; 1 -> Ui.WARN; 2 -> Ui.BAD; else -> Ui.DIM }
            box.addView(ui.iconCard(i.ic, col, i.name, i.detail), ui.lp(6))
        }
        if (r.items.any { it.detail.endsWith("last reading") })
            box.addView(ui.btn("Clear stored readings", Ui.CARD) { Prefs.healthSnap = ""; onShow() }, ui.lp(12))
        box.addView(ui.tv("Readings are kept until the car reports a new value or you clear them.", 11.5f, Ui.DIM).apply { gravity = android.view.Gravity.CENTER }, ui.lp(8))
        box.addView(ui.tv(AppInfo.credit, 11f, Ui.DIM).apply { gravity = android.view.Gravity.CENTER }, ui.lp(16))
    }

    override fun refresh() { if (ObdManager.state == ObdManager.State.CONNECTED) onShow() }
}

/* ======================= Driving coach ======================= */

object Coach {
    /** One short, live tip from current data or null. */
    fun tip(): Pair<Ic, String>? {
        val m = ObdManager
        val v = m.values
        val spd = v[0x0D] ?: return null
        val rpm = v[0x0C] ?: 0.0
        val cool = v[0x05]
        val veh = m.vehicle
        return when {
            cool != null && cool < 60 && rpm > 3000 -> Ic.THERM to "Engine is still cold – keep revs low until it warms up."
            spd < 1 && m.engineOn && m.engine.current?.let { it.idleS > 60 } == true -> Ic.FUEL to "Stopped for a while – switch off to save fuel."
            spd > veh.speedLimit -> Ic.WARN to "Above your ${veh.speedLimit} km/h limit – ease off the throttle."
            rpm > veh.rpmLimit -> Ic.ENGINE to "High revs – shift up or lift off."
            spd in 30.0..90.0 && rpm in 1200.0..2300.0 -> Ic.LEAF to "Smooth cruising – this is your most economical band."
            spd > 95 -> Ic.WIND to "Fuel use rises sharply above ~90 km/h."
            (v[0x11] ?: 0.0) > 85 && spd < 40 -> Ic.BOLT to "Heavy throttle at low speed – squeeze it gently."
            else -> null
        }
    }
}

/* ======================= Nearby ======================= */

fun Page.openNearby(query: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try { a.startActivity(intent) } catch (_: Exception) { ui.toast("No maps app found") }
}

/* ======================= PDF report ======================= */

object PdfReport {
    fun create(ctx: Context, days: Int): String? {
        val v = ObdManager.vehicle
        val u = v.spec.unit
        val since = System.currentTimeMillis() - days * 86_400_000L
        val trips = Db.trips(v.id, since).sortedBy { it.start }
        val km = trips.sumOf { it.distanceKm }
        val fuel = trips.sumOf { it.fuel }
        val cost = trips.sumOf { it.cost }
        val hrs = trips.sumOf { it.durationS } / 3600.0
        val idleP = trips.sumOf { it.idleS } / 3600.0 / max(hrs, 0.01) * 100
        val score = if (km > 0) trips.sumOf { it.score * it.distanceKm } / km else 0.0
        val per100 = 100.0 / max(km, 1.0)

        val doc = PdfDocument()
        val pi = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = doc.startPage(pi)
        val c = page.canvas
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = Color.parseColor("#0F1A24"); c.drawRect(0f, 0f, 595f, 110f, p)
        p.color = Color.parseColor("#2FA8FF"); c.drawRect(0f, 106f, 595f, 110f, p)
        p.color = Color.WHITE; p.textSize = 26f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(AppInfo.NAME + " – Driving report", 32f, 52f, p)
        p.textSize = 13f; p.typeface = Typeface.DEFAULT; p.color = Color.parseColor("#B8C6D4")
        val fmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
        c.drawText("${v.title}   ·   last $days days (${fmt.format(java.util.Date(since))} – ${fmt.format(java.util.Date())})", 32f, 78f, p)

        // KPI cards
        val kp = listOf(
            "Distance" to "%.0f km".format(km), "Trips" to "${trips.size}",
            "Economy" to (if (fuel > 0.01) "%.1f km/$u".format(km / fuel) else "--"), "Fuel cost" to "₹%.0f".format(cost)
        )
        var x = 32f
        for ((k, vv) in kp) {
            p.color = Color.parseColor("#EAF2F9"); c.drawRoundRect(x, 132f, x + 124f, 204f, 12f, 12f, p)
            p.color = Color.parseColor("#5B6B7A"); p.textSize = 11f; c.drawText(k, x + 12f, 154f, p)
            p.color = Color.parseColor("#0F1A24"); p.textSize = 20f; p.typeface = Typeface.DEFAULT_BOLD; c.drawText(vv, x + 12f, 186f, p)
            p.typeface = Typeface.DEFAULT; x += 133f
        }

        // score ring
        val sx = 100f; val sy = 300f
        p.style = Paint.Style.STROKE; p.strokeWidth = 14f; p.color = Color.parseColor("#DDE6EE"); c.drawCircle(sx, sy, 52f, p)
        p.color = if (score >= 75) Color.parseColor("#2EAD6B") else if (score >= 55) Color.parseColor("#E0A21B") else Color.parseColor("#D64545")
        p.strokeCap = Paint.Cap.ROUND
        c.drawArc(sx - 52f, sy - 52f, sx + 52f, sy + 52f, -90f, (360f * score / 100f).toFloat(), false, p)
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#0F1A24"); p.textSize = 30f; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
        c.drawText("%.0f".format(score), sx, sy + 10f, p)
        p.textSize = 11f; p.typeface = Typeface.DEFAULT; p.color = Color.parseColor("#5B6B7A"); c.drawText("DRIVER SCORE", sx, sy + 28f, p)
        p.textAlign = Paint.Align.LEFT

        // summary lines
        p.color = Color.parseColor("#0F1A24"); p.textSize = 13f
        var y = 262f
        val lines = listOf(
            "Driving time: %.1f h (idle %.0f %%)".format(hrs, idleP),
            "Fuel used: %.1f $u".format(fuel),
            "Average speed: %.0f km/h".format(km / max(hrs, 0.01)),
            "Max speed: %.0f km/h".format(trips.maxOfOrNull { it.maxSpeed } ?: 0.0),
            "CO2: %.1f kg".format(trips.sumOf { it.co2Kg }),
            "Odometer: %.0f km".format(v.odoKm)
        )
        for (l in lines) { c.drawText(l, 220f, y, p); y += 22f }

        // events bar chart
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 15f; c.drawText("Driving events", 32f, 430f, p); p.typeface = Typeface.DEFAULT
        val ev = listOf(
            "Rapid acceleration" to trips.sumOf { it.rapidAcc }, "Hard braking" to trips.sumOf { it.hardBrake },
            "Sudden braking" to trips.sumOf { it.suddenBrake }, "Harsh cornering" to trips.sumOf { it.corner },
            "Overspeed" to trips.sumOf { it.overspeed }, "Over-rev" to trips.sumOf { it.overRev }, "Long idle" to trips.sumOf { it.longIdle }
        )
        val mx = max(ev.maxOf { it.second }, 1)
        var by = 452f
        for ((name, n) in ev) {
            p.textSize = 12f; p.color = Color.parseColor("#33414E"); c.drawText(name, 32f, by + 12f, p)
            p.color = Color.parseColor("#E3EBF2"); c.drawRoundRect(170f, by, 440f, by + 16f, 8f, 8f, p)
            p.color = if (name.contains("Sudden")) Color.parseColor("#D64545") else Color.parseColor("#2FA8FF")
            c.drawRoundRect(170f, by, 170f + max(270f * n / mx, if (n > 0) 12f else 0f), by + 16f, 8f, 8f, p)
            p.color = Color.parseColor("#0F1A24"); c.drawText("$n  (%.1f / 100 km)".format(n * per100), 450f, by + 12f, p)
            by += 26f
        }

        // service status
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 15f; c.drawText("Maintenance", 32f, 660f, p); p.typeface = Typeface.DEFAULT
        p.textSize = 12f
        var my = 682f
        val dues = Maint.dueList(v).take(6)
        if (dues.isEmpty()) c.drawText("No service items configured.", 32f, my, p)
        for (d in dues) {
            p.color = if (d.overdue) Color.parseColor("#D64545") else if (d.soon) Color.parseColor("#E0A21B") else Color.parseColor("#2EAD6B")
            c.drawCircle(38f, my - 4f, 5f, p)
            p.color = Color.parseColor("#0F1A24"); c.drawText("${d.item.name}  –  ${d.text}", 52f, my, p); my += 20f
        }

        p.color = Color.parseColor("#7F8D9B"); p.textSize = 10f
        c.drawText("Generated ${java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())} by ${AppInfo.NAME} v${AppInfo.VERSION} · Software developed by ${AppInfo.DEVELOPER}", 32f, 818f, p)
        doc.finishPage(page)

        val name = "CarOBD_report_${java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault()).format(java.util.Date())}.pdf"
        val os = Exporter.open(ctx, name, "application/pdf")
        return try {
            if (os == null) null else { os.use { doc.writeTo(it) }; "Downloads/CarOBD/$name" }
        } catch (_: Exception) { null } finally { doc.close() }
    }
}
