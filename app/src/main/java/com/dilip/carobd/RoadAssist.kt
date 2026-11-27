package com.dilip.carobd

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Road assistant. Downloads the OpenStreetMap roads and hazards around you into saved map areas (works offline afterwards),
 * matches your GPS position to the right road using your driving direction, and announces limits and hazards – also in the
 * background while the foreground service is running. You can correct a wrong limit and report your own hazards.
 */
object RoadAssist {
    class Info(val road: String, val ref: String, val highway: String, val limit: Int?, val estimated: Boolean, val fetched: Long,
               val source: String = "osm", val wayId: Long = 0L)
    class Hazard(val kind: String, val lat: Double, val lon: Double, val label: String, val mine: Boolean = false)
    class Way(val id: Long, val name: String, val ref: String, val hw: String, val limit: Int, val oneway: Boolean, val pts: DoubleArray) {
        val minLa: Double; val maxLa: Double; val minLo: Double; val maxLo: Double
        init {
            var a = 1e9; var b = -1e9; var c = 1e9; var d = -1e9
            var i = 0
            while (i < pts.size) { a = min(a, pts[i]); b = max(b, pts[i]); c = min(c, pts[i + 1]); d = max(d, pts[i + 1]); i += 2 }
            minLa = a; maxLa = b; minLo = c; maxLo = d
        }
    }
    class Tile(val la: Double, val lo: Double, val r: Double, val t: Long, val ways: List<Way>, val hz: List<Hazard>)

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
    private val main = Handler(Looper.getMainLooper())
    private val tiles = ArrayList<Tile>()
    private var loaded = false
    @Volatile private var busy = false
    private var failUntil = 0L
    private var lastFetchT = 0L
    private var curWay = 0L
    private var lastMatchT = 0L
    private var hazT = 0L
    private var lastLimitSeen: Int? = null; private var limitStable = 0; private var lastAnnounced: Int? = null
    private var lastNotifLimit: Int? = -1

    private const val FILE = "road_areas.json"
    private const val TILE_R = 1500.0
    private const val MAX_TILES = 12
    private const val FRESH_MS = 45L * 86_400_000L
    private val OK_ROADS = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"

    fun compass(deg: Float?): String {
        if (deg == null) return "—"
        val n = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return n[((deg + 22.5f) / 45f).toInt() % 8]
    }

    fun addLog(s: String) { synchronized(log) { log.addFirst(System.currentTimeMillis() to s); while (log.size > 30) log.removeLast() } }

    /* ---------------- user data: limit corrections and own hazards ---------------- */

    private fun fixes(): JSONObject = try { JSONObject(Prefs.roadFix) } catch (_: Exception) { JSONObject() }

    fun setLimitFix(wayId: Long, limit: Int?) {
        if (wayId == 0L) return
        val o = fixes()
        if (limit == null) o.remove(wayId.toString()) else o.put(wayId.toString(), limit)
        Prefs.roadFix = o.toString()
        lastMatchT = 0L
    }

    private fun userHazards(): List<Hazard> = try {
        val a = JSONArray(Prefs.roadUserHaz)
        (0 until a.length()).map { val o = a.getJSONObject(it); Hazard(o.getString("k"), o.getDouble("a"), o.getDouble("o"), o.optString("b", labelFor(o.getString("k"))), true) }
    } catch (_: Exception) { emptyList() }

    fun labelFor(kind: String) = when (kind) {
        "bump" -> "Speed breaker"; "camera" -> "Speed camera"; "pothole" -> "Pothole"; "curve" -> "Sharp curve"
        "school" -> "School zone"; "hospital" -> "Hospital zone"; "rail" -> "Level crossing"; "toll" -> "Toll plaza"; else -> "Hazard"
    }

    fun reportHazard(kind: String): Boolean {
        if (lastFix == 0L || (lat == 0.0 && lon == 0.0)) return false
        val a = try { JSONArray(Prefs.roadUserHaz) } catch (_: Exception) { JSONArray() }
        val out = JSONArray()
        val start = max(0, a.length() - 399)
        for (i in start until a.length()) out.put(a.get(i))
        out.put(JSONObject().put("k", kind).put("a", lat).put("o", lon).put("b", labelFor(kind)))
        Prefs.roadUserHaz = out.toString(); hazT = 0L
        addLog("You reported: ${labelFor(kind)}")
        return true
    }

