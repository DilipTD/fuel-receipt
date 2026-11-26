package com.dilip.carobd

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.ImageView
import kotlin.math.cos
import kotlin.math.sin

/** Every pictogram used by the app. All are drawn in code (no image files). */
enum class Ic {
    BLUETOOTH, BLE, WIFI, PLUG, GAUGE, CHART, ROUTE, ENGINE, GRID, FUEL, WRENCH, BELL, BATTERY, TIMER, CAR,
    GEAR, INFO, PIN, PULSE, THERM, DOC, TRASH, DOWNLOAD, UPLOAD, SCAN, CHECK, WARN, LEAF, TERMINAL, SAVE,
    REFRESH, MONITOR, POWER, PLUS, SHARE, SLIDERS, VOICE, BOLT, CALENDAR, DROP, WIND, DATABASE, EDIT, CLOCK,
    SHIELD, PLAY, STOP, BACK, CODE, USER, SEARCH, FLAG, CHEV
}

/** Outline icon on a 24×24 grid, tinted with [color]. */
class IconDrawable(private val ic: Ic, color: Int, private val sizePx: Int) : Drawable() {
    var color: Int = color
        set(v) {
            field = v; invalidateSelf()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val r = RectF()
    private lateinit var c: Canvas

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(a: Int) {
        p.alpha = a; f.alpha = a
    }

    override fun setColorFilter(cf: ColorFilter?) {
        p.colorFilter = cf; f.colorFilter = cf
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        if (s <= 0f) return
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f)
        canvas.scale(s, s)
        p.color = color; f.color = color
        p.strokeWidth = 1.9f
        c = canvas
        paint(ic)
        canvas.restore()
    }

    private fun ln(x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, p)
    private fun poly(vararg v: Float, close: Boolean = false) {
        path.reset()
        path.moveTo(v[0], v[1])
        var i = 2
        while (i + 1 < v.size) { path.lineTo(v[i], v[i + 1]); i += 2 }
        if (close) path.close()
        c.drawPath(path, p)
    }

    private fun circ(x: Float, y: Float, rad: Float) = c.drawCircle(x, y, rad, p)
    private fun dot(x: Float, y: Float, rad: Float) = c.drawCircle(x, y, rad, f)
    private fun arc(x: Float, y: Float, rad: Float, st: Float, sw: Float) {
        r.set(x - rad, y - rad, x + rad, y + rad); c.drawArc(r, st, sw, false, p)
    }

    private fun rr(l: Float, t: Float, rt: Float, b: Float, rad: Float) {
        r.set(l, t, rt, b); c.drawRoundRect(r, rad, rad, p)
    }

