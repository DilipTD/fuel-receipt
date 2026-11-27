package com.dilip.carobd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A road-sign-like region: coloured border (red or blue) around a bright inside. kind 0 = red, 1 = blue. */
class SignCand(val x: Int, val y: Int, val w: Int, val h: Int, val kind: Int, val ring: Boolean)

class LimitRead(val value: Int, val conf: Double)

/**
 * Offline road-sign vision in plain Kotlin (no ML library): finds red / blue sign shapes in a small camera image,
 * reads speed-limit numbers by comparing digit shapes with rendered digit templates, and makes a compact fingerprint
 * of any sign so a sign you taught once can be recognised again.
 */
object SignVision {
    const val FW = 320
    private const val DW = 12
    private const val DH = 16
    private const val FP = 12
    val LIMITS = setOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100, 105, 110, 120, 130)

    /** NV21 camera frame → small upright ARGB image (width, height). */
    fun fromNv21(nv: ByteArray, w: Int, h: Int, rot: Int): Triple<IntArray, Int, Int> {
        val f = max(1, w / FW)
        val ow = w / f; val oh = h / f
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val y = oy * f
            val uvRow = w * h + (y shr 1) * w
            for (ox in 0 until ow) {
                val x = ox * f
                val yy = nv[y * w + x].toInt() and 0xFF
                val ui = uvRow + (x shr 1) * 2
                val v = (nv[ui].toInt() and 0xFF) - 128
                val u = (nv[ui + 1].toInt() and 0xFF) - 128
                val r = (yy + 1.402 * v).toInt().coerceIn(0, 255)
                val g = (yy - 0.344 * u - 0.714 * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.772 * u).toInt().coerceIn(0, 255)
                out[oy * ow + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return rotate(out, ow, oh, rot)
    }

    fun rotate(src: IntArray, w: Int, h: Int, deg: Int): Triple<IntArray, Int, Int> {
        return when (((deg % 360) + 360) % 360) {
            90 -> { val o = IntArray(w * h); for (y in 0 until h) for (x in 0 until w) o[x * h + (h - 1 - y)] = src[y * w + x]; Triple(o, h, w) }
            180 -> { val o = IntArray(w * h); for (i in 0 until w * h) o[w * h - 1 - i] = src[i]; Triple(o, w, h) }
            270 -> { val o = IntArray(w * h); for (y in 0 until h) for (x in 0 until w) o[(w - 1 - x) * h + y] = src[y * w + x]; Triple(o, h, w) }
            else -> Triple(src, w, h)
        }
    }

    private fun luma(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /** Finds sign-like regions (largest first, at most 4). */
    fun detect(px: IntArray, w: Int, h: Int): List<SignCand> {
        if (w < 16 || h < 16 || px.size < w * h) return emptyList()
        val mask = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]; val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
            mask[i] = when {
                r > 120 && r > g * 1.7 && r > b * 1.6 -> 1
                b > 90 && b > r * 1.6 && b > g * 1.15 -> 2
                r > 150 && g > 120 && b < 95 && r > b * 2 && g > b * 1.6 && abs(r - g) < 90 -> 3
                else -> 0
            }
        }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<SignCand>()
        for (s in 0 until w * h) {
            val m = mask[s].toInt()
            if (m == 0 || seen[s]) continue
            var sp = 0; stack[sp++] = s; seen[s] = true
            var minX = w; var maxX = 0; var minY = h; var maxY = 0; var n = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                n++
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && !seen[p - 1] && mask[p - 1].toInt() == m) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < w - 1 && !seen[p + 1] && mask[p + 1].toInt() == m) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && !seen[p - w] && mask[p - w].toInt() == m) { seen[p - w] = true; stack[sp++] = p - w }
                if (y < h - 1 && !seen[p + w] && mask[p + w].toInt() == m) { seen[p + w] = true; stack[sp++] = p + w }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (n < 60 || bw < 14 || bh < 14 || bw > h || bh > h) continue
            val asp = bw.toDouble() / bh
            if (asp < 0.7 || asp > 1.4) continue
            val fill = n.toDouble() / (bw * bh)
            // brightness of the inside (central half of the box)
            var bright = 0; var tot = 0
            for (yy in minY + bh / 4..maxY - bh / 4) for (xx in minX + bw / 4..maxX - bw / 4) {
                tot++
                if (mask[yy * w + xx].toInt() == 0 && luma(px[yy * w + xx]) > 135) bright++
            }
            val bf = if (tot > 0) bright.toDouble() / tot else 0.0
            val ok = if (m == 1) ((fill in 0.12..0.62 && bf > 0.40) || (fill in 0.45..0.95 && bf in 0.05..0.45))
            else if (m == 3) (fill in 0.25..0.95 && bf <= 0.6)
            else (fill in 0.35..0.95 && bf in 0.04..0.55)
            if (!ok) continue
            out += SignCand(minX, minY, bw, bh, if (m == 1) 0 else if (m == 3) 2 else 1, m == 1 && fill < 0.62 && bf > 0.40)
        }
        return out.sortedByDescending { it.w * it.h }.take(4)
    }

    /* ---------------------------- speed-limit reading ---------------------------- */

    private val templates: List<Pair<Int, BooleanArray>> by lazy {
        val list = ArrayList<Pair<Int, BooleanArray>>()
        val faces = listOf(Typeface.DEFAULT_BOLD, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), Typeface.create("sans-serif-medium", Typeface.NORMAL))
        for (tf in faces) for (d in 0..9) renderDigit(d.toString(), tf)?.let { list += d to it }
        list
    }

    private fun renderDigit(ch: String, tf: Typeface): BooleanArray? {
        return try {
            val bmp = Bitmap.createBitmap(140, 170, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp); cv.drawColor(Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = tf; textSize = 120f }
            cv.drawText(ch, 25f, 130f, p)
            val px = IntArray(140 * 170); bmp.getPixels(px, 0, 140, 0, 0, 140, 170); bmp.recycle()
            val dark = BooleanArray(140 * 170) { luma(px[it]) < 128 }
            normDigit(dark, 140, 170, 0, 0, 139, 169)
        } catch (_: Throwable) { null }
    }

    /** Crop the dark pixels' bounding box inside the given window and resample to DW×DH. */
    private fun normDigit(dark: BooleanArray, w: Int, h: Int, wx0: Int, wy0: Int, wx1: Int, wy1: Int): BooleanArray? {
        var x0 = wx1; var x1 = wx0; var y0 = wy1; var y1 = wy0
        for (y in wy0..wy1) for (x in wx0..wx1) if (dark[y * w + x]) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        if (x1 <= x0 || y1 <= y0) return null
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        val out = BooleanArray(DW * DH)
        for (ty in 0 until DH) for (tx in 0 until DW) {
            val sx0 = x0 + tx * bw / DW; val sx1 = max(sx0 + 1, x0 + (tx + 1) * bw / DW)
            val sy0 = y0 + ty * bh / DH; val sy1 = max(sy0 + 1, y0 + (ty + 1) * bh / DH)
            var c = 0; var t = 0
            for (y in sy0 until min(sy1, y1 + 1)) for (x in sx0 until min(sx1, x1 + 1)) { t++; if (dark[y * w + x]) c++ }
            out[ty * DW + tx] = t > 0 && c * 2 >= t
        }
        return out
    }

    /** Reads the number on a round red-ring speed-limit sign, or null when it is not one. */
    @Volatile var why = ""
    private fun fail(w: String): LimitRead? { why = w; return null }

    fun readLimit(px: IntArray, w: Int, h: Int, c: SignCand): LimitRead? {
        if (!c.ring || c.kind != 0) return fail("1")
        val s = max(c.w, c.h)
        val cx = c.x + c.w / 2; val cy = c.y + c.h / 2
        val half = (s * 0.35).toInt()
        val x0 = (cx - half).coerceAtLeast(0); val x1 = (cx + half).coerceAtMost(w - 1)
        val y0 = (cy - half).coerceAtLeast(0); val y1 = (cy + half).coerceAtMost(h - 1)
        val iw = x1 - x0 + 1; val ih = y1 - y0 + 1
        if (iw < 14 || ih < 14) return fail("2")
        val rad2 = (s * 0.35) * (s * 0.35)
        val gray = IntArray(iw * ih); val inside = BooleanArray(iw * ih)
        val hist = IntArray(256); var n = 0
        for (y in 0 until ih) for (x in 0 until iw) {
            val dx = x0 + x - cx; val dy = y0 + y - cy
            if (dx * dx + dy * dy <= rad2) {
                val g = luma(px[(y0 + y) * w + x0 + x]); gray[y * iw + x] = g; inside[y * iw + x] = true; hist[g]++; n++
            }
        }
        if (n < 100) return fail("3")
        // Otsu threshold
        var sum = 0L; for (i in 0..255) sum += i.toLong() * hist[i]
        var wb = 0; var sb = 0L; var best = 0.0; var thr = 128
        for (t in 0..255) {
            wb += hist[t]; if (wb == 0) continue
            val wf = n - wb; if (wf == 0) break
            sb += t.toLong() * hist[t]
            val mb = sb.toDouble() / wb; val mf = (sum - sb).toDouble() / wf
            val v = wb.toDouble() * wf * (mb - mf) * (mb - mf)
            if (v > best) { best = v; thr = t }
        }
        if (thr > 200 || thr < 20) return fail("4")
        val dark = BooleanArray(iw * ih) { inside[it] && gray[it] <= thr }
        // dark components = digits
        val seen = BooleanArray(iw * ih); val stack = IntArray(iw * ih)
        class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val n: Int)
        val boxes = ArrayList<Box>()
        for (st in 0 until iw * ih) {
            if (!dark[st] || seen[st]) continue
            var sp = 0; stack[sp++] = st; seen[st] = true
            var a0 = iw; var a1 = 0; var b0 = ih; var b1 = 0; var cnt = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % iw; val y = p / iw; cnt++
                if (x < a0) a0 = x; if (x > a1) a1 = x; if (y < b0) b0 = y; if (y > b1) b1 = y
                if (x > 0 && dark[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < iw - 1 && dark[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && dark[p - iw] && !seen[p - iw]) { seen[p - iw] = true; stack[sp++] = p - iw }
                if (y < ih - 1 && dark[p + iw] && !seen[p + iw]) { seen[p + iw] = true; stack[sp++] = p + iw }
            }
            val bh = b1 - b0 + 1; val bw = a1 - a0 + 1
            if (bh >= s * 0.2 && bh <= s * 0.66 && bw >= 2 && bw <= s * 0.5 && cnt >= 8) boxes += Box(a0, b0, a1, b1, cnt)
        }
        if (boxes.size !in 2..3) return fail("boxes=${boxes.size} thr=$thr n=$n")//("5")
        boxes.sortBy { it.x0 }
        // digits must sit on one line and have similar height
        val hMax = boxes.maxOf { it.y1 - it.y0 + 1 }; val hMin = boxes.minOf { it.y1 - it.y0 + 1 }
        if (hMin < hMax * 0.75) return fail("heights $hMin..$hMax")
        var value = 0; var score = 0.0
        for (b in boxes) {
            val g = normDigit(dark, iw, ih, b.x0, b.y0, b.x1, b.y1) ?: return fail("7")
            var bestD = -1; var bestS = 0.0
            for ((d, t) in templates) {
                var same = 0
                for (i in g.indices) if (g[i] == t[i]) same++
                val sc = same.toDouble() / g.size
                if (sc > bestS) { bestS = sc; bestD = d }
            }
            if (bestD < 0 || bestS < 0.70) return fail("digit ${bestD} score=${"%.2f".format(bestS)} boxes=${boxes.size} thr=$thr")
            value = value * 10 + bestD; score += bestS
        }
        score /= boxes.size
        if (value !in LIMITS || score < 0.76) return fail("value=$value score=${"%.2f".format(score)}")
        return LimitRead(value, score)
    }

    /* ---------------------------- fingerprint for taught signs ---------------------------- */

    /** Brightness- and size-independent fingerprint of the sign (10×10 colour thumbnail, centred and unit length). */
    fun fingerprint(px: IntArray, w: Int, h: Int, c: SignCand): FloatArray {
        val f = FloatArray(FP * FP * 3)
        // central 70 % only: the border looks alike on many signs, the symbol inside tells them apart
        val ox = c.x + (c.w * 0.15).toInt(); val oy = c.y + (c.h * 0.15).toInt()
        val cw = max(FP, (c.w * 0.7).toInt()); val chh = max(FP, (c.h * 0.7).toInt())
        for (ty in 0 until FP) for (tx in 0 until FP) {
            val sx0 = ox + tx * cw / FP; val sx1 = max(sx0 + 1, ox + (tx + 1) * cw / FP)
            val sy0 = oy + ty * chh / FP; val sy1 = max(sy0 + 1, oy + (ty + 1) * chh / FP)
            var r = 0L; var g = 0L; var b = 0L; var t = 0
            for (y in sy0 until min(sy1, h)) for (x in sx0 until min(sx1, w)) { val p = px[y * w + x]; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; t++ }
            if (t == 0) t = 1
            val i = (ty * FP + tx) * 3
            f[i] = r.toFloat() / t; f[i + 1] = g.toFloat() / t; f[i + 2] = b.toFloat() / t
        }
        return unit(f)
    }

    fun unit(f: FloatArray): FloatArray {
        var m = 0f; for (v in f) m += v; m /= f.size
        var n = 0.0; for (i in f.indices) { f[i] -= m; n += f[i].toDouble() * f[i] }
        val k = if (n > 1e-6) (1.0 / sqrt(n)).toFloat() else 0f
        for (i in f.indices) f[i] *= k
        return f
    }

    fun similarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var d = 0.0; for (i in a.indices) d += a[i] * b[i]
        return d
    }

    /** Small JPEG picture of the sign for the library. */
    fun thumb(px: IntArray, w: Int, h: Int, c: SignCand): ByteArray? = try {
        val x = c.x.coerceIn(0, w - 1); val y = c.y.coerceIn(0, h - 1)
        val cw = min(c.w, w - x); val ch = min(c.h, h - y)
        val bmp = Bitmap.createBitmap(px, 0, w, w, h, Bitmap.Config.ARGB_8888)
        val crop = Bitmap.createBitmap(bmp, x, y, cw, ch)
        val sm = Bitmap.createScaledBitmap(crop, 56, 56, true)
        val os = java.io.ByteArrayOutputStream()
        sm.compress(Bitmap.CompressFormat.JPEG, 80, os)
        os.toByteArray()
    } catch (_: Throwable) { null }

    fun abs2(a: Double) = abs(a)
}
