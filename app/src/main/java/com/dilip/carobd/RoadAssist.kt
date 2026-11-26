package com.dilip.carobd

import android.content.Context
import android.location.Location
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Road assistant: finds the road you are on (OpenStreetMap), its speed limit, your heading and nearby hazards
 * (speed breakers, speed cameras, school/hospital zones) and announces them – also in the background while the
 * foreground service is running.
 */
object RoadAssist {
    class Info(val road: String, val ref: String, val highway: String, val limit: Int?, val estimated: Boolean, val fetched: Long)
    class Hazard(val kind: String, val lat: Double, val lon: Double, val label: String)

    @Volatile var info: Info? = null
    @Volatile var hazards: List<Hazard> = emptyList()
    @Volatile var heading: Float? = null
    @Volatile var speedKmh = 0.0
    @Volatile var lat = 0.0
    @Volatile var lon = 0.0
    @Volatile var lastFix = 0L
    @Volatile var status = "Waiting for GPS"
    val log = ArrayDeque<Pair<Long, String>>()

    private val ex = Executors.newSingleThreadExecutor()
    private var qLat = 0.0; private var qLon = 0.0; private var qT = 0L; private var busy = false; private var failUntil = 0L
    private val cache = HashMap<String, Pair<Info?, List<Hazard>>>()
    private var lastLimitSeen: Int? = null; private var limitStable = 0; private var lastAnnounced: Int? = null
    private var lastNotifLimit: Int? = -1
    private val ctxLock = Any()

    fun compass(deg: Float?): String {
        if (deg == null) return "—"
        val n = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return n[((deg + 22.5f) / 45f).toInt() % 8]
    }

    fun addLog(s: String) { synchronized(log) { log.addFirst(System.currentTimeMillis() to s); while (log.size > 30) log.removeLast() } }

    /** Limit lowered for weather (AI suggestion). */
    fun suggested(limit: Int?): Pair<Int, String>? {
        if (limit == null) return null
        val w = Weather.last ?: return null
        return when {
            w.code in 95..99 -> (limit * 0.75).toInt() to "thunderstorm"
            w.code in 45..48 -> (limit * 0.7).toInt() to "fog"
            w.precip > 0.2 || w.code in 51..67 || w.code in 80..82 -> (limit * 0.85).toInt() to "wet road"
            else -> null
        }
    }

    fun onLocation(l: Location) {
        if (!Prefs.roadAssist) return
        lat = l.latitude; lon = l.longitude; lastFix = System.currentTimeMillis()
        speedKmh = if (l.hasSpeed()) l.speed * 3.6 else 0.0
        if (l.hasBearing() && speedKmh > 4) heading = l.bearing
        status = "Tracking"
        maybeQuery(l)
        evaluate()
    }

    private fun maybeQuery(l: Location) {
        val now = System.currentTimeMillis()
        if (busy || now < failUntil) return
        val moved = Nav.dist(qLat, qLon, l.latitude, l.longitude) * 1000
        if (qT != 0L && (moved < 100 || now - qT < 10_000) && now - qT < 60_000) return
        val key = "%.3f,%.3f".format(Locale.US, l.latitude, l.longitude)
        cache[key]?.let { (i, h) -> info = i; hazards = h; qLat = l.latitude; qLon = l.longitude; qT = now; return }
        busy = true; qLat = l.latitude; qLon = l.longitude; qT = now
        val la = l.latitude; val lo = l.longitude
        ex.execute {
            try {
                val r = fetch(la, lo)
                if (r != null) {
                    info = r.first; hazards = r.second
                    if (cache.size > 300) cache.clear()
                    if (r.first != null) cache[key] = r
                    status = if (r.first == null) "No road data here" else "Tracking"
                } else { failUntil = System.currentTimeMillis() + 45_000; status = "Offline – using last data" }
            } catch (_: Exception) {
                failUntil = System.currentTimeMillis() + 45_000
            } finally { busy = false }
        }
    }