    private fun paint(i: Ic) {
        when (i) {
            Ic.BLUETOOTH -> poly(7f, 7f, 17f, 17f, 12f, 22f, 12f, 2f, 17f, 7f, 7f, 17f)
            Ic.BLE -> { poly(9f, 7f, 17f, 17f, 13f, 21f, 13f, 3f, 17f, 7f, 9f, 17f); dot(4.5f, 12f, 1.4f); dot(21f, 12f, 1.4f) }
            Ic.WIFI -> { arc(12f, 19f, 5f, 225f, 90f); arc(12f, 19f, 9f, 225f, 90f); arc(12f, 19f, 13f, 225f, 90f); dot(12f, 19f, 1.5f) }
            Ic.PLUG -> {
                poly(3f, 8f, 21f, 8f, 19f, 16f, 5f, 16f, close = true)
                for (x in listOf(6.5f, 9.5f, 12.5f, 15.5f, 18f)) dot(x, 10.6f, 0.9f)
                for (x in listOf(8f, 12f, 16f)) dot(x, 13.4f, 0.9f)
                ln(12f, 16f, 12f, 21f)
            }
            Ic.GAUGE -> { arc(12f, 13f, 9f, 150f, 240f); ln(12f, 13f, 16.5f, 8.5f); dot(12f, 13f, 1.7f) }
            Ic.CHART -> { poly(3f, 3f, 3f, 21f, 21f, 21f); poly(7f, 16f, 11f, 11f, 14f, 14f, 20f, 7f) }
            Ic.ROUTE -> {
                circ(6f, 18f, 2.2f); circ(18f, 6f, 2.2f)
                path.reset(); path.moveTo(6f, 15.8f); path.cubicTo(6f, 9f, 18f, 15f, 18f, 8.2f); c.drawPath(path, p)
            }
            Ic.ENGINE -> {
                poly(5f, 10f, 8f, 10f, 9.5f, 7.5f, 15.5f, 7.5f, 17f, 10f, 20f, 10f, 20f, 17f, 5f, 17f, close = true)
                ln(2f, 12.5f, 5f, 12.5f); ln(20f, 13f, 22.5f, 13f); ln(10f, 7.5f, 10f, 5f); ln(14f, 7.5f, 14f, 5f)
            }
            Ic.GRID -> { rr(3f, 3f, 10f, 10f, 1.6f); rr(14f, 3f, 21f, 10f, 1.6f); rr(3f, 14f, 10f, 21f, 1.6f); rr(14f, 14f, 21f, 21f, 1.6f) }
            Ic.FUEL -> {
                rr(5f, 3f, 14f, 21f, 1.8f); rr(7.5f, 6f, 11.5f, 10f, 0.6f); ln(3f, 21f, 16f, 21f)
                path.reset(); path.moveTo(14f, 8f); path.lineTo(16.5f, 8f); path.quadTo(18.5f, 8f, 18.5f, 10f)
                path.lineTo(18.5f, 16f); path.quadTo(18.5f, 18f, 20.5f, 18f); path.lineTo(20.5f, 9f); path.lineTo(18f, 6f)
                c.drawPath(path, p)
            }
            Ic.WRENCH -> { ln(13.5f, 10.5f, 5f, 19f); circ(17f, 7f, 4f); ln(15f, 9f, 13f, 11f) }
            Ic.BELL -> {
                path.reset(); path.moveTo(6f, 16f); path.lineTo(6f, 11f)
                r.set(6f, 5f, 18f, 17f); path.arcTo(r, 180f, 180f)
                path.lineTo(18f, 16f); path.lineTo(20f, 18f); path.lineTo(4f, 18f); path.close()
                c.drawPath(path, p); ln(10f, 21f, 14f, 21f); ln(12f, 3f, 12f, 5f)
            }
            Ic.BATTERY -> {
                rr(2.5f, 7f, 19.5f, 17f, 2f); c.drawRect(19.5f, 10.5f, 21.5f, 13.5f, f)
                poly(12.5f, 9f, 9.5f, 12.5f, 13.5f, 12.5f, 11f, 16f)
            }
            Ic.TIMER -> { circ(12f, 13.5f, 7.5f); ln(10f, 2.5f, 14f, 2.5f); ln(12f, 13.5f, 12f, 9f); ln(18.2f, 7.8f, 19.6f, 6.4f) }
            Ic.CAR -> {
                poly(3f, 16f, 3f, 12f, 6f, 8f, 15f, 8f, 19f, 12f, 21f, 13f, 21f, 16f, close = true)
                poly(7.5f, 12f, 9f, 9.5f, 14f, 9.5f, 16.5f, 12f, close = true)
                circ(7.5f, 16.5f, 2.2f); circ(16.5f, 16.5f, 2.2f)
            }
            Ic.GEAR -> {
                circ(12f, 12f, 3f); circ(12f, 12f, 6.5f)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    ln(12f + 6.5f * cos(a).toFloat(), 12f + 6.5f * sin(a).toFloat(), 12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat())
                }
            }
            Ic.INFO -> { circ(12f, 12f, 9.5f); dot(12f, 7.8f, 1.3f); ln(12f, 11f, 12f, 17f) }
            Ic.PIN -> {
                path.reset(); path.moveTo(12f, 21.5f); path.cubicTo(6f, 14.5f, 5f, 11.5f, 5f, 9.5f)
                r.set(5f, 2.5f, 19f, 16.5f); path.arcTo(r, 180f, 180f); path.cubicTo(19f, 11.5f, 18f, 14.5f, 12f, 21.5f)
                c.drawPath(path, p); circ(12f, 9.5f, 2.5f)
            }
            Ic.PULSE -> poly(2f, 12f, 7f, 12f, 9.5f, 5f, 14f, 19f, 16.5f, 12f, 22f, 12f)
            Ic.THERM -> { rr(10f, 3f, 14f, 15f, 2f); circ(12f, 17.5f, 3.2f); ln(12f, 8f, 12f, 16f); ln(16f, 6f, 18f, 6f); ln(16f, 9f, 18f, 9f) }
            Ic.DOC -> { poly(6f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 6f, 21f, close = true); poly(14f, 3f, 14f, 8f, 19f, 8f); ln(9f, 13f, 16f, 13f); ln(9f, 17f, 16f, 17f) }
            Ic.TRASH -> { ln(4f, 6.5f, 20f, 6.5f); poly(9f, 6.5f, 9f, 4f, 15f, 4f, 15f, 6.5f); poly(6f, 6.5f, 7f, 21f, 17f, 21f, 18f, 6.5f); ln(10f, 10f, 10f, 17f); ln(14f, 10f, 14f, 17f) }
            Ic.DOWNLOAD -> { ln(12f, 3f, 12f, 15f); poly(7f, 10.5f, 12f, 15.5f, 17f, 10.5f); poly(4f, 16f, 4f, 21f, 20f, 21f, 20f, 16f) }
            Ic.UPLOAD -> { ln(12f, 15f, 12f, 3f); poly(7f, 7.5f, 12f, 2.5f, 17f, 7.5f); poly(4f, 16f, 4f, 21f, 20f, 21f, 20f, 16f) }
            Ic.SCAN -> { poly(3f, 8f, 3f, 3f, 8f, 3f); poly(16f, 3f, 21f, 3f, 21f, 8f); poly(21f, 16f, 21f, 21f, 16f, 21f); poly(8f, 21f, 3f, 21f, 3f, 16f); ln(6f, 12f, 18f, 12f) }
            Ic.CHECK -> poly(4f, 12.5f, 9.5f, 18f, 20f, 6.5f)
            Ic.WARN -> { poly(12f, 3f, 22f, 20f, 2f, 20f, close = true); ln(12f, 9.5f, 12f, 14.5f); dot(12f, 17.2f, 1.2f) }
            Ic.LEAF -> {
                path.reset(); path.moveTo(5f, 19f); path.cubicTo(5f, 9f, 11f, 4f, 20f, 4f); path.cubicTo(20f, 13f, 15f, 19f, 5f, 19f)
                c.drawPath(path, p); ln(5f, 19f, 13f, 11f)
            }
            Ic.TERMINAL -> { rr(2.5f, 4f, 21.5f, 20f, 2.5f); poly(6.5f, 9f, 10f, 12f, 6.5f, 15f); ln(12f, 16f, 17.5f, 16f) }
            Ic.SAVE -> { poly(4f, 4f, 17f, 4f, 20f, 7f, 20f, 20f, 4f, 20f, close = true); poly(7f, 4f, 7f, 9f, 15f, 9f, 15f, 4f); poly(7f, 20f, 7f, 14f, 17f, 14f, 17f, 20f) }
            Ic.REFRESH -> { arc(12f, 12f, 8f, 30f, 300f); poly(19f, 3.5f, 19f, 8.5f, 14f, 8.5f) }
            Ic.MONITOR -> { rr(2.5f, 4f, 21.5f, 16f, 2f); ln(8f, 20f, 16f, 20f); ln(12f, 16f, 12f, 20f) }
            Ic.POWER -> { arc(12f, 13f, 8f, -60f, 300f); ln(12f, 3f, 12f, 12f) }
            Ic.PLUS -> { ln(12f, 5f, 12f, 19f); ln(5f, 12f, 19f, 12f) }
            Ic.SHARE -> { circ(6f, 12f, 2.5f); circ(18f, 6f, 2.5f); circ(18f, 18f, 2.5f); ln(8.2f, 10.8f, 15.8f, 7.2f); ln(8.2f, 13.2f, 15.8f, 16.8f) }
            Ic.SLIDERS -> {
                ln(4f, 7f, 20f, 7f); ln(4f, 12f, 20f, 12f); ln(4f, 17f, 20f, 17f)
                dot(9f, 7f, 2.4f); dot(15f, 12f, 2.4f); dot(8f, 17f, 2.4f)
            }
            Ic.VOICE -> { poly(3f, 9f, 7f, 9f, 12f, 5f, 12f, 19f, 7f, 15f, 3f, 15f, close = true); arc(12f, 12f, 5f, -45f, 90f); arc(12f, 12f, 9f, -45f, 90f) }
            Ic.BOLT -> poly(13f, 2f, 5f, 13f, 11f, 13f, 10f, 22f, 19f, 10f, 13f, 10f, close = true)
            Ic.CALENDAR -> { rr(3.5f, 5f, 20.5f, 21f, 2f); ln(3.5f, 10f, 20.5f, 10f); ln(8f, 3f, 8f, 7f); ln(16f, 3f, 16f, 7f); dot(8f, 14f, 1f); dot(12f, 14f, 1f); dot(16f, 14f, 1f); dot(8f, 17.5f, 1f); dot(12f, 17.5f, 1f) }
            Ic.DROP -> {
                path.reset(); path.moveTo(12f, 3f); path.cubicTo(16f, 8f, 18.5f, 11f, 18.5f, 14.5f)
                path.cubicTo(18.5f, 18.4f, 15.6f, 21f, 12f, 21f); path.cubicTo(8.4f, 21f, 5.5f, 18.4f, 5.5f, 14.5f)
                path.cubicTo(5.5f, 11f, 8f, 8f, 12f, 3f); path.close(); c.drawPath(path, p)
            }
            Ic.WIND -> {
                path.reset(); path.moveTo(3f, 9f); path.lineTo(14f, 9f); path.cubicTo(18.5f, 9f, 18.5f, 3.5f, 14.5f, 4f); c.drawPath(path, p)
                path.reset(); path.moveTo(3f, 15f); path.lineTo(17f, 15f); path.cubicTo(21.5f, 15f, 21.5f, 20.5f, 17.5f, 20f); c.drawPath(path, p)
                ln(3f, 12f, 10f, 12f)
            }
            Ic.DATABASE -> {
                r.set(5f, 3.5f, 19f, 8.5f); c.drawOval(r, p)
                ln(5f, 6f, 5f, 18f); ln(19f, 6f, 19f, 18f)
                r.set(5f, 8f, 19f, 13f); c.drawArc(r, 0f, 180f, false, p)
                r.set(5f, 13f, 19f, 18f); c.drawArc(r, 0f, 180f, false, p)
                ln(5f, 18f, 5f, 18f)
            }
            Ic.EDIT -> poly(4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, close = true)
            Ic.CLOCK -> { circ(12f, 12f, 9.5f); poly(12f, 6.5f, 12f, 12f, 16f, 14.5f) }
            Ic.SHIELD -> {
                path.reset(); path.moveTo(12f, 2.5f); path.lineTo(20f, 5.5f); path.lineTo(20f, 12f); path.cubicTo(20f, 17f, 16f, 20.5f, 12f, 21.5f)
                path.cubicTo(8f, 20.5f, 4f, 17f, 4f, 12f); path.lineTo(4f, 5.5f); path.close(); c.drawPath(path, p)
                poly(8.5f, 12f, 11f, 14.5f, 15.5f, 9.5f)
            }
            Ic.PLAY -> poly(7f, 4f, 20f, 12f, 7f, 20f, close = true)
            Ic.STOP -> rr(5f, 5f, 19f, 19f, 2f)
            Ic.BACK -> poly(15f, 5f, 8f, 12f, 15f, 19f)
            Ic.CODE -> { poly(8f, 7f, 3f, 12f, 8f, 17f); poly(16f, 7f, 21f, 12f, 16f, 17f); ln(14f, 5f, 10f, 19f) }
            Ic.USER -> { circ(12f, 8f, 4f); arc(12f, 21f, 8f, 180f, 180f) }
            Ic.SEARCH -> { circ(10.5f, 10.5f, 6.5f); ln(15.5f, 15.5f, 21f, 21f) }
            Ic.FLAG -> { ln(5f, 3f, 5f, 21f); poly(5f, 4f, 18f, 4f, 15f, 8.5f, 18f, 13f, 5f, 13f) }
            Ic.CHEV -> poly(9f, 5f, 16f, 12f, 9f, 19f)
        }
    }
}

/** Convenience: an ImageView showing [ic]. */
fun iconView(ctx: Context, ic: Ic, color: Int, sizeDp: Int): ImageView {
    val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
    return ImageView(ctx).apply {
        setImageDrawable(IconDrawable(ic, color, px))
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = android.widget.LinearLayout.LayoutParams(px, px)
    }
}
