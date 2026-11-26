package com.dilip.carobd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val START = 135f
private const val SWEEP = 270f

/**
 * Animated analogue gauge: gradient progress arc, tick marks with labels, redline band,
 * tapered needle, peak marker and digital read-out. Long-press resets the peak.
 */
class GaugeView(
    ctx: Context,
    private val label: String,
    private val unit: String,
    var max: Float,
    var redFrom: Float,
    private val labelDiv: Float = 1f
) : View(ctx) {
    private var target: Float? = null
    private var shown = 0f
    private var peak = 0f

    var value: Float?
        get() = target
        set(v) {
            target = v
            if (v != null && v > peak) peak = v
            postInvalidateOnAnimation()
        }

    private val dens = ctx.resources.displayMetrics.density
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#2C3845") }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#1F2831")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#C62828") }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8996A3"); strokeCap = Paint.Cap.ROUND }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9D3DC"); textAlign = Paint.Align.CENTER }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA7B4"); textAlign = Paint.Align.CENTER }
    private val needleP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB300") }
    private val rect = RectF()
    private val path = Path()
    private val m = Matrix()
    private var lastW = 0

    init {
        setOnLongClickListener { peak = target ?: 0f; invalidate(); true }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.92f).toInt())
    }

    private fun niceStep(range: Float): Float {
        val raw = range / 8f
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw.toDouble()))).toFloat()
        val n = raw / mag
        return mag * when {
            n < 1.5f -> 1f
            n < 3f -> 2f
            n < 7f -> 5f
            else -> 10f
        }
    }

    override fun onDraw(c: Canvas) {
        val size = min(width.toFloat(), height / 0.92f)
        val cx = width / 2f
        val r = size / 2f * 0.94f
        val cy = r + size * 0.03f
        val stroke = size * 0.055f

        // ease the displayed value towards the target
        val tgt = target ?: 0f
        val diff = tgt - shown
        shown += diff * 0.22f
        if (abs(diff) > max * 0.002f) postInvalidateOnAnimation() else shown = tgt
        val frac = (shown / max).coerceIn(0f, 1f)

        if (width != lastW) {
            lastW = width
            face.shader = RadialGradient(cx, cy - r * 0.3f, r * 1.2f,
                Color.parseColor("#1E2933"), Color.parseColor("#0B0F13"), Shader.TileMode.CLAMP)
            val sg = SweepGradient(cx, cy,
                intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#2F7FD0"), Color.parseColor("#7C4DFF"),
                    Color.parseColor("#FFB300"), Color.parseColor("#FF1744"), Color.parseColor("#FF1744")),
                floatArrayOf(0f, 0.3f, 0.5f, 0.62f, 0.75f, 1f))
            m.reset(); m.setRotate(START, cx, cy); sg.setLocalMatrix(m)
            arc.shader = sg
            glow.shader = sg
            needleP.shader = LinearGradient(0f, 0f, r, 0f, Color.parseColor("#FF6E40"), Color.parseColor("#FF1744"), Shader.TileMode.CLAMP)
            hub.shader = RadialGradient(cx, cy, size * 0.07f, Color.parseColor("#5C6B7A"), Color.parseColor("#1A2129"), Shader.TileMode.CLAMP)
        }

        // dial face + rim
        c.drawCircle(cx, cy, r, face)
        rim.strokeWidth = size * 0.012f
        c.drawCircle(cx, cy, r, rim)

        val ra = r - stroke * 1.2f
        rect.set(cx - ra, cy - ra, cx + ra, cy + ra)
        track.strokeWidth = stroke
        c.drawArc(rect, START, SWEEP, false, track)

        // redline band (outer edge)
        if (redFrom < max) {
            val rr = r - stroke * 0.35f
            red.strokeWidth = stroke * 0.45f
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            val s0 = START + SWEEP * (redFrom / max)
            c.drawArc(rect, s0, START + SWEEP - s0, false, red)
            rect.set(cx - ra, cy - ra, cx + ra, cy + ra)
        }

        // progress arc + glow
        if (target != null && frac > 0.002f) {
            glow.strokeWidth = stroke * 2.2f
            glow.alpha = 50
            c.drawArc(rect, START, SWEEP * frac, false, glow)
            arc.strokeWidth = stroke
            c.drawArc(rect, START, SWEEP * frac, false, arc)
        }

        // ticks + labels
        val step = niceStep(max)
        val minor = step / 5f
        var v = 0f
        num.textSize = size * 0.058f
        while (v <= max + 0.001f) {
            val ang = Math.toRadians((START + SWEEP * v / max).toDouble())
            val major = abs((v / step) - Math.round(v / step)) < 0.01f
            val r1 = ra - stroke * 0.9f
            val r2 = r1 - if (major) size * 0.05f else size * 0.025f
            tick.strokeWidth = if (major) 2.6f * dens else 1.2f * dens
            tick.color = if (v >= redFrom) Color.parseColor("#FF5252") else Color.parseColor("#8996A3")
            c.drawLine(cx + (r1 * cos(ang)).toFloat(), cy + (r1 * sin(ang)).toFloat(),
                cx + (r2 * cos(ang)).toFloat(), cy + (r2 * sin(ang)).toFloat(), tick)
            if (major) {
                val rt = r2 - size * 0.055f
                val lbl = v / labelDiv
                c.drawText(if (lbl % 1f == 0f) "%.0f".format(lbl) else "%.1f".format(lbl),
                    cx + (rt * cos(ang)).toFloat(), cy + (rt * sin(ang)).toFloat() + num.textSize * 0.35f, num)
            }
            v += minor
        }

        // peak marker
        if (peak > 0f) {
            val pa = Math.toRadians((START + SWEEP * (peak / max).coerceIn(0f, 1f)).toDouble())
            val pr = ra + stroke * 0.9f
            c.drawCircle(cx + (pr * cos(pa)).toFloat(), cy + (pr * sin(pa)).toFloat(), size * 0.014f, peakP)
        }

        // needle
        val na = START + SWEEP * frac
        c.save()
        c.rotate(na, cx, cy)
        path.reset()
        val nl = ra - stroke * 0.6f
        path.moveTo(cx - size * 0.06f, cy - size * 0.012f)
        path.lineTo(cx + nl, cy - size * 0.003f)
        path.lineTo(cx + nl, cy + size * 0.003f)
        path.lineTo(cx - size * 0.06f, cy + size * 0.012f)
        path.close()
        c.drawPath(path, needleP)
        c.restore()
        c.drawCircle(cx, cy, size * 0.06f, hub)

        // digital read-out
        big.textSize = size * 0.15f
        small.textSize = size * 0.06f
        val ty = cy + r * 0.52f
        big.color = if (target != null && tgt >= redFrom) Color.parseColor("#FF5252") else Color.WHITE
        c.drawText(target?.let { "%.0f".format(it) } ?: "--", cx, ty, big)
        c.drawText(unit, cx, ty + small.textSize * 1.25f, small)
        small.textSize = size * 0.055f
        c.drawText(label.uppercase(), cx, cy - r * 0.32f, small)
    }
}

