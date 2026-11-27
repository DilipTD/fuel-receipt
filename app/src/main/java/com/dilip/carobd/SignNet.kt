package com.dilip.carobd

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a recognised standard sign means for the driver. */
class SignInfo(val id: Int, val name: String, val kind: Char, val value: Int = 0, val say: String = "")

/**
 * Offline recogniser for the 43 standard (German / international) traffic-sign classes, in plain Kotlin.
 * A small convolutional network (about 160 000 weights, trained on the German sign benchmark) runs on the 32×32 crop
 * of every sign the camera finds. No library, no internet. Class 43 is "not a sign".
 */
object SignNet {
    const val N = 44
    private const val S = 32

    // kinds: L speed limit, W warning, R rule (stop, give way, no entry, no overtaking …), E end of restriction, I information only
    val KB: List<SignInfo> = listOf(
        SignInfo(0, "Speed limit 20", 'L', 20), SignInfo(1, "Speed limit 30", 'L', 30), SignInfo(2, "Speed limit 50", 'L', 50),
        SignInfo(3, "Speed limit 60", 'L', 60), SignInfo(4, "Speed limit 70", 'L', 70), SignInfo(5, "Speed limit 80", 'L', 80),
        SignInfo(6, "End of 80 limit", 'E'), SignInfo(7, "Speed limit 100", 'L', 100), SignInfo(8, "Speed limit 120", 'L', 120),
        SignInfo(9, "No overtaking", 'R', 0, "No overtaking"), SignInfo(10, "No overtaking for trucks", 'R', 0, "No overtaking for trucks"),
        SignInfo(11, "Junction, right of way", 'I'), SignInfo(12, "Priority road", 'I'),
        SignInfo(13, "Give way", 'R', 0, "Give way ahead"), SignInfo(14, "Stop", 'R', 0, "Stop sign ahead"),
        SignInfo(15, "No vehicles", 'R', 0, "No vehicles allowed"), SignInfo(16, "Heavy vehicles prohibited", 'R', 0, "Heavy vehicles prohibited"),
        SignInfo(17, "No entry", 'R', 0, "No entry. Do not go in"), SignInfo(18, "General caution", 'W', 0, "Caution ahead"),
        SignInfo(19, "Curve left", 'W', 0, "Curve to the left ahead"), SignInfo(20, "Curve right", 'W', 0, "Curve to the right ahead"),
        SignInfo(21, "Double curve", 'W', 0, "Double curve ahead"), SignInfo(22, "Bumpy road", 'W', 0, "Bumpy road ahead"),
        SignInfo(23, "Slippery road", 'W', 0, "Slippery road ahead"), SignInfo(24, "Road narrows", 'W', 0, "Road narrows ahead"),
        SignInfo(25, "Road works", 'W', 0, "Road work ahead"), SignInfo(26, "Traffic signals", 'W', 0, "Traffic signal ahead"),
        SignInfo(27, "Pedestrians", 'W', 0, "Pedestrians ahead"), SignInfo(28, "Children", 'W', 0, "Children ahead, drive carefully"),
        SignInfo(29, "Cyclists", 'W', 0, "Cyclists ahead"), SignInfo(30, "Ice or snow", 'W', 0, "Ice or snow risk ahead"),
        SignInfo(31, "Animals crossing", 'W', 0, "Animals may cross"), SignInfo(32, "End of all limits", 'E'),
        SignInfo(33, "Turn right ahead", 'I'), SignInfo(34, "Turn left ahead", 'I'), SignInfo(35, "Ahead only", 'I'),
        SignInfo(36, "Straight or right", 'I'), SignInfo(37, "Straight or left", 'I'), SignInfo(38, "Keep right", 'I'),
        SignInfo(39, "Keep left", 'I'), SignInfo(40, "Roundabout", 'I'), SignInfo(41, "End of no overtaking", 'I'),
        SignInfo(42, "End of no overtaking for trucks", 'I')
    )

    private var w1 = FloatArray(0); private var b1 = FloatArray(0)
    private var w2 = FloatArray(0); private var b2 = FloatArray(0)
    private var w3 = FloatArray(0); private var b3 = FloatArray(0)
    private var f1 = FloatArray(0); private var fb1 = FloatArray(0)
    private var f2 = FloatArray(0); private var fb2 = FloatArray(0)
    @Volatile var ready = false
    @Volatile var error = ""

