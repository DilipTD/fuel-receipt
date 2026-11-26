package com.dilip.carobd

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.*

/** Slippy-map tile source (OpenStreetMap) with memory + disk cache. */
object Tiles {
    private val mem = object : LruCache<String, Bitmap>(140) {}
    private val ex = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val pending = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun peek(z: Int, x: Int, y: Int): Bitmap? = mem.get("$z/$x/$y")

    fun request(ctx: Context, z: Int, x: Int, y: Int, done: () -> Unit) {
        val key = "$z/$x/$y"
        if (mem.get(key) != null) return
        failed[key]?.let { if (System.currentTimeMillis() - it < 30_000) return }
        if (!pending.add(key)) return
        val dir = File(ctx.cacheDir, "tiles").apply { mkdirs() }
        val f = File(dir, "${z}_${x}_$y.png")
        ex.execute {
            var bmp: Bitmap? = null
            try {
                val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                if (f.exists() && f.length() > 100) bmp = BitmapFactory.decodeFile(f.path, opt)
                if (bmp == null) {
                    val c = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
                    c.connectTimeout = 6000; c.readTimeout = 8000
                    c.setRequestProperty("User-Agent", "CarOBDPro/${AppInfo.VERSION} (Android; dilip.carobd)")
                    if (c.responseCode == 200) {
                        val bytes = c.inputStream.use { it.readBytes() }
                        f.writeBytes(bytes)
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                    }
                    c.disconnect()
                }
            } catch (_: Exception) {
            }
            if (bmp != null) mem.put(key, bmp) else failed[key] = System.currentTimeMillis()
            pending.remove(key)
            if (bmp != null) main.post { done() }
        }
    }
}

