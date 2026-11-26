package com.dilip.carobd

import kotlin.math.abs

/**
 * On-device "AI" expert engine: ranks probable root causes for fault codes using a knowledge base,
 * live data (fuel trims, voltage, coolant …), service history and the other codes present.
 * Runs fully offline – no data leaves the phone.
 */
object SmartAi {
    @Volatile var lastScan: List<String> = emptyList()
    @Volatile var scanId = 0

    class Cause(val name: String, val prob: Int, val steps: List<String>, val parts: String, val diy: Boolean, val rc: String, val why: String?)
    class Report(val code: String, val desc: String, val urgency: Int, val causes: List<Cause>, val repeats: Int) {
        val rc get() = "RC-$code"
        val urgencyText get() = when (urgency) { 0 -> "Info"; 1 -> "Fix soon"; 2 -> "Fix now"; else -> "Stop driving" }
    }

    class Ctx(
        val stft: Double?, val ltft: Double?, val coolant: Double?, val volt: Double?, val rpm: Double?, val engineOn: Boolean,
        val fuelPct: Double?, val odo: Double, val overdue: String, val codes: Set<String>, var repeats: Int = 0
    ) {
        fun has(prefix: String) = codes.any { it.startsWith(prefix) }
    }

    private class C(val name: String, val prior: Int, val steps: List<String>, val parts: String, val diy: Boolean,
                    val why: String, val boost: (Ctx) -> Int)

    private fun c(name: String, p: Int, vararg steps: String, parts: String = "", diy: Boolean = true, why: String = "", boost: (Ctx) -> Int = { 0 }) =
        C(name, p, steps.toList(), parts, diy, why, boost)

    private class Rule(val urgency: Int, val match: (String) -> Boolean, val causes: List<C>)

    private const val VAC = "Vacuum / intake air leak"
    private const val MAFD = "Dirty or faulty MAF sensor"
    private const val PLUG = "Worn or fouled spark plug"
    private const val COIL = "Faulty ignition coil"
    private const val INJ = "Clogged or leaking injector"
    private const val WIRE = "Wiring or connector fault"
    private const val BATT = "Weak battery / low system voltage"

    private fun num(code: String) = code.drop(1).toIntOrNull(16) ?: -1
    private fun inR(code: String, lo: Int, hi: Int) = code.startsWith("P") && num(code) in lo..hi

