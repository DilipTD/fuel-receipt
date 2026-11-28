package com.dilip.carobd

/**
 * Brand knowledge for the service-reset page: the VIN's first three characters (WMI) tell the make, and every make has its own usual reset procedure.
 * The steps are the TYPICAL ones for the make; the model year changes details, so the page always says to check the owner's manual.
 * Private ECU reset commands are NOT built in (they are not public and a wrong write can harm a module); they are saved per vehicle (linked to its VIN) or per make.
 */
object ServiceBrands {
    private val wmi = mapOf(
        // Germany
        "WVW" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen", "3VW" to "Volkswagen", "9BW" to "Volkswagen", "AAV" to "Volkswagen",
        "WAU" to "Audi", "WA1" to "Audi", "TRU" to "Audi", "TMB" to "Skoda", "VSS" to "Seat", "WP0" to "Porsche", "WP1" to "Porsche",
        "WBA" to "BMW", "WBS" to "BMW", "WBY" to "BMW", "4US" to "BMW", "5UX" to "BMW", "WMW" to "Mini",
        "WDB" to "Mercedes-Benz", "WDC" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "WDF" to "Mercedes-Benz", "W1K" to "Mercedes-Benz", "W1N" to "Mercedes-Benz", "4JG" to "Mercedes-Benz", "WME" to "Smart",
        "W0L" to "Opel", "W0V" to "Opel", "WF0" to "Ford", "WUA" to "Audi",
        // Japan
        "JTD" to "Toyota", "JTE" to "Toyota", "JTM" to "Toyota", "JT2" to "Toyota", "JT3" to "Toyota", "MR0" to "Toyota", "MHF" to "Toyota", "2T1" to "Toyota", "4T1" to "Toyota", "5TD" to "Toyota", "JTH" to "Lexus", "JTJ" to "Lexus",
        "JHM" to "Honda", "JHL" to "Honda", "MRH" to "Honda", "1HG" to "Honda", "2HG" to "Honda", "19U" to "Acura", "JH4" to "Acura",
        "JN1" to "Nissan", "JN8" to "Nissan", "MNT" to "Nissan", "1N4" to "Nissan", "3N1" to "Nissan", "JNK" to "Infiniti",
        "JMZ" to "Mazda", "JM1" to "Mazda", "JF1" to "Subaru", "JF2" to "Subaru", "4S3" to "Subaru", "JA3" to "Mitsubishi", "JA4" to "Mitsubishi", "MMB" to "Mitsubishi",
        "JS1" to "Suzuki", "JS2" to "Suzuki", "JS3" to "Suzuki", "MA3" to "Suzuki", "MBH" to "Suzuki", "TSM" to "Suzuki", "JAA" to "Isuzu", "MPA" to "Isuzu",
        // Korea
        "KMH" to "Hyundai", "KMC" to "Hyundai", "5NP" to "Hyundai", "MALA" to "Hyundai", "MAL" to "Hyundai", "KNA" to "Kia", "KND" to "Kia", "KNM" to "Kia", "U5Y" to "Kia", "MZB" to "Kia", "KMT" to "Genesis", "KPT" to "SsangYong",
        // USA
        "1FA" to "Ford", "1FT" to "Ford", "1FM" to "Ford", "3FA" to "Ford", "MAJ" to "Ford", "1G1" to "Chevrolet", "1GC" to "Chevrolet", "2G1" to "Chevrolet", "3G1" to "Chevrolet", "1G6" to "Cadillac", "1GY" to "Cadillac",
        "1C4" to "Jeep", "1J4" to "Jeep", "1B3" to "Dodge", "2C3" to "Chrysler", "5YJ" to "Tesla", "7SA" to "Tesla", "LRW" to "Tesla",
        // France / Italy / Spain / UK / Sweden / Czech
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroen", "VR1" to "DS", "UU1" to "Dacia", "VNK" to "Toyota",
        "ZFA" to "Fiat", "ZAR" to "Alfa Romeo", "ZFF" to "Ferrari", "ZHW" to "Lamborghini", "ZAM" to "Maserati",
        "SAL" to "Land Rover", "SAJ" to "Jaguar", "SAR" to "Rover", "SCC" to "Lotus", "SCF" to "Aston Martin", "SBM" to "McLaren", "SCB" to "Bentley",
        "YV1" to "Volvo", "YV4" to "Volvo", "YS3" to "Saab",
        // India
        "MA1" to "Mahindra", "MA6" to "Chevrolet", "MBL" to "Hero", "MC2" to "Eicher", "MCA" to "MG", "MEE" to "Renault", "MDH" to "Nissan", "MD2" to "Bajaj",
        // China
        "LSV" to "SAIC", "LGB" to "Dongfeng", "LFV" to "Volkswagen", "LVS" to "Ford", "LHG" to "Honda", "LDC" to "Peugeot", "LB3" to "Geely", "L6T" to "Geely", "LJD" to "Kia", "LBV" to "BMW", "LMG" to "GAC", "LNB" to "BAIC", "LC0" to "BYD", "LGX" to "BYD",
    )

    /** Make name from the VIN (WMI), or the make typed in the garage, or "". */
    fun makeOf(vin: String, typed: String = ""): String {
        val v = vin.trim().uppercase()
        if (v.length >= 3) { wmi[v.substring(0, 3)]?.let { return it } }
        return typed.trim()
    }

