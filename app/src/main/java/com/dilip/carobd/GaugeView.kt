package com.dilip.carobd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

class GaugeView(
    ctx: Context,
    private val label: String,
    private val unit: String,
    private val max: Float,
    private val redFrom: Float
) : View(ctx) {
    var value: Float? = null
        set(v) {
            field = v; invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#2A333D")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA7B4"); textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.8f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val size = min(width.toFloat(), height / 0.8f)
        val stroke = size * 0.07f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val cx = width / 2f
        val r = size / 2f - stroke
        val cy = r + stroke
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, 150f, 240f, false, track)
        val v = value
        if (v != null) {
            val frac = (v / max).coerceIn(0f, 1f)
            arc.color = when {
                v >= redFrom -> Color.parseColor("#FF5252")
                v >= redFrom * 0.8f -> Color.parseColor("#FFB300")
                else -> Color.parseColor("#2FA8FF")
            }
            c.drawArc(rect, 150f, 240f * frac, false, arc)
        }
        big.textSize = size * 0.2f
        small.textSize = size * 0.085f
        c.drawText(v?.let { "%.0f".format(it) } ?: "--", cx, cy + big.textSize * 0.35f, big)
        c.drawText(unit, cx, cy + big.textSize * 0.35f + small.textSize * 1.3f, small)
        c.drawText(label, cx, cy + r * 0.85f, small)
    }
}
