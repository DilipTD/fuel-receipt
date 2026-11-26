package com.dilip.carobd

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** Current weather + short forecast from Open-Meteo (free, no key). */
object Weather {
    class Hour(val label: String, val temp: Double, val code: Int, val rain: Int)
    class Wx(
        val temp: Double, val feels: Double, val code: Int, val wind: Double, val hum: Int, val precip: Double,
        val hi: Double, val lo: Double, val rainMax: Int, val hours: List<Hour>, val fetched: Long
    )

    private val ex = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var last: Wx? = null
    private var lastKey = ""

    fun desc(c: Int) = when (c) {
        0 -> "Clear sky"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"
        in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"; in 80..82 -> "Showers"; 85, 86 -> "Snow showers"
        95 -> "Thunderstorm"; 96, 99 -> "Thunder & hail"; else -> "—"
    }

    fun icon(c: Int) = when (c) {
        0, 1 -> Ic.SUN; 2, 3, 45, 48 -> Ic.CLOUD; in 51..67, in 80..86, in 71..77 -> Ic.RAIN; in 95..99 -> Ic.BOLT; else -> Ic.CLOUD
    }

    fun color(c: Int) = when (icon(c)) { Ic.SUN -> Ui.WARN; Ic.RAIN -> Ui.ACC; Ic.BOLT -> Ui.ORANGE; else -> Ui.DIM }

    /** Driving advice from the forecast. */
    fun tip(w: Wx): Pair<Ic, String> = when {
        w.code in 95..99 -> Ic.BOLT to "Thunderstorm – avoid driving if you can, keep lights on."
        w.code in 45..48 -> Ic.WARN to "Fog – use low beam / fog lamps and keep distance."
        w.precip > 0.2 || w.code in 51..67 || w.code in 80..82 -> Ic.RAIN to "Wet roads – brake early, keep 2× following distance."
        w.rainMax >= 60 -> Ic.RAIN to "Rain likely today (${w.rainMax}%) – plan your drive before it starts."
        w.temp >= 38 -> Ic.THERM to "Very hot – check coolant & tyre pressure, avoid leaving kids/pets in the car."
        w.temp <= 8 -> Ic.THERM to "Cold – let the engine warm up gently before hard driving."
        w.wind >= 40 -> Ic.WIND to "Strong wind – hold the wheel firmly on open roads."
        else -> Ic.SUN to "Good driving weather."
    }

    /** Best known position: live GPS → last system fix → parked position. */
    fun here(ctx: Context, allowPark: Boolean = true): Pair<Double, Double>? {
        Gps.last?.let { return it.latitude to it.longitude }
        if (Gps.hasPermission(ctx)) try {
            val lm = ctx.getSystemService(android.location.LocationManager::class.java)
            var best: android.location.Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            }
            if (best != null) return best.latitude to best.longitude
        } catch (_: Exception) {
        }
        if (allowPark && (Prefs.parkLat != 0.0 || Prefs.parkLon != 0.0)) return Prefs.parkLat to Prefs.parkLon
        return null
    }

    fun load(ctx: Context, lat: Double, lon: Double, force: Boolean = false, cb: (Wx?) -> Unit) {
        val key = "%.2f,%.2f".format(Locale.US, lat, lon)
        val l = last
        if (!force && l != null && key == lastKey && System.currentTimeMillis() - l.fetched < 20 * 60_000L) { cb(l); return }
        ex.execute {
            val w = try {
                val u = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation" +
                        "&hourly=temperature_2m,precipitation_probability,weather_code" +
                        "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2"
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 7000; c.readTimeout = 9000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                parse(JSONObject(body))
            } catch (_: Exception) { null }
            if (w != null) { last = w; lastKey = key }
            main.post { cb(w ?: last) }
        }
    }

    private fun parse(o: JSONObject): Wx {
        val cur = o.getJSONObject("current")
        val h = o.getJSONObject("hourly"); val d = o.getJSONObject("daily")
        val times = h.getJSONArray("time")
        val now = cur.getString("time").substring(0, 13) + ":00"
        var i0 = 0
        for (i in 0 until times.length()) if (times.getString(i) >= now) { i0 = i; break }
        val hours = ArrayList<Hour>()
        var i = i0 + 1
        while (i < times.length() && hours.size < 6) {
            hours += Hour(times.getString(i).substring(11, 16), h.getJSONArray("temperature_2m").getDouble(i),
                h.getJSONArray("weather_code").getInt(i), h.getJSONArray("precipitation_probability").optInt(i, 0))
            i += 2
        }
        return Wx(
            cur.getDouble("temperature_2m"), cur.getDouble("apparent_temperature"), cur.getInt("weather_code"),
            cur.getDouble("wind_speed_10m"), cur.getInt("relative_humidity_2m"), cur.optDouble("precipitation", 0.0),
            d.getJSONArray("temperature_2m_max").getDouble(0), d.getJSONArray("temperature_2m_min").getDouble(0),
            d.getJSONArray("precipitation_probability_max").optInt(0, 0), hours, System.currentTimeMillis()
        )
    }
}

/* ============================ Navigation helpers ============================ */

object Nav {
    private val sp get() = ObdManager.app.getSharedPreferences("nav_places", Context.MODE_PRIVATE)

