package com.dilip.carobd

import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/* ============================ Trips ============================ */

class TripsPage(a: MainActivity) : Page(a) {
    private lateinit var cur: TextView
    private lateinit var hist: LinearLayout
    private var histSig = ""

    override fun build(): View {
        val c = ui.col()
        cur = ui.mono("", 13f)
        c.addView(cur, ui.lp())
        c.addView(ui.row(
            ui.btn("Start new trip", Ui.GOOD) { ObdManager.stopTrip(); ObdManager.startTrip(); histSig = "" },
            ui.btn("Stop & save", Ui.BAD) { ObdManager.stopTrip(); histSig = "" }
        ), ui.lp(10))
        c.addView(ui.row(
            ui.btn("Export trips CSV", Ui.CARD) { exportAll() },
            ui.btn("Analysis", Ui.CARD) { a.show("Analysis") }
        ), ui.lp(8))
        c.addView(ui.section("Trip history (tap for route, charts & events)"))
        hist = ui.vbox()
        c.addView(hist)
        return ui.scroll(c)
    }

    override fun onShow() {
        histSig = ""
    }

    override fun refresh() {
        val m = ObdManager
        val t = m.engine.current
        val u = m.vehicle.spec.unit
        cur.text = if (t == null) "No trip running.\nA trip starts automatically when the engine runs\nand ends 90 s after it stops (or ignition off)." else buildString {
            append("Running since  ${ui.date(t.start, "dd MMM HH:mm")}\n\n")
            append("Distance       ${ui.f(t.distanceKm, 2, " km")}\n")
            append("Duration       ${ui.hms(t.durationS)}\n")
            append("Fuel used      ${ui.f(t.fuel, 2, " $u")}  (${ui.money(t.cost)})\n")
            append("Economy        ${ui.econ(t.kmpl)}\n")
            append("Avg / moving   ${ui.f(t.avgSpeed, 1)} / ${ui.f(t.movingAvgSpeed, 1, " km/h")}\n")
            append("Max speed/RPM  ${ui.f(t.maxSpeed, 0)} km/h / ${ui.f(t.maxRpm, 0)}\n")
            append("Idle           ${ui.hms(t.idleS)}  (${ui.f(t.idleFuel, 2, " $u")})\n")
            append("CO2            ${ui.f(t.co2Kg, 2, " kg")}\n")
            append("Driver score   ${t.score}/100\n")
            append("Rapid acc      ${t.rapidAcc}    Hard brake  ${t.hardBrake}\n")
            append("Sudden brake   ${t.suddenBrake}    Cornering   ${t.corner}\n")
            append("Overspeed      ${t.overspeed} (${ui.hms(t.overspeedS)})  Over-rev ${t.overRev}\n")
            if (t.best060 > 0) append("0-60 km/h      ${ui.f(t.best060, 1, " s")}\n")
            if (t.best0100 > 0) append("0-100 km/h     ${ui.f(t.best0100, 1, " s")}\n")
            m.values[0x2F]?.let {
                val q = it * m.vehicle.tankL / 100
                append("Fuel in tank   ${ui.f(q, 1, " $u")}")
                t.kmpl?.let { k -> append("  → range ≈ ${ui.f(q * k, 0, " km")}") }
                append("\n")
            }
        }
        val trips = Db.trips(m.vehicle.id, limit = 100)
        val s = trips.size.toString() + ":" + (trips.firstOrNull()?.id ?: 0)
        if (s != histSig) {
            histSig = s
            hist.removeAllViews()
            if (trips.isEmpty()) hist.addView(ui.tv("No saved trips for ${m.vehicle.name} yet.", 13f, Ui.DIM))
            for (tr in trips) {
                val col = when {
                    tr.score >= 85 -> Ui.GOOD; tr.score >= 65 -> Ui.WARN; else -> Ui.BAD
                }
                val row = LinearLayout(a).apply {
                    background = ui.rounded(Ui.CARD, 14)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                    addView(ui.tv("${tr.score}", 17f, col, true).apply {
                        gravity = android.view.Gravity.CENTER
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Ui.alpha(col, 40)); setStroke(ui.dp(3), col)
                        }
                    }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { rightMargin = ui.dp(12) })
                    addView(ui.tv(
                        "${ui.date(tr.start)} · ${ui.f(tr.distanceKm, 1, " km")} · ${ui.hms(tr.durationS)}\n" +
                                "${ui.econ(tr.kmpl)} · ${ui.f(tr.fuel, 2, " $u")} · ${ui.money(tr.cost)} · " +
                                "avg ${ui.f(tr.avgSpeed, 0)} · ${tr.events} events", 12f
                    ), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                    setOnClickListener {
                        a.selectedTripId = tr.id
                        a.show("TripDetail")
                    }
                }
                hist.addView(row, ui.lp(6))
            }
        }
    }

    private fun exportAll() {
        val v = ObdManager.vehicle
        val trips = Db.trips(v.id)
        val sb = StringBuilder("start,end,duration_min,distance_km,fuel,economy_km_per_unit,cost,avg_kmh,moving_avg_kmh,max_kmh,max_rpm,idle_min,co2_kg,score,rapid_acc,hard_brake,sudden_brake,cornering,overspeed,overspeed_s,over_rev,best_0_100_s\n")
        for (t in trips) {
            sb.append(listOf(
                ui.date(t.start, "yyyy-MM-dd HH:mm"), ui.date(t.end, "yyyy-MM-dd HH:mm"), "%.1f".format(t.durationS / 60),
                "%.2f".format(t.distanceKm), "%.3f".format(t.fuel), t.kmpl?.let { "%.2f".format(it) } ?: "",
                "%.2f".format(t.cost), "%.1f".format(t.avgSpeed), "%.1f".format(t.movingAvgSpeed), "%.0f".format(t.maxSpeed),
                "%.0f".format(t.maxRpm), "%.1f".format(t.idleS / 60), "%.2f".format(t.co2Kg), t.score, t.rapidAcc,
                t.hardBrake, t.suddenBrake, t.corner, t.overspeed, "%.0f".format(t.overspeedS), t.overRev, "%.1f".format(t.best0100)
            ).joinToString(",")).append("\n")
        }
        val p = Exporter.writeText(a, "carobd_trips_${stamp()}.csv", "text/csv", sb.toString())
        ui.toast(if (p != null) "Saved to $p" else "Export failed")
    }
}

