package com.dilip.carobd

import android.app.AlertDialog
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.SeekBar

/** Teach and tune the ADAS: lane centre, lane sensitivity, horizon line, car width for distance. Used on the Drive vision panel and the ADAS page. */
object AdasTune {
    fun build(a: MainActivity): LinearLayout {
        val ui = a.ui
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun slider(title: String, min: Int, max: Int, cur: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
            val tv = ui.tv("$title: ${fmt(cur)}", 12.5f, Ui.WHITE)
            box.addView(tv)
            box.addView(SeekBar(a).apply {
                this.max = max - min; progress = cur - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { set(min + v); tv.text = "$title: ${fmt(min + v)}" }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        box.addView(ui.tv("Teach your ADAS", 14f, Ui.CYAN, true))
        box.addView(ui.tv("1. Mount the phone straight, facing the road. 2. Drive straight in the middle of your lane and tap “Teach lane centre”. 3. Behind a car at a known distance, tap “Teach distance”.", 11.5f, Ui.DIM))
        box.addView(ui.btn("Teach lane centre (now)", Ui.GOOD) { Adas.recalibrate(); ui.toast("Lane centre learned – keep driving in the middle for a moment") }, ui.lp(4))
        box.addView(ui.btn("Teach distance to the car ahead", Ui.VIOLET) {
            val v = Adas.veh
            if (v == null) { ui.toast("No vehicle ahead is being tracked yet"); return@btn }
            val et = ui.edit("Real distance in metres", "%.0f".format(v.dist), true)
            AlertDialog.Builder(a).setTitle("How far is the car ahead really?")
                .setMessage("The app now estimates %.1f m. Enter the real distance (for example from a road marking).".format(v.dist)).setView(et)
                .setPositiveButton("Teach") { _, _ ->
                    val m = et.text.toString().replace(',', '.').toDoubleOrNull()
                    val cm = if (m != null) Adas.teachDistance(m) else null
                    ui.toast(if (cm != null) "Learned: assumed car width ${cm / 100.0} m" else "Could not learn from that value")
                }.setNegativeButton("Cancel", null).show()
        }, ui.lp(4))
        slider("Lane warning sensitivity", 15, 45, Prefs.adasDep, { if (it <= 22) "high ($it%)" else if (it >= 36) "relaxed ($it%)" else "normal ($it%)" }) { Prefs.adasDep = it }
        slider("Horizon line (where lane search starts)", 35, 70, Prefs.adasHorizon, { "$it% from top" }) { Prefs.adasHorizon = it }
        slider("Assumed width of cars ahead", 140, 300, Prefs.adasCarW, { "%.2f m".format(it / 100.0) }) { Prefs.adasCarW = it }
        box.addView(ui.btn("Reset to defaults", Ui.CARD) { Prefs.adasDep = 30; Prefs.adasHorizon = 52; Prefs.adasCarW = 180; Adas.recalibrate(); ui.toast("ADAS tuning reset – reopen this panel to see the sliders") }, ui.lp(4))
        return box
    }
}
