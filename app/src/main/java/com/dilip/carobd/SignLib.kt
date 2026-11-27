package com.dilip.carobd

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** A sign you taught once. action: limit (value), warn (arg = text to say), hazard (arg = hazard kind), info, ignore. */
class SignEntry(val id: Long, var name: String, var action: String, var value: Int, var arg: String, val kind: Int,
                val fps: ArrayList<FloatArray>, var thumb: ByteArray?, var seen: Int, val created: Long)

/** Library of taught signs. Stored in the app settings, so Backup & cloud saves and restores it with everything else. */
object SignLib {
    const val MATCH = 0.93
    private const val MAX_SIGNS = 80
    private const val MAX_SAMPLES = 6
    val entries = ArrayList<SignEntry>()
    @Volatile var version = 0
    private var loaded = false
    private var lastJson = ""

    @Synchronized
    fun load(force: Boolean = false) {
        val js = Prefs.signLib
        if (loaded && !force && js == lastJson) return
        loaded = true; lastJson = js
        entries.clear()
        try {
            val a = JSONArray(js)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val fa = o.getJSONArray("f"); val fps = ArrayList<FloatArray>()
                for (k in 0 until fa.length()) fps += deq(Base64.decode(fa.getString(k), Base64.NO_WRAP))
                entries += SignEntry(o.getLong("i"), o.getString("n"), o.getString("a"), o.optInt("v"), o.optString("g"), o.optInt("k"), fps,
                    if (o.has("t")) Base64.decode(o.getString("t"), Base64.NO_WRAP) else null, o.optInt("s"), o.optLong("c"))
            }
        } catch (_: Exception) {}
        version++
    }

    @Synchronized
    fun save() {
        val a = JSONArray()
        for (e in entries) {
            val fa = JSONArray(); e.fps.forEach { fa.put(Base64.encodeToString(q(it), Base64.NO_WRAP)) }
            val o = JSONObject().put("i", e.id).put("n", e.name).put("a", e.action).put("v", e.value).put("g", e.arg).put("k", e.kind)
                .put("f", fa).put("s", e.seen).put("c", e.created)
            e.thumb?.let { o.put("t", Base64.encodeToString(it, Base64.NO_WRAP)) }
            a.put(o)
        }
        lastJson = a.toString(); Prefs.signLib = lastJson; version++
    }

    private fun q(f: FloatArray) = ByteArray(f.size) { (Math.round(f[it] * 400f) + 128).coerceIn(0, 255).toByte() }
    private fun deq(b: ByteArray) = SignVision.unit(FloatArray(b.size) { ((b[it].toInt() and 0xFF) - 128) / 400f })

    /** Best taught sign for a fingerprint, with its similarity; null when nothing is close or two are too similar to tell apart. */
    @Synchronized
    fun match(fp: FloatArray, kind: Int): Pair<SignEntry, Double>? {
        load()
        var best: SignEntry? = null; var bs = 0.0; var second = 0.0
        for (e in entries) {
            if (e.kind != kind) continue
            var s = 0.0
            for (f in e.fps) s = maxOf(s, SignVision.similarity(fp, f))
            if (s > bs) { second = bs; bs = s; best = e } else if (s > second) second = s
        }
        return if (best != null && bs >= MATCH && bs - second >= 0.01) best to bs else null
    }

    @Synchronized
    fun add(name: String, action: String, value: Int, arg: String, kind: Int, fp: FloatArray, thumb: ByteArray?): SignEntry {
        load()
        if (entries.size >= MAX_SIGNS) entries.removeAt(0)
        val e = SignEntry(System.currentTimeMillis(), name.ifBlank { "Sign" }, action, value, arg, kind, arrayListOf(fp), thumb, 0, System.currentTimeMillis())
        entries += e; save()
        return e
    }

    /** Keeps another example of a confirmed sign (different light, distance) so it is found more reliably. */
    @Synchronized
    fun addSample(e: SignEntry, fp: FloatArray) {
        if (e.fps.size >= MAX_SAMPLES) return
        if (e.fps.any { SignVision.similarity(fp, it) > 0.975 }) return
        e.fps += fp; save()
    }

    @Synchronized fun delete(id: Long) { entries.removeAll { it.id == id }; save() }
    @Synchronized fun touch() { save() }
}

/** Frame-by-frame sign recognition: speed limits, taught signs and unknown signs waiting to be taught. */
object SignEngine {
    class Det(val c: SignCand, val label: String, val state: Int)   // state 0 limit, 1 taught, 2 unknown, 3 ignored

    @Volatile var dets: List<Det> = emptyList()
    @Volatile var frameW = 0
    @Volatile var frameH = 0
    @Volatile var lastLimit = 0
    @Volatile var lastLimitAt = 0L
    @Volatile var lastKnown = ""
    @Volatile var lastKnownAt = 0L
    @Volatile var applied = ""
    @Volatile var pendingThumb: ByteArray? = null
    @Volatile var pendingAt = 0L
    private var pendingFp: FloatArray? = null
    private var pendingKind = 0
    private var pendingHits = 0
    private val limHist = ArrayDeque<Int>()
    private val knownHist = ArrayDeque<Long>()
    private val lastAct = HashMap<Long, Long>()

