package com.dilip.carobd

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Live "AI" helpers for the gauge page: driving mode, learned gear, eco score, trends and advice. */
object SmartLive {
    private class S(val t: Long, val cool: Double?, val volt: Double?)
    private val samples = ArrayDeque<S>()
    @Volatile var eco = 70f
    private val ratios = ArrayList<Double>()
    private var ratiosFor = -1L
    private var stableR = 0.0; private var stableN = 0
    @Volatile var gear = 0
    private var idleSince = 0L
    private var st: SmartAi.Status? = null
    private var stAt = 0L

    private fun sp() = ObdManager.app.getSharedPreferences("car_obd", Context.MODE_PRIVATE)

    private fun loadRatios() {
        val id = ObdManager.vehicle.id
        if (id == ratiosFor) return
        ratiosFor = id; ratios.clear()
        sp().getString("gear_$id", "")?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.let { ratios.addAll(it) }
    }

    fun resetGears() { ratios.clear(); sp().edit().remove("gear_${ObdManager.vehicle.id}").apply(); gear = 0 }

    fun tick() {
        val m = ObdManager; val v = m.values
        val spd = v[0x0D] ?: 0.0; val rpm = v[0x0C] ?: 0.0; val thr = v[0x11] ?: v[0x45] ?: 0.0
        val now = System.currentTimeMillis()
        samples.addLast(S(now, v[0x05], m.batteryV ?: v[0x42]))
        while (samples.isNotEmpty() && now - samples.first().t > 120_000) samples.removeFirst()
        loadRatios()
        // gear learning
        if (m.engineOn && spd >= 8 && rpm >= 900) {
            val r = rpm / spd
            if (stableN > 0 && abs(r - stableR) / stableR < 0.02) { stableN++; stableR = stableR * 0.8 + r * 0.2 } else { stableR = r; stableN = 1 }
            if (stableN == 8) {
                val i = ratios.indexOfFirst { abs(it - stableR) / it < 0.07 }
                if (i >= 0) ratios[i] = ratios[i] * 0.9 + stableR * 0.1 else if (ratios.size < 8) ratios.add(stableR)
                ratios.sortDescending()
                sp().edit().putString("gear_${ratiosFor}", ratios.joinToString(",") { "%.2f".format(java.util.Locale.US, it) }).apply()
            }
            val idx = ratios.indexOfFirst { abs(it - r) / it < 0.08 }
            gear = if (ratios.size >= 2 && idx >= 0) idx + 1 else 0
        } else gear = 0
        // eco score
        var tgt = 100.0
        if (m.engineOn) {
            if (rpm > 2500) tgt -= (rpm - 2500) / 40.0
            if (thr > 50) tgt -= (thr - 50) * 0.7
            if (spd > 100) tgt -= (spd - 100) * 0.8
            if (spd < 1) tgt -= 30
        } else tgt = 70.0
        eco += ((tgt.coerceIn(0.0, 100.0) - eco) * 0.12).toFloat()
        if (spd < 1 && m.engineOn) { if (idleSince == 0L) idleSince = now } else idleSince = 0L
    }

    fun idleSeconds() = if (idleSince == 0L) 0L else (System.currentTimeMillis() - idleSince) / 1000

    fun mode(): Pair<String, Int> {
        val m = ObdManager; val v = m.values
        val spd = v[0x0D] ?: 0.0; val rpm = v[0x0C] ?: 0.0; val thr = v[0x11] ?: 0.0; val load = v[0x04] ?: 0.0
        return when {
            m.state != ObdManager.State.CONNECTED -> "Offline" to Ui.DIM
            !m.engineOn -> "Ignition off" to Ui.WARN
            spd < 1 -> "Idling" to Ui.ACC
            rpm > 3800 || load > 75 || thr > 75 -> "Spirited" to Ui.ORANGE
            spd < 45 -> "City" to Ui.CYAN
            else -> "Highway" to Ui.GOOD
        }
    }

