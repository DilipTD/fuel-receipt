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

        box.addView(ui.mono(buildString {
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

        val ra = trips.sumOf { it.rapidAcc }; val hb = trips.sumOf { it.hardBrake }; val sb = trips.sumOf { it.suddenBrake }
        val co = trips.sumOf { it.corner }; val os = trips.sumOf { it.overspeed }; val osS = trips.sumOf { it.overspeedS }
        val orv = trips.sumOf { it.overRev }; val cr = trips.sumOf { it.coldRev }; val li = trips.sumOf { it.longIdle }
        box.addView(ui.section("Driving behaviour (per 100 km)"))
        box.addView(ui.mono(
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

