package com.dilip.carobd

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One-tap hazard reporting at the current GPS position, reachable from Drive vision, Drive mode, Home, Road and the notification. */
object HazardQuick {
    val kinds = listOf("bump", "pothole", "curve", "camera", "school", "rail", "accident", "flood")
    private val colors = mapOf("bump" to Color.rgb(255, 179, 0), "pothole" to Color.rgb(255, 138, 61), "curve" to Color.rgb(0, 212, 232),
        "camera" to Color.rgb(229, 72, 77), "school" to Color.rgb(31, 164, 99), "rail" to Color.rgb(142, 107, 255),
        "accident" to Color.rgb(229, 72, 77), "flood" to Color.rgb(47, 128, 237))

    /** Saves and confirms by voice so the driver does not need to look. */
    fun save(kind: String): String {
        val ok = RoadAssist.reportHazard(kind)
        val msg = if (ok) "${RoadAssist.labelFor(kind)} saved" else "No GPS position yet – try again in a moment"
        try { Alerts.announce("hz-saved-${System.currentTimeMillis() / 2000}", msg, 0) } catch (_: Throwable) {}
        return msg
    }

    /** Draws the road-sign style symbol of a hazard kind inside a circle of radius r. */
    fun drawSymbol(c: android.graphics.Canvas, kind: String, cx: Float, cy: Float, r: Float, col: Int) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.style = android.graphics.Paint.Style.FILL; p.color = Color.argb(46, Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(cx, cy, r, p)
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = r * 0.09f; p.color = col; c.drawCircle(cx, cy, r * 0.96f, p)
        p.strokeWidth = r * 0.10f; p.strokeCap = android.graphics.Paint.Cap.ROUND; p.strokeJoin = android.graphics.Paint.Join.ROUND; p.color = Color.WHITE
        val u = r / 10f
        fun fill() { p.style = android.graphics.Paint.Style.FILL }
        fun line() { p.style = android.graphics.Paint.Style.STROKE }
        when (kind) {
            "bump" -> { line(); val pa = android.graphics.Path(); pa.moveTo(cx - 6 * u, cy + 3 * u); pa.cubicTo(cx - 3 * u, cy - 6 * u, cx + 3 * u, cy - 6 * u, cx + 6 * u, cy + 3 * u); c.drawPath(pa, p); c.drawLine(cx - 7 * u, cy + 3 * u, cx + 7 * u, cy + 3 * u, p)
                p.strokeWidth = r * 0.06f; c.drawLine(cx - 5 * u, cy + 6 * u, cx + 5 * u, cy + 6 * u, p) }
            "pothole" -> { fill(); p.color = Color.argb(200, 20, 20, 30); c.drawOval(android.graphics.RectF(cx - 6 * u, cy - 1.5f * u, cx + 6 * u, cy + 4 * u), p)
                line(); p.color = Color.WHITE; c.drawOval(android.graphics.RectF(cx - 6 * u, cy - 1.5f * u, cx + 6 * u, cy + 4 * u), p)
                p.strokeWidth = r * 0.07f; c.drawLine(cx - 3 * u, cy - 4 * u, cx - 1 * u, cy - 2 * u, p); c.drawLine(cx + 2 * u, cy - 5 * u, cx + 3.5f * u, cy - 2.5f * u, p) }
            "curve" -> { line(); val pa = android.graphics.Path(); pa.moveTo(cx - 4 * u, cy + 6 * u); pa.cubicTo(cx - 4 * u, cy - 1 * u, cx + 4 * u, cy + 1 * u, cx + 4 * u, cy - 5 * u); c.drawPath(pa, p)
                val h = android.graphics.Path(); h.moveTo(cx + 4 * u, cy - 7 * u); h.lineTo(cx + 1.5f * u, cy - 3.5f * u); h.lineTo(cx + 6.5f * u, cy - 3.5f * u); h.close(); fill(); c.drawPath(h, p) }
            "camera" -> { line(); c.drawRoundRect(android.graphics.RectF(cx - 7 * u, cy - 3.5f * u, cx + 7 * u, cy + 5 * u), 1.5f * u, 1.5f * u, p); c.drawCircle(cx, cy + 0.8f * u, 2.6f * u, p)
                fill(); c.drawRect(cx - 3 * u, cy - 6 * u, cx + 1 * u, cy - 3.5f * u, p); c.drawCircle(cx + 5 * u, cy - 1.5f * u, 0.8f * u, p) }
            "school" -> { fill(); c.drawCircle(cx - 3 * u, cy - 5 * u, 1.7f * u, p); c.drawCircle(cx + 3.5f * u, cy - 4 * u, 1.4f * u, p)
                line(); c.drawLine(cx - 3 * u, cy - 3 * u, cx - 3 * u, cy + 2 * u, p); c.drawLine(cx - 3 * u, cy + 2 * u, cx - 5 * u, cy + 7 * u, p); c.drawLine(cx - 3 * u, cy + 2 * u, cx - 1 * u, cy + 7 * u, p); c.drawLine(cx - 6 * u, cy - 1 * u, cx, cy - 1 * u, p)
                p.strokeWidth = r * 0.08f; c.drawLine(cx + 3.5f * u, cy - 2.5f * u, cx + 3.5f * u, cy + 1.5f * u, p); c.drawLine(cx + 3.5f * u, cy + 1.5f * u, cx + 2.2f * u, cy + 6 * u, p); c.drawLine(cx + 3.5f * u, cy + 1.5f * u, cx + 5 * u, cy + 6 * u, p) }
            "rail" -> { line(); c.drawLine(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 6 * u, p); c.drawLine(cx + 6 * u, cy - 6 * u, cx - 6 * u, cy + 6 * u, p)
                p.strokeWidth = r * 0.07f; c.drawLine(cx - 7 * u, cy + 7.5f * u, cx + 7 * u, cy + 7.5f * u, p) }
            "accident" -> { fill(); val pa = android.graphics.Path(); val pts = floatArrayOf(0f, -8f, 2f, -3f, 7f, -5f, 4f, -1f, 8f, 2f, 3f, 2.5f, 3f, 7f, 0f, 3.5f, -3f, 7f, -3f, 2.5f, -8f, 2f, -4f, -1f, -7f, -5f, -2f, -3f)
                pa.moveTo(cx + pts[0] * u, cy + pts[1] * u); var i = 2; while (i < pts.size) { pa.lineTo(cx + pts[i] * u, cy + pts[i + 1] * u); i += 2 }; pa.close(); c.drawPath(pa, p) }
            else -> { line(); for (k in 0..1) { val y = cy + (k * 4.5f - 1.5f) * u; val pa = android.graphics.Path(); pa.moveTo(cx - 7 * u, y)
                pa.cubicTo(cx - 4 * u, y - 3 * u, cx - 2 * u, y - 3 * u, cx, y); pa.cubicTo(cx + 2 * u, y + 3 * u, cx + 4 * u, y + 3 * u, cx + 7 * u, y); c.drawPath(pa, p) }
                fill(); c.drawCircle(cx, cy - 5.5f * u, 1.2f * u, p) }
        }
    }

