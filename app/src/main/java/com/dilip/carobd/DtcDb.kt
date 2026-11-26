package com.dilip.carobd

object DtcDb {
    fun decode(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 3]
        val d1 = (a shr 4) and 3
        val d2 = a and 0x0F
        return "%c%d%X%02X".format(letter, d1, d2, b)
    }

    fun describe(code: String): String = codes[code] ?: category(code)

    /** Likely-cause hints for common codes. */
    fun hint(code: String): String? = hints[code] ?: when {
        code.startsWith("P030") -> "Check spark plug, ignition coil and injector of that cylinder; swap coil to see if the misfire follows."
        code.startsWith("P07") || code.startsWith("P08") || code.startsWith("P09") || code.startsWith("P17") ->
            "Transmission fault. Check gearbox fluid level & condition, clutch/shift adaptation (relearn at the dealer), and TCU connectors."
        code.startsWith("U") -> "CAN communication fault. Check battery voltage, ground straps and module connectors."
        else -> null
    }

    private fun category(code: String): String {
        if (code.length < 5) return "Unknown"
        val sys = code[0]
        val generic = code[1] == '0' || code[1] == '2'
        val n = code.substring(1, 3)
        val base = when (sys) {
            'P' -> when {
                n == "00" || n == "01" || n == "02" || n == "21" || n == "22" -> "Fuel & air metering"
                n == "03" || n == "23" -> "Ignition system / misfire"
                n == "04" || n == "24" -> "Auxiliary emission controls"
                n == "05" || n == "25" -> "Vehicle speed, idle control & auxiliary inputs"
                n == "06" || n == "26" -> "ECU / output circuits"
                n == "07" || n == "08" || n == "09" || n == "27" || n == "28" -> "Transmission"
                n == "0A" -> "Hybrid propulsion"
                n == "16" -> "Manufacturer: ECU / output circuits"
                n == "17" || n == "18" || n == "19" -> "Manufacturer: transmission"
                n.startsWith("1") -> "Manufacturer-specific powertrain"
                n.startsWith("3") -> "Powertrain (reserved / manufacturer)"
                else -> "Powertrain"
            }
            'C' -> "Chassis (ABS / ESP / steering)"
            'B' -> "Body (airbag, BCM, comfort)"
            'U' -> "Network / CAN communication"
            else -> "Unknown"
        }
        return if (generic) base else "$base (manufacturer-specific – check the brand service manual)"
    }

    private val hints = mapOf(
        "P0171" to "Lean: check for vacuum/boost leaks, PCV hose, weak fuel pump, dirty injectors, MAP/MAF sensor.",
        "P0172" to "Rich: check leaking injector, high fuel pressure, purge valve stuck open, faulty O2 sensor.",
        "P0300" to "Random misfire: check plugs, coils, fuel quality, vacuum leaks, low fuel pressure.",
        "P0420" to "Catalyst efficiency low: check for exhaust leaks and rear O2 sensor before replacing catalyst.",
        "P0128" to "Coolant warms up too slowly: thermostat stuck open is the usual cause.",
        "P0562" to "Low system voltage: check battery, alternator, belt and ground connections.",
        "P0563" to "High system voltage: check alternator regulator and battery connections.",
        "P0700" to "TCU has stored its own codes — run 'Deep scan (UDS)' to read the gearbox codes.",
        "P0234" to "Overboost: check wastegate actuator/solenoid and boost hoses.",
        "P0299" to "Underboost: check intercooler hoses for leaks, wastegate stuck open, turbo.",
        "P0016" to "Cam/crank correlation: timing chain stretch, VVT solenoid or oil level/quality.",
        "P0011" to "Intake cam over-advanced: check oil level, VVT solenoid and its filter screen.",
        "P0441" to "Evap purge flow: check purge solenoid and hoses.",
        "P0455" to "Large evap leak: check fuel cap seal first.",
        "P0456" to "Small evap leak: check fuel cap and evap hoses.",
        "P0335" to "No crank signal: check crank sensor, wiring and reluctor ring.",
        "P0101" to "Air-flow range: check air-filter, intake leaks and MAF/MAP sensor.",
        "P0130" to "O2 sensor circuit: check sensor heater fuse, wiring and the sensor."
    )

    private val codes: Map<String, String> = mapOf(
        // Fuel & air metering
        "P0010" to "Intake camshaft actuator circuit (Bank 1)",
        "P0011" to "Intake camshaft timing over-advanced (Bank 1)",
        "P0012" to "Intake camshaft timing over-retarded (Bank 1)",
        "P0013" to "Exhaust camshaft actuator circuit (Bank 1)",
        "P0014" to "Exhaust camshaft timing over-advanced (Bank 1)",
        "P0016" to "Crankshaft/camshaft position correlation (Bank 1 Sensor A)",
        "P0017" to "Crankshaft/camshaft position correlation (Bank 1 Sensor B)",
        "P0030" to "O2 sensor heater control circuit (B1S1)",
        "P0036" to "O2 sensor heater control circuit (B1S2)",
        "P0068" to "MAP/MAF – throttle position correlation",
        "P0087" to "Fuel rail/system pressure too low",
        "P0088" to "Fuel rail/system pressure too high",
        "P0100" to "Mass air flow circuit malfunction",
        "P0101" to "Mass air flow circuit range/performance",
        "P0102" to "Mass air flow circuit low input",
        "P0103" to "Mass air flow circuit high input",
        "P0105" to "MAP/barometric pressure circuit malfunction",
        "P0106" to "MAP/barometric pressure circuit range/performance",
        "P0107" to "MAP/barometric pressure circuit low input",
        "P0108" to "MAP/barometric pressure circuit high input",
        "P0110" to "Intake air temperature circuit malfunction",
        "P0112" to "Intake air temperature circuit low input",
        "P0113" to "Intake air temperature circuit high input",
        "P0115" to "Engine coolant temperature circuit malfunction",
        "P0116" to "Engine coolant temperature circuit range/performance",
        "P0117" to "Engine coolant temperature circuit low input",
        "P0118" to "Engine coolant temperature circuit high input",
        "P0120" to "Throttle/pedal position sensor A circuit malfunction",
        "P0121" to "Throttle/pedal position sensor A range/performance",
        "P0122" to "Throttle/pedal position sensor A circuit low",
        "P0123" to "Throttle/pedal position sensor A circuit high",
        "P0125" to "Insufficient coolant temperature for closed-loop fuel control",
        "P0128" to "Coolant thermostat (coolant temp below regulating temperature)",
        "P0130" to "O2 sensor circuit malfunction (B1S1)",
        "P0131" to "O2 sensor circuit low voltage (B1S1)",
        "P0132" to "O2 sensor circuit high voltage (B1S1)",
        "P0133" to "O2 sensor circuit slow response (B1S1)",
        "P0134" to "O2 sensor circuit no activity detected (B1S1)",
        "P0135" to "O2 sensor heater circuit malfunction (B1S1)",
        "P0136" to "O2 sensor circuit malfunction (B1S2)",
        "P0137" to "O2 sensor circuit low voltage (B1S2)",
        "P0138" to "O2 sensor circuit high voltage (B1S2)",
        "P0140" to "O2 sensor circuit no activity detected (B1S2)",
        "P0141" to "O2 sensor heater circuit malfunction (B1S2)",
        "P0171" to "System too lean (Bank 1)",
        "P0172" to "System too rich (Bank 1)",
        "P0190" to "Fuel rail pressure sensor circuit malfunction",
        "P0191" to "Fuel rail pressure sensor range/performance",
        "P0201" to "Injector circuit malfunction – cylinder 1",
        "P0202" to "Injector circuit malfunction – cylinder 2",
        "P0203" to "Injector circuit malfunction – cylinder 3",
        "P0217" to "Engine over-temperature condition",
        "P0219" to "Engine over-speed condition",
        "P0221" to "Throttle/pedal position sensor B range/performance",
        "P0222" to "Throttle/pedal position sensor B circuit low",
        "P0223" to "Throttle/pedal position sensor B circuit high",
        "P0230" to "Fuel pump primary circuit malfunction",
        "P0234" to "Turbocharger overboost condition",
        "P0236" to "Turbo boost sensor A range/performance",
        "P0237" to "Turbo boost sensor A circuit low",
        "P0238" to "Turbo boost sensor A circuit high",
        "P0243" to "Turbo wastegate solenoid A malfunction",
        "P0245" to "Turbo wastegate solenoid A low",
        "P0246" to "Turbo wastegate solenoid A high",
        "P0261" to "Cylinder 1 injector circuit low",
        "P0262" to "Cylinder 1 injector circuit high",
        "P0264" to "Cylinder 2 injector circuit low",
        "P0265" to "Cylinder 2 injector circuit high",
        "P0267" to "Cylinder 3 injector circuit low",
        "P0268" to "Cylinder 3 injector circuit high",
        "P0299" to "Turbocharger underboost condition",
        // Ignition / misfire
        "P0300" to "Random/multiple cylinder misfire detected",
        "P0301" to "Cylinder 1 misfire detected",
        "P0302" to "Cylinder 2 misfire detected",
        "P0303" to "Cylinder 3 misfire detected",
        "P0313" to "Misfire detected with low fuel",
        "P0325" to "Knock sensor 1 circuit malfunction",
        "P0326" to "Knock sensor 1 circuit range/performance",
        "P0327" to "Knock sensor 1 circuit low input",
        "P0328" to "Knock sensor 1 circuit high input",
        "P0335" to "Crankshaft position sensor A circuit malfunction",
        "P0336" to "Crankshaft position sensor A range/performance",
        "P0340" to "Camshaft position sensor A circuit malfunction (Bank 1)",
        "P0341" to "Camshaft position sensor A range/performance (Bank 1)",
        "P0351" to "Ignition coil A primary/secondary circuit",
        "P0352" to "Ignition coil B primary/secondary circuit",
        "P0353" to "Ignition coil C primary/secondary circuit",
        // Emission controls
        "P0400" to "EGR flow malfunction",
        "P0420" to "Catalyst system efficiency below threshold (Bank 1)",
        "P0421" to "Warm-up catalyst efficiency below threshold (Bank 1)",
        "P0440" to "Evaporative emission system malfunction",
        "P0441" to "Evaporative emission system incorrect purge flow",
        "P0442" to "Evaporative emission system leak detected (small)",
        "P0443" to "Evaporative emission purge control valve circuit",
        "P0444" to "Evaporative emission purge valve circuit open",
        "P0445" to "Evaporative emission purge valve circuit shorted",
        "P0455" to "Evaporative emission system leak detected (large)",
        "P0456" to "Evaporative emission system leak detected (very small)",
        "P0460" to "Fuel level sensor circuit malfunction",
        "P0461" to "Fuel level sensor circuit range/performance",
        "P0462" to "Fuel level sensor circuit low input",
        "P0463" to "Fuel level sensor circuit high input",
        "P0480" to "Cooling fan 1 control circuit malfunction",
        "P0481" to "Cooling fan 2 control circuit malfunction",
        // Speed / idle / inputs
        "P0500" to "Vehicle speed sensor malfunction",
        "P0501" to "Vehicle speed sensor range/performance",
        "P0505" to "Idle control system malfunction",
        "P0506" to "Idle control system RPM lower than expected",
        "P0507" to "Idle control system RPM higher than expected",
        "P0520" to "Engine oil pressure sensor/switch circuit",
        "P0524" to "Engine oil pressure too low",
        "P0530" to "A/C refrigerant pressure sensor circuit",
        "P0560" to "System voltage malfunction",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0571" to "Brake switch A circuit malfunction",
        "P0599" to "Thermostat heater control circuit high",
        // ECU / outputs
        "P0600" to "Serial communication link malfunction",
        "P0601" to "Internal control module memory checksum error",
        "P0602" to "Control module programming error",
        "P0603" to "Internal control module keep-alive memory (KAM) error",
        "P0604" to "Internal control module RAM error",
        "P0605" to "Internal control module ROM error",
        "P0606" to "ECM/PCM processor fault",
        "P0607" to "Control module performance",
        "P0627" to "Fuel pump control circuit open",
        "P0638" to "Throttle actuator control range/performance (Bank 1)",
        "P0641" to "Sensor reference voltage A circuit open",
        "P0650" to "Malfunction indicator lamp (MIL) control circuit",
        "P0685" to "ECM/PCM power relay control circuit open",
        // Transmission / DCT
        "P0700" to "Transmission control system malfunction (TCU requests MIL)",
        "P0701" to "Transmission control system range/performance",
        "P0702" to "Transmission control system electrical",
        "P0705" to "Transmission range sensor (PRNDL) circuit malfunction",
        "P0706" to "Transmission range sensor circuit range/performance",
        "P0710" to "Transmission fluid temperature sensor circuit",
        "P0711" to "Transmission fluid temperature sensor range/performance",
        "P0715" to "Input/turbine speed sensor A circuit",
        "P0716" to "Input/turbine speed sensor A range/performance",
        "P0717" to "Input/turbine speed sensor A no signal",
        "P0720" to "Output speed sensor circuit malfunction",
        "P0721" to "Output speed sensor range/performance",
        "P0722" to "Output speed sensor no signal",
        "P0729" to "Gear 6 incorrect ratio",
        "P0730" to "Incorrect gear ratio",
        "P0731" to "Gear 1 incorrect ratio",
        "P0732" to "Gear 2 incorrect ratio",
        "P0733" to "Gear 3 incorrect ratio",
        "P0734" to "Gear 4 incorrect ratio",
        "P0735" to "Gear 5 incorrect ratio",
        "P0736" to "Reverse incorrect ratio",
        "P0740" to "Torque converter / clutch circuit malfunction",
        "P0750" to "Shift solenoid A malfunction",
        "P0755" to "Shift solenoid B malfunction",
        "P0760" to "Shift solenoid C malfunction",
        "P0765" to "Shift solenoid D malfunction",
        "P0770" to "Shift solenoid E malfunction",
        "P0776" to "Pressure control solenoid B performance / stuck off",
        "P0777" to "Pressure control solenoid B stuck on",
        "P0780" to "Shift malfunction",
        "P0795" to "Pressure control solenoid C malfunction",
        "P0805" to "Clutch position sensor circuit",
        "P0806" to "Clutch position sensor circuit range/performance",
        "P0810" to "Clutch position control error",
        "P0811" to "Excessive clutch slippage",
        "P0815" to "Upshift switch circuit",
        "P0816" to "Downshift switch circuit",
        "P0868" to "Transmission fluid pressure low",
        "P0869" to "Transmission fluid pressure high",
        "P0882" to "TCM power input signal low",
        "P0883" to "TCM power input signal high",
        "P0897" to "Transmission fluid deteriorated",
        "P0900" to "Clutch actuator circuit / open",
        "P0901" to "Clutch actuator circuit range/performance",
        "P0902" to "Clutch actuator circuit low",
        "P0903" to "Clutch actuator circuit high",
        "P0904" to "Transmission gate select position circuit",
        "P0914" to "Gear shift position circuit",
        "P0915" to "Gear shift position circuit range/performance",
        "P0919" to "Gear shift position control error",
        "P0920" to "Gear shift forward actuator circuit",
        "P0930" to "Gear shift lock solenoid circuit",
        "P0960" to "Pressure control solenoid A control circuit open",
        "P0962" to "Pressure control solenoid A control circuit low",
        "P0963" to "Pressure control solenoid A control circuit high",
        "P0966" to "Pressure control solenoid B control circuit low",
        "P0967" to "Pressure control solenoid B control circuit high",
        "P2700" to "Transmission friction element A apply time range/performance",
        "P2701" to "Transmission friction element B apply time range/performance",
        "P2711" to "Unexpected mechanical gear disengagement",
        "P2714" to "Pressure control solenoid D performance / stuck off",
        "P2723" to "Pressure control solenoid E performance / stuck off",
        "P2769" to "Torque converter clutch circuit low",
        "P2783" to "Torque converter temperature too high",
        "P2787" to "Clutch temperature too high",
        "P2788" to "Auto shift manual adaptive learning at limit",
        "P2789" to "Clutch adaptive learning at limit",
        "P2790" to "Clutch pressure engagement adaptive learning at limit",
        "P2793" to "Gear shift direction circuit",
        // Generic 2xxx
        "P2096" to "Post-catalyst fuel trim system too lean (Bank 1)",
        "P2097" to "Post-catalyst fuel trim system too rich (Bank 1)",
        "P2101" to "Throttle actuator control motor circuit range/performance",
        "P2106" to "Throttle actuator control system – forced limited power",
        "P2111" to "Throttle actuator control system stuck open",
        "P2112" to "Throttle actuator control system stuck closed",
        "P2122" to "Pedal position sensor D circuit low",
        "P2127" to "Pedal position sensor E circuit low",
        "P2135" to "Throttle/pedal position sensor A/B voltage correlation",
        "P2138" to "Pedal position sensor D/E voltage correlation",
        "P2176" to "Throttle actuator control system – idle position not learned",
        "P2187" to "System too lean at idle (Bank 1)",
        "P2188" to "System too rich at idle (Bank 1)",
        "P2195" to "O2 sensor signal stuck lean (B1S1)",
        "P2196" to "O2 sensor signal stuck rich (B1S1)",
        "P2261" to "Turbo bypass valve mechanical",
        "P2263" to "Turbo boost system performance",
        "P2270" to "O2 sensor signal stuck lean (B1S2)",
        "P2271" to "O2 sensor signal stuck rich (B1S2)",
        "P2279" to "Intake air system leak",
        "P2300" to "Ignition coil A primary control circuit low",
        "P2302" to "Ignition coil B primary control circuit low",
        "P2304" to "Ignition coil C primary control circuit low",
        // Chassis / body / network
        "C0035" to "Left front wheel speed sensor circuit",
        "C0040" to "Right front wheel speed sensor circuit",
        "C0045" to "Left rear wheel speed sensor circuit",
        "C0050" to "Right rear wheel speed sensor circuit",
        "B1000" to "ECU malfunction (body)",
        "U0001" to "High speed CAN communication bus",
        "U0073" to "Control module communication bus A off",
        "U0100" to "Lost communication with ECM/PCM",
        "U0101" to "Lost communication with TCM (transmission)",
        "U0121" to "Lost communication with ABS control module",
        "U0122" to "Lost communication with vehicle dynamics (ESP) module",
        "U0126" to "Lost communication with steering angle sensor",
        "U0140" to "Lost communication with body control module (BCM)",
        "U0151" to "Lost communication with airbag (SRS) module",
        "U0155" to "Lost communication with instrument cluster",
        "U0164" to "Lost communication with HVAC module",
        "U0401" to "Invalid data received from ECM/PCM",
        "U0402" to "Invalid data received from TCM",
        "U0415" to "Invalid data received from ABS module"
    )
}
