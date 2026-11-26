package com.dilip.carobd

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Animated side-view car: spinning wheels, moving road, AI scan sweep and pulsing alert hot-spots. */
class SmartCarView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    var zones: Map<String, Int> = emptyMap()
    var overall = 0
    var speedKmh = 0.0
    var engineOn = false
    private var t0 = System.nanoTime()
    private var wheelAng = 0f
    private var roadOff = 0f
    private var last = System.nanoTime()

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    private fun statusColor(s: Int) = when (s) { 2 -> Color.parseColor("#FF4D5E"); 1 -> Color.parseColor("#FFB020"); else -> Color.parseColor("#2EE59D") }

    // hot-spot positions in a 100 x 40 car space
    private val spots = listOf(
        Triple("Engine", 84f, 17f), Triple("Cooling", 93f, 23f), Triple("Battery", 70f, 18f),
        Triple("Codes", 50f, 9f), Triple("Fuel", 11f, 22f), Triple("Service", 76f, 30f)
    )

    override fun onVisibilityChanged(v: View, vis: Int) { super.onVisibilityChanged(v, vis); if (vis == VISIBLE) invalidate() }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = (now - last) / 1e9f; last = now
        val t = (now - t0) / 1e9f
        val w = width.toFloat(); val h = height.toFloat()
        val sc = minOf(w / 112f, h / 56f)
        val ox = (w - 100 * sc) / 2f; val oy = h * 0.14f
        val moving = engineOn && speedKmh > 1
        val spin = if (moving) (80f + speedKmh.toFloat() * 9f) else if (engineOn) 0f else 40f
        wheelAng = (wheelAng + spin * dt) % 360f
        roadOff = (roadOff + (if (moving) (20f + speedKmh.toFloat() * 0.9f) else 14f) * dt) % 40f
        val col = statusColor(overall)

        // aura
        val pulse = 0.5f + 0.5f * sin(t * 2.2f)
        p.shader = RadialGradient(w / 2, oy + 24 * sc, w * 0.55f, Color.argb((40 + 40 * pulse).toInt(), Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null

        // road
        val ry = oy + 36.5f * sc
        p.color = Color.parseColor("#2A3441"); p.style = Paint.Style.FILL
        c.drawRect(0f, ry, w, ry + 2 * d, p)
        p.color = Color.parseColor("#4A5666")
        var x = -roadOff * sc
        while (x < w) { c.drawRect(x, ry + 9 * d, x + 14 * sc, ry + 11 * d, p); x += 40 * sc }

        c.save(); c.translate(ox, oy); c.scale(sc, sc)

        // headlight beam
        if (engineOn) {
            p.shader = LinearGradient(96f, 22f, 125f, 22f, Color.argb(110, 255, 244, 190), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            val beam = Path().apply { moveTo(95f, 21f); lineTo(128f, 14f); lineTo(128f, 33f); lineTo(95f, 25f); close() }
            c.drawPath(beam, p); p.shader = null
        }

        // body
        val path = Path().apply {
            moveTo(5f, 30f); lineTo(4f, 21f); quadTo(4.5f, 15f, 10f, 13.5f); lineTo(22f, 10f); quadTo(30f, 6f, 40f, 6f); lineTo(60f, 6f)
            quadTo(70f, 7f, 77f, 14f); lineTo(90f, 17f); quadTo(97f, 19f, 97.5f, 25f); lineTo(97f, 30f); close()
        }
        body.shader = LinearGradient(0f, 6f, 0f, 31f, Color.parseColor("#4A5F7A"), Color.parseColor("#1E2A3A"), Shader.TileMode.CLAMP)
        c.drawPath(path, body)
        edge.color = Color.argb(200, Color.red(col), Color.green(col), Color.blue(col)); edge.strokeWidth = 0.9f
        c.drawPath(path, edge)
        // windows
        val win = Path().apply { moveTo(24f, 13f); lineTo(31f, 8.6f); lineTo(46.5f, 8.6f); lineTo(46.5f, 13f); close()
            moveTo(48.5f, 8.6f); lineTo(60f, 8.6f); quadTo(67f, 9f, 72.5f, 13f); lineTo(48.5f, 13f); close() }
        p.color = Color.parseColor("#0E1722"); p.style = Paint.Style.FILL; c.drawPath(win, p)
        p.color = Color.argb(60, 255, 255, 255); c.drawRect(26f, 9.5f, 29f, 12.6f, p)
        // lights
        p.color = if (engineOn) Color.parseColor("#FFF3B0") else Color.parseColor("#8896A5"); c.drawRoundRect(RectF(92.5f, 19f, 97f, 22.5f), 1.5f, 1.5f, p)
        p.color = if (engineOn) Color.parseColor("#FF4D5E") else Color.parseColor("#5B3A40"); c.drawRoundRect(RectF(3.8f, 20f, 7f, 24f), 1.2f, 1.2f, p)
        // door line
        edge.color = Color.argb(70, 255, 255, 255); edge.strokeWidth = 0.5f
        c.drawLine(47.5f, 13.5f, 47.5f, 29f, edge)
        c.drawLine(24f, 17f, 70f, 17f, edge)

        // wheels
        for (wx in listOf(24f, 76f)) {
            p.color = Color.parseColor("#0B1118"); c.drawCircle(wx, 30f, 8.4f, p)
            p.color = Color.parseColor("#1B2430"); c.drawCircle(wx, 30f, 6.6f, p)
            edge.color = Color.parseColor("#7B8CA0"); edge.strokeWidth = 0.8f
            for (k in 0 until 5) {
                val a = Math.toRadians((wheelAng + k * 72f).toDouble())
                c.drawLine(wx, 30f, wx + (cos(a) * 5.2).toFloat(), 30f + (sin(a) * 5.2).toFloat(), edge)
            }
            edge.color = Color.parseColor("#9FB0C3"); edge.strokeWidth = 0.7f; c.drawCircle(wx, 30f, 5.6f, edge)
            p.color = Color.parseColor("#9FB0C3"); c.drawCircle(wx, 30f, 1.2f, p)
        }

        // AI scan sweep
        val ph = (t % 4.5f) / 4.5f
        val sx = -10f + ph * 120f
        p.shader = LinearGradient(sx - 14f, 0f, sx, 0f, Color.TRANSPARENT, Color.argb(95, Color.red(col), Color.green(col), Color.blue(col)), Shader.TileMode.CLAMP)
        c.drawRect(sx - 14f, 3f, sx, 38f, p); p.shader = null
        p.color = Color.argb(200, Color.red(col), Color.green(col), Color.blue(col)); c.drawRect(sx - 0.4f, 3f, sx + 0.4f, 38f, p)

        // hot-spots
        for ((name, hx, hy) in spots) {
            val s = zones[name] ?: 0
            val sc2 = statusColor(s)
            if (s > 0) {
                val r = 2.4f + 2.6f * ((t * (if (s == 2) 2.4f else 1.4f)) % 1f)
                p.color = Color.argb((120 * (1f - ((t * (if (s == 2) 2.4f else 1.4f)) % 1f))).toInt(), Color.red(sc2), Color.green(sc2), Color.blue(sc2))
                c.drawCircle(hx, hy, r + 1.5f, p)
            }
            p.color = Color.parseColor("#0B1118"); c.drawCircle(hx, hy, 2.5f, p)
            p.color = sc2; c.drawCircle(hx, hy, 1.7f, p)
        }
        c.restore()

        // labels for warnings
        txt.textSize = 10.5f * d
        for ((name, hx, hy) in spots) {
            val s = zones[name] ?: 0
            if (s == 0) continue
            val px = ox + hx * sc; val py = oy + hy * sc
            val lw = txt.measureText(name) + 14 * d
            val lx = (px - lw / 2).coerceIn(2 * d, w - lw - 2 * d); val ly = py - 24 * d
            p.color = Color.argb(230, 16, 22, 31); c.drawRoundRect(RectF(lx, ly, lx + lw, ly + 18 * d), 9 * d, 9 * d, p)
            edge.color = statusColor(s); edge.strokeWidth = d; c.drawRoundRect(RectF(lx, ly, lx + lw, ly + 18 * d), 9 * d, 9 * d, edge)
            txt.color = statusColor(s); c.drawText(name, lx + lw / 2, ly + 13 * d, txt)
        }
        postInvalidateOnAnimation()
    }
}
