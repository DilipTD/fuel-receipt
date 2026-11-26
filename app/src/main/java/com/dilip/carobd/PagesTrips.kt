package com.dilip.carobd

import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

/* ============================ Place names (reverse geocoding, cached) ============================ */

object Geo {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val ends = java.util.concurrent.ConcurrentHashMap<Long, Pair<Pair<Double, Double>, Pair<Double, Double>>>()
    private val ex = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** First and last GPS point of a trip (computed off the UI thread). */
    fun endpoints(tripId: Long, cb: (Pair<Pair<Double, Double>, Pair<Double, Double>>?) -> Unit) {
        ends[tripId]?.let { cb(it); return }
        ex.execute {
            val g = try { Db.track(tripId).filter { it.lat != null && it.lon != null } } catch (_: Exception) { emptyList() }
            val r = if (g.size >= 2) (g.first().lat!! to g.first().lon!!) to (g.last().lat!! to g.last().lon!!) else null
            if (r != null) ends[tripId] = r
            main.post { cb(r) }
        }
    }

    fun name(ctx: android.content.Context, lat: Double, lon: Double, cb: (String) -> Unit) {
        val key = "%.3f,%.3f".format(Locale.US, lat, lon)
        cache[key]?.let { cb(it); return }
        val sp = ctx.getSharedPreferences("geo_cache", android.content.Context.MODE_PRIVATE)
        sp.getString(key, null)?.let { cache[key] = it; cb(it); return }
        ex.execute {
            val txt = try {
                val ad = android.location.Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
                if (ad == null) null else listOfNotNull(
                    ad.thoroughfare ?: ad.featureName?.takeIf { f -> f.any { c -> c.isLetter() } },
                    ad.subLocality, ad.locality, ad.adminArea
                ).distinct().take(3).joinToString(", ").ifBlank { null }
            } catch (_: Exception) { null }
            val out = txt ?: "%.4f, %.4f".format(Locale.US, lat, lon)
            if (txt != null) { cache[key] = txt; sp.edit().putString(key, txt).apply() }
            main.post { cb(out) }
        }
    }
}

/* ============================ Driving log ============================ */

class TripsPage(a: MainActivity) : Page(a) {
    private lateinit var cur: TextView
    private lateinit var hist: LinearLayout
    private lateinit var sumDist: TextView
    private lateinit var sumTime: TextView
    private lateinit var filterTv: TextView
    private var histSig = ""
    private var filter = 0
    private val periods = listOf("All", "Last 7 days", "Last 30 days", "This month")

