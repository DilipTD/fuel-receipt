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
               val source: String = "osm", val wayId: Long = 0L, val conf: Int = 100, val basis: String = "", val lanes: Int = 0,
               val oneway: Boolean = false, val lit: Boolean = false, val urban: Boolean = false)
    class Hazard(val kind: String, val lat: Double, val lon: Double, val label: String, val mine: Boolean = false, val adv: Int = 0)
    class Next(val limit: Int, val dist: Int, val estimated: Boolean, val road: String)
    /** [fl] bits: 1 lit, 2 sidewalk, 4 unpaved, 8 roundabout, bits 8-11 lane count. */
    class Way(val id: Long, val name: String, val ref: String, val hw: String, val limit: Int, val oneway: Boolean, val pts: DoubleArray, val fl: Int = 0, val hgv: Int = -1, val bus: Int = -1) {
        val lit: Boolean get() = fl and 1 != 0
        val sidewalk: Boolean get() = fl and 2 != 0
        val unpaved: Boolean get() = fl and 4 != 0
        val roundabout: Boolean get() = fl and 8 != 0
        val lanes: Int get() = (fl shr 8) and 15
        val minLa: Double; val maxLa: Double; val minLo: Double; val maxLo: Double
        init {
            var a = 1e9; var b = -1e9; var c = 1e9; var d = -1e9
            var i = 0
            while (i < pts.size) { a = min(a, pts[i]); b = max(b, pts[i]); c = min(c, pts[i + 1]); d = max(d, pts[i + 1]); i += 2 }
            minLa = a; maxLa = b; minLo = c; maxLo = d
        }
    }
    class Tile(val la: Double, val lo: Double, val r: Double, val t: Long, val ways: List<Way>, val hz: List<Hazard>, val lite: Boolean = false)

    @Volatile var info: Info? = null
    @Volatile var hazards: List<Hazard> = emptyList()
    @Volatile var heading: Float? = null
    @Volatile var speedKmh = 0.0
    @Volatile var lat = 0.0
    @Volatile var lon = 0.0
    @Volatile var lastFix = 0L
    @Volatile var status = "Waiting for GPS"
    @Volatile var next: Next? = null
    @Volatile var curve: Pair<Hazard, Int>? = null
    @Volatile var statSec = 0.0
    @Volatile var limSec = 0.0
    @Volatile var overSec = 0.0
    @Volatile var maxOver = 0.0
    @Volatile var topSpd = 0.0
    @Volatile var alertsN = 0
    private val announced = HashSet<String>()
    private val estCache = HashMap<Long, Lim>()
    private var aheadT = 0L
    private var prevFix = 0L

    fun resetStats() { statSec = 0.0; limSec = 0.0; overSec = 0.0; maxOver = 0.0; topSpd = 0.0; alertsN = 0; announced.clear() }
    val log = ArrayDeque<Pair<Long, String>>()

    private val ex = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tiles = ArrayList<Tile>()
    private var loaded = false
    @Volatile private var busy = false
    @Volatile var lastErr = ""
    private var failUntil = 0L
    private var lastFetchT = 0L
    private var curWay = 0L
    private var lastMatchT = 0L
    private var hazT = 0L
    private var lastLimitSeen: Int? = null; private var limitStable = 0; private var lastAnnounced: Int? = null
    private var lastNotifLimit: Int? = -1

    private const val FILE = "road_areas.json"
    private const val TILE_R = 1500.0
    private const val MAX_TILES = 40
    private const val MAX_WIDE = 900
    private const val WIDE_R = 12000.0
    private val MAJOR_ROADS = "motorway|trunk|primary|secondary|motorway_link|trunk_link|primary_link|secondary_link"
    private const val FRESH_MS = 45L * 86_400_000L
    private val OK_ROADS = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"

    fun compass(deg: Float?): String {
        if (deg == null) return "—"
        val n = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return n[((deg + 22.5f) / 45f).toInt() % 8]
    }

    /** Position of the car now, moved [meters] ahead along the heading (where a sign it sees really stands). */
    fun hereAhead(meters: Double): Pair<Double, Double>? {
        if (lastFix == 0L || System.currentTimeMillis() - lastFix > 15_000 || (lat == 0.0 && lon == 0.0)) return null
        val h = heading
        if (h == null || speedKmh < 5) return lat to lon
        return (lat + meters * cos(Math.toRadians(h.toDouble())) / 110540.0) to (lon + meters * sin(Math.toRadians(h.toDouble())) / (111320.0 * cos(Math.toRadians(lat))))
    }

    /** Which saved map covers the current position: town, wide (highways only) or none. */
    fun coverage(): String = if (covered(lat, lon, 0.0, false)) "town" else if (coveredWide(lat, lon)) "wide" else "none"

    fun addLog(s: String) { try { DriveLog.event(s) } catch (_: Throwable) {}; synchronized(log) { log.addFirst(System.currentTimeMillis() to s); while (log.size > 30) log.removeLast() } }

    /* ---------------- user data: limit corrections and own hazards ---------------- */

    private fun fixes(): JSONObject = try { JSONObject(Prefs.roadFix) } catch (_: Exception) { JSONObject() }

    fun setLimitFix(wayId: Long, limit: Int?) {
        if (wayId == 0L) return
        val o = fixes()
        if (limit == null) o.remove(wayId.toString()) else o.put(wayId.toString(), limit)
        Prefs.roadFix = o.toString()
        lastMatchT = 0L
    }

    private var camMap: HashMap<Long, Int>? = null

    /** After a restore: forget cached corrections so the restored ones are used. */
    fun reloadUserData() { camMap = null; hazT = 0L; lastMatchT = 0L; synchronized(estCache) { estCache.clear() }; info = null; curWay = 0L }

    /** Car, Truck or Bus – from the active vehicle in the Garage. */
    private fun vclass(): String = try { Prefs.vehicleClass(ObdManager.vehicle.id) } catch (_: Throwable) { "Car" }
    private const val TRUCK_CAP = 80

    /** The marked limit of a way for the current vehicle class (0 or less = none). */
    private fun markedFor(w: Way, cls: String): Int = when (cls) {
        "Truck" -> if (w.hgv > 0) w.hgv else if (w.limit > 0) min(w.limit, TRUCK_CAP) else -1
        "Bus" -> if (w.bus > 0) w.bus else if (w.hgv > 0) w.hgv else w.limit
        else -> w.limit
    }

    private fun cams(): HashMap<Long, Int> = camMap ?: HashMap<Long, Int>().also { m ->
        try { val o = JSONObject(Prefs.roadCam); for (k in o.keys()) m[k.toLong()] = o.getInt(k) } catch (_: Exception) {}
        camMap = m
    }

    /** A speed-limit sign read by the camera applies to the road you are on (kept until you correct it). Returns false when no road is matched. */
    fun cameraLimit(limit: Int): Boolean {
        val id = curWay
        if (id == 0L || info == null) return false
        val m = cams()
        if (m[id] != limit) {
            m[id] = limit
            val o = JSONObject(); for ((k, v) in m) o.put(k.toString(), v)
            Prefs.roadCam = o.toString(); lastMatchT = 0L
            addLog("Camera read a $limit sign" + (info?.road?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))
            info = info?.let { Info(it.road, it.ref, it.highway, limit, false, it.fetched, "cam", it.wayId, 90, "Read from a road sign by the camera", it.lanes, it.oneway, it.lit, it.urban) }
        }
        return true
    }

    fun forgetCamera(wayId: Long) {
        val m = cams(); m.remove(wayId)
        val o = JSONObject(); for ((k, v) in m) o.put(k.toString(), v)
        Prefs.roadCam = o.toString(); lastMatchT = 0L
    }

    fun hazardNear(kind: String, meters: Double): Boolean =
        (userHazards() + hazards).any { it.kind == kind && Loc.dist(lat, lon, it.lat, it.lon) * 1000 < meters }

    private fun userHazards(): List<Hazard> = try {
        val a = JSONArray(Prefs.roadUserHaz)
        (0 until a.length()).map { val o = a.getJSONObject(it); Hazard(o.getString("k"), o.getDouble("a"), o.getDouble("o"), o.optString("b", labelFor(o.getString("k"))), true) }
    } catch (_: Exception) { emptyList() }

    /** Speed from the linked ELM327 adapter when the user chose it and the car is connected. */
    fun obdSpeed(): Double? {
        if (Prefs.roadSpeedSrc == "gps") return null
        if (ObdManager.state != ObdManager.State.CONNECTED) return null
        return ObdManager.values[0x0D]?.takeIf { it in 0.0..300.0 }
    }

    /** Text for the Road page: which source the speed comes from right now. */
    fun speedSourceText(): String = when {
        obdSpeed() != null -> "ELM327 adapter (OBD speed)"
        Prefs.roadSpeedSrc == "obd" -> "GPS (ELM327 not connected yet)"
        else -> "GPS"
    }

    fun labelFor(kind: String) = when (kind) {
        "bump" -> "Speed breaker"; "camera" -> "Speed camera"; "pothole" -> "Pothole"; "curve" -> "Sharp curve"
        "school" -> "School zone"; "hospital" -> "Hospital zone"; "rail" -> "Level crossing"; "toll" -> "Toll plaza"; "accident" -> "Accident"; "flood" -> "Waterlogging"
        "work" -> "Road work"; "animal" -> "Animals"; "police" -> "Police check"; "fog" -> "Fog"; "narrow" -> "Narrow road"; else -> "Hazard"
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
                        DoubleArray(p.length()) { p.getDouble(it) }, w.optInt("x", 0), w.optInt("lh", -1), w.optInt("lb", -1))
                }
                val hz = ArrayList<Hazard>(); val ha = o.getJSONArray("hz")
                for (k in 0 until ha.length()) { val h = ha.getJSONObject(k); hz += Hazard(h.getString("k"), h.getDouble("a"), h.getDouble("o"), h.optString("b", labelFor(h.getString("k")))) }
                tiles += Tile(o.getDouble("la"), o.getDouble("lo"), o.getDouble("r"), o.getLong("t"), ways, hz, o.optInt("lt") == 1)
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
                    wa.put(JSONObject().put("i", w.id).put("n", w.name).put("f", w.ref).put("h", w.hw).put("l", w.limit).put("o", if (w.oneway) 1 else 0).put("x", w.fl).put("lh", w.hgv).put("lb", w.bus).put("p", p))
                }
                val ha = JSONArray()
                t.hz.forEach { ha.put(JSONObject().put("k", it.kind).put("a", it.lat).put("o", it.lon).put("b", it.label)) }
                ta.put(JSONObject().put("la", t.la).put("lo", t.lo).put("r", t.r).put("t", t.t).put("lt", if (t.lite) 1 else 0).put("ways", wa).put("hz", ha))
            }
            f.writeText(JSONObject().put("v", 1).put("tiles", ta).toString())
        } catch (_: Exception) {
        }
    }

    /** The saved offline map file (for backup), or null. */
    fun mapFile(): File? = file()?.takeIf { it.exists() && it.length() > 20 }

    /** After a restore: drop the in-memory copy so the restored file is read. */
    /** Installs a restored map file at the place the app reads it from. */
    fun installMapFile(src: File): Boolean = try {
        val dst = file()
        if (dst != null && src.length() > 20) { src.copyTo(dst, true); true } else false
    } catch (_: Exception) { false }

    fun reloadMaps() { synchronized(tiles) { tiles.clear(); estCache.clear(); loaded = false }; info = null; next = null; curve = null; curWay = 0L; lastMatchT = 0L }

    fun savedInfo(): String {
        synchronized(tiles) { loadTiles() }
        val kb = (file()?.length() ?: 0L) / 1024
        val n = synchronized(tiles) { tiles.size }
        val nl = synchronized(tiles) { tiles.count { it.lite } }
        return if (n == 0) "No saved map yet – drive with internet, or download this area" else "$n saved area(s)${if (nl > 0) " ($nl wide highway)" else ""} · ${if (kb > 1024) "%.1f MB".format(kb / 1024.0) else "$kb KB"} · works offline"
    }

    fun clearSaved() { synchronized(tiles) { tiles.clear(); estCache.clear(); file()?.delete() }; info = null; next = null; curve = null }

    /** [lite]: also count wide highway-only areas (they do not replace detailed town maps). */
    private fun covered(la: Double, lo: Double, margin: Double, fresh: Boolean, lite: Boolean = false): Boolean = synchronized(tiles) {
        val now = System.currentTimeMillis()
        tiles.any { (lite || !it.lite) && (!fresh || now - it.t < FRESH_MS) && Loc.dist(la, lo, it.la, it.lo) * 1000 <= it.r - margin }
    }

    private fun coveredWide(la: Double, lo: Double): Boolean = synchronized(tiles) {
        val now = System.currentTimeMillis()
        tiles.any { it.lite && now - it.t < FRESH_MS && Loc.dist(la, lo, it.la, it.lo) * 1000 <= it.r - 1500 }
    }

    private fun addTile(t: Tile, save: Boolean = true) = synchronized(tiles) {
        tiles.removeAll { it.lite == t.lite && Loc.dist(it.la, it.lo, t.la, t.lo) * 1000 < (if (t.lite) 2500 else 300) }
        tiles += t
        estCache.clear()
        while (tiles.count { !it.lite } > MAX_TILES) tiles.remove(tiles.filter { !it.lite }.minByOrNull { it.t })
        while (tiles.count { it.lite } > MAX_WIDE) tiles.remove(tiles.filter { it.lite }.minByOrNull { it.t })
        if (save) saveTiles()
    }

    /** Used by tests and offline import. */
    fun installTile(t: Tile) = addTile(t)

    /** Download a wide area (about 6 km square) around the current position for offline use, in the background. */
    fun downloadArea(radius: Double = TILE_R, cb: (String) -> Unit) {
        if (busy) { cb("A map download is already running"); return }
        val la = if (lastFix != 0L) lat else Loc.here(ObdManager.app, true)?.first
        val lo = if (lastFix != 0L) lon else Loc.here(ObdManager.app, true)?.second
        if (la == null || lo == null) { cb("No position yet – wait for GPS"); return }
        busy = true; status = "Downloading map 0/9…"
        try { ObdService.start(ObdManager.app) } catch (_: Throwable) {}
        ex.execute {
            var ok = 0; var ways = 0; var hz = 0
            try {
                val order = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to -1, 1 to -1, -1 to 1)
                for ((i, o) in order.withIndex()) {
                    status = "Downloading map ${i + 1}/9…"
                    val tla = la + o.first * 2000.0 / 110540.0
                    val tlo = lo + o.second * 2000.0 / (111320.0 * cos(Math.toRadians(la)))
                    val t = fetchTile(tla, tlo, radius)
                    if (t != null) { addTile(t); ok++; ways += t.ways.size; hz += t.hz.size; lastMatchT = 0L }
                    else if (i == 0) break   // no internet / servers busy: stop early
                }
            } catch (_: Exception) {
            } finally { busy = false; status = "Tracking" }
            val msg = if (ok == 0) "Download failed (${lastErr.ifEmpty { "no internet" }}). Try again in a minute." else "Saved $ok of 9 map areas: $ways roads, $hz hazards. Works offline now."
            main.post { cb(msg) }
        }
    }

    /* ---------------- wide area download (highways, up to 200 km) and automatic prefetch ---------------- */

    private val dlEx = Executors.newSingleThreadExecutor()
    @Volatile var dlBusy = false
    @Volatile var dlPct = 0
    @Volatile var dlText = ""
    @Volatile private var dlCancel = false
    private var autoWideT = 0L

    fun cancelDownload() { dlCancel = true }

    fun isOnline(): Boolean = try {
        val cm = ObdManager.app.getSystemService(android.net.ConnectivityManager::class.java)
        val n = cm?.activeNetwork
        n != null && cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Throwable) { false }

    /** Number of 16 km areas needed for a radius. */
    fun areasFor(radiusKm: Int): Int = wideGrid(0.0, 0.0, radiusKm).size

    private fun wideGrid(la: Double, lo: Double, radiusKm: Int): List<Pair<Double, Double>> {
        val step = 16000.0
        val n = (radiusKm * 1000.0 / step).toInt() + 1
        val out = ArrayList<Pair<Double, Double>>()
        for (i in -n..n) for (j in -n..n) {
            val dn = i * step; val de = j * step
            if (sqrt(dn * dn + de * de) > radiusKm * 1000.0 + 4000.0) continue
            out += (la + dn / 110540.0) to (lo + de / (111320.0 * cos(Math.toRadians(la))))
        }
        return out.sortedBy { Loc.dist(la, lo, it.first, it.second) }
    }

    /** Downloads all highways and highway hazards within [radiusKm] of here, in the background, with a percentage. Safe to repeat: finished areas are skipped. */
    fun downloadWide(radiusKm: Int, cb: (String) -> Unit) {
        if (dlBusy) { cb("A map download is already running"); return }
        val la = if (lastFix != 0L) lat else Loc.here(ObdManager.app, true)?.first
        val lo = if (lastFix != 0L) lon else Loc.here(ObdManager.app, true)?.second
        if (la == null || lo == null) { cb("No position yet – wait for GPS"); return }
        if (!isOnline()) { cb("No internet connection"); return }
        dlBusy = true; dlCancel = false; dlPct = 0; dlText = "Starting…"
        try { ObdService.start(ObdManager.app) } catch (_: Throwable) {}
        dlEx.execute {
            var ok = 0; var fail = 0; var skipped = 0; var streak = 0
            val grid = wideGrid(la, lo, radiusKm)
            try {
                // town detail around you first, so the screen works at once
                try { fetchTile(la, lo, TILE_R)?.let { addTile(it); lastMatchT = 0L } } catch (_: Exception) {}
                for ((i, p) in grid.withIndex()) {
                    if (dlCancel) break
                    dlPct = (i * 100 / grid.size)
                    dlText = "Downloading $dlPct% · area ${i + 1} of ${grid.size}"
                    if (coveredWide(p.first, p.second)) { skipped++; continue }
                    val t = try { fetchTile(p.first, p.second, WIDE_R, true) } catch (_: Exception) { null }
                    if (t != null) { addTile(t, save = ok % 6 == 5); ok++; streak = 0; lastMatchT = 0L }
                    else { fail++; streak++; if (streak >= 6) break }
                    try { Thread.sleep(900) } catch (_: Exception) {}
                }
            } finally {
                synchronized(tiles) { saveTiles() }
                dlBusy = false; dlPct = if (fail == 0 && !dlCancel) 100 else dlPct; dlText = ""
            }
            val msg = when {
                dlCancel -> "Stopped. $ok new areas saved; tap Download again to continue."
                streak >= 6 -> "Stopped – the map servers are not answering (${lastErr.ifEmpty { "busy" }}). $ok saved; try again later, it continues where it stopped."
                fail > 0 -> "Done with $fail areas missing (servers busy). Tap Download again to finish them."
                else -> "Map ready: ${ok + skipped} areas, radius $radiusKm km. Works offline."
            }
            main.post { cb(msg) }
        }
    }

    /** Keeps highway maps ahead of you so the road is known before you reach it. */
    private fun autoWide() {
        if (!Prefs.mapAuto || dlBusy) return
        val now = System.currentTimeMillis()
        if (now < autoWideT) return
        var cLa = lat; var cLo = lon
        if (coveredWide(lat, lon)) {
            val h = heading ?: return
            if (speedKmh < 25) return
            val d = 14000.0
            cLa = lat + d * cos(Math.toRadians(h.toDouble())) / 110540.0
            cLo = lon + d * sin(Math.toRadians(h.toDouble())) / (111320.0 * cos(Math.toRadians(lat)))
            if (coveredWide(cLa, cLo)) return
        }
        if (!isOnline()) return
        autoWideT = now + 45_000
        dlBusy = true; dlText = "Updating highway map ahead…"; dlPct = 0
        dlEx.execute {
            try {
                val t = fetchTile(cLa, cLo, WIDE_R, true)
                if (t != null) { addTile(t); lastMatchT = 0L; autoWideT = 0L } else autoWideT = System.currentTimeMillis() + 120_000
            } catch (_: Exception) { autoWideT = System.currentTimeMillis() + 120_000 }
            finally { dlBusy = false; dlText = "" }
        }
    }

    private fun fetchTile(la: Double, lo: Double, r: Double, lite: Boolean = false): Tile? {
        val ri = r.toInt()
        val q = if (lite) "[out:json][timeout:90];(way(around:$ri,$la,$lo)[\"highway\"~\"^($MAJOR_ROADS)\$\"];" +
                "node(around:$ri,$la,$lo)[\"highway\"=\"speed_camera\"];node(around:$ri,$la,$lo)[\"traffic_calming\"~\"bump|hump|table|cushion\"];" +
                "node(around:$ri,$la,$lo)[\"railway\"=\"level_crossing\"];node(around:$ri,$la,$lo)[\"barrier\"=\"toll_booth\"];);out tags geom;" else "[out:json][timeout:50];(way(around:$ri,$la,$lo)[\"highway\"~\"^($OK_ROADS)\$\"];" +
                "node(around:$ri,$la,$lo)[\"highway\"=\"speed_camera\"];node(around:$ri,$la,$lo)[\"traffic_calming\"];" +
                "node(around:$ri,$la,$lo)[\"amenity\"~\"^(school|hospital)\$\"];node(around:$ri,$la,$lo)[\"railway\"=\"level_crossing\"];" +
                "node(around:$ri,$la,$lo)[\"barrier\"=\"toll_booth\"];);out tags geom;"
        var body: String? = null
        val errs = ArrayList<String>()
        val hosts = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter")
        for (round in 0..1) {
            for (host in hosts) {
                try {
                    val c = URL(host).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"; c.doOutput = true
                    c.connectTimeout = 10000; c.readTimeout = if (lite) 100000 else 50000
                    c.setRequestProperty("User-Agent", "CarOBDPro/${AppInfo.VERSION} (Android; personal OBD app)")
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                    val code = c.responseCode
                    if (code == 200) body = c.inputStream.bufferedReader().use { it.readText() }
                    else errs += "${URL(host).host} HTTP $code"
                    c.disconnect()
                    if (body != null && body.trimStart().startsWith("{")) break else body = null
                } catch (e: Exception) { errs += "${URL(host).host}: ${e.javaClass.simpleName}" }
            }
            if (body != null) break
            try { Thread.sleep(2500) } catch (_: Exception) {}
        }
        if (body == null) { lastErr = errs.takeLast(2).joinToString("; "); return null }
        lastErr = ""
        val els = JSONObject(body ?: return null).optJSONArray("elements") ?: return null
        val ways = ArrayList<Way>(); val hz = ArrayList<Hazard>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i); val tags = e.optJSONObject("tags") ?: JSONObject()
            if (e.getString("type") == "way") {
                val g = e.optJSONArray("geometry") ?: continue
                if (g.length() < 2) continue
                var pts = DoubleArray(g.length() * 2)
                for (k in 0 until g.length()) { val p = g.getJSONObject(k); pts[k * 2] = p.getDouble("lat"); pts[k * 2 + 1] = p.getDouble("lon") }
                if (lite && g.length() > 3) pts = thin(pts)
                val ow = tags.optString("oneway").let { it == "yes" || it == "1" || it == "true" } || tags.optString("junction") == "roundabout"
                val lim = parseLimit(tags.optString("maxspeed").ifBlank { tags.optString("maxspeed:forward") }) ?: -1
                val hgv = parseLimit(tags.optString("maxspeed:hgv").ifBlank { tags.optString("maxspeed:hgv:forward") }) ?: -1
                val bus = parseLimit(tags.optString("maxspeed:bus").ifBlank { tags.optString("maxspeed:bus:forward") }) ?: -1
                var fl = 0
                if (tags.optString("lit") == "yes") fl = fl or 1
                if (listOf("sidewalk", "sidewalk:both", "sidewalk:left", "sidewalk:right").any { k -> tags.optString(k).let { it.isNotEmpty() && it != "no" && it != "none" } }) fl = fl or 2
                if (tags.optString("surface") in setOf("unpaved", "gravel", "dirt", "ground", "sand", "earth", "mud", "grass", "compacted", "fine_gravel")) fl = fl or 4
                if (tags.optString("junction") == "roundabout") fl = fl or 8
                fl = fl or ((tags.optString("lanes").toIntOrNull() ?: 0).coerceIn(0, 15) shl 8)
                ways += Way(e.getLong("id"), tags.optString("name"), tags.optString("ref"), tags.optString("highway"), lim, ow, pts, fl, hgv, bus)
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
        return Tile(la, lo, r, System.currentTimeMillis(), ways, hz, lite)
    }

    /** Drop points closer than 12 m to the previous kept one (keeps bends, shrinks the wide highway maps). */
    private fun thin(p: DoubleArray): DoubleArray {
        val o = ArrayList<Double>(); o += p[0]; o += p[1]
        var la = p[0]; var lo = p[1]
        var i = 2
        while (i < p.size - 2) {
            if (Loc.dist(la, lo, p[i], p[i + 1]) * 1000 >= 12) { o += p[i]; o += p[i + 1]; la = p[i]; lo = p[i + 1] }
            i += 2
        }
        o += p[p.size - 2]; o += p[p.size - 1]
        return o.toDoubleArray()
    }

    /* ---------------- live tracking ---------------- */

    fun onLocation(l: Location) {
        if (!Prefs.roadAssist) return
        // a poor fix (cell/Wi-Fi guess, tunnel, urban canyon) must not move us onto another road
        if (l.hasAccuracy() && l.accuracy > 60f && info != null && lastFix != 0L && System.currentTimeMillis() - lastFix < 8000) return
        prevFix = lastFix
        lat = l.latitude; lon = l.longitude; lastFix = System.currentTimeMillis()
        speedKmh = if (l.hasSpeed()) l.speed * 3.6 else 0.0
        obdSpeed()?.let { speedKmh = it }
        if (l.hasBearing() && speedKmh > 4) heading = l.bearing
        synchronized(tiles) { loadTiles() }
        maybeFetch()
        autoWide()
        matchRoad(l)
        refreshHazards()
        updateStats()
        evaluate()
        try { DriveLog.fix(l) } catch (_: Throwable) {}
    }

    private fun updateStats() {
        val dt = if (prevFix > 0) ((lastFix - prevFix) / 1000.0).coerceIn(0.0, 5.0) else 0.0
        if (speedKmh <= 3) return
        statSec += dt; topSpd = max(topSpd, speedKmh)
        val i = info ?: return
        val lim = i.limit ?: return
        if (i.estimated && !Prefs.roadEstWarn) return
        limSec += dt
        if (speedKmh > lim + Prefs.speedMargin) { overSec += dt; maxOver = max(maxOver, speedKmh - lim) }
    }

    private fun maybeFetch() {
        val now = System.currentTimeMillis()
        if (busy || now < failUntil || now - lastFetchT < 20_000) return
        var cLa = lat; var cLo = lon
        if (covered(lat, lon, 250.0, true)) {
            // already covered here: prefetch the area ahead while driving
            val h = heading ?: return
            if (speedKmh < 20) return
            val d = 1100.0
            val aLa = lat + d * cos(Math.toRadians(h.toDouble())) / 110540.0
            val aLo = lon + d * sin(Math.toRadians(h.toDouble())) / (111320.0 * cos(Math.toRadians(lat)))
            if (covered(aLa, aLo, 200.0, true)) return
            cLa = aLa; cLo = aLo
        }
        busy = true; lastFetchT = now
        if (info == null) status = "Loading map…"
        ex.execute {
            try {
                val t = fetchTile(cLa, cLo, TILE_R)
                if (t != null) { addTile(t); status = "Tracking"; lastMatchT = 0L }
                else { failUntil = System.currentTimeMillis() + 25_000; status = (if (covered(lat, lon, 0.0, false, true)) "Offline – using saved map" else "Offline – no saved map here") + (if (lastErr.isNotEmpty()) " ($lastErr)" else "") }
            } catch (_: Exception) {
                failUntil = System.currentTimeMillis() + 45_000
            } finally { busy = false }
        }
    }

    private class Cand(val w: Way, val d: Double, val near: Int)
    private class Lim(val limit: Int?, val conf: Int, val basis: String, val src: String)

    /** Best matching road at a position, preferring the direction of travel and the road we were on. */
    private fun scan(snap: List<Tile>, la: Double, lo: Double, hd: Float?, tol: Double, prefer: Long): Cand? {
        val dLat = tol / 110540.0; val dLon = tol / (111320.0 * cos(Math.toRadians(la)))
        var best: Way? = null; var bestCost = 1e9; var bestDist = 0.0
        var near = 0
        for (t in snap) {
            if (Loc.dist(la, lo, t.la, t.lo) * 1000 > t.r + 500) continue
            for (w in t.ways) {
                if (la > w.minLa - 0.0025 && la < w.maxLa + 0.0025 && lo > w.minLo - 0.0025 && lo < w.maxLo + 0.0025) near++
                if (la < w.minLa - dLat || la > w.maxLa + dLat || lo < w.minLo - dLon || lo > w.maxLo + dLon) continue
                var dMin = 1e9; var bear = 0.0
                var k = 0
                while (k < w.pts.size - 2) {
                    val d = segDist(la, lo, w.pts[k], w.pts[k + 1], w.pts[k + 2], w.pts[k + 3])
                    if (d < dMin) { dMin = d; bear = bearingTo(w.pts[k], w.pts[k + 1], w.pts[k + 2], w.pts[k + 3]) }
                    k += 2
                }
                if (dMin > tol) continue
                var cost = dMin
                if (hd != null) {
                    val diff = abs(((bear - hd + 540) % 360) - 180)
                    cost += (if (w.oneway) diff else min(diff, 180 - diff)) * 0.4
                }
                if (speedKmh > 45) cost += when (w.hw) { "residential" -> 18.0; "unclassified" -> 12.0; "tertiary" -> 6.0; "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link" -> 6.0; else -> 0.0 }
                if (w.id == prefer) cost -= 22
                if (cost < bestCost) { bestCost = cost; best = w; bestDist = dMin }
            }
        }
        return best?.let { Cand(it, bestDist, near) }
    }

    private fun matchRoad(l: Location) {
        val snap = synchronized(tiles) { tiles.toList() }
        val now = System.currentTimeMillis()
        val tol = max(35.0, (if (l.hasAccuracy()) l.accuracy else 10f) * 1.5 + 15)
        val hd = if (speedKmh > 6) heading else null
        val cand = scan(snap, lat, lon, hd, tol, curWay)
        if (cand == null) {
            val ci = info
            if (ci != null && speedKmh > 8 && now - lastMatchT < 45_000) {
                // off the mapped road (diversion, new road, GPS gap): keep the last limit for a while, flagged as a carry-over
                if (!ci.basis.startsWith("Last road")) info = Info(ci.road, ci.ref, ci.highway, ci.limit, true, ci.fetched, "est", ci.wayId, 30, "Last road limit – current road not on the map", ci.lanes, ci.oneway, ci.lit, ci.urban)
                next = null; curve = null
                return
            }
            if (snap.isNotEmpty() && now - lastMatchT > 10_000) { info = null; curWay = 0L; next = null; curve = null; status = if (snap.any { Loc.dist(lat, lon, it.la, it.lo) * 1000 <= it.r }) "No road data here" else status }
            return
        }
        // do not hop to another road (service road, ramp) for a single fix: need two in a row unless we have clearly left the current one
        if (curWay != 0L && cand.w.id != curWay && info?.basis?.startsWith("Last road") != true) {
            val cw = snap.firstNotNullOfOrNull { t -> t.ways.firstOrNull { it.id == curWay } }
            if (cw != null && wayDist(cw, lat, lon) <= tol * 0.8 && !(cw.oneway && hd != null && wayWrongWay(cw, hd))) {
                if (pendWay == cand.w.id) pendN++ else { pendWay = cand.w.id; pendN = 1 }
                if (pendN < 3) return
            }
        }
        pendWay = 0L; pendN = 0
        val best = cand.w
        val changed = best.id != curWay
        curWay = best.id; lastMatchT = now
        val lim = limitOf(best, cand.near, snap)
        info = Info(best.name, best.ref, best.hw, lim.limit, lim.src == "est", now, lim.src, best.id, lim.conf, lim.basis, best.lanes, best.oneway, best.lit, cand.near >= 14)
        if (!busy) status = if (covered(lat, lon, 0.0, true)) "Tracking" else "Tracking (saved map)"
        if (changed || now - aheadT > 1200) { aheadT = now; lookAhead(snap, best, hd) }
    }

    /** Next speed-limit change and sharp curves on the road ahead. */
    private fun lookAhead(snap: List<Tile>, w: Way, hd: Float?) {
        if (hd == null || speedKmh < 15) { next = null; curve = null; return }
        val dist = (speedKmh / 3.6 * 8.0).coerceIn(90.0, 280.0)
        val aLa = lat + dist * cos(Math.toRadians(hd.toDouble())) / 110540.0
        val aLo = lon + dist * sin(Math.toRadians(hd.toDouble())) / (111320.0 * cos(Math.toRadians(lat)))
        val c = scan(snap, aLa, aLo, hd, 22.0, 0L)
        var nx: Next? = null
        if (c != null && c.w.id != w.id) {
            val l = limitOf(c.w, c.near, snap)
            if (l.limit != null && l.limit != info?.limit) nx = Next(l.limit, dist.toInt(), l.src == "est", c.w.name.ifBlank { c.w.ref })
        }
        next = nx
        curve = detectCurve(w, hd)
    }

    private var pendWay = 0L
    private var pendN = 0

    /** True when we are travelling against a one-way road. */
    private fun wayWrongWay(w: Way, hd: Float): Boolean {
        var bd = 1e9; var bb = 0.0; var k = 0
        while (k < w.pts.size - 2) {
            val d = segDist(lat, lon, w.pts[k], w.pts[k + 1], w.pts[k + 2], w.pts[k + 3])
            if (d < bd) { bd = d; bb = bearingTo(w.pts[k], w.pts[k + 1], w.pts[k + 2], w.pts[k + 3]) }
            k += 2
        }
        return angDiff(bb, hd.toDouble()) > 110
    }

    private fun wayDist(w: Way, la: Double, lo: Double): Double {
        var d = 1e9; var k = 0
        while (k < w.pts.size - 2) { d = min(d, segDist(la, lo, w.pts[k], w.pts[k + 1], w.pts[k + 2], w.pts[k + 3])); k += 2 }
        return d
    }

    private fun angDiff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

    private fun detectCurve(w: Way, hd: Float): Pair<Hazard, Int>? {
        if (w.roundabout) return null
        val n = w.pts.size / 2
        if (n < 3) return null
        var bk = -1; var bd = 1e9; var bb = 0.0
        for (k in 0 until n - 1) {
            val d = segDist(lat, lon, w.pts[2 * k], w.pts[2 * k + 1], w.pts[2 * k + 2], w.pts[2 * k + 3])
            if (d < bd) { bd = d; bk = k; bb = bearingTo(w.pts[2 * k], w.pts[2 * k + 1], w.pts[2 * k + 2], w.pts[2 * k + 3]) }
        }
        if (bk < 0 || bd > 40) return null
        val fwd = angDiff(bb, hd.toDouble()) < 90
        if (!fwd && w.oneway) return null
        val idx = if (fwd) ((bk + 1) until n).toList() else (bk downTo 0).toList()
        var pLa = lat; var pLo = lon; var cum = 0.0
        val cums = ArrayList<Double>(); val brs = ArrayList<Double>(); val vLa = ArrayList<Double>(); val vLo = ArrayList<Double>()
        for (i in idx) {
            val la2 = w.pts[2 * i]; val lo2 = w.pts[2 * i + 1]
            val seg = Loc.dist(pLa, pLo, la2, lo2) * 1000
            if (seg < 4.0) continue
            cums += cum; brs += bearingTo(pLa, pLo, la2, lo2); vLa += pLa; vLo += pLo
            cum += seg; pLa = la2; pLo = lo2
            if (cum > 240) break
        }
        // turning at each vertex; sum the signed turns inside a 60 m window so both a hairpin and a gradual bend count
        val vPos = ArrayList<Double>(); val vTurn = ArrayList<Double>()
        for (i in 0 until brs.size - 1) { vPos += cums[i + 1]; vTurn += ((brs[i + 1] - brs[i] + 540) % 360) - 180 }
        var maxChg = 0.0; var bi = -1
        for (i in vPos.indices) {
            var sum = 0.0
            for (j in i until vPos.size) {
                if (vPos[j] - vPos[i] > 60) break
                sum += vTurn[j]
                if (abs(sum) > maxChg) { maxChg = abs(sum); bi = i }
            }
        }
        if (maxChg < 55 || bi < 0) return null
        if (vPos[bi] < 25) return null
        val d = vPos[bi] - 10
        val adv = if (maxChg < 70) 40 else if (maxChg < 100) 30 else 20
        return Hazard("curve", vLa[bi + 1], vLo[bi + 1], "Sharp curve", false, adv) to d.toInt()
    }

    private fun limitOf(w: Way, near: Int, snap: List<Tile>): Lim {
        val fix = fixes().optInt(w.id.toString(), -1).takeIf { it > 0 }
        if (fix != null) return Lim(fix, 100, "Set by you", "you")
        cams()[w.id]?.let { return Lim(it, 90, "Read from a road sign by the camera", "cam") }
        val cls = vclass()
        val m = markedFor(w, cls)
        if (m > 0) {
            val why = when {
                cls == "Truck" && w.hgv > 0 -> "Marked on the map for trucks (OpenStreetMap)"
                cls == "Truck" && w.limit > m -> "Marked ${w.limit} km/h for cars; capped for trucks"
                cls == "Bus" && w.bus > 0 -> "Marked on the map for buses (OpenStreetMap)"
                cls == "Bus" && w.hgv > 0 -> "Marked on the map for heavy vehicles (OpenStreetMap)"
                else -> "Marked on the map (OpenStreetMap)"
            }
            return Lim(m, if (cls != "Car" && w.limit > 0 && m == w.limit && w.hgv <= 0 && w.bus <= 0) 90 else 95, why, "osm")
        }
        val key = w.id * 4 + (if (cls == "Truck") 1 else if (cls == "Bus") 2 else 0)
        return synchronized(estCache) { estCache.getOrPut(key) { estimateFor(w, near, snap, cls) } }
    }

    private fun region(la: Double, lo: Double) = if (la in 6.0..36.0 && lo in 68.0..98.0) "IN" else "XX"

    /** Estimated limit: same road nearby, else a regional default for the road type in built-up or open areas. */
    private fun estimateFor(w: Way, near: Int, snap: List<Tile>, vc: String = "Car"): Lim {
        val cls = w.hw.removeSuffix("_link")
        // 1. a nearby section of the same road, or a connected road of the same class, that has a marked limit
        var bestLim = -1; var bestD = 1200.0; var how = ""
        for (t in snap) for (o in t.ways) {
            if (o.id == w.id || markedFor(o, vc) <= 0 || o.hw.removeSuffix("_link") != cls) continue
            val same = (w.ref.isNotEmpty() && o.ref == w.ref) || (w.name.isNotEmpty() && o.name == w.name)
            val joined = o.pts.size >= 4 && w.pts.size >= 4 && (
                Loc.dist(o.pts[0], o.pts[1], w.pts[0], w.pts[1]) < 0.004 || Loc.dist(o.pts[0], o.pts[1], w.pts[w.pts.size - 2], w.pts[w.pts.size - 1]) < 0.004 ||
                Loc.dist(o.pts[o.pts.size - 2], o.pts[o.pts.size - 1], w.pts[0], w.pts[1]) < 0.004 || Loc.dist(o.pts[o.pts.size - 2], o.pts[o.pts.size - 1], w.pts[w.pts.size - 2], w.pts[w.pts.size - 1]) < 0.004)
            if (!same && !joined) continue
            var dm = 1e9; var k = 0
            while (k < o.pts.size) { dm = min(dm, Loc.dist(lat, lon, o.pts[k], o.pts[k + 1]) * 1000); k += 2 }
            if (dm < bestD) { bestD = dm; bestLim = markedFor(o, vc); how = if (same) "road" else "connected road" }
        }
        if (bestLim > 0) return Lim(bestLim, 70, "Same $how is marked $bestLim km/h about ${bestD.toInt()} m away", "est")

        // 2. regional default
        val urban = near >= 14 || w.hw == "residential" || (w.lit && w.sidewalk)
        val inIn = region(lat, lon) == "IN"
        var v: Int? = if (inIn) when (cls) {
            "motorway" -> 100; "trunk" -> if (urban) 60 else 80; "primary" -> if (urban) 50 else 70; "secondary" -> if (urban) 40 else 60
            "tertiary" -> if (urban) 40 else 50; "unclassified" -> if (urban) 30 else 40; "residential" -> 30; "living_street" -> 20; "service" -> 20; else -> null
        } else when (cls) {
            "motorway" -> 100; "trunk" -> if (urban) 60 else 80; "primary" -> if (urban) 50 else 60; "secondary" -> if (urban) 40 else 50
            "tertiary" -> if (urban) 30 else 40; "unclassified" -> if (urban) 30 else 40; "residential" -> 30; "living_street" -> 20; "service" -> 20; else -> null
        }
        if (v == null) return Lim(null, 0, "No limit data for this road type", "est")
        val notes = ArrayList<String>()
        if (w.hw.endsWith("_link")) { v = min(v, if (cls == "motorway") 60 else 40); notes += "slip road" }
        if (w.oneway && w.lanes >= 2 && (cls == "trunk" || cls == "primary") && !urban) { v = min(v + 10, 100); notes += "divided carriageway" }
        if (w.unpaved) { v = min(v, 30); notes += "unpaved" }
        if (w.roundabout) { v = 25; notes += "roundabout" }
        if (vc == "Truck" && v > TRUCK_CAP) { v = TRUCK_CAP; notes += "truck" }
        if (vc == "Truck" && inIn && !urban && (cls == "trunk" || cls == "primary") && v > 65) { v = 65; notes += "truck" }
        if (vc == "Bus" && inIn && !urban && cls != "motorway" && v > 70) { v = 70; notes += "bus" }
        val area = if (urban) "built-up area" else "open road"
        return Lim(v, if (urban || inIn) 45 else 40, "${if (inIn) "India" else "General"} default for ${w.hw.replace('_', ' ')} roads, $area${if (vc != "Car") ", for a ${vc.lowercase()}" else ""}" + if (notes.isNotEmpty()) " (${notes.joinToString(", ")})" else "", "est")
    }

    private fun refreshHazards() {
        val now = System.currentTimeMillis()
        if (now - hazT < 3000) return
        hazT = now
        val out = ArrayList<Hazard>(); val seen = HashSet<String>()
        val snap = synchronized(tiles) { tiles.toList() }
        for (z in snap.flatMap { it.hz } + userHazards() + SignMap.hazards(lat, lon)) {
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

    /** Hazards ahead of the car (within ±65° of the heading); the reach grows with speed (about 8 s). */
    fun ahead(): List<Pair<Hazard, Int>> {
        val h = heading ?: return emptyList()
        val reach = (speedKmh / 3.6 * 8.0).coerceIn(0.0, 420.0)
        val out = hazards.mapNotNull { z ->
            val d = Loc.dist(lat, lon, z.lat, z.lon) * 1000
            val br = bearingTo(lat, lon, z.lat, z.lon)
            val diff = abs(((br - h + 540) % 360) - 180)
            val base = if (z.kind == "camera") 350.0 else if (z.kind == "toll" || z.kind == "rail") 300.0 else 260.0
            if (diff <= 65 && d <= max(base, reach)) z to d.toInt() else null
        }.toMutableList()
        curve?.let { out += it }
        return out.sortedBy { it.second }
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
                "curve" -> "Sharp curve ahead" + (if (z.adv > 0) ", ease to ${z.adv}" else ", slow down"); "rail" -> "Level crossing ahead, be ready to stop"; "toll" -> "Toll plaza ahead"
                "school" -> "School zone ahead, drive carefully"; "hospital" -> "Hospital zone ahead, keep quiet and slow"
                "sign" -> z.label
                else -> "${z.label} ahead"
            }
            if (z.kind == "sign") Alerts.announce("signtxt-${z.label}", txt, 60_000) else Alerts.announce(key, txt, 300_000)
            if (announced.add(key)) { alertsN++; addLog("$txt · ${d} m") }
        }
        // limit change ahead
        next?.let { n ->
            if (!n.estimated && spd > 8) {
                val key = "next-${n.limit}-$curWay"
                Alerts.announce(key, "Speed limit ahead ${n.limit}", 120_000)
                if (announced.add(key)) addLog("Limit ahead: ${n.limit} in ${n.dist} m")
            }
        }
        // notification text with the live limit
        if (lim != lastNotifLimit && ObdService.running) {
            lastNotifLimit = lim
            ObdService.update(app, AppInfo.NAME, if (lim != null) "Road assistant · limit $lim km/h" + (if (i.road.isNotEmpty()) " · ${i.road}" else "") else "Road assistant active")
        }
    }
}