    private val rules: List<Rule> by lazy { listOf(
        Rule(2, { inR(it, 0x300, 0x312) }, listOf(
            c(PLUG, 30, "Let the engine cool, remove the plug of the affected cylinder.", "Check electrode wear, deposits and gap (spec in the manual).", "Replace the whole set if worn – never just one.",
                parts = "Spark plugs", why = "Spark plug service is overdue", boost = { x -> (if (x.overdue.contains("spark")) 25 else 0) + (if (x.odo > 40000) 8 else 0) }),
            c(COIL, 28, "Swap the coil with the neighbouring cylinder's coil.", "Clear codes and drive: if the misfire code moves to the other cylinder, the coil is bad.", "Replace the coil.",
                parts = "Ignition coil", why = "Misfire on a single cylinder points to its coil", boost = { x -> if (x.codes.any { it.length == 5 && it.startsWith("P030") && it[4] in '1'..'9' }) 18 else 0 }),
            c(INJ, 14, "Listen for the injector click with a stethoscope/screwdriver.", "Measure injector resistance and compare with the others.", "Try an injector cleaner, else clean or replace.", parts = "Injector / cleaner service", diy = false),
            c(VAC, 12, "With the engine idling, spray carb-cleaner/soapy water around intake hoses and gaskets – listen for rpm change.", "Check the brake-booster hose and PCV hose.", parts = "Hoses, intake gasket",
                why = "Long-term fuel trim is high", boost = { x -> if ((x.ltft ?: 0.0) > 10) 28 else 0 }),
            c("Low or poor-quality fuel", 8, "Refuel with good-quality fuel from a busy station.", "If the tank was below ~10 % when the code appeared, watch if it returns.", why = "Fuel level is very low",
                boost = { x -> if ((x.fuelPct ?: 100.0) < 10) 25 else 0 }),
            c("Low compression / valve problem", 8, "Do a compression and leak-down test.", "Check valve clearance and timing marks.", parts = "Engine work", diy = false)
        )),
        Rule(1, { it == "P0171" || it == "P0174" || it == "P0170" }, listOf(
            c(VAC, 36, "Check all intake hoses, clamps and the intake boot between the MAF and throttle body.", "Smoke-test or spray-test around the intake manifold gasket.", "Inspect PCV valve/hose and the brake-booster line.",
                parts = "Hose, gasket, PCV valve", why = "Positive fuel trims show extra air is entering", boost = { x -> (if ((x.ltft ?: 0.0) > 10) 25 else 0) + (if (x.has("P0300") || x.has("P0301")) 6 else 0) }),
            c(MAFD, 20, "Remove the MAF and spray MAF-cleaner only (no touching). Let it dry fully.", "Compare live MAF g/s at idle with the spec (roughly 2–5 g/s on a small petrol engine).", parts = "MAF cleaner or sensor",
                why = "Both banks lean – a shared sensor is likely", boost = { x -> if (x.codes.contains("P0171") && x.codes.contains("P0174")) 12 else 0 }),
            c("Weak fuel pump / clogged filter", 15, "Check fuel pressure at the rail against the spec.", "Replace the fuel filter if serviceable.", "If pressure drops under load, replace the pump.", parts = "Fuel filter / pump", diy = false),
            c(INJ, 10, "Run an injector cleaner through a full tank.", "Test the balance of injectors with a scan tool.", parts = "Injector service", diy = false),
            c("Exhaust leak before the O2 sensor", 9, "Listen for ticking on cold start.", "Check manifold gasket and flex pipe for soot marks.", parts = "Gasket / pipe"),
            c("Faulty upstream O2 sensor", 10, "Watch the O2 voltage in Live data – it should swing 0.1–0.9 V quickly.", "Replace if slow or stuck.", parts = "Upstream O2 sensor")
        )),
        Rule(1, { it == "P0172" || it == "P0175" }, listOf(
            c(INJ, 26, "Check for fuel smell/black smoke.", "Test injector leak-down and pressure hold.", parts = "Injector set", diy = false),
            c(MAFD, 18, "Clean the MAF sensor with MAF-cleaner.", "Check that the air filter is not oily.", parts = "MAF cleaner / sensor"),
            c("Clogged air filter", 16, "Inspect and replace the air filter.", parts = "Air filter", why = "Air filter service is overdue", boost = { x -> if (x.overdue.contains("air filter")) 25 else 0 }),
            c("Fuel pressure regulator / return fault", 14, "Check fuel pressure and look for fuel in the vacuum line.", parts = "Regulator", diy = false),
            c("Coolant temperature sensor reads cold", 12, "Compare ECT with a real temperature (infrared gun on the thermostat housing).", parts = "ECT sensor",
                why = "Engine is warm but a rich mixture persists", boost = { x -> if ((x.coolant ?: 0.0) > 80 && (x.stft ?: 0.0) < -8) 8 else 0 }),
            c("Faulty upstream O2 sensor", 12, "Check O2 sensor voltage response in Live data.", parts = "O2 sensor")
        )),
        Rule(1, { it == "P0420" || it == "P0430" }, listOf(
            c("Aged catalytic converter", 42, "Rule out misfires and fuel-trim codes first.", "Compare upstream and downstream O2 signals – downstream should be steady.", "Replace the catalyst only after the other checks.", parts = "Catalytic converter", diy = false),
            c("Downstream O2 sensor failing", 22, "Check its voltage and heater in Live data.", parts = "Downstream O2 sensor"),
            c("Exhaust leak near the sensors", 14, "Listen/feel for leaks before the downstream sensor.", parts = "Gasket / pipe"),
            c("Damage from past misfires", 12, "Check history for P030x codes.", parts = "Fix the misfire first", why = "Misfire codes are present", boost = { x -> if (x.has("P030")) 30 else 0 }),
            c("Mixture running lean or rich", 10, "Fix any P0171/P0172 first and re-test.", why = "Fuel-trim codes are present", boost = { x -> if (x.has("P017")) 20 else 0 })
        )),
        Rule(0, { inR(it, 0x440, 0x457) }, listOf(
            c("Loose or worn fuel cap", 40, "Tighten the cap until it clicks, check the seal.", "Clear the code, drive 2–3 days.", parts = "Fuel cap"),
            c("EVAP purge valve stuck", 22, "Listen for a click with ignition on, check vacuum at the valve.", parts = "Purge valve"),
            c("Vent valve / canister fault", 14, "Check the vent valve near the tank/canister.", parts = "Vent valve", diy = false),
            c("Cracked EVAP hose", 16, "Inspect the rubber hoses to the canister for cracks.", parts = "EVAP hose"),
            c("Leak at the fuel tank / filler neck", 8, "Smoke test the EVAP system.", diy = false)
        )),
        Rule(1, { inR(it, 0x100, 0x104) }, listOf(
            c(MAFD, 36, "Clean the MAF sensor with MAF-cleaner.", "Check the live MAF value against the spec.", parts = "MAF cleaner / sensor"),
            c(VAC, 20, "Inspect the intake hose after the MAF for cracks.", parts = "Intake hose"),
            c(WIRE, 20, "Check the MAF connector for corrosion and the 5 V/ground feed.", parts = "Connector"),
            c("Dirty air filter", 12, "Replace the air filter.", parts = "Air filter"),
            c("Failed MAF sensor", 12, "Swap with a known good sensor to confirm.", parts = "MAF sensor")
        )),
        Rule(1, { inR(it, 0x105, 0x109) }, listOf(
            c("MAP sensor failure", 36, "Compare MAP at key-on engine-off (≈ atmospheric, ~100 kPa).", parts = "MAP sensor"),
            c("Cracked vacuum hose", 28, "Inspect the MAP hose and fittings.", parts = "Vacuum hose"),
            c(WIRE, 22, "Check the connector pins and the 5 V reference.", parts = "Connector"),
            c(VAC, 14, "Check the intake manifold gasket.", parts = "Gasket")
        )),
        Rule(1, { inR(it, 0x110, 0x119) || it == "P0128" }, listOf(
            c("Faulty temperature sensor", 40, "Compare the sensor reading with a real temperature.", "Check resistance against the chart for that temperature.", parts = "Temperature sensor"),
            c(WIRE, 26, "Check the connector, look for green corrosion.", parts = "Connector"),
            c("Thermostat stuck open", 24, "If the engine is slow to warm and the heater is weak, replace the thermostat.", parts = "Thermostat + coolant", why = "Engine is not reaching normal temperature",
                boost = { x -> if (x.engineOn && (x.coolant ?: 90.0) < 75) 30 else 0 }),
            c("Low coolant level", 10, "Check the reservoir when cold and top up.", parts = "Coolant")
        )),
        Rule(1, { inR(it, 0x120, 0x124) || it == "P2135" || it == "P2138" || inR(it, 0x220, 0x229) }, listOf(
            c("Throttle / pedal sensor fault", 38, "Compare TPS % in Live data while pressing the pedal slowly – it should be smooth.", parts = "Throttle body / pedal sensor", diy = false),
            c(WIRE, 26, "Check the connector and the 5 V reference/ground.", parts = "Connector"),
            c("Dirty throttle body", 24, "Clean the throttle body and relearn idle (ignition on 10 s).", parts = "Throttle-body cleaner"),
            c("ECU relearn needed", 12, "After cleaning, do the throttle adaptation / battery-disconnect reset.", diy = false)
        )),
        Rule(1, { inR(it, 0x130, 0x167) || inR(it, 0x2270, 0x2299) }, listOf(
            c("O2 sensor / heater failed", 46, "Check heater resistance (typically 3–10 Ω).", "Watch voltage response in Live data.", "Replace the sensor.", parts = "O2 sensor"),
            c(WIRE, 20, "Inspect harness near the exhaust for melted insulation.", parts = "Connector / harness"),
            c("Exhaust leak", 14, "Listen for ticking, check gasket joints.", parts = "Gasket"),
            c("Blown fuse or relay", 10, "Check the O2 heater fuse/relay.", parts = "Fuse"),
            c("Contaminated sensor", 10, "Check for silicone/oil contamination; fix the cause.", parts = "Sensor", diy = false)
        )),
        Rule(2, { inR(it, 0x335, 0x349) || inR(it, 0x16, 0x19) }, listOf(
            c("Crank/cam position sensor", 36, "Inspect the sensor and its air gap, check the signal with a scope.", parts = "Position sensor"),
            c(WIRE, 20, "Inspect the harness and the connector for oil/corrosion.", parts = "Connector"),
            c("Stretched timing chain / reluctor wheel", 20, "Check cam/crank correlation in the scan tool; listen for chain rattle on cold start.", parts = "Timing chain set", diy = false,
                why = "Cam/crank correlation code", boost = { x -> if (x.codes.any { num(it) in 0x16..0x19 }) 22 else 0 }),
            c(BATT, 12, "Check cranking voltage – should stay above 9.6 V.", parts = "Battery", why = "Low system voltage seen", boost = { x -> if ((x.volt ?: 13.0) < 12.0) 18 else 0 }),
            c("Low oil level / VVT oil pressure", 12, "Check the dipstick and the oil grade.", parts = "Engine oil")
        )),
        Rule(1, { inR(it, 0x10, 0x15) || inR(it, 0x20, 0x25) }, listOf(
            c("VVT solenoid / actuator", 34, "Check the solenoid resistance and its screen for sludge.", parts = "VVT solenoid"),
            c("Low or dirty engine oil", 30, "Check the oil level and change it if overdue.", parts = "Engine oil + filter", why = "Oil service is overdue", boost = { x -> if (x.overdue.contains("oil")) 25 else 0 }),
            c("Stretched timing chain", 20, "Check the cam correlation values.", parts = "Timing chain set", diy = false),
            c("Cam position sensor", 16, "Check the signal and connector.", parts = "Cam sensor")
        )),
        Rule(1, { inR(it, 0x505, 0x507) }, listOf(
            c("Dirty throttle body / idle valve", 36, "Clean the throttle body and relearn idle.", parts = "Throttle-body cleaner"),
            c(VAC, 26, "Spray-test the intake for leaks while idling.", parts = "Hose / gasket"),
            c("Electronic throttle fault", 16, "Check the throttle adaptation values.", diy = false),
            c(BATT, 12, "Check the charging voltage at idle.", parts = "Battery / alternator"),
            c("ECU relearn needed", 10, "Do an idle relearn after cleaning.", diy = false)
        )),
        Rule(2, { inR(it, 0x560, 0x563) || inR(it, 0x620, 0x626) }, listOf(
            c("Alternator / regulator failing", 34, "Measure battery voltage at idle and at 2500 rpm: should be 13.8–14.8 V.", "Check the belt and the alternator connector.",
                "If you fitted an LFP battery or a charge-current limiter, check that it isn't throttling the alternator.", parts = "Alternator", diy = false,
                why = "Charging voltage is outside the normal range", boost = { x -> if (x.engineOn && x.volt != null && (x.volt < 13.2 || x.volt > 15.2)) 30 else 0 }),
            c(BATT, 30, "Load-test the battery; check resting voltage (≥12.4 V).", parts = "Battery", why = "Resting voltage is low", boost = { x -> if (!x.engineOn && (x.volt ?: 13.0) < 12.3) 25 else 0 }),
            c("Loose or corroded terminals / earth", 22, "Clean and tighten battery terminals and the engine-to-body earth strap.", parts = "Terminals / earth strap"),
            c(WIRE, 14, "Check the alternator-to-battery cable and fusible link.", parts = "Cable / fuse")
        )),
        Rule(2, { it.startsWith("P06") && it != "P0627" && !inR(it, 0x691, 0x694) }, listOf(
            c("Power or earth supply to the ECU", 38, "Check ECU fuses, relay and earth points.", parts = "Fuse / earth"),
            c(BATT, 22, "Check the battery voltage.", parts = "Battery", why = "Low voltage can set ECU codes", boost = { x -> if ((x.volt ?: 13.0) < 12.0) 20 else 0 }),
            c("CAN wiring problem", 18, "Check the connectors for water ingress.", parts = "Connector"),
            c("ECU internal fault", 22, "Only after the above: reflash or replace the ECU.", diy = false, parts = "ECU")
        )),
        Rule(2, { it.startsWith("U") }, listOf(
            c(BATT, 30, "Measure voltage with all loads on – it must stay above 11.5 V.", "Low voltage causes lost-communication codes.", parts = "Battery", why = "Low voltage seen", boost = { x -> if ((x.volt ?: 13.0) < 12.0) 25 else 0 }),
            c("Loose connector or poor earth", 30, "Check the ECU, TCU and ABS connectors and earth straps.", parts = "Connectors"),
            c("Faulty module", 22, "Scan the other modules to see which one is silent.", diy = false, parts = "Module"),
            c("CAN bus wiring fault", 18, "Check CAN-H/CAN-L resistance (≈ 60 Ω across the bus).", diy = false, parts = "Harness")
        )),
        Rule(2, { inR(it, 0x700, 0x7FF) || inR(it, 0x1700, 0x17FF) }, listOf(
            c("Clutch / shift adaptation off (DCT/AMT)", 30, "Check for judder at low speed or a slipping smell.", "A dealer clutch-adaptation (relearn) often cures it.", diy = false, parts = "Adaptation / clutch pack"),
            c("Fluid low, old or wrong", 24, "Check the level/condition per the manual.", parts = "Gearbox fluid", diy = false, why = "Service is overdue", boost = { x -> if (x.overdue.contains("transmission") || x.overdue.contains("gear")) 20 else 0 }),
            c("Solenoid / valve body fault", 22, "Read module data in a brand tool.", diy = false, parts = "Valve body / mechatronic"),
            c("TCU wiring or low voltage", 14, "Check the TCU connector and battery voltage.", parts = "Connector", why = "Low voltage seen", boost = { x -> if ((x.volt ?: 13.0) < 12.0) 15 else 0 }),
            c("Speed sensor fault", 10, "Check input/output speed sensor signals.", parts = "Speed sensor", diy = false)
        )),
        Rule(1, { inR(it, 0x500, 0x503) }, listOf(
            c("Wheel / vehicle speed sensor", 38, "Compare scan-tool speed with GPS speed.", parts = "Speed sensor"),
            c(WIRE, 24, "Check the connector and harness near the wheels.", parts = "Connector"),
            c("Damaged reluctor / tone ring", 16, "Inspect the ring for broken teeth or rust.", parts = "Hub / tone ring", diy = false),
            c("TCU / ECU input fault", 22, "Test after fixing the above.", diy = false)
        )),
        Rule(2, { it == "P0087" || inR(it, 0x190, 0x194) || it == "P0091" || it == "P0092" || it == "P0089" }, listOf(
            c("Weak fuel pump", 36, "Check rail pressure against the spec at idle and under load.", parts = "Fuel pump", diy = false),
            c("Clogged fuel filter", 26, "Replace the fuel filter.", parts = "Fuel filter"),
            c("Pressure regulator / valve", 20, "Check the regulator and return line.", parts = "Regulator", diy = false),
            c("Rail pressure sensor", 18, "Compare the sensor reading with a mechanical gauge.", parts = "Pressure sensor")
        )),
        Rule(2, { inR(it, 0x230, 0x234) && it != "P0234" || it == "P0627" }, listOf(
            c("Blown fuse or relay", 36, "Check the fuel pump fuse/relay first – cheapest fix.", parts = "Fuse / relay"),
            c("Failed fuel pump", 34, "Listen for the 2 s prime on ignition; measure current.", parts = "Fuel pump", diy = false),
            c(WIRE, 30, "Check the pump connector and earth.", parts = "Connector")
        )),
        Rule(1, { inR(it, 0x400, 0x409) || inR(it, 0x2400, 0x24FF) }, listOf(
            c("Carbon-blocked EGR valve", 44, "Remove and clean the EGR valve and passages.", parts = "EGR cleaner / valve"),
            c("EGR sensor or position fault", 20, "Check the sensor's live signal.", parts = "EGR valve"),
            c("Vacuum hose / pipe leak", 16, "Inspect the EGR vacuum line and pipe joints.", parts = "Hose"),
            c(WIRE, 20, "Check the connector.", parts = "Connector")
        )),
        Rule(1, { inR(it, 0x325, 0x334) }, listOf(
            c("Knock sensor fault", 38, "Check the sensor torque (loose = false signal) and resistance.", parts = "Knock sensor"),
            c(WIRE, 24, "Check the shielded harness.", parts = "Harness"),
            c("Poor fuel / detonation", 22, "Use higher-octane fuel for a tank.", parts = "Fuel"),
            c("Carbon build-up / hot engine", 16, "Check coolant level and temperature.", diy = false, parts = "Decarb service")
        )),
        Rule(2, { inR(it, 0x480, 0x484) || inR(it, 0x691, 0x694) }, listOf(
            c("Blown fuse or relay", 34, "Check the radiator-fan fuse/relay.", parts = "Fuse / relay"),
            c("Fan motor failed", 30, "Apply 12 V directly to the fan to test.", parts = "Fan motor"),
            c(WIRE, 20, "Check the connector.", parts = "Connector"),
            c("Coolant temperature sensor", 16, "Compare readings.", parts = "ECT sensor")
        )),
        Rule(1, { it == "P0299" || it == "P0234" || it == "P0243" || it == "P0045" || it == "P0046" || it == "P0047" }, listOf(
            c("Boost leak (hose / intercooler)", 34, "Pressure-test the charge-air pipes and clamps.", parts = "Hose / clamp"),
            c("Wastegate / actuator fault", 30, "Check the actuator moves freely.", parts = "Actuator", diy = false),
            c("MAP sensor fault", 14, "Compare MAP in Live data.", parts = "MAP sensor"),
            c("Clogged air filter", 10, "Replace the air filter.", parts = "Air filter"),
            c("Worn turbo", 12, "Check shaft play and oil leaks.", diy = false, parts = "Turbo")
        )),
        Rule(2, { it.startsWith("C") }, listOf(
            c("Wheel-speed sensor fault", 38, "Compare all four wheel speeds in a brand scan tool.", parts = "ABS sensor"),
            c(WIRE, 22, "Inspect the harness near the wheels.", parts = "Connector"),
            c("Damaged tone ring", 16, "Inspect the ring.", diy = false, parts = "Hub"),
            c(BATT, 10, "Check the voltage.", parts = "Battery", why = "Low voltage seen", boost = { x -> if ((x.volt ?: 13.0) < 12.0) 18 else 0 }),
            c("ABS / ESP module", 14, "Brand tool needed.", diy = false, parts = "ABS module")
        )),
        Rule(2, { it.startsWith("B") }, listOf(
            c("Seat / airbag connector", 38, "Safety system – have a workshop check it. (Never probe airbag circuits yourself.)", diy = false, parts = "Connector"),
            c("Clock spring / sensor", 28, "Workshop check.", diy = false, parts = "Clock spring"),
            c("Occupant / crash sensor", 20, "Workshop check.", diy = false, parts = "Sensor"),
            c("Control module", 14, "Workshop check.", diy = false, parts = "Module")
        ))
    ) }

