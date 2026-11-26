package com.dilip.carobd

/** On-device emission-readiness analysis. Decodes OBD-II PID 01 monitors and explains, per fuel type, what is still needed. */
object EmissionAi {
    class Mon(val name: String, val ready: Boolean)
    class Report(
        val applicable: Boolean, val known: Boolean, val mil: Boolean, val dtc: Int, val monitors: List<Mon>,
        val level: Int,              // -1 unknown, 0 ready, 1 not ready yet, 2 will fail (MIL / codes)
        val headline: String, val detail: String, val tips: List<String>, val extra: List<String>
    ) {
        val incomplete get() = monitors.count { !it.ready }
        val readyPct get() = if (monitors.isEmpty()) 0 else monitors.count { it.ready } * 100 / monitors.size
    }

    private val SPARK = listOf("Catalyst", "Heated catalyst", "Evaporative system", "Secondary air", "A/C refrigerant", "Oxygen sensor", "O2 sensor heater", "EGR / VVT")
    private val DIESEL = listOf("NMHC catalyst", "NOx / SCR (AdBlue)", "", "Boost pressure", "", "Exhaust gas sensor", "Particulate filter (DPF)", "EGR / VVT")

    fun analyse(): Report {
        val m = ObdManager; val veh = m.vehicle; val v = m.values
        val f = veh.fuel
        if (veh.isEv) return Report(false, true, false, 0, emptyList(), 0, "No tailpipe emissions",
            "An electric car has no exhaust monitors. The relevant checks are the HV battery, charging and 12 V health.",
            listOf("Check HV battery health on the service schedule.", "Keep the 12 V battery healthy – most EV faults start there."), emptyList())
        val d = m.monitorRaw
        if (d == null || d.size < 4) return Report(true, false, false, 0, emptyList(), -1, "Emission readiness unknown",
            "Connect to the car and let it read the monitor status.", fuelTips(f, emptyList(), warm = true), emptyList())
        val mil = d[0] and 0x80 != 0; val n = d[0] and 0x7F
        val compression = d[1] and 0x08 != 0
        val mons = ArrayList<Mon>()
        fun add(name: String, sup: Boolean, inc: Boolean) { if (sup && name.isNotBlank()) mons += Mon(name, !inc) }
        add("Misfire", d[1] and 0x01 != 0, d[1] and 0x10 != 0)
        add("Fuel system", d[1] and 0x02 != 0, d[1] and 0x20 != 0)
        add("Components", d[1] and 0x04 != 0, d[1] and 0x40 != 0)
        val names = if (compression) DIESEL else SPARK
        for (i in 0 until 8) add(names[i], d[2] and (1 shl i) != 0, d[3] and (1 shl i) != 0)

        val inc = mons.filter { !it.ready }
        val extra = ArrayList<String>()
        v[0x30]?.let { extra += "Warm-ups since codes cleared: %.0f".format(it) }
        v[0x31]?.let { extra += "Distance since codes cleared: ${"%.0f %s".format(Units.km(it), Units.distLabel)}" }
        v[0x4E]?.let { extra += "Time since codes cleared: %.0f min".format(it) }
        v[0x21]?.let { if (it > 0) extra += "Driven with check-engine light on: ${"%.0f %s".format(Units.km(it), Units.distLabel)}" }
        val warm = (v[0x05] ?: 0.0) >= 70.0
        val recentClear = (v[0x31] != null && v[0x31]!! < 60) || (v[0x30] != null && v[0x30]!! < 4)

        val (level, head, detail) = when {
            mil -> Triple(2, "Not ready – check-engine light is ON",
                "$n confirmed code(s). An emission (PUC) test fails while the light is on. Fix the codes first, then drive to let the monitors complete.")
            inc.isEmpty() -> Triple(0, "Ready for emission test",
                "All ${mons.size} supported monitors are complete and the check-engine light is off. A test should pass if the tailpipe readings are good.")
            inc.size == 1 && !compression -> Triple(1, "Almost ready – 1 monitor pending",
                "Most test centres accept one incomplete monitor, but ${inc[0].name.lowercase()} should be completed first to be safe.")
            else -> Triple(1, "Not ready – ${inc.size} monitors pending",
                (if (recentClear) "Codes or the battery were cleared recently, which resets every monitor. " else "") +
                    "Pending: ${inc.joinToString { it.name }}. A drive cycle is needed before the test.")
        }
        return Report(true, true, mil, n, mons, level, head, detail, if (level == 0) emptyList() else fuelTips(f, inc, warm), extra)
    }

