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
        // shadow
        fill.color = Color.argb(110, 0, 0, 0)
        rect.set(4f, 33f, 96f, 38f); c.drawOval(rect, fill)

        // body
        val (c1, c2) = bodyColors()
        body.shader = LinearGradient(0f, 6f, 0f, 32f, c1, c2, Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(3f, 30f); path.lineTo(3f, 23f)
        path.quadTo(3f, 19.5f, 9f, 18.5f); path.lineTo(23f, 16.5f)
        path.cubicTo(28f, 11f, 31f, 7f, 36f, 6.2f); path.lineTo(61f, 6.2f)
        path.cubicTo(67f, 6.5f, 72f, 12f, 77f, 16.5f); path.lineTo(91f, 19f)
        path.quadTo(97f, 20f, 97f, 25f); path.lineTo(97f, 30f); path.close()
        c.drawPath(path, body)
        // glass
        glass.shader = LinearGradient(0f, 8f, 0f, 17f, Color.parseColor("#CFEFFF"), Color.parseColor("#4B7BA6"), Shader.TileMode.CLAMP)
        glass.alpha = if (state == 0) 110 else 220
        path.reset()
        path.moveTo(31f, 16f); path.cubicTo(34f, 11.5f, 36f, 8.8f, 39f, 8.4f); path.lineTo(48f, 8.4f); path.lineTo(48f, 16f); path.close()
        c.drawPath(path, glass)
        path.reset()
        path.moveTo(51f, 8.4f); path.lineTo(61f, 8.4f); path.cubicTo(65f, 8.8f, 68.5f, 12.5f, 72f, 16f); path.lineTo(51f, 16f); path.close()
        c.drawPath(path, glass)
        // door line, handle
        line.color = Color.argb(120, 0, 0, 0); line.strokeWidth = 0.7f
        c.drawLine(49.5f, 8.4f, 49.5f, 29f, line)
        line.color = Color.argb(180, 255, 255, 255)
        c.drawLine(53f, 19.5f, 58f, 19.5f, line)
        // lamps
        fill.color = if (state == 2) Color.parseColor("#FFF59D") else Color.parseColor("#B0BEC5")
        rect.set(92f, 19.8f, 97f, 23.2f); c.drawRoundRect(rect, 1.2f, 1.2f, fill)
        fill.color = if (state == 2) Color.parseColor("#FF3D3D") else Color.parseColor("#6D4C4C")
        rect.set(3f, 21f, 6f, 24f); c.drawRoundRect(rect, 1f, 1f, fill)
        // bumper line
        line.color = Color.argb(90, 0, 0, 0); line.strokeWidth = 0.8f
        c.drawLine(4f, 28f, 96f, 28f, line)

        // wheels
        val ang = if (state == 2) ((t * speed * 9.0) % 360.0).toFloat() else 0f
        for (wx in listOf(22f, 77f)) {
            fill.color = Color.parseColor("#0A0C0E"); c.drawCircle(wx, 29f, 7.6f, fill)
            fill.color = Color.parseColor("#9AA7B4"); c.drawCircle(wx, 29f, 4.6f, fill)
            line.color = Color.parseColor("#37424D"); line.strokeWidth = 0.9f
            for (k in 0 until 5) {
                val a = Math.toRadians((ang + k * 72).toDouble())
                c.drawLine(wx, 29f, wx + 4.3f * cos(a).toFloat(), 29f + 4.3f * sin(a).toFloat(), line)
            }
            fill.color = Color.parseColor("#1B2128"); c.drawCircle(wx, 29f, 1.2f, fill)
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