    /** Group of makes that share a procedure. */
    private fun family(make: String): String = when (make.lowercase()) {
        "volkswagen", "audi", "skoda", "seat", "cupra", "porsche", "bentley", "lamborghini" -> "vag"
        "bmw", "mini" -> "bmw"
        "mercedes-benz", "mercedes", "smart" -> "mb"
        "toyota", "lexus", "daihatsu" -> "toyota"
        "honda", "acura" -> "honda"
        "hyundai", "kia", "genesis", "ssangyong" -> "hk"
        "ford", "lincoln" -> "ford"
        "chevrolet", "gmc", "cadillac", "buick", "holden" -> "gm"
        "nissan", "infiniti", "datsun" -> "nissan"
        "suzuki", "maruti", "maruti suzuki" -> "suzuki"
        "mahindra" -> "indian"
        "renault", "dacia", "peugeot", "citroen", "ds", "opel", "vauxhall", "fiat", "alfa romeo", "jeep", "chrysler", "dodge" -> "euro"
        "volvo", "polestar" -> "volvo"
        "land rover", "jaguar", "mg", "rover" -> "uk"
        "mazda", "subaru", "mitsubishi", "isuzu" -> "jp"
        "tesla" -> "tesla"
        else -> ""
    }

    /** Typical reset steps for one reset type on this make; falls back to the general text. */
    fun steps(make: String, type: String, general: String): String {
        val f = family(make)
        val head = if (make.isNotBlank()) "Typical for $make (details change with model and year – check the owner's manual).\n\n" else ""
        val body = when (type) {
            "Engine oil service", "General service interval" -> when (f) {
                "vag" -> "Newer: instrument cluster menu → Service / Settings → Service interval → Reset (ignition ON). Older: ignition OFF, hold the trip reset button, switch ignition ON, release; confirm “Service interval reset”. Some models need a diagnostic tool (service reminder adaptation)."
                "bmw" -> "Ignition ON (engine off). Press and hold the trip-reset knob in the instrument cluster until the service menu shows; press again to choose the item and hold to reset. iDrive cars: Vehicle info → Vehicle status → Service required → Reset."
                "mb" -> "ASSYST Plus: steering-wheel menu → Service → ASSYST PLUS → Reset (confirm with OK). Older cars: ignition OFF, hold the trip reset, ignition ON, keep holding until the service display resets."
                "toyota" -> "Older: ignition ON (engine off), hold the trip-meter reset button, switch ignition OFF, keep holding, ignition ON until the maintenance light blinks and goes out. Newer: Settings → Vehicle → Maintenance → Reset."
                "honda" -> "Newer: Settings → Vehicle → Maintenance info → select the item → Reset. Older: use SEL/RESET on the steering wheel or dash with ignition ON; hold until the oil-life figure resets to 100%."
                "hk" -> "Cluster menu: User settings → Service interval → Reset (ignition ON). Some models: hold the trip button while switching ignition ON."
                "ford" -> "Cluster menu: Settings → Oil life → Reset. Older: ignition ON (engine off), press the accelerator fully 3 times within 10 seconds, then switch OFF."
                "gm" -> "Driver information centre: Vehicle information → Oil life → press SET/CLEAR to reset. Older: ignition ON, press the accelerator 3 times within 5 seconds."
                "nissan" -> "Cluster or screen: Settings → Maintenance → Oil/Filter → Reset. Older: hold the trip button while switching ignition ON."
                "suzuki" -> "Ignition OFF, hold the trip button, switch ignition ON and keep holding until the service reminder shows 0 / resets. Newer cars: cluster Settings → Service reminder → Reset."
                "indian" -> "Engine stopped, ignition ON. Newer clusters: Settings screen with UP/DOWN and SET → Service reminder → Reset: Yes → SET. Older clusters: hold the SET/reset button about 10 seconds until the service kilometres flash, release, then press again until the value resets."
                "euro" -> "Ignition OFF, hold the trip reset button, switch ignition ON and keep holding until the service display shows dashes then resets. Newer cars: menu → Service / Maintenance → Reset."
                "volvo" -> "Ignition ON. Use the trip button/steering-wheel menu: Settings → Car settings → Service → Reset. Older: hold the trip button while switching ignition ON."
                "uk" -> "Ignition ON. Cluster menu: Settings → Service → Reset, or hold the trip button while switching ignition ON."
                "jp" -> "Ignition OFF, hold the trip button, switch ignition ON and keep holding until the maintenance indicator resets. Newer: cluster menu → Maintenance → Reset."
                "tesla" -> "Not needed: the car shows service messages on the touchscreen and schedules service itself."
                else -> general
            }
            "Brake pad / brake service" -> when (f) {
                "vag", "bmw", "mb", "volvo", "uk", "ford", "gm", "hk", "euro" -> "Cars with an electronic parking brake: put the rear brakes into service/maintenance mode first (cluster menu or parking-brake switch sequence), change the pads, then close service mode. The pad-wear warning clears after the pad sensor is replaced; some makes need a scan tool to reset the wear counter."
                else -> general
            }
            "Battery registration (BMS)" -> when (f) {
                "bmw", "vag", "mb", "volvo", "uk", "ford" -> "After a new 12 V battery these cars need it registered (type, capacity, age) with a scan tool, otherwise charging can be wrong. Many also relearn partly on their own after several drives."
                else -> general
            }
            "Tyre pressure (TPMS) relearn" -> when (f) {
                "vag", "bmw", "mb", "toyota", "honda", "ford", "gm", "nissan", "hk", "euro" -> "Set the tyres to the placard pressure, then use the cluster or screen menu: Tyre pressure / TPMS → Store or Reset (ignition ON). Direct-TPMS cars may also need a short drive above 25 km/h."
                else -> general
            }
            else -> general
        }
        return head + body
    }
}