/** Compact ring gauge for a single value with green/amber/red zones. */
class RingGauge(
    ctx: Context,
    private val label: String,
    private val unit: String,
    private val min: Float,
    private val max: Float,
    private val decimals: Int = 0,
    /** returns 0 = normal, 1 = warning, 2 = critical */
    private val zone: (Float) -> Int = { 0 }
) : View(ctx) {
    var value: Float? = null
        set(v) {
            field = v; invalidate()
        }
    var subText: String = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#26303A")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#161C22") }
    private val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val t2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA7B4"); textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, (s * 1.12f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val cx = s / 2f
        val cy = s / 2f
        val st = s * 0.085f
        val r = s / 2f - st
        c.drawCircle(cx, cy, s / 2f - st * 0.3f, bg)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = st
        arc.strokeWidth = st
        c.drawArc(rect, 135f, 270f, false, track)
        val v = value
        if (v != null) {
            val f = ((v - min) / (max - min)).coerceIn(0f, 1f)
            arc.color = when (zone(v)) {
                2 -> Color.parseColor("#FF5252")
                1 -> Color.parseColor("#FFB300")
                else -> Color.parseColor("#26C6DA")
            }
            c.drawArc(rect, 135f, maxOf(270f * f, 1f), false, arc)
        }
        t1.textSize = s * 0.2f
        t2.textSize = s * 0.1f
        t1.color = if (v != null && zone(v) == 2) Color.parseColor("#FF5252") else Color.WHITE
        c.drawText(v?.let { "%.${decimals}f".format(it) } ?: "--", cx, cy + t1.textSize * 0.25f, t1)
        c.drawText(unit, cx, cy + t1.textSize * 0.25f + t2.textSize * 1.2f, t2)
        t2.textSize = s * 0.105f
        c.drawText(if (subText.isNotEmpty()) "$label · $subText" else label, cx, s * 1.08f, t2)
    }
}

