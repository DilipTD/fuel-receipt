package com.dilip.carobd

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Extra driver-assist vision in plain Kotlin: traffic-light colour (red / amber / green, with "get set – go" prompts),
 * possible pedestrians (movement based) and "vehicle ahead is moving" at a stop. WARNINGS ONLY – estimates from one phone camera.
 */
object Perception {
    class Box(val x: Int, val y: Int, val w: Int, val h: Int)
    /** 0 none, 1 red, 2 amber, 3 green */
    @Volatile var light = 0
    @Volatile var lightBox: Box? = null
    @Volatile var peds: List<Box> = emptyList()
    @Volatile var pedNear = false
    @Volatile var started = false

    private val votes = ArrayList<Int>()
    private var stableLight = 0
    private var prev: IntArray? = null
    private var pw = 0; private var ph = 0
    private var pedHits = 0
    private var stopDist = -1.0
    private var stopSince = 0L

    fun reset() { light = 0; lightBox = null; peds = emptyList(); pedNear = false; started = false; votes.clear(); stableLight = 0; prev = null; pedHits = 0; stopDist = -1.0 }

    private fun say(key: String, text: String, ms: Long) { if (Prefs.signSpeak) Alerts.announce(key, text, ms) }

    private fun colourOf(c: Int): Int {
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx < 150 || mx - mn < 90) return 0
        return when {
            r >= 200 && g < r * 0.45 && b < r * 0.55 -> 1                      // red
            r >= 210 && g >= r * 0.55 && g <= r * 0.92 && b < g * 0.5 -> 2      // amber
            g >= 150 && r < g * 0.6 && b >= g * 0.35 -> 3                       // green (often a little blue-ish)
            else -> 0
        }
    }

    /** Bright, round, coloured spot with a dark surround in the upper part of the picture. */
    fun detectLight(px: IntArray, w: Int, h: Int): Pair<Int, Box>? {
        val y1 = (h * 0.55f).toInt()
        val lab = ByteArray(w * y1)
        for (y in 0 until y1) for (x in 0 until w) lab[y * w + x] = colourOf(px[y * w + x]).toByte()
        val seen = BooleanArray(w * y1)
        var best: Pair<Int, Box>? = null; var bestScore = 0.0
        val stack = IntArray(w * y1)
        for (s in 0 until w * y1) {
            val k = lab[s].toInt()
            if (k == 0 || seen[s]) continue
            var sp = 0; stack[sp++] = s; seen[s] = true
            var minX = w; var maxX = 0; var minY = h; var maxY = 0; var n = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w; n++
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                for (d in intArrayOf(-1, 1, -w, w)) {
                    val q = p + d
                    if (q < 0 || q >= w * y1 || seen[q] || lab[q].toInt() != k) continue
                    if ((d == -1 && x == 0) || (d == 1 && x == w - 1)) continue
                    seen[q] = true; stack[sp++] = q
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (n < 6 || bw < 3 || bh < 3 || bw > w * 0.08 || bh > w * 0.08) continue
            val asp = bw.toDouble() / bh
            if (asp < 0.6 || asp > 1.6 || n < bw * bh * 0.55) continue
            // dark housing around the lamp
            var sum = 0; var cnt = 0
            val mx = max(2, bw); val my = max(2, bh)
            for (y in max(0, minY - my) until min(y1, maxY + my + 1)) for (x in max(0, minX - mx) until min(w, maxX + mx + 1)) {
                if (x in minX..maxX && y in minY..maxY) continue
                val c = px[y * w + x]; sum += ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3; cnt++
            }
            if (cnt == 0 || sum / cnt > 95) continue
            // a lit lamp is clearly brighter than the housing
            val cx = (minX + maxX) / 2
            val score = n * (1.0 - abs(cx - w / 2.0) / w) * (if (k == 1) 1.15 else 1.0)
            if (score > bestScore) { bestScore = score; best = k to Box(minX, minY, bw, bh) }
        }
        return best
    }

    /** People-sized moving shapes in the corridor ahead, found by frame difference. */
    private fun detectPeds(px: IntArray, w: Int, h: Int, hood: Float): List<Box> {
        val gw = w / 4; val gh = h / 4
        if (gw < 16 || gh < 12) return emptyList()
        val g = IntArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) { val c = px[(y * 4) * w + x * 4]; g[y * gw + x] = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3 }
        val pv = prev; val same = pv != null && pw == gw && ph == gh
        prev = g; pw = gw; ph = gh
        if (!same) return emptyList()
        val x0 = (gw * 0.12f).toInt(); val x1 = (gw * 0.88f).toInt(); val y0 = (gh * 0.40f).toInt(); val y1 = (gh * hood).toInt() - 2
        if (y1 <= y0) return emptyList()
        val mot = BooleanArray(gw * gh); var total = 0
        for (y in y0 until y1) for (x in x0 until x1) if (abs(g[y * gw + x] - pv!![y * gw + x]) > 28) { mot[y * gw + x] = true; total++ }
        if (total > (x1 - x0) * (y1 - y0) * 0.22) return emptyList()      // the whole scene moved: it is us, not a person
        val out = ArrayList<Box>()
        var x = x0
        while (x < x1) {
            var colSum = 0; var top = gh; var bot = 0
            for (y in y0 until y1) if (mot[y * gw + x]) { colSum++; if (y < top) top = y; if (y > bot) bot = y }
            if (colSum >= 3) {
                var xe = x; var t = top; var b = bot; var gap = 0
                while (xe + 1 < x1 && gap < 4) {
                    val nx = xe + 1; var cs = 0
                    for (y in y0 until y1) if (mot[y * gw + nx]) { cs++; if (y < t) t = y; if (y > b) b = y }
                    if (cs >= 2) { xe = nx; gap = 0 } else { xe = nx; gap++ }
                }
                val bw = xe - gap - x + 1; val bh = b - t + 1
                if (bw in 2..(gw / 7) && bh >= gh * 0.10 && bh.toDouble() / bw in 1.2..6.0) out += Box(x * 4, t * 4, bw * 4, bh * 4)
                x = xe + 1
            } else x++
        }
        return out.take(3)
    }

    fun process(px: IntArray, w: Int, h: Int, now: Long = System.currentTimeMillis()) {
        val spd = RoadAssist.speedKmh
        // ---- traffic light ----
        if (Prefs.adasLight) {
            val d = detectLight(px, w, h)
            votes += d?.first ?: 0; while (votes.size > 6) votes.removeAt(0)
            val top = (1..3).maxByOrNull { k -> votes.count { it == k } } ?: 0
            val st = if (top != 0 && votes.count { it == top } >= 3) top else if (votes.count { it == 0 } >= 4) 0 else stableLight
            lightBox = if (st != 0) d?.second ?: lightBox else null
            if (st != stableLight) {
                val old = stableLight; stableLight = st
                when {
                    st == 1 && spd > 12 -> say("tl-red", "Red light ahead. Prepare to stop", 6000)
                    st == 2 && old == 1 -> say("tl-set", "Get ready", 4000)
                    st == 2 -> say("tl-amber", "Amber light", 5000)
                    st == 3 && (old == 1 || old == 2) && spd < 8 -> say("tl-go", "Green light. Go", 4000)
                }
            }
            light = stableLight
        } else { light = 0; lightBox = null; stableLight = 0; votes.clear() }
        // ---- pedestrians ----
        if (Prefs.adasPed) {
            val p = detectPeds(px, w, h, Prefs.adasHood / 100f)
            pedHits = if (p.isNotEmpty()) min(6, pedHits + 1) else max(0, pedHits - 1)
            if (pedHits >= 2) { peds = p.ifEmpty { peds }; pedNear = true } else { peds = emptyList(); pedNear = false }
            if (pedNear && spd > 3) say("ped-warn", "Pedestrian ahead", 6000)
        } else { peds = emptyList(); pedNear = false; prev = null }
        // ---- vehicle ahead started moving while we are stopped ----
        val v = Adas.veh
        if (spd < 2 && v != null) {
            if (stopDist < 0 || now - stopSince > 20000) { stopDist = v.dist; stopSince = now; started = false }
            else if (v.dist > stopDist + 4.0 && Prefs.adasLight) { if (!started) say("veh-go", "Vehicle ahead is moving", 8000); started = true }
        } else { stopDist = -1.0; started = false }
    }
}