/* ============================ Trip detail ============================ */

class TripDetailPage(a: MainActivity) : Page(a) {
    override val parent = "Trips"
    private lateinit var box: LinearLayout

    override fun build(): View {
        box = ui.col()
        return ui.scroll(box)
    }

    override fun onShow() {
        box.removeAllViews()
        val t = Db.trip(a.selectedTripId)
        if (t == null) {
            box.addView(ui.tv("Trip not found.", 14f, Ui.DIM)); return
        }
        val u = (Db.vehicle(t.vehicleId) ?: ObdManager.vehicle).spec.unit
        val track = Db.track(t.id)
        val events = Db.events(t.id)

        box.addView(ui.btn("← Back to trips", Ui.CARD) { a.show("Trips") }, ui.lp(6))
        box.addView(ui.section("Trip ${ui.date(t.start)}"))
        box.addView(ui.mono(buildString {
            append("Distance     ${ui.f(t.distanceKm, 2, " km")} (GPS ${ui.f(t.gpsKm, 2, " km")})\n")
            append("Duration     ${ui.hms(t.durationS)}  · moving ${ui.hms(t.movingS)}\n")
            append("Fuel         ${ui.f(t.fuel, 2, " $u")} · ${ui.money(t.cost)} · CO2 ${ui.f(t.co2Kg, 1, " kg")}\n")
            append("Economy      ${ui.econ(t.kmpl, u)}\n")
            append("Speed avg    ${ui.f(t.avgSpeed, 1)} · moving ${ui.f(t.movingAvgSpeed, 1)} · max ${ui.f(t.maxSpeed, 0, " km/h")}\n")
            append("Max RPM      ${ui.f(t.maxRpm, 0)} · max coolant ${ui.f(t.maxCoolant, 0, "°C")}\n")
            append("Idle         ${ui.hms(t.idleS)} (${ui.f(t.idleFuel, 2, " $u")})\n")
            append("Score        ${t.score}/100\n")
            t.speedoErrorPct?.let { append("Speedometer  %+.1f%% vs GPS\n".format(it)) }
            if (t.best0100 > 0) append("0-100 km/h   ${ui.f(t.best0100, 1, " s")}\n")
            if (t.fuelStartPct >= 0 && t.fuelEndPct >= 0) append("Tank         ${ui.f(t.fuelStartPct, 0)}% → ${ui.f(t.fuelEndPct, 0)}%\n")
        }), ui.lp())

        box.addView(ui.section("Route"))
        val route = RouteView(a)
        route.set(
            track.filter { it.lat != null && it.lon != null }.map { it.lat!! to it.lon!! },
            events.filter { it.lat != null && it.lon != null }.map { Triple(it.lat!!, it.lon!!, Ui.eventColor(it.type)) }
        )
        box.addView(route, ui.lp())

        val spdChart = LineChart(a)
        spdChart.set(track.map { it.speed.toFloat() }.toFloatArray(), "Speed", " km/h", Color.parseColor("#4FC3F7"))
        box.addView(spdChart, ui.lp(10))
        val rpmChart = LineChart(a)
        rpmChart.set(track.map { it.rpm.toFloat() }.toFloatArray(), "RPM", "", Color.parseColor("#FFB300"))
        box.addView(rpmChart, ui.lp(8))

        box.addView(ui.section("Driving events (${events.size})"))
        if (events.isEmpty()) box.addView(ui.tv("No harsh events – smooth drive 👍", 13f, Ui.DIM))
        for (e in events) {
            box.addView(ui.card("${ui.date(e.t, "HH:mm:ss")}  ${e.label}" +
                    (if (e.lat != null) "\n%.5f, %.5f".format(e.lat, e.lon) else ""), 13f, Ui.WHITE).apply {
                background = ui.rounded(Ui.eventColor(e.type) and 0x66FFFFFF, 6)
            }, ui.lp(4))
        }

        box.addView(ui.section("Export / manage"))
        box.addView(ui.row(
            ui.btn("GPX") { export(t, track, events, "gpx") },
            ui.btn("KML") { export(t, track, events, "kml") },
            ui.btn("CSV") { export(t, track, events, "csv") }
        ), ui.lp())
        box.addView(ui.btn("Delete trip", Ui.BAD) {
            AlertDialog.Builder(a).setTitle("Delete this trip?")
                .setPositiveButton("Delete") { _, _ -> Db.deleteTrip(t.id); a.show("Trips") }
                .setNegativeButton("Cancel", null).show()
        }, ui.lp(10))
    }