    override fun build(): View {
        val c = ui.col()
        // filter dropdown
        filterTv = ui.tv("All ▾", 22f, Ui.WHITE, true).apply {
            setOnClickListener { v ->
                val pm = android.widget.PopupMenu(a, v)
                periods.forEachIndexed { i, f -> pm.menu.add(0, i, i, f) }
                pm.setOnMenuItemClickListener { filter = it.itemId; histSig = ""; true }
                pm.show()
            }
        }
        c.addView(filterTv, ui.lp(4))
        sumDist = ui.tv("0 km", 26f, Ui.WHITE, true)
        sumTime = ui.tv("0 h", 26f, Ui.WHITE, true)
        c.addView(ui.row(
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(a).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(ui.iconView(Ic.CAR, Ui.ACC, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(6) })
                    addView(ui.tv("Total distance", 13f, Ui.DIM))
                }); addView(sumDist)
            },
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(a).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(ui.iconView(Ic.CLOCK, Ui.ACC, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(6) })
                    addView(ui.tv("Driving time", 13f, Ui.DIM))
                }); addView(sumTime)
            }
        ), ui.lp(10))
        cur = ui.card("", 13f).apply { visibility = View.GONE; setOnClickListener { } }
        c.addView(cur, ui.lp(12))
        c.addView(ui.row(
            ui.btn("Start", Ui.GOOD) { ObdManager.stopTrip(); ObdManager.startTrip(); histSig = "" },
            ui.btn("Stop", Ui.BAD) { ObdManager.stopTrip(); histSig = "" },
            ui.btn("CSV", Ui.CARD) { exportAll() }
        ), ui.lp(8))
        hist = ui.vbox()
        c.addView(hist)
        return ui.scroll(c)
    }

    override fun onShow() { histSig = "" }

    private fun tag(score: Int) = when { score >= 85 -> "Smooth" to Ui.GOOD; score >= 65 -> "Normal" to Ui.ACC; else -> "Aggressive" to Ui.BAD }

    private fun flagRow(ic: Ic, col: Int, text: String): Pair<LinearLayout, TextView> {
        val t = ui.tv(text, 14f, Ui.WHITE)
        return LinearLayout(a).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ui.iconView(ic, col, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(10) })
            addView(t, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        } to t
    }

    override fun refresh() {
        val m = ObdManager
        val t = m.engine.current
        val u = m.vehicle.spec.unit
        if (t == null) cur.visibility = View.GONE else {
            cur.visibility = View.VISIBLE
            cur.text = "● Recording   ${ui.f(t.distanceKm, 2, " km")} · ${ui.hms(t.durationS)} · ${ui.econ(t.kmpl)}\n" +
                    "${ui.f(t.fuel, 2, " $u")} · ${ui.money(t.cost)} · max ${ui.f(t.maxSpeed, 0, " km/h")} · score ${t.score}"
        }
        val now = System.currentTimeMillis()
        val since = when (filter) {
            1 -> now - 7 * 86_400_000L; 2 -> now - 30 * 86_400_000L
            3 -> Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
            else -> 0L
        }
        val trips = Db.trips(m.vehicle.id, since, 300)
        val s = filter.toString() + ":" + trips.size + ":" + (trips.firstOrNull()?.id ?: 0)
        if (s == histSig) return
        histSig = s
        filterTv.text = periods[filter] + " ▾"
        sumDist.text = "%,.0f km".format(trips.sumOf { it.distanceKm })
        sumTime.text = "%,.0f h".format(trips.sumOf { it.durationS } / 3600.0)
        hist.removeAllViews()
        if (trips.isEmpty()) {
            hist.addView(ui.iconCard(Ic.ROUTE, Ui.ACC, "No drives yet", "Trips are saved automatically"), ui.lp(14)); return
        }
        var day = ""
        for (tr in trips) {
            val d = ui.date(tr.start, "EEE/MMM/dd")
            if (d != day) { day = d; hist.addView(ui.tv(d, 20f, Ui.WHITE, true), ui.lp(18)) }
            val (tg, tc) = tag(tr.score)
            val (startRow, startTv) = flagRow(Ic.FLAG, Ui.ACC, "…")
            val (endRow, endTv) = flagRow(Ic.FLAG, Ui.BAD, "…")
            val mins = (tr.durationS / 60).toInt()
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.rounded(Ui.CARD, 20)
                setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
                addView(LinearLayout(a).apply {
                    gravity = android.view.Gravity.BOTTOM
                    addView(ui.tv("%.2f".format(tr.distanceKm), 30f, Ui.ACC, true))
                    addView(ui.tv(" km", 20f, Ui.DIM, true), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { bottomMargin = ui.dp(2) })
                    addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(ui.tv(ui.econ(tr.kmpl) + " · " + ui.money(tr.cost), 12.5f, Ui.DIM))
                })
                addView(LinearLayout(a).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(ui.iconView(Ic.CLOCK, Ui.DIM, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(10) })
                    addView(ui.tv("${ui.date(tr.start, "HH:mm")} ~ ${ui.date(if (tr.end > 0) tr.end else tr.start, "HH:mm")} (${mins}min)", 14.5f, Ui.WHITE))
                }, ui.lp(8))
                addView(startRow, ui.lp(8)); addView(endRow, ui.lp(8))
                addView(LinearLayout(a).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(ui.iconView(Ic.FLAG, tc, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(10) })
                    addView(ui.tv(tg, 14.5f, tc, true))
                    addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(ui.tv("${tr.score}", 14f, tc, true).apply {
                        gravity = android.view.Gravity.CENTER
                        background = ui.rounded(Ui.alpha(tc, 40), 12); setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(2))
                    })
                }, ui.lp(8))
                setOnClickListener { a.selectedTripId = tr.id; a.show("TripDetail") }
            }
            ui.pressAnim(card)
            hist.addView(card, ui.lp(8))
            Geo.endpoints(tr.id) { ep ->
                if (ep == null) { startTv.text = "No GPS"; endTv.text = "No GPS"; return@endpoints }
                Geo.name(a, ep.first.first, ep.first.second) { startTv.text = it }
                Geo.name(a, ep.second.first, ep.second.second) { endTv.text = it }
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

        box.addView(ui.tv(ui.date(t.start, "EEE/MMM/dd"), 28f, Ui.WHITE, true), ui.lp(6))

        box.addView(ui.section("Driving replay", Ic.ROUTE))
        val route = RouteView(a)
        val gpsTrack = track.filter { it.lat != null && it.lon != null }
        route.setSpeeds(gpsTrack.map { it.speed })
        route.set(
            gpsTrack.map { it.lat!! to it.lon!! },
            events.filter { it.lat != null && it.lon != null }.map { Triple(it.lat!!, it.lon!!, Ui.eventColor(it.type)) }
        )
        box.addView(route, ui.lp(4))

        // driving record card
        val tagTxt = when { t.score >= 85 -> "Smooth" to Ui.GOOD; t.score >= 65 -> "Normal" to Ui.ACC; else -> "Aggressive" to Ui.BAD }
        val depTv = ui.tv("…", 14f, Ui.DIM); val arrTv = ui.tv("…", 14f, Ui.DIM)
        box.addView(ui.section("Driving records", Ic.DOC))
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Ui.CARD, 20); setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(10))
            addView(ui.chip(tagTxt.first, tagTxt.second, Ic.FLAG))
            addView(LinearLayout(a).apply {
                gravity = android.view.Gravity.TOP
                addView(ui.iconView(Ic.PIN, Ui.ACC, 22), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { rightMargin = ui.dp(10) })
                addView(ui.tv("Dep.", 17f, Ui.WHITE, true), LinearLayout.LayoutParams(ui.dp(56), Ui.WRAP))
                addView(ui.vbox().apply { addView(ui.tv(ui.date(t.start, "HH:mm"), 15f, Ui.WHITE)); addView(depTv) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            }, ui.lp(12))
            addView(LinearLayout(a).apply {
                gravity = android.view.Gravity.TOP
                addView(ui.iconView(Ic.PIN, Ui.BAD, 22), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { rightMargin = ui.dp(10) })
                addView(ui.tv("Arr.", 17f, Ui.WHITE, true), LinearLayout.LayoutParams(ui.dp(56), Ui.WRAP))
                addView(ui.vbox().apply { addView(ui.tv(ui.date(if (t.end > 0) t.end else t.start, "HH:mm"), 15f, Ui.WHITE)); addView(arrTv) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            }, ui.lp(12))
            addView(View(a).apply { setBackgroundColor(Ui.STROKE) }, LinearLayout.LayoutParams(Ui.MATCH, 1).apply { topMargin = ui.dp(12); bottomMargin = ui.dp(4) })
            addView(ui.dotRow("Time", "${(t.durationS / 60).toInt()}m", Ic.CLOCK))
            addView(ui.dotRow("Dist.", ui.f(t.distanceKm, 2, " km"), Ic.CAR))
            addView(ui.dotRow("Odometer on Dep.", if (t.odoStart > 0) ui.f(t.odoStart, 0, " km") else "- km", Ic.CHECK, Ui.GOOD))
            addView(ui.dotRow("Odometer on Arr.", if (t.odoStart > 0) ui.f(t.odoStart + t.distanceKm, 0, " km") else "- km", Ic.CHECK, Ui.GOOD))
        }, ui.lp(6))
        Geo.endpoints(t.id) { ep ->
            if (ep == null) { depTv.text = "No GPS data"; arrTv.text = "No GPS data" }
            else {
                Geo.name(a, ep.first.first, ep.first.second) { depTv.text = it }
                Geo.name(a, ep.second.first, ep.second.second) { arrTv.text = it }
            }
        }

        // general data
        val rpms = track.map { it.rpm }.filter { it > 300 }
        val idleShare = if (t.durationS > 0) t.idleS / t.durationS else 0.0
        val eco = (100 - idleShare * 80 - t.overRev * 3 - t.rapidAcc * 3 - t.coldRev * 3).coerceIn(0.0, 100.0)
        box.addView(ui.section("General data", Ic.GRID))
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Ui.CARD, 20); setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
            addView(ui.dotRow("Safety score", "${t.score} P", Ic.SHIELD, tagTxt.second))
            addView(ui.dotRow("Economic score", "%.0f P".format(eco), Ic.LEAF, Ui.GOOD))
            addView(ui.dotRow("Fuel efficiency", ui.econ(t.kmpl, u), Ic.CHART))
            addView(ui.dotRow("Fuel consumed", ui.f(t.fuel, 2, " $u"), Ic.FUEL))
            addView(ui.dotRow("Carbon emissions", ui.f(t.co2Kg, 2, " kg CO₂"), Ic.WIND))
            addView(ui.dotRow("Fuel cost (₹)", "%.2f".format(t.cost), Ic.DATABASE))
            addView(ui.dotRow("Rapid decel.", "${t.hardBrake + t.suddenBrake} times", Ic.WARN))
            addView(ui.dotRow("Rapid acc.", "${t.rapidAcc} times", Ic.BOLT))
            addView(ui.dotRow("Speeding", "${t.overspeed} times", Ic.GAUGE))
            addView(ui.dotRow("Harsh cornering", "${t.corner} times", Ic.ROUTE))
            addView(ui.dotRow("Avg. speed", ui.f(t.avgSpeed, 0, " km/h"), Ic.GAUGE))
            addView(ui.dotRow("Max speed", ui.f(t.maxSpeed, 0, " km/h"), Ic.GAUGE))
            addView(ui.dotRow("Avg. RPM", if (rpms.isEmpty()) "-" else "%.0f RPM".format(rpms.average()), Ic.ENGINE))
            addView(ui.dotRow("Max RPM", ui.f(t.maxRpm, 0, " RPM"), Ic.ENGINE))
            addView(ui.dotRow("Max coolant temp", ui.f(t.maxCoolant, 0, " °C"), Ic.THERM))
            addView(ui.dotRow("Idle", ui.hms(t.idleS), Ic.TIMER))
            if (t.best0100 > 0) addView(ui.dotRow("0-100 km/h", ui.f(t.best0100, 1, " s"), Ic.TIMER))
        }, ui.lp(6))

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