    fun reset() {
        bHist.clear(); recent.clear(); lastBuiltin.clear()
        dets = emptyList(); pendingThumb = null; pendingFp = null; pendingHits = 0; limHist.clear(); knownHist.clear()
        lastLimit = 0; lastLimitAt = 0; lastKnown = ""; lastKnownAt = 0; applied = ""
    }

    fun hasPending() = pendingThumb != null && System.currentTimeMillis() - pendingAt < 6000

    class Seen(val name: String, val kind: Char, val t: Long)
    val recent = java.util.concurrent.CopyOnWriteArrayList<Seen>()
    private val bHist = ArrayDeque<Set<Int>>()
    private val bLast = HashMap<Int, Float>()
    var builtinSeen = 0

    private fun note(name: String, kind: Char, now: Long) {
        recent.add(0, Seen(name, kind, now)); while (recent.size > 8) recent.removeAt(recent.size - 1); builtinSeen++
    }

    fun process(px: IntArray, w: Int, h: Int) {
        frameW = w; frameH = h
        val now = System.currentTimeMillis()
        val out = ArrayList<Det>()
        var lim = 0; var known: SignEntry? = null; var knownSim = 0.0
        var unk: Pair<SignCand, FloatArray>? = null
        val builtin = Prefs.signBuiltin && SignNet.ready
        val bIds = HashSet<Int>()
        for (c in SignVision.detect(px, w, h)) {
            if (c.kind != 2) {
                val lr = SignVision.readLimit(px, w, h, c)
                if (lr != null) { out += Det(c, lr.value.toString(), 0); if (lim == 0) lim = lr.value; continue }
                val fp = SignVision.fingerprint(px, w, h, c)
                val m = SignLib.match(fp, c.kind)
                if (m != null) {
                    out += Det(c, m.first.name, if (m.first.action == "ignore") 3 else 1)
                    if (m.first.action != "ignore" && known == null) { known = m.first; knownSim = m.second; if (m.second < 0.97) SignLib.addSample(m.first, fp) }
                    continue
                }
                out += Det(c, "?", 2)
                if (unk == null) unk = c to fp
            }
        }
        if (builtin) {
            for (c in try { SignNet.propose(px, w, h) } catch (_: Throwable) { emptyList() }) {
                val cxp = c.x + c.w / 2; val cyp = c.y + c.h / 2
                if (out.any { it.state != 2 && cxp in it.c.x..it.c.x + it.c.w && cyp in it.c.y..it.c.y + it.c.h }) continue
                val bn = try { SignNet.classify(px, w, h, c) } catch (_: Throwable) { null } ?: continue
                out.removeAll { it.state == 2 && (it.c.x + it.c.w / 2) in c.x..c.x + c.w && (it.c.y + it.c.h / 2) in c.y..c.y + c.h }
                out += Det(c, bn.first.name, 4); bIds += bn.first.id
                if (out.none { it.state == 2 }) unk = null
            }
        }
        dets = out
        limHist.addLast(lim); while (limHist.size > 5) limHist.removeFirst()
        if (lim > 0 && limHist.count { it == lim } >= 2) onLimit(lim, now)
        knownHist.addLast(known?.id ?: 0L); while (knownHist.size > 5) knownHist.removeFirst()
        if (known != null && knownHist.count { it == known.id } >= 2) onKnown(known, now)
        bHist.addLast(bIds); while (bHist.size > 6) bHist.removeFirst()
        for (id in bIds) if (bHist.count { id in it } >= 3) onBuiltin(SignNet.KB[id], now)
        val u = unk
        if (u != null) {
            val pf = pendingFp
            if (pf != null && SignVision.similarity(pf, u.second) > 0.9) pendingHits++ else { pendingFp = u.second; pendingHits = 1 }
            if (pendingHits >= 3) { pendingKind = u.first.kind; pendingThumb = SignVision.thumb(px, w, h, u.first); pendingAt = now; pendingFp = u.second }
        } else if (now - pendingAt > 6000) { pendingThumb = null; pendingFp = null; pendingHits = 0 }
    }

    private val lastBuiltin = HashMap<Int, Long>()

    /** A standard sign seen on at least 3 of the last 6 pictures. */
    private fun onBuiltin(s: SignInfo, now: Long) {
        lastKnown = s.name; lastKnownAt = now
        if (now - (lastBuiltin[s.id] ?: 0L) < 45_000) return
        lastBuiltin[s.id] = now
        note(s.name, s.kind, now)
        val heavyOnly = s.id == 10 || s.id == 16 || s.id == 42
        val heavy = try { Prefs.vehicleClass(ObdManager.vehicle.id) != "Car" } catch (_: Throwable) { false }
        when (s.kind) {
            'L' -> onLimit(s.value, now)
            'W', 'R' -> {
                if (heavyOnly && !heavy) { RoadAssist.addLog("Sign: ${s.name}"); return }
                if (Prefs.signSpeak) Alerts.announce("signtxt-${s.say}", s.say, 60_000)
                RoadAssist.addLog("Sign: ${s.name}")
                SignMap.observe(s, now)
            }
            else -> RoadAssist.addLog("Sign: ${s.name}")
        }
    }

