package com.dilip.carobd

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** Position and distance helpers. */
object Loc {
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

    fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = FloatArray(1); android.location.Location.distanceBetween(la1, lo1, la2, lo2, r); return r[0] / 1000.0
    }
}
