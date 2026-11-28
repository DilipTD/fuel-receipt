package com.dilip.carobd

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/* ============================ Home (overview) ============================ */

@SuppressLint("SetTextI18n")
class HomePage(a: MainActivity) : Page(a) {
    private lateinit var box: LinearLayout
    private var statusTv: TextView? = null
    private var gen = 0
    private var carView: SmartCarView? = null
    private var roadTv: TextView? = null
    private var roadSub: TextView? = null
    private var signView: RoadSignView? = null

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    override fun onShow() { buildContent() }

    override fun refresh() {
        val m = ObdManager
        val ri = RoadAssist.info
        signView?.let { it.limit = ri?.limit; it.estimated = ri?.estimated == true; it.over = ri?.limit != null && RoadAssist.speedKmh > ri.limit + Prefs.speedMargin }
        if (ri == null && Prefs.roadAssist) roadTv?.text = "Road AI · " + (if (!RoadAssist.active()) RoadAssist.modeText() else Gps.statusText(a))
        roadSub?.text = if (ri == null) "" else "${RoadAssist.compass(RoadAssist.heading)} · " + Units.sStr(RoadAssist.speedKmh) + (ri.limit?.let { " · limit " + Units.sStr(it.toDouble()) } ?: "")
        carView?.let { it.engineOn = m.engineOn && m.state == ObdManager.State.CONNECTED; it.speedKmh = m.values[0x0D] ?: 0.0 }
        statusTv?.text = when (m.state) {
            ObdManager.State.CONNECTED -> "Connected · ${m.deviceName}" + if (m.engineOn) " · ${ui.f(m.batteryV, 1, " V")}" else " · ignition off"
            ObdManager.State.CONNECTING -> m.status
            else -> "Offline · tap to open gauges"
        }
    }