    private fun onLimit(v: Int, now: Long) {
        lastLimit = v; lastLimitAt = now
        if (Prefs.signAuto) applied = if (RoadAssist.cameraLimit(v)) "Applied to the current road" else "Waiting for a matched road"
        else applied = "Not applied (automatic is off)"
    }

    private fun onKnown(e: SignEntry, now: Long) {
        lastKnown = e.name; lastKnownAt = now
        if (now - (lastAct[e.id] ?: 0L) < 60_000) return
        lastAct[e.id] = now
        e.seen++; if (e.seen % 5 == 1) SignLib.touch()
        when (e.action) {
            "limit" -> if (Prefs.signAuto) RoadAssist.cameraLimit(e.value)
            "warn" -> { if (Prefs.signSpeak) Alerts.announce("sign-${e.id}", e.arg.ifBlank { e.name }, 60_000); RoadAssist.addLog("Sign: ${e.name}") }
            "hazard" -> { if (!RoadAssist.hazardNear(e.arg, 60.0)) RoadAssist.reportHazard(e.arg); RoadAssist.addLog("Sign: ${e.name}") }
            else -> RoadAssist.addLog("Sign: ${e.name}")
        }
    }

    /** Teach the sign that is waiting (the unknown one the camera has been looking at). Returns false when none is waiting. */
    fun teach(name: String, action: String, value: Int, arg: String): SignEntry? {
        val fp = pendingFp ?: return null
        val e = SignLib.add(name, action, value, arg, pendingKind, fp, pendingThumb)
        pendingThumb = null; pendingFp = null; pendingHits = 0
        return e
    }

    fun dismissPending() { pendingThumb = null; pendingFp = null; pendingHits = 0 }
}


/**
 * Signs seen by the camera, saved with their position so the road assistant can warn before you see them next time.
 * A sign counts as confirmed after it has been seen on two separate passes (or many times in one).
 */
object SignMap {
    class Obs(val cls: Int, var lat: Double, var lon: Double, var n: Int, var passes: Int, var t: Long)
    private var list: ArrayList<Obs>? = null
    @Volatile var version = 0

    private fun load(): ArrayList<Obs> {
        list?.let { return it }
        val out = ArrayList<Obs>()
        try {
            val a = org.json.JSONArray(Prefs.signMap)
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out += Obs(o.getInt("c"), o.getDouble("a"), o.getDouble("o"), o.optInt("n", 1), o.optInt("p", 1), o.optLong("t", 0L)) }
        } catch (_: Exception) {}
        list = out; return out
    }

    private fun save() {
        val l = load()
        val a = org.json.JSONArray()
        for (o in l) a.put(org.json.JSONObject().put("c", o.cls).put("a", o.lat).put("o", o.lon).put("n", o.n).put("p", o.passes).put("t", o.t))
        Prefs.signMap = a.toString(); version++
    }

    fun reload() { list = null; version++ }

    @Synchronized fun observe(s: SignInfo, now: Long) {
        val pos = RoadAssist.hereAhead(25.0) ?: return
        val l = load()
        val e = l.filter { it.cls == s.id }.minByOrNull { Loc.dist(pos.first, pos.second, it.lat, it.lon) }
        if (e != null && Loc.dist(pos.first, pos.second, e.lat, e.lon) * 1000 < 45) {
            e.lat = (e.lat * e.n + pos.first) / (e.n + 1); e.lon = (e.lon * e.n + pos.second) / (e.n + 1)
            if (now - e.t > 180_000) e.passes++
            e.n++; e.t = now
        } else {
            l += Obs(s.id, pos.first, pos.second, 1, 1, now)
            while (l.size > 1500) l.remove(l.minByOrNull { it.t })
        }
        save()
    }

    fun confirmed(o: Obs) = o.passes >= 2 || o.n >= 6
    fun count() = load().size
    fun confirmedCount() = load().count { confirmed(it) }
    fun clear() { list = ArrayList(); save() }

    /** Confirmed signs near the car, as hazards for the road assistant. */
    fun hazards(lat: Double, lon: Double): List<RoadAssist.Hazard> {
        val out = ArrayList<RoadAssist.Hazard>()
        for (o in load()) {
            if (!confirmed(o)) continue
            if (Math.abs(o.lat - lat) > 0.008 || Math.abs(o.lon - lon) > 0.01) continue
            val k = SignNet.KB.getOrNull(o.cls) ?: continue
            if (k.say.isEmpty()) continue
            out += RoadAssist.Hazard("sign", o.lat, o.lon, k.say)
        }
        return out
    }
}