/** Friction-circle G-meter: longitudinal (braking/accel) vs lateral (cornering) g with a fading trail. */
class GMeterView(ctx: Context) : View(ctx) {
    private val trail = ArrayDeque<Pair<Float, Float>>()
    private var peakAcc = 0f
    private var peakBrk = 0f
    private var peakLat = 0f
    private var lon = 0f
    private var lat = 0f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#33414E") }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA7B4"); textAlign = Paint.Align.CENTER }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        setOnLongClickListener { peakAcc = 0f; peakBrk = 0f; peakLat = 0f; invalidate(); true }
    }

    /** [longG] positive = accelerating, [latG] positive = turning right. */
    fun push(longG: Float, latG: Float) {
        lon = longG.coerceIn(-1f, 1f); lat = latG.coerceIn(-1f, 1f)
        trail.addLast(lat to lon)
        while (trail.size > 40) trail.removeFirst()
        if (lon > peakAcc) peakAcc = lon
        if (-lon > peakBrk) peakBrk = -lon
        if (abs(lat) > peakLat) peakLat = abs(lat)
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val cx = s / 2f
        val cy = s / 2f
        val r = s / 2f * 0.86f
        val scale = r / 0.6f   // full radius = 0.6 g
        bg.shader = RadialGradient(cx, cy, r, Color.parseColor("#1A2530"), Color.parseColor("#0E1216"), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, bg)
        grid.strokeWidth = 1.2f
        for (g in listOf(0.2f, 0.4f, 0.6f)) c.drawCircle(cx, cy, g * scale, grid)
        c.drawLine(cx - r, cy, cx + r, cy, grid)
        c.drawLine(cx, cy - r, cx, cy + r, grid)
        // threshold ring (hard braking)
        ring.color = Color.parseColor("#55FF7043"); ring.strokeWidth = 2f
        c.drawCircle(cx, cy, Prefs.brakeG.toFloat() * scale, ring)

        trail.forEachIndexed { i, (la, lo) ->
            dot.color = Color.argb(40 + i * 4, 0, 229, 255)
            c.drawCircle(cx + la * scale, cy - lo * scale, s * 0.012f, dot)
        }
        val mag = sqrt(lat * lat + lon * lon)
        dot.color = when {
            mag >= Prefs.suddenG -> Color.parseColor("#FF1744")
            mag >= Prefs.accelG -> Color.parseColor("#FFB300")
            else -> Color.parseColor("#00E5FF")
        }
        c.drawCircle(cx + lat * scale, cy - lon * scale, s * 0.04f, dot)
        txt.textSize = s * 0.065f
        c.drawText("ACCEL", cx, cy - r - 2f + txt.textSize, txt)
        c.drawText("BRAKE", cx, cy + r - 6f, txt)
        txt.textSize = s * 0.075f
        txt.color = Color.WHITE
        c.drawText("%.2f g".format(mag), cx, cy + r * 0.5f, txt)
        txt.color = Color.parseColor("#9AA7B4")
        txt.textSize = s * 0.055f
        c.drawText("peak +%.2f  −%.2f  ↔%.2f".format(peakAcc, peakBrk, peakLat), cx, cy - r * 0.42f, txt)
    }
}

/** Horizontal economy bar: instant economy vs trip average, red (thirsty) → green (efficient). */
class EcoBar(ctx: Context) : View(ctx) {
    var instant: Float? = null
    var average: Float? = null
    var maxVal = 30f
    private val dens = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#26303A") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 3f }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (14 * dens).toInt())
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        rect.set(0f, h * 0.2f, w, h * 0.8f)
        c.drawRoundRect(rect, h, h, track)
        fill.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(Color.parseColor("#FF1744"), Color.parseColor("#FFB300"), Color.parseColor("#00E676")), null, Shader.TileMode.CLAMP)
        instant?.let {
            rect.set(0f, h * 0.2f, w * (it / maxVal).coerceIn(0.02f, 1f), h * 0.8f)
            c.drawRoundRect(rect, h, h, fill)
        }
        average?.let {
            val x = w * (it / maxVal).coerceIn(0f, 1f)
            c.drawLine(x, 0f, x, h, mark)
        }
    }
}