    private fun roundBtn(ic: Ic, onClick: () -> Unit) = ImageView(a).apply {
        setImageDrawable(ui.icon(ic, Ui.WHITE, 16)); scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(Ui.ACC, 60)) }
        setOnClickListener { onClick() }
    }

    private fun buildContent() {
        val my = ++gen
        box.removeAllViews()
        val m = ObdManager
        val v = m.vehicle
        val now = System.currentTimeMillis()

        // greeting + vehicle header
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        box.addView(ui.tv(when { h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; else -> "Good evening" } + " · " +
                ui.date(now, "EEE, dd MMM"), 13f, Ui.DIM), ui.lp(0))
        statusTv = ui.tv("", 12f, Ui.DIM)
        box.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(Ui.CARD, 20); setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            addView(ui.badge(Ic.CAR, Ui.ACC, 48), LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { rightMargin = ui.dp(12) })
            addView(ui.vbox().apply {
                addView(ui.tv(v.name, 17f, Ui.WHITE, true))
                addView(statusTv)
                addView(ui.tv(ui.dist(v.odoKm, 0) + " · " + v.fuel, 12f, Ui.DIM))
            }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(roundBtn(Ic.GAUGE) { a.show("Dash") }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            setOnClickListener { a.show("Dash") }
            ui.pressAnim(this)
        }, ui.lp(6))
        refresh()

        // AI status with animated car
        val st = SmartAi.status()
        val stCol = when (st.overall) { 2 -> Ui.BAD; 1 -> Ui.WARN; else -> Ui.GOOD }
        val car = SmartCarView(a).apply {
            zones = st.zones; overall = st.overall; engineOn = m.engineOn && m.state == ObdManager.State.CONNECTED
            speedKmh = m.values[0x0D] ?: 0.0
        }
        carView = car
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.outlined(Ui.CARD, Ui.alpha(stCol, 130), 20, 1); setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.chip("AI", Ui.VIOLET, Ic.SHIELD), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
                addView(ui.tv(st.headline, 14.5f, Ui.WHITE, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(stCol) } }, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)))
            })
            addView(car, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(150)).apply { topMargin = ui.dp(4) })
            if (st.alerts.isEmpty()) addView(ui.tv("Engine, battery, cooling, fuel and service are all being watched by the on-device AI.", 12f, Ui.DIM))
            else st.alerts.take(3).forEach { al ->
                val col = if (al.sev >= 2) Ui.BAD else Ui.WARN
                addView(ui.iconCard(al.ic, col, al.title, al.detail, Ui.SEL) { a.show(al.page) }, ui.lp(8))
            }
            if (st.alerts.size > 3) addView(ui.tv("+ ${st.alerts.size - 3} more · open Diagnose", 12f, Ui.DIM).apply { setOnClickListener { a.show("Diag") } }, ui.lp(8))
            SmartAi.insights().take(2).forEach { (ic, tx) ->
                addView(LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.iconView(ic, Ui.VIOLET, 16), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { rightMargin = ui.dp(8) })
                    addView(ui.tv(tx, 12f, Color.parseColor("#C8D3DE")))
                }, ui.lp(8))
            }
            addView(ui.btn("AI diagnosis", Ui.VIOLET) { a.show("Diag") }, ui.lp(10))
        }, ui.lp(10))

        // Road AI strip
        run {
            val ri = RoadAssist.info
            val sg = RoadSignView(a).apply { limit = ri?.limit; estimated = ri?.estimated == true; scaleX = 0.6f; scaleY = 0.6f }
            roadTv = ui.tv(if (!Prefs.roadAssist) "Road assistant is off" else ri?.let { listOf(it.ref, it.road).filter { s -> s.isNotEmpty() }.joinToString(" · ").ifEmpty { "Current road" } } ?: "Road AI · " + Gps.statusText(a), 14f, Ui.WHITE, true)
            roadSub = ui.tv("", 12f, Ui.DIM)
            box.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.rounded(Ui.CARD, 20); setPadding(0, 0, ui.dp(14), 0)
                addView(sg, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
                addView(ui.vbox().apply { addView(roadTv); addView(roadSub) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                addView(ui.chip("Road AI", Ui.GOOD, Ic.NAV))
                setOnClickListener { a.show("Road") }; ui.pressAnim(this)
            }, ui.lp(10))
            signView = sg
        }

        // overview numbers
        val month = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        val trips = Db.trips(v.id, month, 400)
        val km = trips.sumOf { it.distanceKm }; val fuel = trips.sumOf { it.fuel }; val spend = trips.sumOf { it.cost }
        box.addView(ui.section("Overview · this month", Ic.CHART))
        val (s1, t1) = ui.stat(Ic.ROUTE, "Distance", Ui.ACC); t1.text = ui.dist(km, 0)
        val (s2, t2) = ui.stat(Ic.CAR, "Drives", Ui.CYAN); t2.text = trips.size.toString()
        val (s3, t3) = ui.stat(Ic.LEAF, "Economy", Ui.GOOD); t3.text = ui.econ(if (fuel > 0) km / fuel else null)
        val (s4, t4) = ui.stat(Ic.DATABASE, "Fuel spend", Ui.VIOLET); t4.text = ui.money(spend)
        val (s5, t5) = ui.stat(Ic.CLOCK, "Drive time", Ui.ORANGE); t5.text = "%.1f h".format(trips.sumOf { it.durationS } / 3600.0)
        val (s6, t6) = ui.stat(Ic.SHIELD, "Avg score", Ui.PINK); t6.text = if (trips.isNotEmpty()) trips.map { it.score }.average().toInt().toString() else "--"
        box.addView(ui.row(s1, s2, s3), ui.lp(2)); box.addView(ui.row(s4, s5, s6), ui.lp(8))

        // car status
        box.addView(ui.section("Car status", Ic.SHIELD))
        val hs = try { HealthScore.compute().score } catch (_: Exception) { -1 }
        val due = try { Maint.dueList(v).firstOrNull() } catch (_: Exception) { null }
        val alertN = try { Db.alertCountSince(now - 7 * 86_400_000L) } catch (_: Exception) { 0 }
        val range = m.rangeKm()
        val fl = m.values[0x2F]
        val (c1, d1) = ui.stat(Ic.SHIELD, "Health", Ui.GOOD); d1.text = if (hs >= 0) "$hs / 100" else "--"
        val (c2, d2) = ui.stat(if (v.isEv) Ic.BATTERY else Ic.FUEL, if (v.isEv) "Charge" else "Fuel", Ui.ORANGE); d2.text = ui.f(fl, 0, " %")
        val (c3, d3) = ui.stat(Ic.ROUTE, "Range", Ui.CYAN); d3.text = ui.dist(range, 0)
        val (c4, d4) = ui.stat(Ic.WRENCH, "Next service", Ui.WARN); d4.text = due?.let { if (it.kmLeft != null) Units.dStr(it.kmLeft!!) else "${it.daysLeft} d" } ?: "--"
        val (c5, d5) = ui.stat(Ic.BELL, "Alerts · 7d", Ui.PINK); d5.text = alertN.toString()
        val (c6, d6) = ui.stat(Ic.BATTERY, "Battery", Ui.ACC); d6.text = if (m.state == ObdManager.State.CONNECTED) ui.f(m.batteryV, 1, " V") else "--"
        c1.setOnClickListener { a.show("Health") }; c4.setOnClickListener { a.show("Maint") }; c5.setOnClickListener { a.show("Alerts") }; c6.setOnClickListener { a.show("Battery") }
        box.addView(ui.row(c1, c2, c3), ui.lp(2)); box.addView(ui.row(c4, c5, c6), ui.lp(8))

        // last drive with map
        val last = trips.firstOrNull() ?: Db.trips(v.id, 0, 1).firstOrNull()
        box.addView(ui.section("Last drive", Ic.ROUTE))
        if (last == null) box.addView(ui.iconCard(Ic.ROUTE, Ui.ACC, "No drives yet", "Trips are recorded automatically"))
        else {
            val map = TileMapView(a, true)
            map.lazyLoader = { mv -> Geo.route(last.id) { r -> if (r != null) mv.setRoute(r.pts, r.speeds) else mv.post { mv.visibility = View.GONE } } }
            val sTv = ui.tv("…", 13f, Ui.WHITE); val eTv = ui.tv("…", 13f, Ui.WHITE)
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.rounded(Ui.CARD, 20); setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                addView(map, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(150)))
                addView(LinearLayout(a).apply {
                    gravity = Gravity.BOTTOM
                    addView(ui.tv("%.2f".format(last.distanceKm), 26f, Ui.ACC, true))
                    addView(ui.tv(" " + Units.distLabel, 16f, Ui.DIM, true), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { bottomMargin = ui.dp(2) })
                    addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(ui.tv(ui.date(last.start, "EEE dd MMM · HH:mm") + " · ${(last.durationS / 60).toInt()} min", 12.5f, Ui.DIM))
                }, ui.lp(10))
                addView(ui.row(
                    LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
                        addView(ui.iconView(Ic.FLAG, Ui.ACC, 16), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { rightMargin = ui.dp(6) }); addView(sTv) },
                    LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
                        addView(ui.iconView(Ic.FLAG, Ui.BAD, 16), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { rightMargin = ui.dp(6) }); addView(eTv) }
                ), ui.lp(6))
                setOnClickListener { a.selectedTripId = last.id; a.show("TripDetail") }
                ui.pressAnim(this)
            }, ui.lp(2))
            Geo.endpoints(last.id) { ep ->
                if (my != gen) return@endpoints
                if (ep == null) { sTv.text = "No GPS"; eTv.text = "No GPS"; return@endpoints }
                Geo.name(a, ep.first.first, ep.first.second) { sTv.text = it }
                Geo.name(a, ep.second.first, ep.second.second) { eTv.text = it }
            }
        }

        // quick actions
        box.addView(ui.section("Quick actions", Ic.GRID))
        box.addView(ui.grid(
            ui.menuTile(Ic.CHART, "Live data", Ui.ACC) { a.show("Live") },
            ui.menuTile(Ic.GAUGE, "Drive mode", Ui.CYAN) { a.show("Car") },
            ui.menuTile(Ic.FUEL, "Add fill-up", Ui.ORANGE) { a.selectedFuelId = 0L; a.show("FuelAdd") }
        ), ui.lp(2))
        box.addView(ui.grid(
            ui.menuTile(Ic.PULSE, "Diagnose", Ui.PINK) { a.show("Diag") },
            ui.menuTile(Ic.PIN, "Parking", Ui.VIOLET) { openParking() },
            ui.menuTile(Ic.SHIELD, "Car health", Ui.GOOD) { a.show("Health") }
        ), ui.lp(2))
        box.addView(ui.grid(
            ui.menuTile(Ic.WARN, "Report hazard", Ui.WARN) { HazardQuick.show(a) },
            ui.menuTile(Ic.SCAN, "ADAS", Ui.CYAN) { a.show("Signs") },
            ui.menuTile(Ic.ROUTE, "Road assistant", Ui.GOOD) { a.show("Road") }
        ), ui.lp(8))
        box.addView(ui.tv("${AppInfo.NAME} v${AppInfo.VERSION}\nSoftware developed by ${AppInfo.DEVELOPER}", 12f, Ui.DIM).apply { gravity = Gravity.CENTER }, ui.lp(22))
    }
}

