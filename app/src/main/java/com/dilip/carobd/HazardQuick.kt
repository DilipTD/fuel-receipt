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

    /** Big-tile picker: one tap reports and closes. */
    fun show(a: MainActivity) {
        val ui = a.ui
        val grid = GridLayout(a).apply { columnCount = 4; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(4)) }
        var dlg: AlertDialog? = null
        for (k in kinds) {
            val t = TextView(a).apply {
                text = RoadAssist.labelFor(k); textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = ui.rounded(colors[k] ?: Ui.ACC, 14)
                setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                setOnClickListener { ui.toast(save(k)); dlg?.dismiss() }
            }
            grid.addView(t, GridLayout.LayoutParams().apply { width = ui.dp(76); height = ui.dp(66); setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(grid)
            addView(ui.tv("Saved at your position. You will be warned here next time. Report only when it is safe, or let a passenger do it.", 11.5f, Ui.DIM).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) }) }
        dlg = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle("Report hazard here").setView(box)
            .setNeutralButton("Remove mine nearby") { _, _ -> ui.toast("Removed ${RoadAssist.removeMyHazardsHere()} hazard(s) within 80 m") }
            .setNegativeButton("Close", null).show()
    }
}