    private fun has(inc: List<Mon>, s: String) = inc.any { it.name.contains(s, true) }

    /** What to do next, specific to the fuel type and to which monitors are still pending. */
    fun fuelTips(f: String, inc: List<Mon>, warm: Boolean): List<String> {
        val out = ArrayList<String>()
        if (!warm) out += "Warm the engine fully first (coolant above ~80 °C); most monitors only run when hot."
        when {
            f == "Diesel" -> {
                if (inc.isEmpty() || has(inc, "particulate")) out += "DPF: drive 20+ minutes at a steady 60–90 km/h on open road so the filter can regenerate."
                if (inc.isEmpty() || has(inc, "NOx")) out += "SCR: check the AdBlue/DEF level and keep it topped up; the system tests itself on a warm, steady drive."
                if (has(inc, "boost")) out += "Boost: a few firm (not flat-out) accelerations on a warm engine complete the boost check."
                out += "Avoid only short, cold trips – diesel after-treatment needs sustained heat."
            }
            f == "CNG" || f == "LPG" -> {
                out += "Run on $f (not petrol) for the drive cycle, with a steady 50–80 km/h cruise for 15–20 minutes."
                if (inc.isEmpty() || has(inc, "catalyst") || has(inc, "oxygen")) out += "Catalyst and O2 monitors need a warm engine on gas; a rich or lean mixture from the $f kit can stop them completing."
                if (has(inc, "fuel system") || has(inc, "misfire")) out += "Check the $f kit tuning and spark plugs – they are the usual cause of misfire and fuel-system monitors staying incomplete."
            }
            f.startsWith("Hybrid") -> {
                out += "The petrol engine must actually run: use normal/hybrid mode (not EV-only) for a 20-minute mixed drive."
                if (has(inc, "evap")) out += "Evap monitor: keep the tank between 15 % and 85 % and park it cold overnight."
            }
            else -> {   // petrol, E20
                if (inc.isEmpty() || has(inc, "catalyst") || has(inc, "oxygen")) out += "Catalyst and O2 sensor: cruise at a steady 60–90 km/h for 10–15 minutes after a cold start, then idle for 2 minutes."
                if (has(inc, "evap")) out += "Evap system: keep the tank between 15 % and 85 %, make sure the fuel cap is tight and let the car sit cold for 6+ hours before a drive."
                if (has(inc, "egr")) out += "EGR/VVT: include a few light accelerations and a gentle decelerating coast."
                if (has(inc, "misfire") || has(inc, "fuel system")) out += "Misfire/fuel-system: a mixed 20-minute drive with varied throttle completes these; persistent failure points to plugs or injectors."
                if (f.startsWith("E20")) out += "E20 fuel can briefly change fuel trims; refill with the same blend and drive a full cycle."
            }
        }
        out += "Don't clear codes or disconnect the battery before the test – it resets every monitor."
        return out.distinct()
    }

    fun text(): String {
        val r = analyse()
        val sb = StringBuilder("Emission readiness (${ObdManager.vehicle.fuel})\n${r.headline}\n${r.detail}\n")
        if (r.monitors.isNotEmpty()) {
            sb.append("\nMonitors – ${r.readyPct} % complete\n")
            for (mo in r.monitors) sb.append("  ${if (mo.ready) "✅ ready     " else "⏳ incomplete"}  ${mo.name}\n")
        }
        if (r.extra.isNotEmpty()) sb.append("\n").append(r.extra.joinToString("\n")).append("\n")
        if (r.tips.isNotEmpty()) { sb.append("\nWhat to do\n"); r.tips.forEach { sb.append("• $it\n") } }
        sb.append("\nA guide only – the official PUC / emission test decides.")
        return sb.toString()
    }

    /** One live driving tip while monitors are pending and conditions are right. */
    fun liveTip(): String? {
        val m = ObdManager; val v = m.values
        if (m.state != ObdManager.State.CONNECTED || m.vehicle.isEv) return null
        val r = analyse()
        if (!r.known || r.level != 1) return null
        val spd = v[0x0D] ?: return null; val cool = v[0x05] ?: 0.0
        if (cool < 70) return "Emission monitors pending – keep driving until the engine is fully warm."
        val f = m.vehicle.fuel
        return when {
            f == "Diesel" && spd in 60.0..95.0 -> "Hold this steady speed 15–20 min to help the DPF/SCR monitors finish."
            f != "Diesel" && spd in 55.0..90.0 -> "Hold a steady speed for 10–15 min to complete the emission monitors (${r.incomplete} pending)."
            else -> null
        }
    }
}