/* ============================ Driving replay ============================ */

@SuppressLint("SetTextI18n")
class TripReplayPage(a: MainActivity) : Page(a) {
    override val parent = "TripDetail"
    private lateinit var box: LinearLayout
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var playing = false
    private var frac = 0f
    private var pts: List<TrackPoint> = emptyList()
    private lateinit var map: TileMapView
    private lateinit var seek: android.widget.SeekBar
    private lateinit var spdTv: TextView
    private lateinit var rpmTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var playBtn: android.widget.Button
    private var totalMs = 40_000f

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            if (a.current != "TripReplay") { playing = false; return }
            frac += 60f / totalMs
            if (frac >= 1f) { frac = 1f; playing = false; playBtn.text = "Replay" }
            apply(); if (playing) h.postDelayed(this, 60)
        }
    }

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    private fun apply() {
        if (pts.isEmpty()) return
        val i = (frac * (pts.size - 1)).toInt().coerceIn(0, pts.size - 1)
        val p = pts[i]
        map.setProgress(frac)
        spdTv.text = "%.0f".format(p.speed); rpmTv.text = "%.0f".format(p.rpm)
        timeTv.text = ui.date(p.t, "HH:mm:ss")
        seek.progress = (frac * 1000).toInt()
    }

    override fun onShow() {
        playing = false; frac = 0f; h.removeCallbacks(tick)
        box.removeAllViews()
        val t = Db.trip(a.selectedTripId) ?: run { box.addView(ui.tv("Trip not found.", 14f, Ui.DIM)); return }
        pts = Db.track(t.id).filter { it.lat != null && it.lon != null }
        box.addView(ui.tv(ui.date(t.start, "EEE/MMM/dd · HH:mm"), 20f, Ui.WHITE, true), ui.lp(4))
        if (pts.size < 2) { box.addView(ui.iconCard(Ic.ROUTE, Ui.WARN, "No GPS route", "Enable GPS in Settings to record routes"), ui.lp(12)); return }
        totalMs = (pts.size * 120f).coerceIn(15_000f, 60_000f)
        map = TileMapView(a)
        val evs = Db.events(t.id)
        map.setRoute(pts.map { it.lat!! to it.lon!! }, pts.map { it.speed }, evs.filter { it.lat != null && it.lon != null }.map { Triple(it.lat!!, it.lon!!, Ui.speedColor3(it.speed)) }, "Start", "End")
        map.setProgress(0f)
        box.addView(map, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(360)).apply { topMargin = ui.dp(10) })
        val (s1, t1) = ui.stat(Ic.GAUGE, "Speed " + Units.spdLabel, Ui.ACC); spdTv = t1
        val (s2, t2) = ui.stat(Ic.ENGINE, "RPM", Ui.ORANGE); rpmTv = t2
        val (s3, t3) = ui.stat(Ic.CLOCK, "Time", Ui.CYAN); timeTv = t3
        box.addView(ui.row(s1, s2, s3), ui.lp(10))
        seek = android.widget.SeekBar(a).apply {
            max = 1000
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar, p: Int, user: Boolean) { if (user) { frac = p / 1000f; apply() } }
                override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
            })
        }
        box.addView(seek, ui.lp(10))
        playBtn = ui.btn("Play", Ui.ACC) {
            if (playing) { playing = false; playBtn.text = "Play" }
            else { if (frac >= 1f) frac = 0f; playing = true; playBtn.text = "Pause"; h.post(tick) }
        }
        box.addView(playBtn, ui.lp(8))
        box.addView(LinearLayout(a).apply {
            gravity = android.view.Gravity.CENTER
            for ((c, l) in listOf(Ui.speedColor3(0.0) to "Below " + Units.sStr(50.0), Ui.speedColor3(60.0) to "%.0f–%s".format(Units.spd(50.0), Units.sStr(80.0)), Ui.speedColor3(100.0) to "Above " + Units.sStr(80.0))) {
                addView(View(a).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c) } }, LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)).apply { rightMargin = ui.dp(5) })
                addView(ui.tv(l, 11.5f, Ui.DIM), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(14) })
            }
        }, ui.lp(8))
        box.addView(ui.section("Events on this drive (${evs.size})", Ic.FLAG))
        if (evs.isEmpty()) box.addView(ui.iconCard(Ic.CHECK, Ui.GOOD, "Smooth drive", "No over-speed, harsh braking or rapid acceleration events.", Ui.CARD), ui.lp(4))
        else {
            box.addView(ui.tv("Tap an event to jump to it in the replay. Symbol colour shows the speed at that moment.", 11.5f, Ui.DIM), ui.lp(2))
            for (e in evs) {
                val sc = Ui.speedColor3(e.speed)
                box.addView(ui.iconCard(Ui.eventIcon(e.type), sc, Ui.eventName(e.type),
                    "${ui.date(e.t, "HH:mm:ss")} · ${"%.0f".format(e.speed)} km/h · ${e.label.substringAfter(e.type).trim()}", Ui.CARD) {
                    if (pts.isNotEmpty()) {
                        val i = pts.indexOfFirst { it.t >= e.t }.let { if (it < 0) pts.size - 1 else it }
                        frac = i.toFloat() / (pts.size - 1).coerceAtLeast(1); playing = false; playBtn.text = "Play"; apply()
                    }
                }, ui.lp(4))
            }
        }
        apply()
    }
}
