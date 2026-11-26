package com.dilip.carobd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Receipt photos kept in the app's private storage (included in backups). */
object Receipts {
    fun dir(ctx: Context) = File(ctx.filesDir, "receipts").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    /** Copies an image (camera or gallery) in, scaled down to ≤1600 px. Returns the stored file name. */
    fun save(ctx: Context, uri: Uri): String? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 2400) s *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        if (bmp == null) null else {
            val k = 1600f / maxOf(bmp.width, bmp.height)
            val out = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
            val name = "r_${System.currentTimeMillis()}.jpg"
            file(ctx, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            name
        }
    } catch (_: Exception) { null }

    fun bitmap(ctx: Context, name: String, maxPx: Int = 900): Bitmap? = try {
        val f = file(ctx, name)
        if (!f.exists()) null else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / s > maxPx * 2) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (_: Exception) { null }

    fun delete(ctx: Context, name: String) { if (name.isNotEmpty()) try { file(ctx, name).delete() } catch (_: Exception) {} }

    /** Name of the fuel station closest to a position (OpenStreetMap), or null. */
    fun stationNear(lat: Double, lon: Double, cb: (String?) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            var res: String? = null
            try {
                val q = "[out:json][timeout:8];(node(around:130,$lat,$lon)[amenity=fuel];way(around:130,$lat,$lon)[amenity=fuel];);out tags center 6;"
                val c = URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 9000
                c.setRequestProperty("User-Agent", "CarOBDPro/${AppInfo.VERSION} (Android)")
                val body = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect()
                val els = JSONObject(body).optJSONArray("elements")
                var best = 1e9
                if (els != null) for (i in 0 until els.length()) {
                    val e = els.getJSONObject(i); val t = e.optJSONObject("tags") ?: continue
                    val nm = listOf(t.optString("name"), t.optString("brand"), t.optString("operator")).firstOrNull { it.isNotEmpty() } ?: continue
                    val la = e.optDouble("lat", e.optJSONObject("center")?.optDouble("lat") ?: Double.NaN)
                    val lo = e.optDouble("lon", e.optJSONObject("center")?.optDouble("lon") ?: Double.NaN)
                    val d = if (la.isNaN() || lo.isNaN()) 999.0 else Nav.dist(lat, lon, la, lo)
                    if (d < best) { best = d; res = nm }
                }
            } catch (_: Exception) {}
            main.post { cb(res) }
        }.start()
    }
}