    private val fallback = listOf(
        c("Sensor or actuator in this system", 40, "Look up the code for your exact make/model.", "Inspect the related connector, fuse and earth.", parts = "Depends on the code"),
        c(WIRE, 28, "Check the wiring and connector of the related component.", parts = "Connector"),
        c(BATT, 12, "Check the battery voltage.", parts = "Battery", boost = { x -> if ((x.volt ?: 13.0) < 12.0) 25 else 0 }),
        c("Control-unit software", 20, "Ask the dealer about software updates.", diy = false, parts = "ECU update")
    )

    fun context(codes: Set<String>): Ctx {
        val m = ObdManager; val v = m.values
        fun avg(a: Int, b: Int): Double? { val x = listOfNotNull(v[a], v[b]); return if (x.isEmpty()) null else x.average() }
        val over = try { Maint.dueList(m.vehicle).filter { it.overdue || it.soon }.joinToString(" ") { it.item.name.lowercase() } } catch (_: Exception) { "" }
        return Ctx(avg(0x06, 0x08), avg(0x07, 0x09), v[0x05], m.batteryV ?: v[0x42], v[0x0C], m.engineOn, v[0x2F], m.vehicle.odoKm, over, codes)
    }

    fun analyze(codesIn: Collection<String>): List<Report> {
        val codes = codesIn.map { it.trim().uppercase() }.filter { it.length >= 5 }.distinct()
        val ctx = context(codes.toSet())
        val hist = try { Db.dtcHistory(ObdManager.vehicle.id) } catch (_: Exception) { emptyList() }
        return codes.map { code ->
            val rule = rules.firstOrNull { it.match(code) }
            val list = rule?.causes ?: fallback
            ctx.repeats = hist.count { it.code == code }
            val scored = list.map { cz ->
                val b = cz.boost(ctx)
                (cz to (cz.prior + b + if (ctx.repeats >= 3 && b > 0) 4 else 0)) to b
            }
            val total = scored.sumOf { it.first.second }.toDouble()
            var letter = 'A'
            val causes = scored.sortedByDescending { it.first.second }.map { (p, b) ->
                Cause(p.first.name, (p.second / total * 100).toInt().coerceIn(1, 99), p.first.steps, p.first.parts, p.first.diy,
                    "RC-$code-${letter++}", if (b > 0 && p.first.why.isNotEmpty()) p.first.why else null)
            }
            val urg = (rule?.urgency ?: 1).let { u ->
                if (code == "P0300" && ctx.engineOn && (ctx.rpm ?: 0.0) > 0 && ctx.has("P030")) maxOf(u, 2) else u
            }
            Report(code, DtcDb.describe(code), urg, causes, ctx.repeats)
        }.sortedByDescending { it.urgency }
    }