    private fun fetch(la: Double, lo: Double): Pair<Info?, List<Hazard>>? {
        val q = "[out:json][timeout:8];(way(around:25,$la,$lo)[\"highway\"];" +
                "node(around:350,$la,$lo)[\"highway\"=\"speed_camera\"];node(around:300,$la,$lo)[\"traffic_calming\"];" +
                "node(around:300,$la,$lo)[\"amenity\"~\"^(school|hospital)\$\"];);out tags geom 60;"
        var body: String? = null
        for (host in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
            try {
                val c = URL("$host?data=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 9000
                c.setRequestProperty("User-Agent", "CarOBDPro/${AppInfo.VERSION} (Android)")
                if (c.responseCode == 200) body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                if (body != null) break
            } catch (_: Exception) {
            }
        }
        val els = JSONObject(body ?: return null).optJSONArray("elements") ?: return null
        var best: Pair<Double, JSONObject>? = null
        val hz = ArrayList<Hazard>()
        val okRoads = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "service", "living_street",
            "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link")
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i); val tags = e.optJSONObject("tags") ?: JSONObject()
            if (e.getString("type") == "way") {
                if (tags.optString("highway") !in okRoads) continue
                val g = e.optJSONArray("geometry") ?: continue
                var dMin = 1e9
                for (k in 0 until g.length() - 1) {
                    val a = g.getJSONObject(k); val b = g.getJSONObject(k + 1)
                    dMin = min(dMin, segDist(la, lo, a.getDouble("lat"), a.getDouble("lon"), b.getDouble("lat"), b.getDouble("lon")))
                }
                if (g.length() == 1) dMin = 0.0
                if (best == null || dMin < best.first) best = dMin to e
            } else {
                val nl = e.optDouble("lat", Double.NaN); val no = e.optDouble("lon", Double.NaN)
                if (nl.isNaN()) continue
                when {
                    tags.optString("highway") == "speed_camera" -> hz += Hazard("camera", nl, no, "Speed camera")
                    tags.has("traffic_calming") -> hz += Hazard("bump", nl, no, if (tags.optString("traffic_calming").contains("bump") || tags.optString("traffic_calming").contains("hump") || tags.optString("traffic_calming") == "table") "Speed breaker" else "Traffic calming")
                    tags.optString("amenity") == "school" -> hz += Hazard("school", nl, no, "School zone")
                    tags.optString("amenity") == "hospital" -> hz += Hazard("hospital", nl, no, "Hospital zone")
                }
            }
        }
        val tg = best?.second?.optJSONObject("tags")
        val info = if (tg == null) null else {
            val hw = tg.optString("highway")
            val tagged = parseLimit(tg.optString("maxspeed"))
            val est = estimate(hw)
            Info(tg.optString("name"), tg.optString("ref"), hw, tagged ?: est, tagged == null, System.currentTimeMillis())
        }
        return info to hz
    }

    private fun parseLimit(s: String): Int? {
        if (s.isBlank()) return null
        val n = Regex("\\d+").find(s)?.value?.toIntOrNull() ?: return null
        return if (s.contains("mph", true)) (n * 1.609).roundToInt() else n
    }

    private fun estimate(hw: String) = when (hw) {
        "motorway" -> 100; "motorway_link" -> 60; "trunk" -> 80; "trunk_link" -> 50; "primary" -> 60; "primary_link" -> 40
        "secondary" -> 50; "tertiary" -> 40; "unclassified" -> 40; "residential" -> 30; "living_street" -> 20; "service" -> 20; else -> null
    }

    private fun segDist(pLa: Double, pLo: Double, aLa: Double, aLo: Double, bLa: Double, bLo: Double): Double {
        val k = cos(Math.toRadians(pLa)) * 111320.0; val m = 110540.0
        val ax = (aLo - pLo) * k; val ay = (aLa - pLa) * m; val bx = (bLo - pLo) * k; val by = (bLa - pLa) * m
        val dx = bx - ax; val dy = by - ay
        val t = if (dx == 0.0 && dy == 0.0) 0.0 else ((-ax * dx - ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    private fun bearingTo(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val y = sin(Math.toRadians(lo2 - lo1)) * cos(Math.toRadians(la2))
        val x = cos(Math.toRadians(la1)) * sin(Math.toRadians(la2)) - sin(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * cos(Math.toRadians(lo2 - lo1))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Hazards ahead of the car (within ±65° of the heading). */
    fun ahead(): List<Pair<Hazard, Int>> {
        val h = heading ?: return emptyList()
        return hazards.mapNotNull { z ->
            val d = Nav.dist(lat, lon, z.lat, z.lon) * 1000
            val br = bearingTo(lat, lon, z.lat, z.lon)
            val diff = abs(((br - h + 540) % 360) - 180)
            if (diff <= 65 && d <= (if (z.kind == "camera") 350 else 260)) z to d.toInt() else null
        }.sortedBy { it.second }
    }

    private fun evaluate() {
        val i = info ?: return
        val spd = speedKmh
        val lim = i.limit
        val app = try { ObdManager.app } catch (_: Exception) { return }
        // limit change announcement
        if (lim != null && lim == lastLimitSeen) limitStable++ else { lastLimitSeen = lim; limitStable = 1 }
        if (lim != null && limitStable >= 2 && lim != lastAnnounced && spd > 6) {
            lastAnnounced = lim
            val txt = "Speed limit $lim" + if (i.estimated) ", estimated" else ""
            Alerts.announce("road-limit", txt, 15_000); addLog(txt + if (i.road.isNotEmpty()) " · ${i.road}" else "")
        }
        // over-speed
        if (lim != null && (!i.estimated || Prefs.roadEstWarn) && spd > lim + Prefs.speedMargin && spd > 10) {
            val txt = "Over speed limit. Limit is $lim."
            Alerts.announce("road-over", txt, 25_000); addLog("⚠ ${"%.0f".format(spd)} km/h in a $lim zone")
        }
        // hazards
        if (Prefs.roadHazards && spd > 8) for ((z, d) in ahead()) {
            val key = "hz-${z.kind}-${"%.4f".format(Locale.US, z.lat)}-${"%.4f".format(Locale.US, z.lon)}"
            val slow = z.kind == "school" || z.kind == "hospital"
            if (slow && spd < 30) continue
            val txt = when (z.kind) {
                "camera" -> "Speed camera ahead"; "bump" -> "${z.label} ahead, slow down"
                "school" -> "School zone ahead, drive carefully"; else -> "Hospital zone ahead, keep quiet and slow"
            }
            Alerts.announce(key, txt, 300_000); addLog("$txt · ${d} m")
        }
        // notification text with the live limit
        if (lim != lastNotifLimit && ObdService.running) {
            lastNotifLimit = lim
            ObdService.update(app, AppInfo.NAME, if (lim != null) "Road assistant · limit $lim km/h" + (if (i.road.isNotEmpty()) " · ${i.road}" else "") else "Road assistant active")
        }
    }
}