    /** A card tile: coloured symbol circle with its name under it, in the app's card style. */
    private class Tile(a: MainActivity, val kind: String, val col: Int) : android.view.View(a) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val label = RoadAssist.labelFor(kind)
        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.style = android.graphics.Paint.Style.FILL; p.color = Ui.CARD; c.drawRoundRect(0f, 0f, w, h, 16 * resources.displayMetrics.density, 16 * resources.displayMetrics.density, p)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1.5f * resources.displayMetrics.density; p.color = Color.argb(140, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(1f, 1f, w - 1, h - 1, 16 * resources.displayMetrics.density, 16 * resources.displayMetrics.density, p)
            drawSymbol(c, kind, w / 2, h * 0.40f, Math.min(w * 0.36f, h * 0.30f), col)
            p.style = android.graphics.Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = true
            p.textSize = 11.5f * resources.displayMetrics.density; c.drawText(label, w / 2, h - 9 * resources.displayMetrics.density, p)
        }
    }

    /** Big symbol picker: one tap reports and closes. */
    fun show(a: MainActivity) {
        val ui = a.ui
        val grid = GridLayout(a).apply { columnCount = 4; setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(4)) }
        var dlg: AlertDialog? = null
        for (k in kinds) {
            val t = Tile(a, k, colors[k] ?: Ui.ACC).apply { setOnClickListener { ui.toast(save(k)); dlg?.dismiss() } }
            grid.addView(t, GridLayout.LayoutParams().apply { width = ui.dp(80); height = ui.dp(92); setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(grid)
            addView(ui.tv("Saved at your position. You will be warned here next time. Report only when it is safe, or let a passenger do it.", 11.5f, Ui.DIM).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) }) }
        dlg = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle("Report hazard here").setView(box)
            .setNeutralButton("Remove mine nearby") { _, _ -> ui.toast("Removed ${RoadAssist.removeMyHazardsHere()} hazard(s) within 80 m") }
            .setNegativeButton("Close", null).show()
    }
}