    fun place(key: String): Pair<Double, Double>? {
        val s = sp.getString(key, null) ?: return null
        val p = s.split(","); return if (p.size == 2) p[0].toDouble() to p[1].toDouble() else null
    }
    fun setPlace(key: String, ll: Pair<Double, Double>?) {
        sp.edit().apply { if (ll == null) remove(key) else putString(key, "${ll.first},${ll.second}") }.apply()
    }

    fun drive(ctx: Context, lat: Double, lon: Double) = launch(ctx, "google.navigation:q=$lat,$lon&mode=d", "geo:$lat,$lon?q=$lat,$lon")
    fun driveTo(ctx: Context, query: String) = launch(ctx, "google.navigation:q=" + android.net.Uri.encode(query) + "&mode=d", "geo:0,0?q=" + android.net.Uri.encode(query))

    private fun launch(ctx: Context, nav: String, fallback: String) {
        try {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(nav))
                .setPackage("com.google.android.apps.maps").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(fallback))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                android.widget.Toast.makeText(ctx, "No maps app found", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    class Sug(val ic: Ic, val color: Int, val title: String, val body: String, val lat: Double?, val lon: Double?, val query: String?)

    /** Learns destinations from trip history and adds condition-based ideas. Runs off the UI thread. */
    fun suggestions(ctx: Context, here: Pair<Double, Double>?, wx: Weather.Wx?, cb: (List<Sug>) -> Unit) {
        Thread {
            val out = ArrayList<Sug>()
            try {
                val veh = ObdManager.vehicle
                val trips = Db.trips(veh.id, System.currentTimeMillis() - 120 * 86_400_000L, 70)
                class Cl(var lat: Double, var lon: Double, var n: Int = 0, var near: Int = 0, var last: Long = 0)
                val cl = HashMap<String, Cl>()
                val cal = java.util.Calendar.getInstance()
                val nowH = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                val weekend = cal.get(java.util.Calendar.DAY_OF_WEEK).let { it == 1 || it == 7 }
                for (t in trips) {
                    val e = Geo.endpointsNow(t.id)?.second ?: continue
                    val k = "%.3f,%.3f".format(Locale.US, e.first, e.second)
                    val c = cl.getOrPut(k) { Cl(e.first, e.second) }
                    c.n++; c.last = maxOf(c.last, t.start)
                    val tc = java.util.Calendar.getInstance().apply { timeInMillis = t.start }
                    val th = tc.get(java.util.Calendar.HOUR_OF_DAY) * 60 + tc.get(java.util.Calendar.MINUTE)
                    val tw = tc.get(java.util.Calendar.DAY_OF_WEEK).let { it == 1 || it == 7 }
                    if (kotlin.math.abs(th - nowH) <= 120 && tw == weekend) c.near++
                }
                val ranked = cl.values.filter { c ->
                    here == null || dist(here.first, here.second, c.lat, c.lon) > 0.4
                }.sortedByDescending { it.near * 3 + it.n }.take(4)
                for (c in ranked) {
                    val why = when {
                        c.near >= 2 -> "You usually drive here around this time · ${c.n}×"
                        else -> "Visited ${c.n}× · last ${java.text.SimpleDateFormat("dd MMM", Locale.getDefault()).format(java.util.Date(c.last))}"
                    }
                    out += Sug(if (c.near >= 2) Ic.CLOCK else Ic.PIN, if (c.near >= 2) Ui.GOOD else Ui.ACC, "…", why, c.lat, c.lon, null)
                }
            } catch (_: Exception) {
            }
            val veh = ObdManager.vehicle
            val range = ObdManager.rangeKm()
            val fuelPct = ObdManager.values[0x2F]
            if ((range != null && range < 90) || (fuelPct != null && fuelPct < Prefs.lowFuelPct + 5))
                out += Sug(if (veh.isEv) Ic.PLUG else Ic.FUEL, Ui.ORANGE, if (veh.isEv) "Charging station" else "Petrol pump",
                    "Range is low (${range?.let { "%.0f km".format(it) } ?: "fuel low"}) – refuel soon", null, null,
                    if (veh.isEv) "EV charging station" else "petrol pump")
            val due = try { Maint.dueList(veh).firstOrNull { it.overdue || it.soon } } catch (_: Exception) { null }
            if (due != null) out += Sug(Ic.WRENCH, Ui.WARN, "Service centre", "${due.item.name} – ${due.text}", null, null, "car service center")
            if (wx != null) when {
                wx.code in 51..82 || wx.rainMax >= 60 -> out += Sug(Ic.RAIN, Ui.ACC, "Covered parking", "Rain expected – park under cover", null, null, "covered parking")
                wx.code <= 2 && wx.temp in 18.0..34.0 -> out += Sug(Ic.SUN, Ui.WARN, "Scenic drive", "Clear weather – a nice day for a drive", null, null, "scenic viewpoint")
            }
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (h in 7..10) out += Sug(Ic.CAR, Ui.CYAN, "Breakfast spots", "Good morning – places nearby", null, null, "breakfast restaurant")
            else if (h in 12..14) out += Sug(Ic.CAR, Ui.CYAN, "Lunch nearby", "Restaurants around you", null, null, "restaurant")
            else if (h in 18..21) out += Sug(Ic.CAR, Ui.CYAN, "Dinner nearby", "Restaurants around you", null, null, "restaurant")
            Handler(Looper.getMainLooper()).post { cb(out) }
        }.start()
    }

    fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = FloatArray(1); android.location.Location.distanceBetween(la1, lo1, la2, lo2, r); return r[0] / 1000.0
    }
}
