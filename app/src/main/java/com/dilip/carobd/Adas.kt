package com.dilip.carobd

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Driver-assist vision in plain Kotlin (no ML library): lane lines, the vehicle ahead and its distance.
 * WARNINGS ONLY. It never controls the car. Everything is an estimate from one phone camera.
 */
object Adas {
    class Lane(val xl: Float, val xr: Float, val yBottom: Float, val xlTop: Float, val xrTop: Float, val yTop: Float)
    class Veh(val x: Int, val y: Int, val w: Int, val h: Int, val dist: Double, val night: Boolean)

    @Volatile var lane: Lane? = null
    @Volatile var veh: Veh? = null
    @Volatile var offset = 0.0           // lane position, 0 = centred, ± = towards a line (after calibration)
    @Volatile var departing = 0          // -1 left, 1 right, 0 none
    @Volatile var closing = 0.0          // m/s, positive = getting closer
    @Volatile var ttc = 99.0
    @Volatile var headway = 99.0
    @Volatile var level = 0              // 0 ok, 1 keep distance, 2 collision warning
    @Volatile var fw = 0
    @Volatile var fh = 0
    @Volatile var hfovDeg = 60.0
    @Volatile var frames = 0

    private var base = 0.0
    private var baseReady = false
    private var leftHits = 0; private var rightHits = 0
    private val hist = ArrayList<Pair<Long, Double>>()
    private var vMiss = 0
    private var lastVeh: Veh? = null
    private var depCount = 0
    private var lastDep = 0

    fun reset() {
        lane = null; veh = null; offset = 0.0; departing = 0; closing = 0.0; ttc = 99.0; headway = 99.0; level = 0
        base = 0.0; baseReady = false; hist.clear(); vMiss = 0; lastVeh = null; depCount = 0; lastDep = 0; frames = 0
    }

