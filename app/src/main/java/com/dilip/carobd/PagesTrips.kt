package com.dilip.carobd

import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Date
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

    class Route(val pts: List<Pair<Double, Double>>, val speeds: List<Double>)
    private val routes = java.util.concurrent.ConcurrentHashMap<Long, Route>()

    /** GPS route of a trip (loaded off the UI thread, cached). */
    fun route(tripId: Long, cb: (Route?) -> Unit) {
        routes[tripId]?.let { cb(it); return }
        ex.execute {
            val g = try { Db.track(tripId).filter { it.lat != null && it.lon != null } } catch (_: Exception) { emptyList() }
            val r = if (g.size >= 2) Route(g.map { it.lat!! to it.lon!! }, g.map { it.speed }) else null
            if (r != null) { routes[tripId] = r; ends[tripId] = r.pts.first() to r.pts.last() }
            main.post { cb(r) }
        }
    }

    /** Synchronous variant for background threads. */
    fun endpointsNow(tripId: Long): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
        ends[tripId]?.let { return it }
        val g = try { Db.track(tripId).filter { it.lat != null && it.lon != null } } catch (_: Exception) { return null }
        if (g.size < 2) return null
        val r = (g.first().lat!! to g.first().lon!!) to (g.last().lat!! to g.last().lon!!)
        ends[tripId] = r; return r
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
    private lateinit var rangeTv: TextView
    private var filter = Prefs.tripFilter

    override fun build(): View {
        val c = ui.col()
        // filter dropdown
        filterTv = ui.tv("All time ▾", 22f, Ui.WHITE, true).apply {
            setOnClickListener { v ->
                val pm = android.widget.PopupMenu(a, v)
                TripFilter.names.forEachIndexed { i, f -> pm.menu.add(0, i, i, f).apply { isCheckable = true; isChecked = i == filter } }
                pm.menu.setGroupCheckable(0, true, true)
                pm.setOnMenuItemClickListener { filter = it.itemId; Prefs.tripFilter = filter; histSig = ""; refresh(); true }
                pm.show()
            }
        }
        c.addView(filterTv, ui.lp(4))
        rangeTv = ui.tv("", 12f, Ui.DIM)
        c.addView(rangeTv, ui.lp(0))
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
        c.addView(ui.tv("Swipe a drive to the left to delete it", 11.5f, Ui.DIM), ui.lp(4))
        hist = ui.vbox()
        c.addView(hist)
        val sv = ui.scroll(c)
        fun fab(ic: Ic, size: Int, bg: Int, fg: Int, onClick: () -> Unit) = ImageView(a).apply {
            setImageDrawable(ui.icon(ic, fg, size / 2)); scaleType = ImageView.ScaleType.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(bg) }
            elevation = ui.dp(6).toFloat(); setOnClickListener { onClick() }
        }
        val up = fab(Ic.CHEV, 44, Color.parseColor("#2A3441"), Ui.WHITE) { sv.smoothScrollTo(0, 0) }.apply { rotation = -90f }
        val add = fab(Ic.PLUS, 56, Ui.ACC, Ui.WHITE) { manualTrip() }
        val fabs = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(up, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { bottomMargin = ui.dp(12) })
            addView(add, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
        }
        return FrameLayout(a).apply {
            addView(sv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            addView(fabs, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, android.view.Gravity.END or android.view.Gravity.BOTTOM).apply { rightMargin = ui.dp(18); bottomMargin = ui.dp(104) })
        }
    }

    private fun manualTrip() {
        val v = ObdManager.vehicle
        val now = System.currentTimeMillis()
        val date = ui.edit("Date (yyyy-MM-dd)", ui.date(now, "yyyy-MM-dd"))
        val time = ui.edit("Start time (HH:mm)", ui.date(now, "HH:mm"))
        val dist = ui.edit("Distance (km)", "", true)
        val dur = ui.edit("Duration (minutes)", "", true)
        val l = ui.vbox().apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(date); addView(time); addView(dist); addView(dur)
        }
        AlertDialog.Builder(a).setTitle("Add a drive manually").setView(l)
            .setPositiveButton("Save") { _, _ ->
                val km = dist.text.toString().toDoubleOrNull(); val mins = dur.text.toString().toDoubleOrNull() ?: 0.0
                val st = try { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(date.text.toString() + " " + time.text.toString())?.time } catch (_: Exception) { null }
                if (km == null || km <= 0 || st == null) { ui.toast("Enter a valid date, time and distance"); return@setPositiveButton }
                val t = Trip(st, v.id).apply {
                    end = st + (mins * 60_000).toLong(); distanceKm = km; movingS = mins * 60.0; odoStart = v.odoKm
                    fuelPrice = v.fuelPrice; co2PerUnit = v.spec.co2PerUnit
                    val e = ObdManager.longTermKmpl(); if (e != null && e > 0) fuel = km / e
                }
                Db.insertTrip(t); histSig = ""; refresh(); ui.toast("Drive added")
            }.setNegativeButton("Cancel", null).show()
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
            cur.text = "● Recording   ${ui.dist(t.distanceKm, 2)} · ${ui.hms(t.durationS)} · ${ui.econ(t.kmpl)}\n" +
                    "${ui.vol(t.fuel, 2)} · ${ui.money(t.cost)} · max ${ui.spd(t.maxSpeed, 0)} · score ${t.score}"
        }
        val now = System.currentTimeMillis()
        val (since, until) = TripFilter.range(filter, m.vehicle.id)
        val trips = Db.trips(m.vehicle.id, since, 2000).filter { it.start < until }
        val s = filter.toString() + ":" + trips.size + ":" + (trips.firstOrNull()?.id ?: 0)
        if (s == histSig) return
        histSig = s
        filterTv.text = TripFilter.names[filter.coerceIn(0, TripFilter.names.size - 1)] + " ▾"
        rangeTv.text = TripFilter.describe(filter, since, until, trips.size)
        sumDist.text = "%,.0f km".format(trips.sumOf { it.distanceKm })
        sumTime.text = "%,.0f h".format(trips.sumOf { it.durationS } / 3600.0)
        hist.removeAllViews()
        if (trips.isEmpty()) {
            hist.addView(ui.iconCard(Ic.ROUTE, Ui.ACC, if (filter == 0) "No drives yet" else "No drives in this period", if (filter == 0) "Trips are saved automatically" else "Choose another period above"), ui.lp(14)); return
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
                addView(TileMapView(a, true).apply {
                    lazyLoader = { mv -> Geo.route(tr.id) { r -> if (r != null) mv.setRoute(r.pts, r.speeds) else mv.post { mv.visibility = View.GONE } } }
                }, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(130)).apply { bottomMargin = ui.dp(10) })
                addView(LinearLayout(a).apply {
                    gravity = android.view.Gravity.BOTTOM
                    addView(ui.tv("%.2f".format(tr.distanceKm), 30f, Ui.ACC, true))
                    addView(ui.tv(" " + Units.distLabel, 20f, Ui.DIM, true), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { bottomMargin = ui.dp(2) })
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
            hist.addView(ui.swipeDelete(card, "Delete this drive?", "${ui.date(tr.start, "EEE dd MMM, HH:mm")} · ${"%.1f".format(tr.distanceKm)} km will be removed with its route.") {
                Db.deleteTrip(tr.id); histSig = ""; refresh()
            }, ui.lp(8))
            Geo.endpoints(tr.id) { ep ->
                if (ep == null) { startTv.text = "No GPS"; endTv.text = "No GPS"; return@endpoints }
                Geo.name(a, ep.first.first, ep.first.second) { startTv.text = it }
                Geo.name(a, ep.second.first, ep.second.second) { endTv.text = it }
            }
        }
    }

    private fun exportAll() {
        val v = ObdManager.vehicle
        val (since, until) = TripFilter.range(filter, v.id)
        val trips = Db.trips(v.id, since).filter { it.start < until }
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

/** Time filter for the drive list (same choices as the fuel-log style period menu). */
object TripFilter {
    val names = listOf("All time", "Year-To-Date", "Previous year", "This month", "Previous month", "Last 30 days", "Last 3 months",
        "Last 6 months", "Last 12 months", "Today", "Yesterday", "Since last fill-up")

    private fun dayStart(): Calendar = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }

    /** (from, until) in epoch ms. until is exclusive. */
    fun range(i: Int, vid: Long): Pair<Long, Long> {
        val now = System.currentTimeMillis(); val inf = Long.MAX_VALUE
        return when (i) {
            1 -> dayStart().apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis to inf
            2 -> { val s = dayStart().apply { set(Calendar.DAY_OF_YEAR, 1) }; val e = s.timeInMillis; s.add(Calendar.YEAR, -1); s.timeInMillis to e }
            3 -> dayStart().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis to inf
            4 -> { val s = dayStart().apply { set(Calendar.DAY_OF_MONTH, 1) }; val e = s.timeInMillis; s.add(Calendar.MONTH, -1); s.timeInMillis to e }
            5 -> now - 30 * 86_400_000L to inf
            6 -> dayStart().apply { add(Calendar.MONTH, -3) }.timeInMillis to inf
            7 -> dayStart().apply { add(Calendar.MONTH, -6) }.timeInMillis to inf
            8 -> dayStart().apply { add(Calendar.MONTH, -12) }.timeInMillis to inf
            9 -> dayStart().timeInMillis to inf
            10 -> { val s = dayStart(); val e = s.timeInMillis; s.add(Calendar.DAY_OF_YEAR, -1); s.timeInMillis to e }
            11 -> (try { Db.fuelLogs(vid).firstOrNull()?.t } catch (_: Exception) { null } ?: 0L) to inf
            else -> 0L to inf
        }
    }

    fun describe(i: Int, from: Long, until: Long, n: Int): String {
        val f = java.text.SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val span = when {
            i == 0 -> "All drives"
            i == 11 && from == 0L -> "No fill-up logged yet – showing all drives"
            until == Long.MAX_VALUE -> "${f.format(Date(from))} – now"
            else -> "${f.format(Date(from))} – ${f.format(Date(until - 1))}"
        }
        return "$span · $n drive${if (n == 1) "" else "s"}"
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

        val gpsTrack = track.filter { it.lat != null && it.lon != null }
        if (gpsTrack.size < 2) box.addView(ui.iconCard(Ic.ROUTE, Ui.WARN, "No GPS route", "Enable GPS in Settings to record the route of your drives"), ui.lp(6))
        else {
            val map = TileMapView(a)
            map.setRoute(gpsTrack.map { it.lat!! to it.lon!! }, gpsTrack.map { it.speed },
                events.filter { it.lat != null && it.lon != null }.map { Triple(it.lat!!, it.lon!!, Ui.eventColor(it.type)) },
                "Dep. " + ui.date(t.start, "HH:mm"), "Arr. " + ui.date(if (t.end > 0) t.end else t.start, "HH:mm"))
            val fl = FrameLayout(a)
            fl.addView(map, FrameLayout.LayoutParams(Ui.MATCH, ui.dp(300)))
            fl.addView(ImageView(a).apply {
                setImageDrawable(ui.icon(Ic.SHARE, Ui.WHITE, 18)); scaleType = ImageView.ScaleType.CENTER
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor("#CC10161F")) }
                setOnClickListener { share(t, gpsTrack.first(), gpsTrack.last()) }
            }, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin = ui.dp(10); rightMargin = ui.dp(10) })
            fl.addView(LinearLayout(a).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ui.rounded(Color.parseColor("#E610161F"), 24); setPadding(ui.dp(16), ui.dp(10), ui.dp(12), ui.dp(10))
                addView(ui.tv("Want to see precise data?  ", 12.5f, Ui.WHITE))
                addView(ui.tv("Driving Replay", 12.5f, Ui.ACC, true))
                addView(ui.iconView(Ic.CHEV, Ui.ACC, 14))
                setOnClickListener { a.show("TripReplay") }
                ui.pressAnim(this)
            }, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(16) })
            map.setOnClickListener { a.show("TripReplay") }
            box.addView(fl, ui.lp(6))
        }

        // driving record card
        val tagTxt = when { t.score >= 85 -> "Smooth" to Ui.GOOD; t.score >= 65 -> "Normal" to Ui.ACC; else -> "Aggressive" to Ui.BAD }
        val depTv = ui.tv("…", 14f, Ui.DIM); val arrTv = ui.tv("…", 14f, Ui.DIM)
        box.addView(LinearLayout(a).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(20), 0, ui.dp(8))
            addView(ui.badge(Ic.DOC, Ui.ACC, 28), LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).apply { rightMargin = ui.dp(10) })
            addView(ui.tv("Driving records", 16f, Ui.WHITE, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(ui.tv("Edit ›", 15f, Ui.ACC, true).apply { setPadding(ui.dp(10), ui.dp(4), 0, ui.dp(4)); setOnClickListener { editTrip(t) } })
        })
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
            addView(ui.dotRow("Dist.", ui.dist(t.distanceKm, 2), Ic.CAR))
            addView(ui.dotRow("Odometer on Dep.", if (t.odoStart > 0) ui.dist(t.odoStart, 0) else "- km", Ic.CHECK, Ui.GOOD))
            addView(ui.dotRow("Odometer on Arr.", if (t.odoStart > 0) ui.dist(t.odoStart + t.distanceKm, 0) else "- km", Ic.CHECK, Ui.GOOD))
            addView(ui.tv("You can write the total distance with edit function.", 12f, Ui.DIM), ui.lp(8))
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
            addView(ui.dotRow("Fuel consumed", ui.vol(t.fuel, 2), Ic.FUEL))
            addView(ui.dotRow("Carbon emissions", ui.f(t.co2Kg, 2, " kg CO₂"), Ic.WIND))
            addView(ui.dotRow("Fuel cost (${Prefs.currency})", "%.2f".format(t.cost), Ic.DATABASE))
            addView(ui.dotRow("Rapid decel.", "${t.hardBrake + t.suddenBrake} times", Ic.WARN))
            addView(ui.dotRow("Rapid acc.", "${t.rapidAcc} times", Ic.BOLT))
            addView(ui.dotRow("Speeding", "${t.overspeed} times", Ic.GAUGE))
            addView(ui.dotRow("Harsh cornering", "${t.corner} times", Ic.ROUTE))
            addView(ui.dotRow("Avg. speed", ui.spd(t.avgSpeed, 0), Ic.GAUGE))
            addView(ui.dotRow("Max speed", ui.spd(t.maxSpeed, 0), Ic.GAUGE))
            addView(ui.dotRow("Avg. RPM", if (rpms.isEmpty()) "-" else "%.0f RPM".format(rpms.average()), Ic.ENGINE))
            addView(ui.dotRow("Max RPM", ui.f(t.maxRpm, 0, " RPM"), Ic.ENGINE))
            addView(ui.dotRow("Max coolant temp", ui.f(t.maxCoolant, 0, " °C"), Ic.THERM))
            addView(ui.dotRow("Idle", ui.hms(t.idleS), Ic.TIMER))
            if (t.best0100 > 0) addView(ui.dotRow("0-100 km/h", ui.f(t.best0100, 1, " s"), Ic.TIMER))
        }, ui.lp(6))

        val spdChart = LineChart(a)
        spdChart.set(track.map { Units.spd(it.speed).toFloat() }.toFloatArray(), "Speed", " " + Units.spdLabel, Color.parseColor("#4FC3F7"))
        box.addView(spdChart, ui.lp(10))
        val rpmChart = LineChart(a)
        rpmChart.set(track.map { it.rpm.toFloat() }.toFloatArray(), "RPM", "", Color.parseColor("#FFB300"))
        box.addView(rpmChart, ui.lp(8))

        box.addView(ui.section("Driving events (${events.size})"))
        if (events.isEmpty()) box.addView(ui.tv("No harsh events – smooth drive 👍", 13f, Ui.DIM))
        for (e in events) {
            box.addView(ui.iconCard(Ui.eventIcon(e.type), Ui.speedColor3(e.speed), Ui.eventName(e.type),
                "${ui.date(e.t, "HH:mm:ss")} · ${e.label.substringAfter(e.type).trim()}" + (if (e.lat != null) "\n%.5f, %.5f".format(e.lat, e.lon) else ""), Ui.CARD), ui.lp(4))
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

    private fun editTrip(t: Trip) {
        val dist = ui.edit("Total distance (km)", "%.2f".format(Locale.US, t.distanceKm), true)
        val odo = ui.edit("Odometer on departure (km)", if (t.odoStart > 0) "%.0f".format(Locale.US, t.odoStart) else "", true)
        val l = ui.vbox().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(dist); addView(odo) }
        AlertDialog.Builder(a).setTitle("Edit driving record").setView(l)
            .setPositiveButton("Save") { _, _ ->
                dist.text.toString().toDoubleOrNull()?.takeIf { it >= 0 }?.let { t.distanceKm = it }
                odo.text.toString().toDoubleOrNull()?.takeIf { it >= 0 }?.let { t.odoStart = it }
                Db.updateTrip(t); onShow(); ui.toast("Saved")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun share(t: Trip, s: TrackPoint, e: TrackPoint) {
        val txt = "Drive ${ui.date(t.start, "EEE dd MMM yyyy")}\n${ui.date(t.start, "HH:mm")} → ${ui.date(if (t.end > 0) t.end else t.start, "HH:mm")} · " +
                "${ui.dist(t.distanceKm, 2)} · ${(t.durationS / 60).toInt()} min\n" +
                "https://www.google.com/maps/dir/?api=1&origin=${s.lat},${s.lon}&destination=${e.lat},${e.lon}\n— ${AppInfo.NAME}"
        a.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, txt), "Share drive"))
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