    fun load(ctx: Context): Boolean {
        if (ready) return true
        return try { ctx.assets.open("signnet.bin").use { parse(it.readBytes()) } } catch (e: Throwable) { error = e.message ?: "missing"; false }
    }

    fun parse(bytes: ByteArray): Boolean {
        return try {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4); bb.get(magic)
            if (String(magic) != "SGN1") { error = "bad model file"; return false }
            bb.int
            fun rd(n: Int) = FloatArray(n).also { bb.asFloatBuffer().get(it); bb.position(bb.position() + n * 4) }
            w1 = rd(16 * 3 * 9); b1 = rd(16); w2 = rd(32 * 16 * 9); b2 = rd(32); w3 = rd(64 * 32 * 9); b3 = rd(64)
            f1 = rd(128 * 1024); fb1 = rd(128); f2 = rd(N * 128); fb2 = rd(N)
            ready = true; true
        } catch (e: Throwable) { error = e.message ?: "bad model"; false }
    }

    private fun conv(inp: FloatArray, cin: Int, h: Int, w: Int, wt: FloatArray, b: FloatArray, cout: Int): FloatArray {
        val out = FloatArray(cout * h * w)
        for (o in 0 until cout) {
            for (y in 0 until h) for (x in 0 until w) {
                var s = b[o]
                for (c in 0 until cin) {
                    val wb = (o * cin + c) * 9; val ib = c * h * w
                    for (ky in 0..2) {
                        val yy = y + ky - 1; if (yy < 0 || yy >= h) continue
                        for (kx in 0..2) {
                            val xx = x + kx - 1; if (xx < 0 || xx >= w) continue
                            s += wt[wb + ky * 3 + kx] * inp[ib + yy * w + xx]
                        }
                    }
                }
                out[(o * h + y) * w + x] = if (s > 0f) s else 0f
            }
        }
        return out
    }

    private fun pool(inp: FloatArray, c: Int, h: Int, w: Int): FloatArray {
        val oh = h / 2; val ow = w / 2; val out = FloatArray(c * oh * ow)
        for (k in 0 until c) for (y in 0 until oh) for (x in 0 until ow) {
            val b = k * h * w + 2 * y * w + 2 * x
            out[(k * oh + y) * ow + x] = max(max(inp[b], inp[b + 1]), max(inp[b + w], inp[b + w + 1]))
        }
        return out
    }

    /** Probabilities for a 32×32 RGB image given as 3072 floats in 0..1, channel-first (R plane, G plane, B plane). */
    fun forward(img: FloatArray): FloatArray {
        // per-image standardisation, the same as in training
        var m = 0.0; for (v in img) m += v; m /= img.size
        var ss = 0.0; for (v in img) ss += (v - m) * (v - m)
        val sd = sqrt(ss / (img.size - 1)) + 1e-3
        val x = FloatArray(img.size) { ((img[it] - m) / sd).toFloat() }
        var a = pool(conv(x, 3, S, S, w1, b1, 16), 16, S, S)
        a = pool(conv(a, 16, 16, 16, w2, b2, 32), 32, 16, 16)
        a = pool(conv(a, 32, 8, 8, w3, b3, 64), 64, 8, 8)
        val h = FloatArray(128)
        for (o in 0 until 128) { var s = fb1[o]; val b = o * 1024; for (i in 0 until 1024) s += f1[b + i] * a[i]; h[o] = if (s > 0f) s else 0f }
        val lo = FloatArray(N)
        for (o in 0 until N) { var s = fb2[o]; val b = o * 128; for (i in 0 until 128) s += f2[b + i] * h[i]; lo[o] = s }
        var mx = lo[0]; for (v in lo) if (v > mx) mx = v
        var sum = 0f; for (i in lo.indices) { lo[i] = exp(lo[i] - mx); sum += lo[i] }
        for (i in lo.indices) lo[i] /= sum
        return lo
    }

    /** Cuts a square (with a margin like the training pictures) around a candidate and shrinks it to 32×32. */
    fun crop(px: IntArray, w: Int, h: Int, c: SignCand, margin: Double = 1.12): FloatArray {
        val cx = c.x + c.w / 2.0; val cy = c.y + c.h / 2.0
        val side = max(c.w, c.h) * margin
        val x0 = cx - side / 2; val y0 = cy - side / 2
        val out = FloatArray(3 * S * S)
        val ss = min(4, max(1, (side / S).toInt()))
        val step = side / S
        for (ty in 0 until S) for (tx in 0 until S) {
            var r = 0f; var g = 0f; var b = 0f
            for (j in 0 until ss) for (i in 0 until ss) {
                val sx = (x0 + (tx + (i + 0.5) / ss) * step).toInt().coerceIn(0, w - 1)
                val sy = (y0 + (ty + (j + 0.5) / ss) * step).toInt().coerceIn(0, h - 1)
                val p = px[sy * w + sx]
                r += (p shr 16 and 0xFF); g += (p shr 8 and 0xFF); b += (p and 0xFF)
            }
            val n = ss * ss * 255f
            out[ty * S + tx] = r / n; out[S * S + ty * S + tx] = g / n; out[2 * S * S + ty * S + tx] = b / n
        }
        return out
    }

    /**
     * Candidate sign regions from colour alone: red, blue or yellow pixels, joined up so a thin ring becomes one blob.
     * Deliberately loose: the network (which also knows "not a sign") decides what is really a sign.
     */
    fun propose(px: IntArray, w: Int, h: Int, maxN: Int = 8): List<SignCand> {
        val m = BooleanArray(w * h)
        for (i in px.indices) {
            val c = px[i]; val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
            m[i] = (r > 60 && r > 1.35 * max(g, b)) || (b > 60 && b > 1.25 * max(r, g)) || (r > 110 && g > 90 && r > 1.5 * b && g > 1.3 * b)
        }
        // grow by 3 pixels in each direction (two passes)
        val t = BooleanArray(w * h)
        val R = 3
        for (y in 0 until h) { var last = -100; for (x in 0 until w) { if (m[y * w + x]) last = x; if (x - last <= R) t[y * w + x] = true }
            last = 1000; for (x in w - 1 downTo 0) { if (m[y * w + x]) last = x; if (last - x <= R) t[y * w + x] = true } }
        val d = BooleanArray(w * h)
        for (x in 0 until w) { var last = -100; for (y in 0 until h) { if (t[y * w + x]) last = y; if (y - last <= R) d[y * w + x] = true }
            last = 1000; for (y in h - 1 downTo 0) { if (t[y * w + x]) last = y; if (last - y <= R) d[y * w + x] = true } }
        val seen = BooleanArray(w * h); val stack = IntArray(w * h)
        val out = ArrayList<SignCand>()
        for (s0 in 0 until w * h) {
            if (!d[s0] || seen[s0]) continue
            var sp = 0; stack[sp++] = s0; seen[s0] = true
            var minX = w; var maxX = 0; var minY = h; var maxY = 0; var n = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w; n++
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && d[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < w - 1 && d[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && d[p - w] && !seen[p - w]) { seen[p - w] = true; stack[sp++] = p - w }
                if (y < h - 1 && d[p + w] && !seen[p + w]) { seen[p + w] = true; stack[sp++] = p + w }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val side = max(bw, bh)
            if (side < 14 || side > h || n < 80) continue
            val cx = (minX + maxX + 1) / 2; val cy = (minY + maxY + 1) / 2
            val x0 = (cx - side / 2).coerceIn(0, max(0, w - side)); val y0 = (cy - side / 2).coerceIn(0, max(0, h - side))
            out += SignCand(x0, y0, min(side, w - x0), min(side, h - y0), 3, false)
        }
        return out.sortedByDescending { it.w * it.h }.take(maxN)
    }

    /** Best sign for a candidate, trying three crop sizes: class id and probability, or null when it looks like background or the network is unsure. */
    fun classify(px: IntArray, w: Int, h: Int, c: SignCand, minProb: Float = 0.85f): Pair<SignInfo, Float>? {
        if (!ready) return null
        var bestI = -1; var bestP = 0f
        for (mg in doubleArrayOf(0.9, 1.0, 1.1)) {
            val p = forward(crop(px, w, h, c, mg))
            var best = 0; for (i in 1 until N) if (p[i] > p[best]) best = i
            if (best < 43 && p[best] > bestP) { bestP = p[best]; bestI = best }
        }
        if (bestI < 0 || bestP < minProb) return null
        return KB[bestI] to bestP
    }
}