    private fun luma(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
    private fun isMark(c: Int, base: Int): Boolean {
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        val l = luma(c)
        return (l > base + 42 && l > 120) || (r > 150 && g > 120 && b < 100 && r > b * 1.8)
    }

    /** Robust line x = a + b*y through points; drops far points once. Returns (a, b) or null. */
    private fun fit(pts: List<Pair<Float, Float>>, tol: Float): Pair<Float, Float>? {
        if (pts.size < 5) return null
        var p = pts
        var res: Pair<Float, Float>? = null
        for (pass in 0..1) {
            val n = p.size.toFloat()
            val sy = p.sumOf { it.first.toDouble() }.toFloat(); val sx = p.sumOf { it.second.toDouble() }.toFloat()
            val syy = p.sumOf { (it.first * it.first).toDouble() }.toFloat(); val sxy = p.sumOf { (it.first * it.second).toDouble() }.toFloat()
            val den = n * syy - sy * sy
            if (abs(den) < 1e-3f) return null
            val b = (n * sxy - sy * sx) / den; val a = (sx - b * sy) / n
            res = a to b
            if (pass == 0) { p = p.filter { abs(a + b * it.first - it.second) < tol }; if (p.size < 5) return null }
        }
        return res
    }

    fun detectLane(px: IntArray, w: Int, h: Int, hood: Float): Lane? {
        val y0 = (h * 0.52f).toInt(); val y1 = (h * (hood - 0.04f)).toInt().coerceAtLeast(y0 + 20)
        val cx = w / 2
        val left = ArrayList<Pair<Float, Float>>(); val right = ArrayList<Pair<Float, Float>>()
        var y = y1
        val step = max(3, (y1 - y0) / 14)
        while (y >= y0) {
            // road brightness near the middle of this row
            var sum = 0; var n = 0
            for (x in cx - w / 10..cx + w / 10 step 3) { sum += luma(px[y * w + x.coerceIn(0, w - 1)]); n++ }
            val roadL = sum / max(1, n)
            // the lane gets narrower towards the horizon, so only look as far out as a plausible lane
            val t = (y - y0).toFloat() / max(1, y1 - y0)
            val reach = (w * (0.10f + 0.40f * t)).toInt()
            val inner = (w * 0.03f).toInt()
            fun scan(dir: Int): Int? {
                var x = cx + dir * inner; var run = 0; var start = -1
                val lim = if (dir < 0) max(1, cx - reach) else min(w - 2, cx + reach)
                while (x != lim) {
                    if (isMark(px[y * w + x], roadL)) { if (run == 0) start = x; run++ } else if (run > 0) { if (run in 2..(w / 14)) return (start + x - dir) / 2; run = 0 }
                    x += dir
                }
                return null
            }
            scan(-1)?.let { left += y.toFloat() to it.toFloat() }
            scan(1)?.let { right += y.toFloat() to it.toFloat() }
            y -= step
        }
        val tol = w * 0.035f
        val fl = fit(left, tol)?.takeIf { it.second < -0.02f && it.second > -3f }
        val fr = fit(right, tol)?.takeIf { it.second > 0.02f && it.second < 3f }
        leftHits = if (fl != null) min(leftHits + 1, 5) else max(leftHits - 1, 0)
        rightHits = if (fr != null) min(rightHits + 1, 5) else max(rightHits - 1, 0)
        if (fl == null || fr == null) {
            // one line only: still useful for the departure warning
            return null
        }
        val yb = y1.toFloat(); val yt = y0.toFloat()
        val xl = fl.first + fl.second * yb; val xr = fr.first + fr.second * yb
        val wd = xr - xl
        if (wd < w * 0.22f || wd > w * 0.95f || xl > cx + w * 0.05f || xr < cx - w * 0.05f) return null
        return Lane(xl, xr, yb, fl.first + fl.second * yt, fr.first + fr.second * yt, yt)
    }

    /** The vehicle in front: dark shadow under the rear (day) or a pair of red lights (night). */
    fun detectVehicle(px: IntArray, w: Int, h: Int, hood: Float, ln: Lane?): Veh? {
        val yMax = (h * (hood - 0.01f)).toInt(); val yMin = (h * 0.40f).toInt()
        fun cor(y: Int): Pair<Int, Int> {
            if (ln != null) {
                val t = ((y - ln.yTop) / (ln.yBottom - ln.yTop)).coerceIn(-0.5f, 1.2f)
                val l = ln.xlTop + (ln.xl - ln.xlTop) * t; val r = ln.xrTop + (ln.xr - ln.xrTop) * t
                val m = (r - l) * 0.12f
                return (l + m).toInt().coerceIn(0, w - 2) to (r - m).toInt().coerceIn(1, w - 1)
            }
            val half = (w * (0.04f + 0.14f * ((y - h * 0.45f) / (h * 0.45f)).coerceIn(0f, 1f))).toInt()
            return (w / 2 - half).coerceAtLeast(0) to (w / 2 + half).coerceAtMost(w - 1)
        }
        // road brightness from a stripe near the bonnet line
        val ref = yMax - 6
        val (rl, rr) = cor(ref)
        val vals = ArrayList<Int>()
        for (x in rl..rr step 2) vals += luma(px[ref * w + x])
        if (vals.size < 6) return null
        vals.sort(); val road = vals[vals.size / 2]
        if (road < 45) return nightVehicle(px, w, h, yMin, yMax)
        fun run(y: Int, l: Int, r: Int, thr: Double): IntArray? {
            var best: IntArray? = null; var st = -1; var gap = 0; var last = -1
            for (x in l..r) {
                if (luma(px[y * w + x]) < thr) { if (st < 0) st = x; last = x; gap = 0 }
                else if (st >= 0) { gap++; if (gap > 2) { if (best == null || last - st > best[1] - best[0]) best = intArrayOf(st, last); st = -1 } }
            }
            if (st >= 0 && (best == null || last - st > best[1] - best[0])) best = intArrayOf(st, last)
            return best
        }
        var y = yMax - 14
        var found = -1
        while (y > yMin) {
            val (l, r) = cor(y)
            val a = run(y, l, r, road * 0.58)
            if (a != null && a[1] - a[0] >= max(w * 0.04, (r - l) * 0.2)) {
                val (l2, r2) = cor(y - 4)
                val b2 = run(y - 4, l2, r2, road * 0.75)
                if (b2 != null && minOf(a[1], b2[1]) - maxOf(a[0], b2[0]) >= (a[1] - a[0]) * 0.5) { found = y; break }
            }
            y -= 2
        }
        if (found < 0) return nightVehicle(px, w, h, yMin, yMax)
        // width of the dark run at this row, extended outwards from the corridor centre
        val (l, r) = cor(found)
        val rr0 = run(found, l, r, road * 0.58)!!
        var xl = (rr0[0] + rr0[1]) / 2; var xr = xl
        var gap = 0
        while (xl > 1 && gap < 4) { if (luma(px[found * w + xl - 1]) < road * 0.72) gap = 0 else gap++; xl-- }
        gap = 0
        while (xr < w - 2 && gap < 4) { if (luma(px[found * w + xr + 1]) < road * 0.72) gap = 0 else gap++; xr++ }
        val wd = xr - xl
        if (wd < w * 0.03f || wd > w * 0.6f) return null
        val hh = (wd * 0.75f).toInt()
        return Veh(xl, found - hh, wd, hh + 3, distFor(wd), false)
    }

    private fun nightVehicle(px: IntArray, w: Int, h: Int, yMin: Int, yMax: Int): Veh? {
        // bright red blobs: columns of red pixels, then pair them
        val pts = ArrayList<IntArray>()
        var y = yMin
        while (y < yMax) {
            var x = w / 5
            while (x < w * 4 / 5) {
                val c = px[y * w + x]; val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
                if (r > 170 && r > g * 2.2 && r > b * 2.0) pts += intArrayOf(x, y)
                x += 2
            }
            y += 2
        }
        if (pts.size < 8) return null
        val cx = pts.sumOf { it[0] } / pts.size
        val l = pts.filter { it[0] < cx }; val r = pts.filter { it[0] >= cx }
        if (l.size < 3 || r.size < 3) return null
        val lx = l.sumOf { it[0] } / l.size; val rx = r.sumOf { it[0] } / r.size
        val ly = l.sumOf { it[1] } / l.size; val ry = r.sumOf { it[1] } / r.size
        val wd = rx - lx
        if (wd < w * 0.03f || wd > w * 0.5f || abs(ly - ry) > wd * 0.25f) return null
        val by = max(ly, ry) + 4
        return Veh(lx - wd / 8, by - (wd * 0.7).toInt(), (wd * 1.25).toInt(), (wd * 0.7).toInt(), distFor((wd * 1.15).toInt()), true)
    }

    /** Pinhole estimate: a car is about 1.8 m wide. Trucks are wider, so their distance reads too far. */
    private fun distFor(widthPx: Int): Double {
        val f = (fw / 2.0) / tan(Math.toRadians(hfovDeg / 2.0))
        return (f * 1.8 / widthPx.coerceAtLeast(1)).coerceIn(2.0, 150.0)
    }

    private fun say(key: String, text: String, ms: Long) { if (Prefs.signSpeak) Alerts.announce(key, text, ms) }

    fun process(px: IntArray, w: Int, h: Int, now: Long = System.currentTimeMillis()) {
        fw = w; fh = h; frames++
        val hood = Prefs.adasHood / 100f
        val spd = RoadAssist.speedKmh
        val ln = detectLane(px, w, h, hood)
        lane = ln
        // ---- lane position and departure ----
        if (ln != null) {
            val centre = (ln.xl + ln.xr) / 2f; val wd = ln.xr - ln.xl
            val off = ((centre - w / 2f) / wd).toDouble()
            if (!baseReady) { base = off; baseReady = true } else if (abs(off - base) < 0.12) base += (off - base) * 0.03
            offset = off - base
            val dep = if (offset > 0.30) -1 else if (offset < -0.30) 1 else 0   // camera centre drifts right of lane centre => car moves left
            if (dep == 0) depCount = 0 else if (dep == lastDep) depCount++ else depCount = 1
            lastDep = dep
            departing = if (dep != 0 && depCount >= 3) dep else 0
            if (departing != 0 && Prefs.adasLane && spd > 45) say("adas-lane", if (departing < 0) "Drifting left" else "Drifting right", 8000)
        } else { departing = 0; depCount = 0 }
        // ---- vehicle ahead ----
        val v = detectVehicle(px, w, h, hood, ln)
        if (v != null) {
            val lv = lastVeh
            if (lv == null || abs(lv.x - v.x) < w * 0.15 && abs(lv.dist - v.dist) < lv.dist * 0.5 + 3) { vMiss = 0; veh = v } else { vMiss++; if (vMiss >= 2) veh = v }
            lastVeh = v
            hist += now to v.dist; while (hist.size > 8) hist.removeAt(0)
            if (hist.size >= 4) {
                val dt = (hist.last().first - hist.first().first) / 1000.0
                if (dt > 0.6) closing = ((hist.first().second - hist.last().second) / dt).coerceIn(-30.0, 40.0)
            }
            val sMs = spd / 3.6
            ttc = if (closing > 1.0) v.dist / closing else 99.0
            headway = if (sMs > 3.0) v.dist / sMs else 99.0
            level = when {
                spd > 25 && (ttc < 2.0 || v.dist < sMs * 0.5) -> 2
                spd > 25 && (ttc < 3.5 || headway < 1.0) -> 1
                else -> 0
            }
            if (Prefs.adasFcw) {
                if (level == 2) say("adas-fcw", "Collision warning", 4000)
                else if (level == 1) say("adas-gap", "Keep your distance", 9000)
            }
        } else {
            vMiss++
            if (vMiss >= 3) { veh = null; lastVeh = null; level = 0; ttc = 99.0; headway = 99.0; closing = 0.0; hist.clear() }
        }
    }
}
