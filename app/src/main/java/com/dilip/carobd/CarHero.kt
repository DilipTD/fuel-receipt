package com.dilip.carobd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Illustrated car with a live link status:
 *  state 0 = offline (grey car), 1 = connecting (amber, radio waves), 2 = connected (blue, lights on,
 *  wheels turn with speed), 3 = connected but ignition off (teal, dim).
 */
class CarHeroView(ctx: Context, private val compact: Boolean = false) : View(ctx) {
    var state = 0
        set(v) { if (field != v) { field = v; postInvalidateOnAnimation() } }
    var title = ""
    var subtitle = ""
    var speed = 0.0
    var linkIc = Ic.BLUETOOTH
    var chips: List<Pair<Ic, String>> = emptyList()

    private val d = ctx.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val badgeIcon = HashMap<Ic, IconDrawable>()

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), ((if (compact) 150 else 215) * d).toInt())
    }

    private fun bodyColors(): Pair<Int, Int> = when (state) {
        2 -> Color.parseColor("#3D8BFF") to Color.parseColor("#14366B")
        1 -> Color.parseColor("#FFB74D") to Color.parseColor("#7A4A00")
        3 -> Color.parseColor("#26C6B0") to Color.parseColor("#0B4A44")
        else -> Color.parseColor("#6B7886") to Color.parseColor("#262F38")
    }

    private fun accent() = when (state) {
        2 -> Color.parseColor("#00E5FF")
        1 -> Color.parseColor("#FFB300")
        3 -> Color.parseColor("#26C6B0")
        else -> Color.parseColor("#8E9BAA")
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val t = now / 1000.0
        val acc = accent()

        // background card
        rect.set(0f, 0f, w, h)
        bg.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#17324F"), Color.parseColor("#0A1016"), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, 18 * d, 18 * d, bg)
        c.save()
        path.reset(); path.addRoundRect(rect, 18 * d, 18 * d, Path.Direction.CW); c.clipPath(path)

        // soft glow behind the car
        fill.shader = RadialGradient(w * 0.62f, h * 0.62f, w * 0.5f,
            Color.argb(if (state == 0) 30 else 85, Color.red(acc), Color.green(acc), Color.blue(acc)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // road
        val roadTop = h * 0.80f
        fill.color = Color.parseColor("#0C1116")
        c.drawRect(0f, roadTop, w, h, fill)
        line.color = Color.parseColor("#33414F"); line.strokeWidth = 2 * d; line.pathEffect = null
        c.drawLine(0f, roadTop, w, roadTop, line)
        line.color = Color.parseColor("#55657A"); line.strokeWidth = 2.5f * d
        val dash = 26 * d
        line.pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
        val off = if (state == 2) ((t * (30 + speed * 2.2)) * d % (2 * dash)).toFloat() else 0f
        c.save(); c.translate(-off, 0f)
        c.drawLine(0f, roadTop + (h - roadTop) * 0.55f, w + 2 * dash, roadTop + (h - roadTop) * 0.55f, line)
        c.restore()
        line.pathEffect = null

        // car geometry (100 × 40 unit space)
        val carW = w * (if (compact) 0.50f else 0.66f)
        val sc = carW / 100f
        val cx0 = w - carW - w * 0.05f
        val cy0 = roadTop - 29f * sc + 5 * sc
        c.save()
        c.translate(cx0, cy0)
        c.scale(sc, sc)

        // headlight beam
        if (state == 2) {
            fill.shader = LinearGradient(96f, 22f, 125f, 22f, Color.argb(120, 255, 241, 160), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            path.reset(); path.moveTo(96f, 20f); path.lineTo(130f, 12f); path.lineTo(130f, 34f); path.lineTo(96f, 25f); path.close()
            c.drawPath(path, fill)
            fill.shader = null
        }
        // ground shadow
        fill.shader = RadialGradient(50f, 35.5f, 50f, Color.argb(150, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.save(); c.scale(1f, 0.12f, 50f, 35.5f); c.drawCircle(50f, 35.5f, 50f, fill); c.restore()
        fill.shader = null

        val (c1, c2) = bodyColors()
        val mid = blend(c1, c2, 0.45f)
        val dark = Color.argb(255, 12, 15, 19)

        // body silhouette
        path.reset()
        path.moveTo(3f, 31f); path.lineTo(3f, 23.5f)
        path.cubicTo(3f, 20f, 5f, 18.2f, 9f, 17.6f); path.lineTo(22f, 16f)
        path.cubicTo(27f, 10.5f, 31f, 7.2f, 37f, 6.5f); path.lineTo(60f, 6.5f)
        path.cubicTo(66f, 6.9f, 71f, 12f, 76f, 16.2f); path.lineTo(90f, 18.6f)
        path.cubicTo(95f, 19.6f, 97.6f, 21.3f, 97.6f, 25f); path.lineTo(97.6f, 31f); path.close()
        body.shader = LinearGradient(0f, 6f, 0f, 31f, intArrayOf(blend(c1, Color.WHITE, 0.25f), c1, mid, c2),
            floatArrayOf(0f, 0.25f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, body)
        body.shader = null
        c.save(); c.clipPath(path)
        // glossy reflection band along the shoulder
        fill.shader = LinearGradient(0f, 17f, 0f, 21.5f, Color.argb(0, 255, 255, 255), Color.argb(if (state == 0) 40 else 90, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRect(3f, 17f, 98f, 21.5f, fill)
        fill.shader = LinearGradient(0f, 21.5f, 0f, 24f, Color.argb(if (state == 0) 40 else 90, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRect(3f, 21.5f, 98f, 24f, fill)
        fill.shader = null
        // black lower cladding
        fill.shader = LinearGradient(0f, 27.2f, 0f, 31f, Color.parseColor("#2A3037"), dark, Shader.TileMode.CLAMP)
        path.reset(); path.moveTo(3f, 27.6f); path.lineTo(97.6f, 27.2f); path.lineTo(97.6f, 31f); path.lineTo(3f, 31f); path.close()
        c.drawPath(path, fill); fill.shader = null
        c.restore()

        // glasshouse: tinted windows + pillars
        glass.shader = LinearGradient(0f, 7.8f, 0f, 16f, Color.parseColor("#E3F6FF"), Color.parseColor("#2F5D86"), Shader.TileMode.CLAMP)
        glass.alpha = if (state == 0) 120 else 235
        path.reset()
        path.moveTo(25f, 15.6f); path.cubicTo(29f, 11f, 32f, 8.6f, 37.4f, 7.9f); path.lineTo(47.4f, 7.9f); path.lineTo(47.4f, 15.6f); path.close()
        c.drawPath(path, glass)
        path.reset()
        path.moveTo(50.4f, 7.9f); path.lineTo(60f, 7.9f); path.cubicTo(65f, 8.3f, 68.6f, 12.3f, 72.2f, 15.6f); path.lineTo(50.4f, 15.6f); path.close()
        c.drawPath(path, glass)
        glass.shader = null
        // sky reflection streak on glass
        line.color = Color.argb(70, 255, 255, 255); line.strokeWidth = 0.9f
        c.drawLine(52f, 14.5f, 59f, 8.8f, line); c.drawLine(31f, 14.5f, 36f, 9.2f, line)
        // B pillar, roof rail, beltline chrome
        fill.color = dark; rect.set(47.4f, 7.2f, 50.4f, 16f); c.drawRect(rect, fill)
        line.color = Color.argb(200, 190, 200, 210); line.strokeWidth = 0.7f
        c.drawLine(36f, 6.6f, 60f, 6.6f, line)
        line.color = Color.argb(170, 215, 225, 235); line.strokeWidth = 0.55f
        c.drawLine(24f, 16.2f, 74f, 16.5f, line)
        // door cuts, handles, mirror, fuel flap
        line.color = Color.argb(150, 0, 0, 0); line.strokeWidth = 0.45f
        c.drawLine(28f, 16.4f, 29f, 27.4f, line); c.drawLine(49f, 16f, 49f, 27.5f, line); c.drawLine(73f, 16.8f, 72f, 27.4f, line)
        line.color = Color.argb(230, 235, 240, 245); line.strokeWidth = 0.9f
        c.drawLine(41f, 19.3f, 45f, 19.3f, line); c.drawLine(62f, 19.4f, 66f, 19.5f, line)
        fill.color = blend(c1, dark, 0.3f); path.reset()
        path.moveTo(70.5f, 15.2f); path.lineTo(75.5f, 15.8f); path.lineTo(75f, 17.6f); path.lineTo(70.8f, 17.2f); path.close(); c.drawPath(path, fill)
        // side character crease
        line.color = Color.argb(90, 255, 255, 255); line.strokeWidth = 0.5f
        c.drawLine(8f, 22.8f, 92f, 22.2f, line)
        line.color = Color.argb(110, 0, 0, 0); line.strokeWidth = 0.5f
        c.drawLine(8f, 24.4f, 92f, 24f, line)

        // headlight cluster with DRL, fog lamp, grille hint
        val on = state == 2
        path.reset(); path.moveTo(88.5f, 19.4f); path.lineTo(96.6f, 21.4f); path.lineTo(96.8f, 24.2f); path.lineTo(89f, 22.6f); path.close()
        fill.color = if (on) Color.parseColor("#FFF9C4") else Color.parseColor("#B8C4CC"); c.drawPath(path, fill)
        line.color = if (on) Color.WHITE else Color.parseColor("#7C8B96"); line.strokeWidth = 0.6f
        c.drawLine(89.5f, 23.4f, 96.7f, 25f, line)
        fill.color = Color.parseColor("#11161B"); rect.set(94f, 26.3f, 97.4f, 28f); c.drawRoundRect(rect, 0.8f, 0.8f, fill)
        fill.color = if (on) Color.parseColor("#FFE082") else Color.parseColor("#55616B"); c.drawCircle(95.6f, 27.2f, 0.7f, fill)
        // tail lamp LED bar
        path.reset(); path.moveTo(3f, 19.8f); path.lineTo(7.2f, 19.1f); path.lineTo(7.4f, 22.8f); path.lineTo(3f, 23.6f); path.close()
        fill.color = if (on) Color.parseColor("#FF3030") else Color.parseColor("#7A3C3C"); c.drawPath(path, fill)
        // rear bumper reflector + exhaust-less lower trim
        fill.color = Color.parseColor("#8E2B2B"); rect.set(3.4f, 28.4f, 7.5f, 29.4f); c.drawRect(rect, fill)

        // wheels with arches, tyres, dual-spoke alloys, brake discs
        val ang = if (state == 2) ((t * speed * 9.0) % 360.0).toFloat() else 0f
        for (wx in listOf(22f, 77f)) {
            fill.color = Color.parseColor("#06080A"); c.drawCircle(wx, 29f, 9.4f, fill)            // arch
            fill.color = Color.parseColor("#14181C"); c.drawCircle(wx, 29f, 8.0f, fill)            // tyre
            line.color = Color.parseColor("#2A3037"); line.strokeWidth = 0.7f
            line.style = Paint.Style.STROKE; c.drawCircle(wx, 29f, 7.2f, line)                      // sidewall ring
            val rim = LinearGradient(wx - 5f, 24f, wx + 5f, 34f, Color.parseColor("#E8EEF3"), Color.parseColor("#6D7A86"), Shader.TileMode.CLAMP)
            fill.shader = rim; c.drawCircle(wx, 29f, 5.4f, fill); fill.shader = null
            fill.color = Color.parseColor("#2B333A"); c.drawCircle(wx, 29f, 4.2f, fill)            // dark pockets
            fill.color = Color.parseColor("#B73A3A"); c.drawCircle(wx, 29f, 3.2f, fill)            // caliper hint
            fill.color = Color.parseColor("#59646E"); c.drawCircle(wx, 29f, 2.9f, fill)            // disc
            line.color = Color.parseColor("#E1E8EE"); line.strokeWidth = 1.05f
            for (k in 0 until 5) {
                val a1 = Math.toRadians((ang + k * 72 - 7).toDouble()); val a2 = Math.toRadians((ang + k * 72 + 7).toDouble())
                c.drawLine(wx, 29f, wx + 5.2f * cos(a1).toFloat(), 29f + 5.2f * sin(a1).toFloat(), line)
                c.drawLine(wx, 29f, wx + 5.2f * cos(a2).toFloat(), 29f + 5.2f * sin(a2).toFloat(), line)
            }
            fill.color = Color.parseColor("#C5CED6"); c.drawCircle(wx, 29f, 1.1f, fill)
            line.strokeWidth = 0.9f
        }

        // radio waves from the OBD port (under the dash)
        if (state == 1 || state == 2) {
            for (k in 0 until 3) {
                val ph = ((t * (if (state == 1) 0.9 else 0.5) + k / 3.0) % 1.0).toFloat()
                line.color = Color.argb(((1f - ph) * 200).toInt(), Color.red(acc), Color.green(acc), Color.blue(acc))
                line.strokeWidth = 1.2f
                rect.set(44f - 6 - ph * 32, 17f - 6 - ph * 32, 44f + 6 + ph * 32, 17f + 6 + ph * 32)
                c.drawArc(rect, 250f, 100f, false, line)
            }
            fill.color = acc; c.drawCircle(44f, 17f, 1.8f, fill)
        }
        c.restore()

        // texts
        val pad = 16 * d
        txt.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        txt.color = Color.WHITE
        txt.textSize = (if (compact) 17 else 21) * d
        val maxW = w * (if (compact) 0.5f else 0.52f)
        c.drawText(ellipsize(title, maxW), pad, pad + 18 * d, txt)
        txt.typeface = Typeface.DEFAULT
        txt.color = acc
        txt.textSize = 12.5f * d
        var y = pad + 38 * d
        for (ln in wrap(subtitle, maxW).take(if (compact) 2 else 3)) {
            c.drawText(ln, pad, y, txt); y += 16 * d
        }
        // chips
        var cxp = pad
        val cyp = h * 0.80f - 26 * d
        txt.textSize = 11f * d
        for ((ic, s) in chips) {
            if (compact) break
            val tw = txt.measureText(s)
            val bw = tw + 38 * d
            if (cxp + bw > w * 0.60f) break
            fill.color = Color.argb(60, 255, 255, 255)
            rect.set(cxp, cyp, cxp + bw, cyp + 26 * d)
            c.drawRoundRect(rect, 13 * d, 13 * d, fill)
            val dr = badgeIcon.getOrPut(ic) { IconDrawable(ic, Color.WHITE, (15 * d).toInt()) }
            val ix = (cxp + 8 * d).toInt(); val iy = (cyp + 5.5f * d).toInt()
            dr.setBounds(ix, iy, ix + (15 * d).toInt(), iy + (15 * d).toInt()); dr.draw(c)
            txt.color = Color.WHITE
            c.drawText(s, cxp + 27 * d, cyp + 17.5f * d, txt)
            cxp += bw + 6 * d
        }
        // link badge (top right)
        val br = 19 * d
        val bx = w - pad - br
        val by = pad + br
        fill.color = Color.argb(70, Color.red(acc), Color.green(acc), Color.blue(acc))
        c.drawCircle(bx, by, br + (if (state == 1) (((t * 2) % 1.0).toFloat() * 6 * d) else 0f), fill)
        fill.color = Color.parseColor("#0E161E"); c.drawCircle(bx, by, br, fill)
        line.color = acc; line.strokeWidth = 2 * d; c.drawCircle(bx, by, br, line)
        val dr = badgeIcon.getOrPut(linkIc) { IconDrawable(linkIc, acc, (22 * d).toInt()) }
        dr.color = acc
        val ix = (bx - 11 * d).toInt(); val iy = (by - 11 * d).toInt()
        dr.setBounds(ix, iy, ix + (22 * d).toInt(), iy + (22 * d).toInt()); dr.draw(c)
        c.restore()

        if (state == 1 || state == 2) postInvalidateOnAnimation()
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (txt.measureText(s) <= maxW) return s
        var e = s
        while (e.length > 3 && txt.measureText("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }

    private fun wrap(s: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in s.split(" ", "\n")) {
            val test = if (cur.isEmpty()) word else "$cur $word"
            if (txt.measureText(test) > maxW && cur.isNotEmpty()) { out.add(cur); cur = word } else cur = test
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }
}
