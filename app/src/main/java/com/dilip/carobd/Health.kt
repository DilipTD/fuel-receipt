package com.dilip.carobd

import kotlin.math.abs

object Health {
    class Finding(val level: Int, val title: String, val detail: String) // 0 OK, 1 info, 2 warning, 3 critical

    fun icon(level: Int) = when (level) {
        0 -> "✅"; 1 -> "ℹ️"; 2 -> "⚠️"; else -> "⛔"
    }

    /** Readiness monitors from PID 01 (spark-ignition engine). */
    fun readiness(d: IntArray?): String {
        if (d == null || d.size < 4) return "Readiness: not available"
        val sb = StringBuilder()
        sb.append(if (d[0] and 0x80 != 0) "MIL (check-engine lamp): ON\n" else "MIL (check-engine lamp): off\n")
        sb.append("Confirmed emission DTCs: ${d[0] and 0x7F}\n")
        fun mon(name: String, sup: Boolean, inc: Boolean) {
            if (sup) sb.append("  ${if (inc) "⏳ incomplete" else "✅ ready     "}  $name\n")
        }
        val b = d[1]
        mon("Misfire", b and 0x01 != 0, b and 0x10 != 0)
        mon("Fuel system", b and 0x02 != 0, b and 0x20 != 0)
        mon("Components", b and 0x04 != 0, b and 0x40 != 0)
        val c = d[2]
        val e = d[3]
        val names = listOf("Catalyst", "Heated catalyst", "Evap system", "Secondary air", "A/C refrigerant", "O2 sensor", "O2 sensor heater", "EGR / VVT")
        for (i in 0 until 8) mon(names[i], c and (1 shl i) != 0, e and (1 shl i) != 0)
        return "Readiness monitors:\n$sb"
    }

    fun analyze(): List<Finding> {
        val m = ObdManager
        val v = m.values
        val out = mutableListOf<Finding>()
        val rpm = v[0x0C]
        val running = (rpm ?: 0.0) > 400

        m.monitorRaw?.let { d ->
            if (d[0] and 0x80 != 0) out.add(Finding(3, "Check-engine lamp ON", "${d[0] and 0x7F} confirmed DTC(s). Read DTCs for details."))
            else out.add(Finding(0, "Check-engine lamp off", "${d[0] and 0x7F} confirmed DTC(s) reported."))
        }

        val bv = m.batteryV ?: v[0x42]
        if (bv != null) {
            if (running) {
                when {
                    bv < 13.0 -> out.add(Finding(2, "Charging voltage low (%.2f V)".format(bv), "Alternator may not be charging. Check belt, alternator and battery terminals."))
                    bv > 14.8 -> out.add(Finding(2, "Charging voltage high (%.2f V)".format(bv), "Regulator may be over-charging (lead-acid max ~14.8 V; LFP starter batteries ~14.6 V)."))
                    else -> out.add(Finding(0, "Charging system OK (%.2f V)".format(bv), "Normal running range 13.0–14.8 V."))
                }
            } else {
                when {
                    bv < 11.8 -> out.add(Finding(3, "Battery very low (%.2f V)".format(bv), "Engine may not crank. Charge or test the battery."))
                    bv < 12.3 -> out.add(Finding(2, "Battery low (%.2f V)".format(bv), "Battery is partly discharged."))
                    else -> out.add(Finding(0, "Battery OK (%.2f V)".format(bv), "Engine off."))
                }
            }
        }

        v[0x05]?.let { c ->
            val runTime = v[0x1F] ?: 0.0
            when {
                c > 108 -> out.add(Finding(3, "Engine overheating (%.0f °C)".format(c), "Stop safely. Check coolant level, radiator fan and thermostat."))
                c > 102 -> out.add(Finding(2, "Coolant hot (%.0f °C)".format(c), "Watch the temperature; check fan operation and coolant level."))
                running && runTime > 900 && c < 70 -> out.add(Finding(2, "Engine not warming up (%.0f °C)".format(c), "After 15 min it should be 85–100 °C. Thermostat may be stuck open (P0128)."))
                else -> out.add(Finding(0, "Coolant temperature %.0f °C".format(c), "Normal operating range 85–100 °C."))
            }
        }

        val st = v[0x06]
        val lt = v[0x07]
        if (st != null && lt != null && running) {
            val total = st + lt
            when {
                total > 15 || lt > 10 -> out.add(Finding(2, "Running lean (STFT %.1f%%, LTFT %.1f%%)".format(st, lt), "ECU is adding fuel: possible boost/vacuum leak, weak fuel pump, clogged injectors or MAP sensor."))
                total < -15 || lt < -10 -> out.add(Finding(2, "Running rich (STFT %.1f%%, LTFT %.1f%%)".format(st, lt), "ECU is removing fuel: leaking injector, purge valve stuck, high fuel pressure or O2 sensor."))
                else -> out.add(Finding(0, "Fuel trims normal (%.1f%% / %.1f%%)".format(st, lt), "Total trim within ±15 %."))
            }
        }

        m.idleRpmStdDev()?.let { sd ->
            if (sd > 60) out.add(Finding(2, "Unstable idle (±%.0f rpm)".format(sd), "Rough idle: check plugs/coils (3-cyl engines feel it more), throttle body, vacuum leaks, engine mounts."))
            else out.add(Finding(0, "Idle stable (±%.0f rpm)".format(sd), ""))
        }

        val iat = v[0x0F]
        val amb = v[0x46]
        if (iat != null && amb != null && running && iat - amb > 35) {
            out.add(Finding(1, "High intake temperature (%.0f °C vs %.0f °C ambient)".format(iat, amb), "Turbo heat-soak in traffic. Power will be reduced; check intercooler if it persists while moving."))
        }

        val timing = v[0x0E]
        val load = v[0x04]
        if (timing != null && load != null && load > 60 && timing < 0) {
            out.add(Finding(1, "Ignition retard under load (%.1f°)".format(timing), "ECU is pulling timing — likely knock. Use good quality fuel; check plugs."))
        }

        v[0x3C]?.let { if (it > 900) out.add(Finding(2, "Catalyst very hot (%.0f °C)".format(it), "Possible misfire or rich running.")) }

        val map = v[0x0B]
        val baro = v[0x33]
        if (map != null && baro != null && running && (v[0x11] ?: 0.0) > 70 && (rpm ?: 0.0) > 2500 && map - baro < 20) {
            out.add(Finding(2, "Low boost at full throttle (%.0f kPa)".format(map - baro), "Turbo under-boost: check intercooler hoses, wastegate, air filter."))
        }

        v[0x31]?.let { if (it < 50) out.add(Finding(1, "Codes cleared recently (%.0f km ago)".format(it), "Readiness monitors may still be incomplete.")) }
        v[0x4D]?.let { if (it > 0) out.add(Finding(2, "Driven %.0f min with MIL on".format(it), "")) }
        v[0x21]?.let { if (it > 0) out.add(Finding(2, "Driven %.0f km with MIL on".format(it), "")) }

        if (abs(v[0x2F] ?: 50.0) < 10) out.add(Finding(1, "Fuel low (%.0f %%)".format(v[0x2F]), "Refuel soon; low fuel can starve the pump."))

        out.add(Finding(1, "Transmission note",
            "Gearbox temperature, gear and clutch adaptation are manufacturer-specific (not standard OBD). Use 'Deep scan (UDS)' for TCU codes and Settings → Custom PIDs for brand PIDs."))
        if (out.size <= 1) out.add(0, Finding(1, "Not enough data yet", "Keep the engine running for a minute so the app can sample all PIDs."))
        return out.sortedByDescending { it.level }
    }
}