/** Static map with a route line, Dep./Arr. call-outs and an optional replay marker. */
class TileMapView(ctx: Context, private val compact: Boolean = false) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var pts: List<Pair<Double, Double>> = emptyList()
    private var spd: List<Double>? = null
    private var marks: List<Triple<Double, Double, Int>> = emptyList()
    private var depLabel: String? = null
    private var arrLabel: String? = null
    private var me: Pair<Double, Double>? = null
    private var centerZoom = 15
    private var progress = -1f
    private var zoom = 15
    private var cx = 0.0   // centre in world pixels at current zoom
    private var cy = 0.0
    var lazyLoader: ((TileMapView) -> Unit)? = null
    private var loaded = false

    private val bg = Paint().apply { color = Color.parseColor("#1B2430") }
    private val grid = Paint().apply { color = Color.parseColor("#26323F"); strokeWidth = d }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE
    }
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD }
    private val attr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#334155"); textSize = 9f * d }

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 18f * d) }
        }
        clipToOutline = true
    }

    fun setRoute(points: List<Pair<Double, Double>>, speeds: List<Double>?, markers: List<Triple<Double, Double, Int>> = emptyList(),
                 dep: String? = null, arr: String? = null) {
        pts = points; spd = speeds; marks = markers; depLabel = dep; arrLabel = arr
        loaded = true; fit(); invalidate()
    }

    fun setPlace(lat: Double, lon: Double, zoom: Int = 15) {
        me = lat to lon; centerZoom = zoom; pts = emptyList(); loaded = true; fit(); invalidate()
    }

    /** 0..1 position of the replay car along the route, or -1 to hide. */
    fun setProgress(f: Float) { progress = f; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun wx(lon: Double, z: Int) = (lon + 180.0) / 360.0 * 256.0 * (1 shl z)
    private fun wy(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 256.0 * (1 shl z)
    }

    private fun fit() {
        if (width == 0 || height == 0) return
        if (pts.size >= 2) {
            val minLat = pts.minOf { it.first }; val maxLat = pts.maxOf { it.first }
            val minLon = pts.minOf { it.second }; val maxLon = pts.maxOf { it.second }
            val padX = (if (compact) 36 else 60) * d; val padY = (if (compact) 30 else 80) * d
            var z = 17
            while (z > 3) {
                val w = abs(wx(maxLon, z) - wx(minLon, z)); val h = abs(wy(minLat, z) - wy(maxLat, z))
                if (w <= width - padX && h <= height - padY) break
                z--
            }
            zoom = z
            cx = (wx(minLon, z) + wx(maxLon, z)) / 2; cy = (wy(minLat, z) + wy(maxLat, z)) / 2
        } else if (me != null) {
            zoom = centerZoom; cx = wx(me!!.second, zoom); cy = wy(me!!.first, zoom)
        }
    }

    private fun px(lat: Double, lon: Double) = (wx(lon, zoom) - cx + width / 2.0).toFloat() to (wy(lat, zoom) - cy + height / 2.0).toFloat()

    private fun speedColor(v: Double) = Ui.speedColor3(v)

    override fun onDraw(c: Canvas) {
        if (!loaded) { lazyLoader?.let { l -> lazyLoader = null; l(this) } }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        if (width == 0) return
        // tiles
        val n = 1 shl zoom
        val left = cx - width / 2.0; val top = cy - height / 2.0
        val tx0 = floor(left / 256).toInt(); val tx1 = floor((left + width) / 256).toInt()
        val ty0 = floor(top / 256).toInt(); val ty1 = floor((top + height) / 256).toInt()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            if (ty < 0 || ty >= n) continue
            val x = ((tx % n) + n) % n
            val sx = (tx * 256 - left).toFloat(); val sy = (ty * 256 - top).toFloat()
            val b = Tiles.peek(zoom, x, ty)
            if (b != null) c.drawBitmap(b, null, RectF(sx, sy, sx + 256, sy + 256), tilePaint)
            else {
                c.drawLine(sx, sy, sx + 256, sy, grid); c.drawLine(sx, sy, sx, sy + 256, grid)
                Tiles.request(context, zoom, x, ty) { invalidate() }
            }
        }
        // route
        if (pts.size >= 2) {
            val step = max(1, pts.size / 900)
            val xy = ArrayList<Pair<Float, Float>>(); val idx = ArrayList<Int>()
            var i = 0
            while (i < pts.size) { xy += px(pts[i].first, pts[i].second); idx += i; i += step }
            if (idx.last() != pts.size - 1) { xy += px(pts.last().first, pts.last().second); idx += pts.size - 1 }
            casing.strokeWidth = (if (compact) 6 else 9) * d
            seg.strokeWidth = (if (compact) 3.5f else 5.5f) * d
            val path = Path(); path.moveTo(xy[0].first, xy[0].second)
            for (k in 1 until xy.size) path.lineTo(xy[k].first, xy[k].second)
            c.drawPath(path, casing)
            val sp = spd
            for (k in 1 until xy.size) {
                val v = if (sp != null && idx[k] < sp.size) sp[idx[k]] else -1.0
                seg.color = if (v >= 0) speedColor(v) else Color.parseColor("#2F7BFF")
                if (progress >= 0 && idx[k] > progress * (pts.size - 1)) seg.alpha = 90 else seg.alpha = 255
                c.drawLine(xy[k - 1].first, xy[k - 1].second, xy[k].first, xy[k].second, seg)
            }
            seg.alpha = 255
            for (m in marks) { val (x, y) = px(m.first, m.second); fill.color = Color.WHITE; c.drawCircle(x, y, 5 * d, fill); fill.color = m.third; c.drawCircle(x, y, 3.5f * d, fill) }
            val (sx, sy) = px(pts.first().first, pts.first().second)
            val (ex, ey) = px(pts.last().first, pts.last().second)
            if (compact) { dot(c, sx, sy, Color.parseColor("#2EBD85")); dot(c, ex, ey, Color.parseColor("#E5383B")) }
            else { callout(c, sx, sy, depLabel ?: "Dep.", Color.parseColor("#2F7BFF"), true); callout(c, ex, ey, arrLabel ?: "Arr.", Color.parseColor("#E5383B"), false) }
            if (progress >= 0) {
                val k = (progress * (pts.size - 1)).roundToInt().coerceIn(0, pts.size - 1)
                val (x, y) = px(pts[k].first, pts[k].second)
                fill.color = Color.argb(70, 47, 123, 255); c.drawCircle(x, y, 16 * d, fill)
                fill.color = Color.WHITE; c.drawCircle(x, y, 8 * d, fill); fill.color = Color.parseColor("#2F7BFF"); c.drawCircle(x, y, 5.5f * d, fill)
            }
        }
        me?.let { (la, lo) ->
            val (x, y) = px(la, lo)
            fill.color = Color.argb(60, 47, 123, 255); c.drawCircle(x, y, 22 * d, fill)
            fill.color = Color.WHITE; c.drawCircle(x, y, 9 * d, fill); fill.color = Color.parseColor("#2F7BFF"); c.drawCircle(x, y, 6.5f * d, fill)
        }
        if (!compact) c.drawText("© OpenStreetMap", width - attr.measureText("© OpenStreetMap") - 6 * d, height - 5 * d, attr)
    }

    private fun dot(c: Canvas, x: Float, y: Float, col: Int) {
        fill.color = Color.WHITE; c.drawCircle(x, y, 6 * d, fill); fill.color = col; c.drawCircle(x, y, 4 * d, fill)
    }

    private fun callout(c: Canvas, x: Float, y: Float, label: String, col: Int, above: Boolean) {
        dot(c, x, y, col)
        val w = txt.measureText(label) + 20 * d; val h = 26 * d
        var l = x - w / 2; l = l.coerceIn(4 * d, width - w - 4 * d)
        val t = if (above) y - 12 * d - h else y + 12 * d
        fill.color = col
        c.drawRoundRect(RectF(l, t, l + w, t + h), 8 * d, 8 * d, fill)
        val tri = Path()
        if (above) { tri.moveTo(x - 5 * d, t + h); tri.lineTo(x + 5 * d, t + h); tri.lineTo(x, t + h + 6 * d) }
        else { tri.moveTo(x - 5 * d, t); tri.lineTo(x + 5 * d, t); tri.lineTo(x, t - 6 * d) }
        tri.close(); c.drawPath(tri, fill)
        c.drawText(label, l + 10 * d, t + h / 2 + 4.3f * d, txt)
    }
}