    /** One cause that explains several codes at once. */
    fun rootCause(reports: List<Report>): String? {
        if (reports.size < 2) return null
        val tally = HashMap<String, Pair<Int, Int>>()
        for (r in reports) for (c in r.causes.take(2)) { val (n, s) = tally[c.name] ?: (0 to 0); tally[c.name] = (n + 1) to (s + c.prob) }
        val best = tally.filter { it.value.first >= 2 }.maxByOrNull { it.value.second } ?: return null
        return "${best.key} – could explain ${best.value.first} of your ${reports.size} codes"
    }

    fun toText(reports: List<Report>): String = buildString {
        append("AI diagnosis – ${ObdManager.vehicle.title}\n")
        rootCause(reports)?.let { append("Most likely common root cause: $it\n") }
        for (r in reports) {
            append("\n${r.code} – ${r.desc}\nRoot-cause code ${r.rc} · ${r.urgencyText}\n")
            r.causes.take(4).forEach { c ->
                append("  ${c.rc}  ${c.name} (${c.prob}%)\n")
                c.why?.let { append("     why: $it\n") }
                c.steps.forEachIndexed { i, s -> append("     ${i + 1}. $s\n") }
                if (c.parts.isNotEmpty()) append("     parts: ${c.parts} · ${if (c.diy) "DIY possible" else "workshop"}\n")
            }
        }
        append("\nGenerated by the on-device expert engine. A guide, not a guarantee – confirm with tests before replacing parts.")
    }

