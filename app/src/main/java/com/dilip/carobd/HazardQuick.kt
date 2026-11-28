package com.dilip.carobd

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One-tap hazard reporting at the current GPS position, reachable from Drive vision, Drive mode, Home, Road and the notification. */
object HazardQuick {
    val kinds = listOf("bump", "pothole", "curve", "camera", "school", "rail", "accident", "flood", "work", "animal", "police", "fog", "narrow", "hospital", "toll")
    private val colors = mapOf("bump" to Color.rgb(255, 179, 0), "pothole" to Color.rgb(255, 138, 61), "curve" to Color.rgb(0, 212, 232),
        "camera" to Color.rgb(229, 72, 77), "school" to Color.rgb(31, 164, 99), "rail" to Color.rgb(142, 107, 255),
        "accident" to Color.rgb(229, 72, 77), "flood" to Color.rgb(47, 128, 237), "work" to Color.rgb(255, 179, 0), "animal" to Color.rgb(176, 124, 76),
        "police" to Color.rgb(80, 130, 255), "fog" to Color.rgb(154, 167, 181), "narrow" to Color.rgb(255, 138, 61), "hospital" to Color.rgb(229, 72, 77), "toll" to Color.rgb(142, 107, 255))

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
            "work" -> { fill(); val pa = android.graphics.Path(); pa.moveTo(cx - 6 * u, cy + 6 * u); pa.lineTo(cx - 2 * u, cy - 6 * u); pa.lineTo(cx + 2 * u, cy - 6 * u); pa.lineTo(cx + 6 * u, cy + 6 * u); pa.close(); c.drawPath(pa, p)
                p.color = col; c.drawRect(cx - 4.2f * u, cy - 2 * u, cx + 4.2f * u, cy - 0.2f * u, p); c.drawRect(cx - 5.2f * u, cy + 2.4f * u, cx + 5.2f * u, cy + 4.2f * u, p) }
            "animal" -> { fill(); c.drawOval(android.graphics.RectF(cx - 6 * u, cy - 2 * u, cx + 4 * u, cy + 3 * u), p); c.drawCircle(cx + 5 * u, cy - 3.5f * u, 2 * u, p)
                line(); c.drawLine(cx - 4 * u, cy + 2 * u, cx - 4 * u, cy + 7 * u, p); c.drawLine(cx + 2 * u, cy + 2 * u, cx + 2 * u, cy + 7 * u, p); c.drawLine(cx + 5 * u, cy - 5 * u, cx + 4 * u, cy - 8 * u, p) }
            "police" -> { fill(); val pa = android.graphics.Path(); pa.moveTo(cx, cy - 7 * u); pa.lineTo(cx + 6 * u, cy - 4 * u); pa.lineTo(cx + 5 * u, cy + 3 * u); pa.lineTo(cx, cy + 7.5f * u); pa.lineTo(cx - 5 * u, cy + 3 * u); pa.lineTo(cx - 6 * u, cy - 4 * u); pa.close(); c.drawPath(pa, p)
                p.color = col; c.drawCircle(cx, cy - 0.5f * u, 2 * u, p) }
            "fog" -> { line(); for (k in 0..2) { val y = cy + (k * 4f - 4f) * u; c.drawLine(cx - 6 * u + (k % 2) * 2 * u, y, cx + 6 * u - ((k + 1) % 2) * 2 * u, y, p) } }
            "narrow" -> { line(); c.drawLine(cx - 6 * u, cy - 6.5f * u, cx - 2 * u, cy, p); c.drawLine(cx - 2 * u, cy, cx - 6 * u, cy + 6.5f * u, p); c.drawLine(cx + 6 * u, cy - 6.5f * u, cx + 2 * u, cy, p); c.drawLine(cx + 2 * u, cy, cx + 6 * u, cy + 6.5f * u, p) }
            "hospital" -> { fill(); c.drawRect(cx - 1.8f * u, cy - 6 * u, cx + 1.8f * u, cy + 6 * u, p); c.drawRect(cx - 6 * u, cy - 1.8f * u, cx + 6 * u, cy + 1.8f * u, p) }
            "toll" -> { line(); c.drawRect(cx - 6 * u, cy - 1 * u, cx + 6 * u, cy + 6 * u, p); c.drawLine(cx - 7 * u, cy - 4 * u, cx + 4 * u, cy - 4 * u, p); fill(); c.drawCircle(cx + 5.5f * u, cy - 4 * u, 1 * u, p) }
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
            drawSymbol(c, kind, w / 2, h * 0.42f, Math.min(w * 0.36f, h * 0.31f), col)
            p.style = android.graphics.Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = true
            p.textSize = 10f * resources.displayMetrics.density; var lb = label; while (p.measureText(lb) > w - 6 * resources.displayMetrics.density && lb.length > 4) lb = lb.dropLast(1); c.drawText(lb, w / 2, h - 7 * resources.displayMetrics.density, p)
        }
    }

    /** Compact symbol picker (scrolls, fits landscape): one tap reports and closes. */
    fun show(a: MainActivity) {
        val ui = a.ui
        val dm = a.resources.displayMetrics
        val availDp = (dm.widthPixels / dm.density) - 70f
        val tile = if (dm.heightPixels < dm.widthPixels) 62 else 72          // landscape: smaller
        val cols = (availDp / (tile + 8)).toInt().coerceIn(3, 6)
        val grid = GridLayout(a).apply { columnCount = cols; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        var dlg: AlertDialog? = null
        for (k in kinds) {
            val t = Tile(a, k, colors[k] ?: Ui.ACC).apply { setOnClickListener { ui.toast(save(k)); dlg?.dismiss() } }
            grid.addView(t, GridLayout.LayoutParams().apply { width = ui.dp(tile); height = ui.dp(tile + 12); setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        }
        val sv = android.widget.ScrollView(a).apply { addView(grid) }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL
            addView(sv, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            addView(ui.tv("Saved at your position and warned next time. Report only when safe.", 11f, Ui.DIM).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(2)) }) }
        dlg = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle("Report hazard here").setView(box)
            .setNeutralButton("Remove mine nearby") { _, _ -> ui.toast("Removed ${RoadAssist.removeMyHazardsHere()} hazard(s) within 80 m") }
            .setNegativeButton("Close", null).create()
        dlg.show()
        val maxH = (dm.heightPixels * 0.78f).toInt()
        dlg.window?.setLayout(Math.min(dm.widthPixels - ui.dp(24), ui.dp(cols * (tile + 6) + 40)), maxH)
    }
}
