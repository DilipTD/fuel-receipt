package com.dilip.carobd

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import java.io.File
import java.util.Calendar

/* ============================ Analysis ============================ */

class AnalysisPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout
    private var days = 30

    override fun build(): View {
        box = ui.col()
        return ui.scroll(box)
    }

    override fun onShow() {
        box.removeAllViews()
        val v = ObdManager.vehicle
        val u = v.spec.unit
        box.addView(ui.row(
            *listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 0 to "All").map { (d, label) ->
                ui.btn(label, if (d == days) Ui.ACC else Ui.CARD) { days = d; onShow() }
            }.toTypedArray()
        ), ui.lp(6))

        val since = if (days == 0) 0L else System.currentTimeMillis() - days * 86_400_000L
        val trips = Db.trips(v.id, since).sortedBy { it.start }
        box.addView(ui.section("${v.title} – ${if (days == 0) "all time" else "last $days days"}"))
        if (trips.isEmpty()) {
            box.addView(ui.tv("No trips in this period yet.", 13f, Ui.DIM)); return
        }
        val km = trips.sumOf { it.distanceKm }
        val fuel = trips.sumOf { it.fuel }
        val cost = trips.sumOf { it.cost }
        val hours = trips.sumOf { it.durationS } / 3600.0
        val idle = trips.sumOf { it.idleS } / 3600.0
        val idleFuel = trips.sumOf { it.idleFuel }
        val co2 = trips.sumOf { it.co2Kg }
        val per100 = 100.0 / maxOf(km, 1.0)
        val score = trips.map { it.score.toDouble() * it.distanceKm }.sum() / maxOf(km, 0.1)
        val b100 = trips.map { it.best0100 }.filter { it > 0 }.minOrNull()
        val b60 = trips.map { it.best060 }.filter { it > 0 }.minOrNull()
        val obd = trips.sumOf { it.sumObdSpd }
        val gps = trips.sumOf { it.sumGpsSpd }

        box.addView(ui.facts(buildString {
            append("Trips          ${trips.size}\n")
            append("Distance       ${ui.f(km, 1, " km")}\n")
            append("Driving time   ${ui.f(hours, 1, " h")}  (idle ${ui.f(idle / maxOf(hours, 0.01) * 100, 0, "%")})\n")
            append("Fuel used      ${ui.f(fuel, 1, " $u")} (idle ${ui.f(idleFuel, 2, " $u")})\n")
            append("Economy        ${ui.econ(if (fuel > 0) km / fuel else null, u)}\n")
            append("Fuel cost      ${ui.money(cost)}  (${ui.f(if (km > 0) cost / km else null, 2, " ₹/km")})\n")
            append("CO2            ${ui.f(co2, 1, " kg")}\n")
            append("Avg speed      ${ui.f(km / maxOf(hours, 0.01), 1, " km/h")}\n")
            append("Max speed      ${ui.f(trips.maxOf { it.maxSpeed }, 0, " km/h")}\n")
            append("Driver score   ${score.toInt()}/100 (distance-weighted)\n")
            if (b60 != null) append("Best 0-60      ${ui.f(b60, 1, " s")}\n")
            if (b100 != null) append("Best 0-100     ${ui.f(b100, 1, " s")}\n")
            if (gps > 1000) append("Speedometer    %+.1f%% vs GPS\n".format((obd / gps - 1) * 100))
        }), ui.lp())

        // km per day, last 14 days
        val cal = Calendar.getInstance()
        val labels = mutableListOf<String>()
        val kmDay = mutableListOf<Float>()
        for (i in 13 downTo 0) {
            val c = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis; add(Calendar.DAY_OF_YEAR, -i)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val s = c.timeInMillis
            val e = s + 86_400_000L
            labels += c.get(Calendar.DAY_OF_MONTH).toString()
            kmDay += Db.trips(v.id, s).filter { it.start < e }.sumOf { it.distanceKm }.toFloat()
        }
        val bars = BarChart(a)
        bars.set(labels, kmDay, "Distance per day (last 14 days, km)")
        box.addView(bars, ui.lp(10))

        val econ = trips.mapNotNull { it.kmpl }.map { it.toFloat() }.toFloatArray()
        val ec = LineChart(a, 140)
        ec.set(econ, "Economy per trip", " km/$u", Color.parseColor("#66BB6A"))
        box.addView(ec, ui.lp(8))
        val sc = LineChart(a, 140)
        sc.set(trips.map { it.score.toFloat() }.toFloatArray(), "Driver score per trip", "", Color.parseColor("#FFB300"))
        box.addView(sc, ui.lp(8))

        val ra0 = trips.sumOf { it.rapidAcc }; val hb0 = trips.sumOf { it.hardBrake }; val sb0 = trips.sumOf { it.suddenBrake }
        val co0 = trips.sumOf { it.corner }; val osS0 = trips.sumOf { it.overspeedS }
        val orv0 = trips.sumOf { it.overRev } + trips.sumOf { it.coldRev }; val os0 = trips.sumOf { it.overspeed }; val li0 = trips.sumOf { it.longIdle }
        val p100 = 100.0 / maxOf(km, 1.0)
        fun cl(x: Double) = x.coerceIn(0.0, 100.0).toFloat()
        box.addView(ui.section("Driving profile", Ic.PULSE))
        val radar = RadarView(a)
        radar.set(listOf("Smooth", "Braking", "Cornering", "Speed", "Idle", "Engine"), floatArrayOf(
            cl(100 - ra0 * p100 * 8), cl(100 - (hb0 + sb0 * 2) * p100 * 8), cl(100 - co0 * p100 * 10),
            cl(100 - osS0 / maxOf(hours * 3600, 1.0) * 500), cl(100 - idle / maxOf(hours, 0.01) * 300), cl(100 - orv0 * p100 * 10)))
        box.addView(radar, ui.lp(6))
        val donut = DonutView(a)
        val totalEv = ra0 + hb0 + sb0 + co0 + os0 + orv0 + li0
        donut.set(listOf(
            Triple("Rapid acc", ra0.toFloat(), Ui.ORANGE), Triple("Hard brake", hb0.toFloat(), Ui.WARN),
            Triple("Sudden brake", sb0.toFloat(), Ui.BAD), Triple("Cornering", co0.toFloat(), Ui.VIOLET),
            Triple("Overspeed", os0.toFloat(), Ui.PINK), Triple("Over-rev", orv0.toFloat(), Ui.CYAN),
            Triple("Long idle", li0.toFloat(), Ui.DIM)), "$totalEv", "events")
        box.addView(donut, ui.lp(8))

        // 12-week calendar heatmap + streak + badges
        run {
            val dayMs = 86_400_000L
            val c0 = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -83); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val s0 = c0.timeInMillis
            val arr = FloatArray(84)
            for (t in Db.trips(v.id, s0)) { val i = ((t.start - s0) / dayMs).toInt(); if (i in 0..83) arr[i] += t.distanceKm.toFloat() }
            val hm = HeatmapView(a)
            hm.set(arr, (c0.get(Calendar.DAY_OF_WEEK) + 5) % 7)
            box.addView(ui.section("Activity – last 12 weeks", Ic.CALENDAR))
            box.addView(hm, ui.lp(6))
            var streak = 0
            var i = if (arr[83] > 0) 83 else 82
            while (i >= 0 && arr[i] > 0) { streak++; i-- }
            val active = arr.count { it > 0 }
            val chips = mutableListOf<View>()
            chips += ui.chip("$streak-day streak", if (streak >= 3) Ui.ORANGE else Ui.DIM, Ic.FLAG)
            chips += ui.chip("$active active days", Ui.ACC, Ic.CALENDAR)
            if (score >= 85 && km > 50) chips += ui.chip("Smooth operator", Ui.GOOD, Ic.SHIELD)
            if (idle / maxOf(hours, 0.01) < 0.10 && km > 50) chips += ui.chip("Idle-free", Ui.CYAN, Ic.LEAF)
            if ((hb0 + sb0) * p100 < 1 && km > 50) chips += ui.chip("Calm braker", Ui.VIOLET, Ic.CHECK)
            if (km >= 1000) chips += ui.chip("1000 km club", Ui.PINK, Ic.ROUTE)
            val flow = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            var rowL: LinearLayout? = null
            chips.forEachIndexed { idx, ch ->
                if (idx % 2 == 0) { rowL = LinearLayout(a); flow.addView(rowL, ui.lp(6)) }
                rowL!!.addView(ch, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = ui.dp(6) })
            }
            box.addView(ui.section("Achievements", Ic.FLAG))
            box.addView(flow)
        }

        box.addView(ui.btn("Save monthly PDF report", Ui.ACC) {
            val path = PdfReport.create(a, if (days == 0) 30 else days)
            ui.toast(if (path != null) "Saved: $path" else "Could not save PDF – check storage permission")
        }, ui.lp(12))

        val ra = trips.sumOf { it.rapidAcc }; val hb = trips.sumOf { it.hardBrake }; val sb = trips.sumOf { it.suddenBrake }
        val co = trips.sumOf { it.corner }; val os = trips.sumOf { it.overspeed }; val osS = trips.sumOf { it.overspeedS }
        val orv = trips.sumOf { it.overRev }; val cr = trips.sumOf { it.coldRev }; val li = trips.sumOf { it.longIdle }
        box.addView(ui.section("Driving behaviour (per 100 km)"))
        box.addView(ui.facts(
            "Rapid acceleration  %3d  (%.1f)\nHard braking        %3d  (%.1f)\nSudden braking      %3d  (%.1f)\n".format(ra, ra * per100, hb, hb * per100, sb, sb * per100) +
                    "Harsh cornering     %3d  (%.1f)\nOverspeed           %3d  (%s total)\nOver-rev            %3d\nCold high-RPM       %3d\nLong idle           %3d".format(
                        co, co * per100, os, ui.hms(osS), orv, cr, li)
        ), ui.lp())

        // Suggestions
        val tips = mutableListOf<String>()
        if (idle / maxOf(hours, 0.01) > 0.15) tips += "Idle time is ${ui.f(idle / hours * 100, 0, "%")} of driving – switch off during long waits (saved ≈ ${ui.f(idleFuel, 1, " $u")})."
        if ((hb + sb) * per100 > 3) tips += "Frequent hard braking – keep a larger following distance and anticipate signals."
        if (ra * per100 > 3) tips += "Frequent rapid acceleration – smoother throttle can improve economy 10–20 %."
        if (osS > 600) tips += "Spent ${ui.hms(osS)} above ${v.speedLimit} km/h – fuel use rises sharply above ~90 km/h."
        if (cr > 0) tips += "High RPM with a cold engine $cr time(s) – let the engine warm up gently."
        if (co * per100 > 2) tips += "Harsh cornering – slow down before bends."
        if (gps > 1000 && (obd / gps - 1) * 100 > 4) tips += "Speedometer reads %.0f %% high vs GPS – real distance is lower than shown.".format((obd / gps - 1) * 100)
        val due = Maint.dueList(v).filter { it.overdue }
        if (due.isNotEmpty()) tips += "Overdue service: " + due.joinToString { it.item.name }
        if (tips.isEmpty()) tips += "Great driving – no issues found in this period. 👍"
        box.addView(ui.section("Suggestions"))
        box.addView(ui.card(tips.joinToString("\n\n") { "• $it" }), ui.lp())

        // Fuel log comparison
        val logs = Db.fuelLogs(v.id).filter { it.t >= since }
        if (logs.isNotEmpty()) {
            box.addView(ui.section("Fuel purchases"))
            box.addView(ui.card("${logs.size} refuels · ${ui.f(logs.sumOf { it.qty }, 1, " $u")} · ${ui.money(logs.sumOf { it.cost })}\n" +
                    "Avg price ${ui.f(logs.sumOf { it.cost } / maxOf(logs.sumOf { it.qty }, 0.01), 2, " ₹/$u")}"), ui.lp())
        }
    }
}

