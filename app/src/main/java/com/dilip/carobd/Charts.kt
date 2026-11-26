package com.dilip.carobd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.max

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** Simple single-series line chart. */
class LineChart(ctx: Context, private val heightDp: Int = 160) : View(ctx) {
    private var data = FloatArray(0)
    private var title = ""
    private var unit = ""
    private var color = Color.parseColor("#2FA8FF")
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2f) }
    private val grid = Paint().apply { color = Color.parseColor("#2A333D"); strokeWidth = 1f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA7B4"); textSize = ctx.dpf(11f) }
    private val path = Path()

    fun set(values: FloatArray, title: String, unit: String = "", color: Int = this.color) {
        data = values; this.title = title; this.unit = unit; this.color = color
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpf(heightDp.toFloat()).toInt())
    }

    override fun onDraw(c: Canvas) {
        val pad = context.dpf(8f)
        val top = context.dpf(22f)
        val w = width - pad * 2
        val h = height - top - pad
        c.drawColor(Color.parseColor("#1C232B"))
        for (i in 0..3) {
            val y = top + h * i / 3f
            c.drawLine(pad, y, pad + w, y, grid)
        }
        if (data.size < 2) {
            c.drawText("$title – no data yet", pad, top - context.dpf(6f), txt)
            return
        }
        var mn = data.min()
        var mx = data.max()
        if (mx - mn < 1e-3f) { mx += 1f; mn -= 1f }
        val last = data.last()
        c.drawText("%s   now %.1f%s   min %.1f   max %.1f".format(title, last, unit, data.min(), data.max()), pad, top - context.dpf(6f), txt)
        path.reset()
        for (i in data.indices) {
            val x = pad + w * i / (data.size - 1).toFloat()
            val y = top + h - (data[i] - mn) / (mx - mn) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        line.color = color
        c.drawPath(path, line)
    }
}

/** Vertical bar chart with labels. */
class BarChart(ctx: Context, private val heightDp: Int = 170) : View(ctx) {
    private var labels: List<String> = emptyList()
    private var values: List<Float> = emptyList()
    private var title = ""
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2F7FD0") }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA7B4"); textSize = ctx.dpf(10f) }

    fun set(labels: List<String>, values: List<Float>, title: String) {
        this.labels = labels; this.values = values; this.title = title
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpf(heightDp.toFloat()).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#1C232B"))
        val pad = context.dpf(8f)
        val top = context.dpf(22f)
        val bottom = height - context.dpf(18f)
        c.drawText(title, pad, top - context.dpf(6f), txt)
        if (values.isEmpty()) return
        val mx = max(values.max(), 0.001f)
        val slot = (width - pad * 2) / values.size
        for (i in values.indices) {
            val x = pad + slot * i
            val bh = (bottom - top) * values[i] / mx
            c.drawRect(x + slot * 0.15f, bottom - bh, x + slot * 0.85f, bottom, bar)
            if (values.size <= 16 || i % 2 == 0) c.drawText(labels.getOrElse(i) { "" }, x + slot * 0.1f, height - context.dpf(5f), txt)
            if (values[i] > 0 && values.size <= 14)
                c.drawText("%.0f".format(values[i]), x + slot * 0.15f, bottom - bh - context.dpf(2f), txt)
        }
    }
}

/** Route drawing without map tiles: GPS polyline, start/end and event markers. */
class RouteView(ctx: Context) : View(ctx) {
    private var pts: List<Pair<Double, Double>> = emptyList()
    private var marks: List<Triple<Double, Double, Int>> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(3f); color = Color.parseColor("#2FA8FF")
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA7B4"); textSize = ctx.dpf(11f) }
    private val path = Path()

    fun set(points: List<Pair<Double, Double>>, markers: List<Triple<Double, Double, Int>>) {
        pts = points; marks = markers; invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val wd = MeasureSpec.getSize(w)
        setMeasuredDimension(wd, (wd * 0.75f).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#1C232B"))
        if (pts.size < 2) {
            c.drawText("No GPS track for this trip", context.dpf(10f), context.dpf(20f), txt)
            return
        }
        val minLat = pts.minOf { it.first }; val maxLat = pts.maxOf { it.first }
        val minLon = pts.minOf { it.second }; val maxLon = pts.maxOf { it.second }
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = max((maxLon - minLon) * k, 1e-5)
        val spanY = max(maxLat - minLat, 1e-5)
        val pad = context.dpf(16f)
        val scale = minOf((width - pad * 2) / spanX, (height - pad * 2) / spanY)
        val ox = (width - spanX * scale) / 2
        val oy = (height - spanY * scale) / 2
        fun x(lon: Double) = (ox + (lon - minLon) * k * scale).toFloat()
        fun y(lat: Double) = (height - oy - (lat - minLat) * scale).toFloat()
        path.reset()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.second), y(p.first)) else path.lineTo(x(p.second), y(p.first)) }
        c.drawPath(path, line)
        val r = context.dpf(5f)
        for ((la, lo, col) in marks) {
            dot.color = col; c.drawCircle(x(lo), y(la), r, dot)
        }
        dot.color = Color.parseColor("#2E9E4F"); c.drawCircle(x(pts.first().second), y(pts.first().first), r * 1.4f, dot)
        dot.color = Color.parseColor("#D64545"); c.drawCircle(x(pts.last().second), y(pts.last().first), r * 1.4f, dot)
        val km = spanX * 111.32
        c.drawText("≈ %.1f km wide · green start · red end".format(km), context.dpf(8f), height - context.dpf(8f), txt)
    }
}
