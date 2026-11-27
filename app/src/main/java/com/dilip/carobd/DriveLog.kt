package com.dilip.carobd

import android.location.Location
import java.util.Locale

/** Records one drive (GPS, matched road, limit and where it came from) so wrong limits can be diagnosed from real data. */
object DriveLog {
    @Volatile var recording = false
    private var startedAt = 0L
    private val rows = StringBuilder()
    private val events = ArrayList<String>()
    private var lastRow = 0L
    private var n = 0; private var matched = 0; private var withLimit = 0; private var est = 0; private var cam = 0; private var poor = 0
    private var accSum = 0.0; private var dist = 0.0; private var maxSpd = 0.0; private var overRows = 0
    private val roads = LinkedHashSet<String>()
    private val seq = ArrayList<Long>(); private val seqT = ArrayList<Long>()
    private var lastLim = -1; private var limChanges = 0
    private var pLa = 0.0; private var pLo = 0.0; private var havePrev = false
    private var offMap = 0; private var noMapRows = 0

    fun seconds() = if (recording) ((System.currentTimeMillis() - startedAt) / 1000).toInt() else 0

    @Synchronized fun start() {
        rows.setLength(0); events.clear(); roads.clear(); seq.clear(); seqT.clear()
        n = 0; matched = 0; withLimit = 0; est = 0; cam = 0; poor = 0; accSum = 0.0; dist = 0.0; maxSpd = 0.0; overRows = 0
        lastLim = -1; limChanges = 0; havePrev = false; offMap = 0; noMapRows = 0; lastRow = 0L
        startedAt = System.currentTimeMillis(); recording = true
        rows.append("time,lat,lon,acc_m,speed_kmh,bearing,provider,road,ref,class,limit,source,conf,way,coverage,status\n")
    }

    @Synchronized fun event(text: String) {
        if (!recording || events.size > 600) return
        events += "${(System.currentTimeMillis() - startedAt) / 1000}s  $text"
    }

    fun mark() { event("MARK – driver says: wrong limit or road here (${RoadAssist.info?.road ?: "no road"}, limit ${RoadAssist.info?.limit ?: "none"})") }

    @Synchronized fun fix(l: Location) {
        if (!recording) return
        val now = System.currentTimeMillis()
        if (now - lastRow < 950 || n >= 7200) return
        lastRow = now
        val i = RoadAssist.info
        n++
        val acc = if (l.hasAccuracy()) l.accuracy.toDouble() else -1.0
        if (acc > 30) poor++
        if (acc >= 0) accSum += acc
        val spd = RoadAssist.speedKmh
        if (spd > maxSpd) maxSpd = spd
        if (havePrev) dist += Loc.dist(pLa, pLo, l.latitude, l.longitude)
        pLa = l.latitude; pLo = l.longitude; havePrev = true
        val cov = RoadAssist.coverage()
        if (cov == "none") noMapRows++
        if (i != null) {
            matched++; roads += i.road.ifEmpty { i.ref.ifEmpty { "(unnamed ${i.highway})" } }
            if (i.limit != null) { withLimit++; if (i.limit != lastLim) { if (lastLim >= 0) limChanges++; lastLim = i.limit } ; if (spd > i.limit + Prefs.speedMargin) overRows++ }
            if (i.estimated) est++
            if (i.source == "cam") cam++
            if (seq.isEmpty() || seq.last() != i.wayId) { seq += i.wayId; seqT += now }
            if (i.basis.startsWith("Last road")) offMap++
        }
        rows.append("%d,%.6f,%.6f,%.0f,%.0f,%s,%s,\"%s\",%s,%s,%s,%s,%s,%s,%s,\"%s\"\n".format(Locale.US,
            (now - startedAt) / 1000, l.latitude, l.longitude, acc, spd, if (l.hasBearing()) "%.0f".format(Locale.US, l.bearing) else "", l.provider ?: "",
            (i?.road ?: "").replace("\"", "'"), i?.ref ?: "", i?.highway ?: "", i?.limit?.toString() ?: "", i?.source ?: "", i?.conf?.toString() ?: "",
            i?.wayId?.toString() ?: "", cov, RoadAssist.status.replace("\"", "'")))
    }

    fun summary(): String {
        val secs = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)
        fun pct(x: Int) = if (n == 0) 0 else x * 100 / n
        // road flip-flops: A -> B -> A inside 15 s
        var flips = 0
        for (k in 2 until seq.size) if (seq[k] == seq[k - 2] && seqT[k] - seqT[k - 2] < 15_000) flips++
        val sb = StringBuilder()
        sb.append("Drive report – ${AppInfo.NAME} ${AppInfo.VERSION}\n")
        sb.append("Phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n")
        sb.append("Vehicle type: ${try { Prefs.vehicleClass(ObdManager.vehicle.id) } catch (_: Throwable) { "?" }}\n")
        sb.append("Length: ${secs / 60} min ${secs % 60} s, %.1f km, top speed %.0f km/h, %d GPS rows\n".format(Locale.US, dist, maxSpd, n))
        sb.append("GPS: average accuracy %.0f m, %d%% of fixes worse than 30 m\n".format(Locale.US, if (n > 0) accSum / n else 0.0, pct(poor)))
        sb.append("Road matched: ${pct(matched)}%  ·  with a limit: ${pct(withLimit)}%  ·  estimated: ${pct(est)}%  ·  read by camera: ${pct(cam)}%\n")
        sb.append("No saved map here: ${pct(noMapRows)}%  ·  carried over from last road: ${pct(offMap)}%\n")
        sb.append("Roads seen: ${roads.size}, road switches ${seq.size}, flip-flops $flips, limit changes $limChanges, over the limit ${pct(overRows)}% of the time\n")
        sb.append("Saved maps: ${RoadAssist.savedInfo()}\n")
        if (roads.isNotEmpty()) sb.append("Roads: ${roads.take(25).joinToString(" → ")}${if (roads.size > 25) " …" else ""}\n")
        return sb.toString()
    }

    /** Stops and returns the full text report (summary, events, then the one-row-per-second table), or null if nothing was recorded. */
    @Synchronized fun stop(): String? {
        if (!recording) return null
        val s = summary()
        recording = false
        if (n == 0) return null
        return s + "\nEvents\n" + (if (events.isEmpty()) "(none)\n" else events.joinToString("\n") + "\n") + "\nRows\n" + rows.toString() + "\n# ${AppInfo.credit}\n"
    }
}