    /* ======================= Live status / alerts ======================= */

    class Alert(val sev: Int, val ic: Ic, val zone: String, val title: String, val detail: String, val page: String)
    class Status(val overall: Int, val headline: String, val alerts: List<Alert>, val zones: Map<String, Int>)

    fun status(): Status {
        val m = ObdManager; val v = m.values; val veh = m.vehicle
        val alerts = ArrayList<Alert>()
        try {
            for (it in HealthScore.compute().items) {
                if (it.status < 1) continue
                val (zone, page) = when (it.name) {
                    "Cooling" -> "Cooling" to "Health"; "Battery" -> "Battery" to "Battery"; "Fuel" -> "Fuel" to "Health"
                    "Catalyst", "Intake air" -> "Engine" to "Health"; "Service" -> "Service" to "Maint"; "Fault codes" -> "Codes" to "Diag"
                    else -> continue
                }
                val act = when (it.name) {
                    "Cooling" -> "Stop safely and let the engine cool; check coolant level."
                    "Battery" -> "Open Battery for a test and trend."
                    "Fuel" -> if (veh.isEv) "Plan charging soon." else "Refuel soon – Navigate can find a pump."
                    "Service" -> "Open Maintenance to log or schedule."
                    "Fault codes" -> "Run AI diagnosis for root causes."
                    else -> "Check Car health."
                }
                alerts += Alert(it.status, it.ic, zone, "${it.name}: ${it.detail}", act, page)
            }
        } catch (_: Exception) {
        }
        // fuel-trim anomaly detection
        val lt = listOfNotNull(v[0x07], v[0x09]).let { if (it.isEmpty()) null else it.average() }
        if (m.state == ObdManager.State.CONNECTED && lt != null && abs(lt) > 10)
            alerts += Alert(if (abs(lt) > 20) 2 else 1, Ic.ENGINE, "Engine", "Long-term fuel trim %+.0f %%".format(lt),
                if (lt > 0) "Engine adds extra fuel – likely an air leak or weak fuel delivery. Run AI diagnosis." else "Engine is cutting fuel – possible leaking injector or dirty air filter.", "Diag")
        // recent fault codes (7 days)
        try {
            val since = System.currentTimeMillis() - 7 * 86_400_000L
            val codes = Db.dtcHistory(veh.id).filter { it.t > since }.map { it.code }.distinct()
            for (cd in codes.take(3)) alerts += Alert(2, Ic.WARN, "Codes", "$cd – ${DtcDb.describe(cd)}", "Tap for AI root-cause analysis and repair steps.", "Diag")
            if (codes.size > 3) alerts += Alert(2, Ic.WARN, "Codes", "${codes.size - 3} more fault codes", "Open Diagnose for the full AI analysis.", "Diag")
        } catch (_: Exception) {
        }
        Weather.last?.let { w ->
            if (w.code in 45..99 || w.temp >= 38) { val (ic, t) = Weather.tip(w); alerts += Alert(1, ic, "", "Weather: ${Weather.desc(w.code)}", t, "Nav") }
        }
        val sorted = alerts.distinctBy { it.title }.sortedByDescending { it.sev }
        val zones = HashMap<String, Int>()
        for (a in sorted) if (a.zone.isNotEmpty()) zones[a.zone] = maxOf(zones[a.zone] ?: 0, a.sev)
        val worst = sorted.maxOfOrNull { it.sev } ?: 0
        val headline = when {
            worst >= 2 -> "${sorted.count { it.sev >= 2 }} alert(s) need attention"
            worst == 1 -> "${sorted.size} thing(s) to watch"
            m.state != ObdManager.State.CONNECTED -> "AI watching · connect for live checks"
            else -> "All systems healthy"
        }
        return Status(worst, headline, sorted, zones)
    }