    /** Removes your own hazards within 80 m; returns how many. */
    fun removeMyHazardsHere(): Int {
        val keep = JSONArray(); var n = 0
        val a = try { JSONArray(Prefs.roadUserHaz) } catch (_: Exception) { JSONArray() }
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (Loc.dist(lat, lon, o.getDouble("a"), o.getDouble("o")) * 1000 < 80) n++ else keep.put(o)
        }
        Prefs.roadUserHaz = keep.toString(); hazT = 0L
        return n
    }

    /* ---------------- saved map areas ---------------- */

    private fun file(): File? = try { File(ObdManager.app.filesDir, FILE) } catch (_: Exception) { null }

    private fun loadTiles() {
        if (loaded) return
        loaded = true
        val f = file() ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            val ta = root.getJSONArray("tiles")
            for (i in 0 until ta.length()) {
                val o = ta.getJSONObject(i)
                val ways = ArrayList<Way>()
                val wa = o.getJSONArray("ways")
                for (k in 0 until wa.length()) {
                    val w = wa.getJSONObject(k); val p = w.getJSONArray("p")
                    ways += Way(w.getLong("i"), w.optString("n"), w.optString("f"), w.optString("h"), w.optInt("l", -1), w.optInt("o") == 1,
                        DoubleArray(p.length()) { p.getDouble(it) })
                }
                val hz = ArrayList<Hazard>(); val ha = o.getJSONArray("hz")
                for (k in 0 until ha.length()) { val h = ha.getJSONObject(k); hz += Hazard(h.getString("k"), h.getDouble("a"), h.getDouble("o"), h.optString("b", labelFor(h.getString("k")))) }
                tiles += Tile(o.getDouble("la"), o.getDouble("lo"), o.getDouble("r"), o.getLong("t"), ways, hz)
            }
        } catch (_: Exception) {
            tiles.clear()
        }
    }

    private fun saveTiles() {
        val f = file() ?: return
        try {
            val ta = JSONArray()
            for (t in tiles) {
                val wa = JSONArray()
                for (w in t.ways) {
                    val p = JSONArray(); w.pts.forEach { p.put(Math.round(it * 1e5) / 1e5) }
                    wa.put(JSONObject().put("i", w.id).put("n", w.name).put("f", w.ref).put("h", w.hw).put("l", w.limit).put("o", if (w.oneway) 1 else 0).put("p", p))
                }
                val ha = JSONArray()
                t.hz.forEach { ha.put(JSONObject().put("k", it.kind).put("a", it.lat).put("o", it.lon).put("b", it.label)) }
                ta.put(JSONObject().put("la", t.la).put("lo", t.lo).put("r", t.r).put("t", t.t).put("ways", wa).put("hz", ha))
            }
            f.writeText(JSONObject().put("v", 1).put("tiles", ta).toString())
        } catch (_: Exception) {
        }
    }

    fun savedInfo(): String {
        synchronized(tiles) { loadTiles() }
        val kb = (file()?.length() ?: 0L) / 1024
        val n = synchronized(tiles) { tiles.size }
        return if (n == 0) "No saved map yet – drive with internet, or download this area" else "$n saved area(s) · ${if (kb > 1024) "%.1f MB".format(kb / 1024.0) else "$kb KB"} · works offline"
    }

    fun clearSaved() { synchronized(tiles) { tiles.clear(); file()?.delete() }; info = null }

    private fun covered(la: Double, lo: Double, margin: Double, fresh: Boolean): Boolean = synchronized(tiles) {
        val now = System.currentTimeMillis()
        tiles.any { (!fresh || now - it.t < FRESH_MS) && Loc.dist(la, lo, it.la, it.lo) * 1000 <= it.r - margin }
    }

    private fun addTile(t: Tile) = synchronized(tiles) {
        tiles.removeAll { Loc.dist(it.la, it.lo, t.la, t.lo) * 1000 < 300 }
        tiles += t
        while (tiles.size > MAX_TILES) tiles.remove(tiles.minByOrNull { it.t })
        saveTiles()
    }

    /** Used by tests and offline import. */
    fun installTile(t: Tile) = addTile(t)

    /** Download an area around the current position for offline use. */
    fun downloadArea(radius: Double = 3000.0, cb: (String) -> Unit) {
        if (busy) { cb("Another download is running"); return }
        val la = if (lastFix != 0L) lat else Loc.here(ObdManager.app, true)?.first
        val lo = if (lastFix != 0L) lon else Loc.here(ObdManager.app, true)?.second
        if (la == null || lo == null) { cb("No position yet – wait for GPS"); return }
        busy = true; status = "Downloading map…"
        ex.execute {
            val msg = try {
                val t = fetchTile(la, lo, radius)
                if (t == null) "Download failed – check internet and try again" else { addTile(t); "Saved ${t.ways.size} roads and ${t.hz.size} hazards around you" }
            } catch (e: Exception) { "Download failed: ${e.message}" } finally { busy = false; status = "Tracking" }
            main.post { cb(msg) }
        }
    }

    private fun fetchTile(la: Double, lo: Double, r: Double): Tile? {
        val ri = r.toInt()
        val q = "[out:json][timeout:50];(way(around:$ri,$la,$lo)[\"highway\"~\"^($OK_ROADS)\$\"];" +
                "node(around:$ri,$la,$lo)[\"highway\"=\"speed_camera\"];node(around:$ri,$la,$lo)[\"traffic_calming\"];" +
                "node(around:$ri,$la,$lo)[\"amenity\"~\"^(school|hospital)\$\"];node(around:$ri,$la,$lo)[\"railway\"=\"level_crossing\"];" +
                "node(around:$ri,$la,$lo)[\"barrier\"=\"toll_booth\"];);out tags geom;"
        var body: String? = null
        for (host in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
            try {
                val c = URL("$host?data=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 55000
                c.setRequestProperty("User-Agent", "CarOBDPro/${AppInfo.VERSION} (Android)")
                if (c.responseCode == 200) body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                if (body != null) break
            } catch (_: Exception) {
            }
        }
        val els = JSONObject(body ?: return null).optJSONArray("elements") ?: return null
        val ways = ArrayList<Way>(); val hz = ArrayList<Hazard>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i); val tags = e.optJSONObject("tags") ?: JSONObject()
            if (e.getString("type") == "way") {
                val g = e.optJSONArray("geometry") ?: continue
                if (g.length() < 2) continue
                val pts = DoubleArray(g.length() * 2)
                for (k in 0 until g.length()) { val p = g.getJSONObject(k); pts[k * 2] = p.getDouble("lat"); pts[k * 2 + 1] = p.getDouble("lon") }
                val ow = tags.optString("oneway").let { it == "yes" || it == "1" || it == "true" } || tags.optString("junction") == "roundabout"
                val lim = parseLimit(tags.optString("maxspeed").ifBlank { tags.optString("maxspeed:forward") }) ?: -1
                ways += Way(e.getLong("id"), tags.optString("name"), tags.optString("ref"), tags.optString("highway"), lim, ow, pts)
            } else {
                val nl = e.optDouble("lat", Double.NaN); val no = e.optDouble("lon", Double.NaN)
                if (nl.isNaN()) continue
                val tc = tags.optString("traffic_calming")
                when {
                    tags.optString("highway") == "speed_camera" -> hz += Hazard("camera", nl, no, "Speed camera")
                    tc.isNotEmpty() -> hz += Hazard("bump", nl, no, if (tc.contains("bump") || tc.contains("hump") || tc == "table" || tc == "cushion") "Speed breaker" else "Traffic calming")
                    tags.optString("amenity") == "school" -> hz += Hazard("school", nl, no, "School zone")
                    tags.optString("amenity") == "hospital" -> hz += Hazard("hospital", nl, no, "Hospital zone")
                    tags.optString("railway") == "level_crossing" -> hz += Hazard("rail", nl, no, "Level crossing")
                    tags.optString("barrier") == "toll_booth" -> hz += Hazard("toll", nl, no, "Toll plaza")
                }
            }
        }
        return Tile(la, lo, r, System.currentTimeMillis(), ways, hz)
    }

    /* ---------------- live tracking ---------------- */

    fun onLocation(l: Location) {
        if (!Prefs.roadAssist) return
        lat = l.latitude; lon = l.longitude; lastFix = System.currentTimeMillis()
        speedKmh = if (l.hasSpeed()) l.speed * 3.6 else 0.0
        if (l.hasBearing() && speedKmh > 4) heading = l.bearing
        synchronized(tiles) { loadTiles() }
        maybeFetch()
        matchRoad(l)
        refreshHazards()
        evaluate()
    }

    private fun maybeFetch() {
        val now = System.currentTimeMillis()
        if (busy || now < failUntil || now - lastFetchT < 20_000) return
        var cLa = lat; var cLo = lon
        if (covered(lat, lon, 250.0, true)) {
            // already covered here: prefetch the area ahead while driving
            val h = heading ?: return
            if (speedKmh < 20) return
            val d = 700.0
            val aLa = lat + d * cos(Math.toRadians(h.toDouble())) / 110540.0
            val aLo = lon + d * sin(Math.toRadians(h.toDouble())) / (111320.0 * cos(Math.toRadians(lat)))
            if (covered(aLa, aLo, 100.0, true)) return
            cLa = aLa; cLo = aLo
        }
        busy = true; lastFetchT = now
        if (info == null) status = "Loading map…"
        ex.execute {
            try {
                val t = fetchTile(cLa, cLo, TILE_R)
                if (t != null) { addTile(t); status = "Tracking"; lastMatchT = 0L }
                else { failUntil = System.currentTimeMillis() + 45_000; status = if (covered(lat, lon, 0.0, false)) "Offline – using saved map" else "Offline – no saved map here" }
            } catch (_: Exception) {
                failUntil = System.currentTimeMillis() + 45_000
            } finally { busy = false }
        }
    }

    private fun matchRoad(l: Location) {
        val snap = synchronized(tiles) { tiles.toList() }
        val now = System.currentTimeMillis()
        val tol = max(35.0, (if (l.hasAccuracy()) l.accuracy else 10f) * 1.5 + 15)
        val dLat = tol / 110540.0; val dLon = tol / (111320.0 * cos(Math.toRadians(lat)))
        val hd = if (speedKmh > 6) heading else null
        var best: Way? = null; var bestCost = 1e9; var bestDist = 0.0
        for (t in snap) {
            if (Loc.dist(lat, lon, t.la, t.lo) * 1000 > t.r + 500) continue
            for (w in t.ways) {
                if (lat < w.minLa - dLat || lat > w.maxLa + dLat || lon < w.minLo - dLon || lon > w.maxLo + dLon) continue
                var dMin = 1e9; var bear = 0.0
                var k = 0
                while (k < w.pts.size - 2) {
                    val d = segDist(lat, lon, w.pts[k], w.pts[k + 1], w.pts[k + 2], w.pts[k + 3])
                    if (d < dMin) { dMin = d; bear = bearingTo(w.pts[k], w.pts[k + 1], w.pts[k + 2], w.pts[k + 3]) }
                    k += 2
                }
                if (dMin > tol) continue
                var cost = dMin
                if (hd != null) {
                    val diff = abs(((bear - hd + 540) % 360) - 180)
                    cost += (if (w.oneway) diff else min(diff, 180 - diff)) * 0.4
                }
                if (w.id == curWay) cost -= 10
                if (cost < bestCost) { bestCost = cost; best = w; bestDist = dMin }
            }
        }
        if (best == null) {
            if (snap.isNotEmpty() && now - lastMatchT > 10_000) { info = null; curWay = 0L; status = if (snap.any { Loc.dist(lat, lon, it.la, it.lo) * 1000 <= it.r }) "No road data here" else status }
            return
        }
        curWay = best.id; lastMatchT = now
        val fix = fixes().optInt(best.id.toString(), -1).takeIf { it > 0 }
        val tagged = best.limit.takeIf { it > 0 }
        val limit = fix ?: tagged ?: estimate(best.hw)
        info = Info(best.name, best.ref, best.hw, limit, fix == null && tagged == null, now, if (fix != null) "you" else if (tagged != null) "osm" else "est", best.id)
        if (!busy) status = if (covered(lat, lon, 0.0, true)) "Tracking" else "Tracking (saved map)"
    }

    private fun refreshHazards() {
        val now = System.currentTimeMillis()
        if (now - hazT < 3000) return
        hazT = now
        val out = ArrayList<Hazard>(); val seen = HashSet<String>()
        val snap = synchronized(tiles) { tiles.toList() }
        for (z in snap.flatMap { it.hz } + userHazards()) {
            if (abs(z.lat - lat) > 0.008 || abs(z.lon - lon) > 0.01) continue
            if (Loc.dist(lat, lon, z.lat, z.lon) * 1000 > 700) continue
            if (seen.add("${z.kind}${"%.4f".format(Locale.US, z.lat)}${"%.4f".format(Locale.US, z.lon)}")) out += z
        }
        hazards = out
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
            val d = Loc.dist(lat, lon, z.lat, z.lon) * 1000
            val br = bearingTo(lat, lon, z.lat, z.lon)
            val diff = abs(((br - h + 540) % 360) - 180)
            if (diff <= 65 && d <= (if (z.kind == "camera") 350 else if (z.kind == "toll" || z.kind == "rail") 300 else 260)) z to d.toInt() else null
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
            val txt = "Speed limit $lim" + if (i.estimated) ", estimated" else if (i.source == "you") ", as you set" else ""
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
                "camera" -> "Speed camera ahead"; "bump", "pothole" -> "${z.label} ahead, slow down"
                "curve" -> "Sharp curve ahead, slow down"; "rail" -> "Level crossing ahead, be ready to stop"; "toll" -> "Toll plaza ahead"
                "school" -> "School zone ahead, drive carefully"; "hospital" -> "Hospital zone ahead, keep quiet and slow"
                else -> "${z.label} ahead"
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