    /** °C per minute over the last ~90 s, or null. */
    fun coolantSlope(): Double? {
        val l = samples.filter { it.cool != null }
        if (l.size < 8) return null
        val a = l.first(); val b = l.last()
        val dt = (b.t - a.t) / 60000.0
        return if (dt < 0.4) null else (b.cool!! - a.cool!!) / dt
    }

    fun voltTrend(): Double? {
        val l = samples.filter { it.volt != null }
        if (l.size < 8) return null
        val dt = (l.last().t - l.first().t) / 60000.0
        return if (dt < 0.4) null else (l.last().volt!! - l.first().volt!!) / dt
    }

    fun shiftHint(): Int {
        val v = ObdManager.values
        val rpm = v[0x0C] ?: return 0; val spd = v[0x0D] ?: return 0; val thr = v[0x11] ?: 0.0
        if (gear == 0) return 0
        if (gear < ratios.size && rpm > 2600 && thr < 55 && spd > 15) return 1
        if (gear > 1 && rpm < 1100 && thr > 45) return -1
        return 0
    }

    fun status(): SmartAi.Status {
        val now = System.currentTimeMillis()
        if (st == null || now - stAt > 6000) { st = SmartAi.status(); stAt = now }
        return st!!
    }

    /** Up to two live tips: (icon, text, severity 0 good / 1 tip / 2 warn). */
    fun advice(): List<Triple<Ic, String, Int>> {
        val m = ObdManager; val v = m.values; val veh = m.vehicle
        val out = ArrayList<Triple<Ic, String, Int>>()
        if (m.state != ObdManager.State.CONNECTED) return listOf(Triple(Ic.PLUG, "Connect the adapter for live AI coaching.", 1))
        val spd = v[0x0D] ?: 0.0; val rpm = v[0x0C] ?: 0.0; val thr = v[0x11] ?: 0.0
        val lim = RoadAssist.info?.limit
        if (lim != null && RoadAssist.info?.estimated != true && spd > lim + Prefs.speedMargin) out += Triple(Ic.WARN, "Over the $lim km/h limit on this road – ease off.", 2)
        val sl = coolantSlope(); val cool = v[0x05] ?: 0.0
        if (sl != null && sl > 4 && cool > 88) out += Triple(Ic.THERM, "Coolant rising fast (+%.0f°/min) – ease off and watch the temperature.".format(if (Units.tempF) sl * 1.8 else sl), 2)
        if (cool in 1.0..60.0 && rpm > 3000) out += Triple(Ic.THERM, "Engine is cold – keep revs low until it warms up.", 1)
        when (shiftHint()) {
            1 -> out += Triple(Ic.BOLT, "Revs are high for the load – ease the throttle so it upshifts (gear ${gear + 1}).", 1)
            -1 -> out += Triple(Ic.ENGINE, "Engine is lugging in gear $gear – lift off or let it downshift.", 1)
        }
        if (idleSeconds() > 60) out += Triple(Ic.FUEL, "Stopped for ${idleSeconds()} s – switching off saves fuel.", 1)
        if (spd > 95) out += Triple(Ic.WIND, "Fuel use rises sharply above ~90 km/h.", 1)
        if (thr > 85 && spd < 40) out += Triple(Ic.BOLT, "Heavy throttle at low speed – squeeze it gently.", 1)
        val bv = m.batteryV ?: v[0x42]
        if (m.engineOn && bv != null && bv < Prefs.chargeMinV) out += Triple(Ic.BATTERY, "Charging voltage is low (%.1f V) – check the alternator/battery.".format(bv), 2)
        if (out.isEmpty()) EmissionAi.liveTip()?.let { out += Triple(Ic.LEAF, it, 1) }
        if (out.isEmpty() && spd in 30.0..90.0 && rpm in 1200.0..2300.0) out += Triple(Ic.LEAF, "In the efficient band – hold a steady speed.", 0)
        if (out.isEmpty()) out += Triple(Ic.SHIELD, "AI is watching: all readings are normal.", 0)
        return out.sortedByDescending { it.third }.take(2)
    }
}