    /** Short trend insights from trip history. */
    fun insights(): List<Pair<Ic, String>> {
        val out = ArrayList<Pair<Ic, String>>()
        try {
            val vid = ObdManager.vehicle.id; val now = System.currentTimeMillis()
            val wk = Db.trips(vid, now - 7 * 86_400_000L); val mo = Db.trips(vid, now - 30 * 86_400_000L)
            fun eco(l: List<Trip>): Double? { val f = l.sumOf { it.fuel }; return if (f > 1) l.sumOf { it.distanceKm } / f else null }
            val a = eco(wk); val b = eco(mo)
            if (a != null && b != null && b > 0) {
                val d = (a / b - 1) * 100
                if (abs(d) >= 4) out += (if (d > 0) Ic.LEAF else Ic.FUEL) to "This week's economy is %.0f %% %s than your 30-day average.".format(abs(d), if (d > 0) "better" else "worse")
            }
            val idle = wk.sumOf { it.idleS }; val dur = wk.sumOf { it.durationS }
            if (dur > 3600 && idle / dur > 0.2) out += Ic.TIMER to "You idled %.0f %% of drive time this week – switching off at long stops saves fuel.".format(idle / dur * 100)
            val harsh = wk.sumOf { it.hardBrake + it.suddenBrake + it.rapidAcc }
            if (wk.isNotEmpty() && harsh / maxOf(1.0, wk.sumOf { it.distanceKm }) * 100 > 6) out += Ic.USER to "Harsh braking/acceleration is high this week – smoother driving saves fuel and brakes."
        } catch (_: Exception) {
        }
        return out
    }
}
