package com.dilip.carobd

import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

/** On-device battery analysis: estimated CCA, health, state of charge, charging system and advice. */
object BattAi {
    val types = listOf("Lead-acid (flooded)", "AGM", "EFB", "Gel", "LFP (LiFePO4)")

    class Finding(val ic: Ic, val title: String, val text: String, val level: Int)   // level 0 good · 1 watch · 2 bad
    class Report(
        val estCca: Int?, val resMohm: Double?, val sohPct: Int?, val socPct: Int?, val socNote: String,
        val crankN: Int, val avgMin: Double?, val avgRest: Double?, val avgDip: Double?, val trendV: Double?,
        val vMin: Double?, val vMax: Double?, val runAvg: Double?, val ageMonths: Int?, val ratedCca: Int,
        val findings: List<Finding>, val score: Int
    )

    private class Crank(val t: Long, val min: Double, val rest: Double)

    private fun cranks(): List<Crank> {
        val out = ArrayList<Crank>()
        try {
            val a = JSONArray(Prefs.crankHistory); val vid = ObdManager.vehicle.id
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optLong("vid", vid) == vid) out += Crank(o.getLong("t"), o.getDouble("min"), o.getDouble("rest"))
            }
        } catch (_: Exception) {}
        return out
    }

    /** Typical cranking current (A) for the engine – an assumption, used only for the CCA estimate. */
    fun crankAmps(): Double {
        val v = ObdManager.vehicle
        val base = 140.0 + 110.0 * v.dispL
        return base * (if (v.fuel.contains("Diesel", true)) 1.5 else 1.0)
    }

    fun socFromRest(v: Double, type: String): Pair<Int?, String> {
        if (type.startsWith("LFP")) return null to "LFP voltage is almost flat between 20 % and 90 %, so SoC cannot be read from voltage alone."
        val tbl = if (type == "AGM" || type == "Gel") listOf(12.9 to 100, 12.8 to 90, 12.6 to 75, 12.4 to 55, 12.2 to 40, 12.0 to 25, 11.8 to 10)
                  else listOf(12.7 to 100, 12.5 to 80, 12.3 to 60, 12.15 to 40, 12.0 to 25, 11.8 to 10)
        if (v >= tbl.first().first) return 100 to "Rested reading"
        if (v <= tbl.last().first) return tbl.last().second to "Rested reading"
        for (i in 0 until tbl.size - 1) {
            val (v1, s1) = tbl[i]; val (v2, s2) = tbl[i + 1]
            if (v <= v1 && v >= v2) return (s2 + (v - v2) / (v1 - v2) * (s1 - s2)).toInt() to "Rested reading"
        }
        return null to ""
    }

    fun analyze(): Report {
        val type = Prefs.battType; val lfp = type.startsWith("LFP")
        val list = cranks().sortedBy { it.t }
        val recent = list.takeLast(5)
        val avgMin = recent.map { it.min }.average().takeIf { recent.isNotEmpty() }
        val avgRest = recent.map { it.rest }.average().takeIf { recent.isNotEmpty() }
        val avgDip = if (avgMin != null && avgRest != null) avgRest - avgMin else null
        val rated = Prefs.battCca
        var res: Double? = null; var est: Int? = null; var soh: Int? = null
        if (avgDip != null && avgDip > 0.15) {
            val r = avgDip / crankAmps()
            res = r * 1000.0
            val k = if (lfp) 4.8 else 5.4                // ohm·A – 12.6 V → 7.2 V (lead-acid) / → 7.8 V (LFP) at the rated current
            est = (k / r).toInt().coerceIn(50, 1500)
            if (rated > 0) soh = (est * 100.0 / rated).toInt().coerceIn(0, 100)
        }
        // charging statistics
        val hist = ObdManager.battHist.values()
        val vMin = hist.minOrNull()?.toDouble(); val vMax = hist.maxOrNull()?.toDouble()
        val charging = hist.filter { it > 13.0 }
        val runAvg = if (charging.size >= 10) charging.average() else null
        val trend = if (list.size >= 6) list.takeLast(3).map { it.min }.average() - list.dropLast(3).takeLast(3).map { it.min }.average() else null

        val live = ObdManager.batteryV
        val running = ObdManager.engineOn && (ObdManager.values[0x0C] ?: 0.0) > 450
        val restV = if (!running) live ?: avgRest else avgRest
        val (soc, socNote) = if (restV != null) socFromRest(restV, type) else null to "Connect with the engine off for a rested reading."

        val ageM = if (Prefs.battSince > 0) ((System.currentTimeMillis() - Prefs.battSince) / (30.4 * 86_400_000)).toInt() else null
        val f = ArrayList<Finding>()
        if (list.isEmpty()) f += Finding(Ic.INFO, "No cranking data yet", "Connect with the ignition OFF, then start the engine. The app records the lowest voltage while cranking and the AI works out the rest.", 1)
        else {
            if (avgMin != null) {
                val low = Prefs.crankLowV
                f += when {
                    avgMin < low -> Finding(Ic.WARN, "Weak cranking", "Average minimum %.2f V is below your %.1f V limit. The battery, terminals or earth cable may be weak – load-test it.".format(avgMin, low), 2)
                    avgMin < low + 0.6 -> Finding(Ic.BATTERY, "Cranking is borderline", "Average minimum %.2f V is close to the %.1f V limit.".format(avgMin, low), 1)
                    else -> Finding(Ic.CHECK, "Cranking is strong", "Average minimum %.2f V while starting (dip %.2f V).".format(avgMin, avgDip ?: 0.0), 0)
                }
            }
            if (est != null) {
                val t = if (rated > 0) "Estimated %d CCA vs %d rated – about %d %% of new.".format(est, rated, soh ?: 0)
                        else "Estimated about %d CCA. Enter the rated CCA (tap Battery details) to see the health percentage.".format(est)
                f += Finding(Ic.BOLT, "AI cold-cranking estimate", t + " Based on %.1f mΩ internal resistance at an assumed %.0f A start; accuracy is roughly ±25 %%.".format(res ?: 0.0, crankAmps()),
                    if (soh != null && soh < 60) 2 else if (soh != null && soh < 75) 1 else 0)
            }
            if (trend != null) f += when {
                trend < -0.35 -> Finding(Ic.CHART, "Cranking voltage is falling", "Last starts are %.2f V lower than the ones before – the battery is ageing or a connection is getting worse.".format(-trend), 1)
                trend > 0.35 -> Finding(Ic.CHART, "Cranking voltage improved", "Recent starts are %.2f V higher.".format(trend), 0)
                else -> Finding(Ic.CHART, "Cranking is stable", "No meaningful change over the last starts.", 0)
            }
        }
        if (runAvg != null) {
            f += when {
                runAvg > Prefs.chargeMaxV -> Finding(Ic.WARN, "Over-charging", "Average running voltage %.2f V is above %.1f V.".format(runAvg, Prefs.chargeMaxV), 2)
                runAvg < Prefs.chargeMinV -> Finding(Ic.WARN, "Charging is low", "Average running voltage %.2f V is below %.1f V. Check the alternator, belt and earth.".format(runAvg, Prefs.chargeMinV), 2)
                else -> Finding(Ic.CHECK, "Charging system OK", "Average running voltage %.2f V (smart alternators vary this on purpose).".format(runAvg), 0)
            }
        }
        if (!running && live != null && live < Prefs.parkLowV && !lfp) f += Finding(Ic.BATTERY, "Rest voltage is low", "%.2f V with the engine off. Drive 30 min or use a charger; if it keeps falling overnight look for a parasitic drain.".format(live), 1)
        if (lfp) f += Finding(Ic.INFO, "LFP starter battery", "Keep the alternator current limited so the LFP is not over-charged; cranking should stay above ~11.5 V. Cold weather below 0 °C reduces LFP cranking power.", 0)
        if (ageM != null) {
            val life = if (lfp) 96 else 54
            f += Finding(Ic.CALENDAR, "Battery age: ${ageM / 12} y ${ageM % 12} m", if (ageM > life * 0.85) "Near the typical life (~${life / 12} years) – plan a replacement soon." else "Typical life is about ${life / 12} years.", if (ageM > life * 0.85) 1 else 0)
        }
        var score = 100
        for (x in f) score -= when (x.level) { 2 -> 25; 1 -> 10; else -> 0 }
        if (soh != null) score = min(score, soh + 15)
        return Report(est, res, soh, soc, socNote, list.size, avgMin, avgRest, avgDip, trend, vMin, vMax, runAvg, ageM, rated, f, max(0, min(100, score)))
    }
}