    private fun export(t: Trip, track: List<TrackPoint>, events: List<DriveEvent>, kind: String) {
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val name = "carobd_trip_${ui.date(t.start, "yyyyMMdd_HHmm")}.$kind"
        val gps = track.filter { it.lat != null && it.lon != null }
        val text = when (kind) {
            "gpx" -> buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"CarOBD\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                for (e in events) if (e.lat != null) append("<wpt lat=\"${e.lat}\" lon=\"${e.lon}\"><time>${iso.format(e.t)}</time><name>${e.label}</name></wpt>\n")
                append("<trk><name>Trip ${ui.date(t.start)}</name><trkseg>\n")
                for (p in gps) append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\"><time>${iso.format(p.t)}</time><desc>${"%.0f".format(p.speed)} km/h ${"%.0f".format(p.rpm)} rpm</desc></trkpt>\n")
                append("</trkseg></trk>\n</gpx>\n")
            }
            "kml" -> buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>Trip ${ui.date(t.start)}</name>\n")
                append("<Style id=\"r\"><LineStyle><color>ffff8800</color><width>4</width></LineStyle></Style>\n")
                append("<Placemark><name>Route</name><styleUrl>#r</styleUrl><LineString><tessellate>1</tessellate><coordinates>\n")
                for (p in gps) append("${p.lon},${p.lat},0\n")
                append("</coordinates></LineString></Placemark>\n")
                for (e in events) if (e.lat != null) append("<Placemark><name>${e.label}</name><Point><coordinates>${e.lon},${e.lat},0</coordinates></Point></Placemark>\n")
                append("</Document></kml>\n")
            }
            else -> buildString {
                append("time,lat,lon,obd_speed_kmh,rpm,gps_speed_kmh\n")
                for (p in track) append("${iso.format(p.t)},${p.lat ?: ""},${p.lon ?: ""},${"%.0f".format(p.speed)},${"%.0f".format(p.rpm)},${p.gpsSpeed?.let { "%.1f".format(it) } ?: ""}\n")
            }
        }
        val mime = when (kind) { "gpx" -> "application/gpx+xml"; "kml" -> "application/vnd.google-earth.kml+xml"; else -> "text/csv" }
        val path = Exporter.writeText(a, name, mime, text)
        ui.toast(if (path != null) "Saved to $path" else "Export failed")
    }
}