/** Big instrument cluster: RPM ring, speed ring, digital speed, gear, shift hint, AI aura and speed-limit sign. */
class ClusterView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    var rpm: Float? = null; var speed: Float? = null
    var maxRpm = 8000f; var redRpm = 6500f; var maxSpeed = 220f
    var limit: Int? = null; var limitEstimated = false
    var gearText = "N"; var shift = 0
    var coolant: Float? = null; var fuel: Float? = null; var fuelLabel = "FUEL"
    var aiColor = Color.parseColor("#2EE59D"); var modeText = ""; var modeColor = Color.WHITE
    var overSpeed = false

    private var sRpm = 0f; private var sSpd = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val t0 = System.nanoTime()

    override fun onMeasure(w: Int, h: Int) {
        val ww = MeasureSpec.getSize(w); setMeasuredDimension(ww, (ww * 0.9f).toInt())
    }

    private fun arc(c: Canvas, rect: RectF, from: Float, to: Float, max: Float, color: Int, stroke: Float) {
        if (to <= from) return
        p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.color = color; p.strokeCap = Paint.Cap.BUTT
        c.drawArc(rect, 135f + 270f * from / max, 270f * (to - from) / max, false, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h * 0.5f
        val R = minOf(w, h) * 0.46f
        val time = (System.nanoTime() - t0) / 1e9f
        sRpm += ((rpm ?: 0f) - sRpm) * 0.22f; sSpd += ((speed ?: 0f) - sSpd) * 0.22f
        if (abs((rpm ?: 0f) - sRpm) < 2f) sRpm = rpm ?: 0f
        if (abs((speed ?: 0f) - sSpd) < 0.2f) sSpd = speed ?: 0f

        // AI aura
        val pulse = 0.5f + 0.5f * sin(time * 2f)
        p.shader = RadialGradient(cx, cy, R * 1.25f, Color.argb((26 + 30 * pulse).toInt(), Color.red(aiColor), Color.green(aiColor), Color.blue(aiColor)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL; c.drawRect(0f, 0f, w, h, p); p.shader = null
        p.color = Color.parseColor("#0F161E"); c.drawCircle(cx, cy, R + 4 * d, p)

        // RPM ring
        val sw = R * 0.095f
        val rr = RectF(cx - R + sw / 2, cy - R + sw / 2, cx + R - sw / 2, cy + R - sw / 2)
        arc(c, rr, 0f, maxRpm, maxRpm, Color.parseColor("#1C2733"), sw)
        val v = sRpm.coerceIn(0f, maxRpm)
        val z1 = redRpm * 0.62f
        arc(c, rr, 0f, minOf(v, z1), maxRpm, Color.parseColor("#2EE59D"), sw)
        arc(c, rr, z1, minOf(v, redRpm), maxRpm, Color.parseColor("#FFB020"), sw)
        arc(c, rr, redRpm, v, maxRpm, Color.parseColor("#FF3B4E"), sw)
        arc(c, rr, redRpm, maxRpm, maxRpm, Color.argb(60, 255, 59, 78), sw * 0.35f)
        // tip glow
        val ta = Math.toRadians((135f + 270f * v / maxRpm).toDouble())
        val tx = cx + ((R - sw / 2) * cos(ta)).toFloat(); val ty = cy + ((R - sw / 2) * sin(ta)).toFloat()
        p.style = Paint.Style.FILL; p.color = Color.argb(90, 255, 255, 255); c.drawCircle(tx, ty, sw * 0.8f, p)
        p.color = Color.WHITE; c.drawCircle(tx, ty, sw * 0.38f, p)
        // ticks + labels
        t.textSize = R * 0.075f; t.color = Color.parseColor("#7C8A99")
        var k = 0
        while (k * 1000f <= maxRpm) {
            val a = Math.toRadians((135f + 270f * k * 1000f / maxRpm).toDouble())
            val r1 = R - sw - 3 * d; val r2 = r1 - 5 * d
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = Color.parseColor("#4A5666")
            c.drawLine(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(), cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), p)
            val rl = r2 - 9 * d
            c.drawText("$k", cx + (rl * cos(a)).toFloat(), cy + (rl * sin(a)).toFloat() + t.textSize * 0.35f, t)
            k++
        }

        // digital speed (single gauge – the RPM ring; no nested ring)
        t.color = if (overSpeed) Color.parseColor("#FF3B4E") else Color.WHITE; t.textSize = R * 0.56f
        c.drawText("%.0f".format(Units.spd(sSpd.toDouble())), cx, cy + R * 0.22f, t)
        t.color = Color.parseColor("#8E9BAA"); t.textSize = R * 0.11f; c.drawText(Units.spdLabel, cx, cy + R * 0.36f, t)
        t.color = modeColor; t.textSize = R * 0.105f; c.drawText(modeText, cx, cy - R * 0.52f, t)
        t.color = Color.parseColor("#C8D3DE"); t.textSize = R * 0.115f; c.drawText("%.0f rpm".format(sRpm), cx, cy - R * 0.34f, t)

        // gear pill + shift hint
        val gw = R * 0.5f; val gh = R * 0.24f; val gy = cy + R * 0.46f
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#1C2733")
        c.drawRoundRect(RectF(cx - gw / 2, gy, cx + gw / 2, gy + gh), gh / 2, gh / 2, p)
        t.color = Color.WHITE; t.textSize = R * 0.15f; c.drawText(gearText, cx, gy + gh * 0.7f, t)
        if (shift != 0) {
            val sx = cx + gw / 2 + R * 0.14f; val sy = gy + gh / 2
            p.color = if (shift > 0) Color.parseColor("#2EE59D") else Color.parseColor("#FFB020")
            val tri = Path()
            if (shift > 0) { tri.moveTo(sx, sy - R * 0.09f); tri.lineTo(sx + R * 0.08f, sy + R * 0.06f); tri.lineTo(sx - R * 0.08f, sy + R * 0.06f) }
            else { tri.moveTo(sx, sy + R * 0.09f); tri.lineTo(sx + R * 0.08f, sy - R * 0.06f); tri.lineTo(sx - R * 0.08f, sy - R * 0.06f) }
            tri.close(); c.drawPath(tri, p)
        }

        // coolant / fuel corners
        fun corner(x: Float, label: String, value: String, col: Int) {
            t.color = col; t.textSize = R * 0.15f; c.drawText(value, x, cy + R * 0.9f, t)
            t.color = Color.parseColor("#7C8A99"); t.textSize = R * 0.085f; c.drawText(label, x, cy + R * 1.0f, t)
        }
        val cool = coolant
        corner(cx - R * 0.72f, "COOLANT", cool?.let { "%.0f°".format(it) } ?: "--", if (cool != null && cool >= Prefs.coolantMax - 5) Color.parseColor("#FF3B4E") else Color.WHITE)
        val fl = fuel
        corner(cx + R * 0.72f, fuelLabel, fl?.let { "%.0f%%".format(it) } ?: "--", if (fl != null && fl <= Prefs.lowFuelPct) Color.parseColor("#FF3B4E") else Color.WHITE)

        // speed-limit sign
        limit?.let { l ->
            val sr = R * 0.2f; val sx = w - sr - 8 * d; val sy = sr + 6 * d
            p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawCircle(sx, sy, sr, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = sr * 0.22f; p.color = Color.parseColor("#D7263D"); c.drawCircle(sx, sy, sr * 0.89f, p)
            t.color = if (limitEstimated) Color.parseColor("#55606E") else Color.BLACK; t.textSize = sr * (if (l >= 100) 0.8f else 1.0f)
            c.drawText("$l", sx, sy + t.textSize * 0.35f, t)
        }
        postInvalidateOnAnimation()
    }
}
