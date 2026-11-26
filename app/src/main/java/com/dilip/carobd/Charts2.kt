package com.dilip.carobd

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

private fun Context.px(v: Float) = v * resources.displayMetrics.density

/* ---------- Radar chart: driving behaviour profile ---------- */

class RadarView(ctx: Context) : View(ctx) {
    private var labels: List<String> = emptyList()
    private var vals: FloatArray = floatArrayOf()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#2E3A46"); strokeWidth = ctx.px(1f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(90, 47, 168, 255) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#2FA8FF"); strokeWidth = ctx.px(2.5f); strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C8D3DE"); textSize = ctx.px(11f); textAlign = Paint.Align.CENTER }
    private val path = Path()
    private var anim = 0f
    private var t0 = 0L

    fun set(l: List<String>, v: FloatArray) { labels = l; vals = v; t0 = System.currentTimeMillis(); anim = 0f; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val wd = MeasureSpec.getSize(w)
        setMeasuredDimension(wd, (wd * 0.82f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val n = labels.size
        if (n < 3) return
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - context.px(30f)
        anim = ((System.currentTimeMillis() - t0) / 700f).coerceIn(0f, 1f)
        val e = 1f - (1f - anim) * (1f - anim)
        for (ring in 1..4) {
            path.reset()
            for (i in 0 until n) {
                val a = -PI / 2 + 2 * PI * i / n
                val x = cx + (r * ring / 4f * cos(a)).toFloat(); val y = cy + (r * ring / 4f * sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close(); c.drawPath(path, grid)
        }
        for (i in 0 until n) {
            val a = -PI / 2 + 2 * PI * i / n
            c.drawLine(cx, cy, cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat(), grid)
            val lx = cx + ((r + context.px(16f)) * cos(a)).toFloat()
            val ly = cy + ((r + context.px(16f)) * sin(a)).toFloat() + context.px(4f)
            c.drawText(labels[i], lx, ly, txt)
        }
        path.reset()
        for (i in 0 until n) {
            val a = -PI / 2 + 2 * PI * i / n
            val rr = r * (vals.getOrElse(i) { 0f } / 100f).coerceIn(0.04f, 1f) * e
            val x = cx + (rr * cos(a)).toFloat(); val y = cy + (rr * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); c.drawPath(path, fill); c.drawPath(path, edge)
        for (i in 0 until n) {
            val a = -PI / 2 + 2 * PI * i / n
            val v = vals.getOrElse(i) { 0f }
            val rr = r * (v / 100f).coerceIn(0.04f, 1f) * e
            dot.color = if (v >= 75) Color.parseColor("#4CAF50") else if (v >= 50) Color.parseColor("#FFB300") else Color.parseColor("#E5484D")
            c.drawCircle(cx + (rr * cos(a)).toFloat(), cy + (rr * sin(a)).toFloat(), context.px(4f), dot)
        }
        if (anim < 1f) postInvalidateOnAnimation()
    }
}

/* ---------- Donut chart ---------- */

class DonutView(ctx: Context) : View(ctx) {
    private var slices: List<Triple<String, Float, Int>> = emptyList()
    private var centerTop = ""; private var centerBottom = ""
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ctx.px(26f); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA7B4"); textSize = ctx.px(11f); textAlign = Paint.Align.CENTER }
    private val leg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C8D3DE"); textSize = ctx.px(12f) }
    private val sw = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t0 = 0L

    fun set(s: List<Triple<String, Float, Int>>, top: String, bottom: String) {
        slices = s.filter { it.second > 0 }; centerTop = top; centerBottom = bottom; t0 = System.currentTimeMillis(); invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val wd = MeasureSpec.getSize(w)
        val rows = max(slices.size, 1)
        setMeasuredDimension(wd, max((wd * 0.46f).toInt(), (context.px(28f) * rows).toInt() + context.px(20f).toInt()))
    }

    override fun onDraw(c: Canvas) {
        val total = slices.sumOf { it.second.toDouble() }.toFloat()
        val d = min(height - context.px(16f), width * 0.44f)
        val stroke = d * 0.2f
        val l = context.px(10f) + stroke / 2; val t = (height - d) / 2 + stroke / 2
        rect.set(l, t, l + d - stroke, t + d - stroke)
        arc.strokeWidth = stroke
        val prog = ((System.currentTimeMillis() - t0) / 800f).coerceIn(0f, 1f)
        if (total <= 0f) {
            arc.color = Color.parseColor("#2E3A46"); c.drawArc(rect, 0f, 360f, false, arc)
        } else {
            var a = -90f
            for ((_, v, col) in slices) {
                val sweep = v / total * 360f * prog
                arc.color = col
                c.drawArc(rect, a, max(sweep - 1.5f, 0.5f), false, arc)
                a += v / total * 360f * prog
            }
        }
        c.drawText(centerTop, rect.centerX(), rect.centerY() + context.px(6f), big)
        c.drawText(centerBottom, rect.centerX(), rect.centerY() + context.px(22f), small)
        var y = max((height - slices.size * context.px(26f)) / 2f, context.px(8f)) + context.px(14f)
        val x = l + d + context.px(18f)
        for ((name, v, col) in slices) {
            sw.color = col
            c.drawRoundRect(x, y - context.px(10f), x + context.px(12f), y + context.px(2f), 4f, 4f, sw)
            c.drawText("$name  ${v.toInt()}", x + context.px(20f), y, leg)
            y += context.px(26f)
        }
        if (prog < 1f) postInvalidateOnAnimation()
    }
}

/* ---------- Calendar heatmap: km per day, last 12 weeks ---------- */

class HeatmapView(ctx: Context) : View(ctx) {
    private var km = FloatArray(0)     // oldest .. today
    private var firstDow = 0           // weekday column index of the first day (0 = Monday)
    private val cell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA7B4"); textSize = ctx.px(10f) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = ctx.px(1.5f) }

    fun set(kmPerDay: FloatArray, firstDayOfWeekIndex: Int) { km = kmPerDay; firstDow = firstDayOfWeekIndex; requestLayout(); invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val wd = MeasureSpec.getSize(w)
        setMeasuredDimension(wd, (context.px(7 * 17f) + context.px(20f)).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (km.isEmpty()) return
        val weeks = (km.size + firstDow + 6) / 7
        val gap = context.px(3f)
        val left = context.px(26f)
        val size = min((width - left - context.px(6f) - gap * (weeks - 1)) / weeks, context.px(17f) - gap)
        val mx = max(km.max(), 1f)
        val names = listOf("M", "", "W", "", "F", "", "S")
        for (r in 0 until 7) if (names[r].isNotEmpty()) c.drawText(names[r], context.px(6f), r * (size + gap) + size * 0.8f + context.px(2f), txt)
        for (i in km.indices) {
            val idx = i + firstDow
            val col = idx / 7; val row = idx % 7
            val x = left + col * (size + gap); val y = row * (size + gap) + context.px(2f)
            val f = km[i] / mx
            cell.color = if (km[i] <= 0f) Color.parseColor("#1F2A35")
            else lerp(Color.parseColor("#17496B"), Color.parseColor("#3DDC97"), f.coerceIn(0f, 1f))
            c.drawRoundRect(x, y, x + size, y + size, size * 0.22f, size * 0.22f, cell)
            if (i == km.size - 1) c.drawRoundRect(x, y, x + size, y + size, size * 0.22f, size * 0.22f, ring)
        }
    }

    private fun lerp(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
}

/* ---------- Shift-light LED bar ---------- */

class LedBarView(ctx: Context) : View(ctx) {
    var frac = 0f
        set(v) { field = v.coerceIn(0f, 1.2f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val n = 16

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(22f).toInt())

    override fun onDraw(c: Canvas) {
        val gap = context.px(4f)
        val wd = (width - gap * (n - 1)) / n
        val flash = frac >= 1f && (System.currentTimeMillis() / 120) % 2L == 0L
        val lit = (frac.coerceAtMost(1f) * n).roundToInt()
        for (i in 0 until n) {
            val x = i * (wd + gap)
            val base = when {
                i < n * 0.55 -> Color.parseColor("#3DDC97")
                i < n * 0.8 -> Color.parseColor("#FFC247")
                else -> Color.parseColor("#FF5D5D")
            }
            val on = i < lit || (flash && i >= n * 0.8)
            p.color = if (on) base else Color.argb(50, Color.red(base), Color.green(base), Color.blue(base))
            if (on) {
                glow.color = Color.argb(60, Color.red(base), Color.green(base), Color.blue(base))
                c.drawRoundRect(x - 2f, 0f, x + wd + 2f, height.toFloat(), 8f, 8f, glow)
            }
            c.drawRoundRect(x, context.px(3f), x + wd, height - context.px(3f), 6f, 6f, p)
        }
        if (frac >= 1f) postInvalidateDelayed(120)
    }
}

/* ---------- Stacked mini progress bars with icons (used for coach / achievements) ---------- */

class ProgressBarView(ctx: Context) : View(ctx) {
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color = Color.parseColor("#2FA8FF")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(8f).toInt())
    override fun onDraw(c: Canvas) {
        p.color = Color.parseColor("#1F2A35")
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, p)
        p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, color, Color.WHITE, Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, max(width * value, height.toFloat()), height.toFloat(), height / 2f, height / 2f, p)
        p.shader = null
    }
}

/* ---------- Mini sparkline for PID cards ---------- */

class SparkView(ctx: Context) : View(ctx) {
    private var data = FloatArray(0)
    var color = Color.parseColor("#2FA8FF")
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.px(1.8f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = Path()

    fun set(d: FloatArray) { data = d; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(30f).toInt())

    override fun onDraw(c: Canvas) {
        if (data.size < 2) return
        val mn = data.min(); val mx = data.max()
        val span = max(mx - mn, 1e-6f)
        val pad = context.px(2f)
        val h = height - pad * 2
        path.reset(); area.reset()
        for (i in data.indices) {
            val x = i * (width - 1f) / (data.size - 1)
            val y = pad + h - (data[i] - mn) / span * h
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, height.toFloat()); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(width.toFloat(), height.toFloat()); area.close()
        fill.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        line.color = color; c.drawPath(path, line)
    }
}
