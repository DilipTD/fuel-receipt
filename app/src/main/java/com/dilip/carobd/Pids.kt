package com.dilip.carobd

class PidDef(
    val pid: Int,
    val name: String,
    val unit: String,
    val decimals: Int = 1,
    val decode: ((IntArray) -> Double)? = null,
    val text: ((IntArray) -> String)? = null
)

object Pids {
    private fun ab(d: IntArray) = d[0] * 256.0 + d[1]
    private fun pct(d: IntArray) = d[0] * 100.0 / 255.0
    private fun trim(d: IntArray) = (d[0] - 128) * 100.0 / 128.0
    private fun temp(d: IntArray) = d[0] - 40.0

    private val fuelSys = mapOf(
        0 to "Off", 1 to "Open loop (cold)", 2 to "Closed loop (O2)", 4 to "Open loop (load/decel)",
        8 to "Open loop (fault)", 16 to "Closed loop (fault)"
    )
    private val fuelTypes = mapOf(
        1 to "Petrol", 2 to "Methanol", 3 to "Ethanol", 4 to "Diesel", 5 to "LPG", 6 to "CNG",
        8 to "Electric", 9 to "Bifuel petrol"
    )
    private val obdStd = mapOf(
        1 to "OBD-II (CARB)", 2 to "OBD (EPA)", 3 to "OBD + OBD-II", 6 to "EOBD", 7 to "EOBD + OBD-II",
        9 to "EOBD + OBD + OBD-II", 13 to "JOBD", 17 to "EMD", 30 to "BS-VI / other"
    )

    val list: List<PidDef> = listOf(
        PidDef(0x01, "Monitor status (MIL, DTC count)", "", 0, { it[0].toDouble() }) { d ->
            val mil = if (d[0] and 0x80 != 0) "MIL ON" else "MIL off"
            "$mil · ${d[0] and 0x7F} DTC"
        },
        PidDef(0x03, "Fuel system status", "", 0, { it[0].toDouble() }) { d -> fuelSys[d[0]] ?: "Code ${d[0]}" },
        PidDef(0x04, "Calculated engine load", "%", 1, ::pct),
        PidDef(0x05, "Coolant temperature", "°C", 0, ::temp),
        PidDef(0x06, "Short term fuel trim B1", "%", 1, ::trim),
        PidDef(0x07, "Long term fuel trim B1", "%", 1, ::trim),
        PidDef(0x08, "Short term fuel trim B2", "%", 1, ::trim),
        PidDef(0x09, "Long term fuel trim B2", "%", 1, ::trim),
        PidDef(0x0A, "Fuel pressure", "kPa", 0, { it[0] * 3.0 }),
        PidDef(0x0B, "Intake manifold pressure (MAP)", "kPa", 0, { it[0].toDouble() }),
        PidDef(0x0C, "Engine RPM", "rpm", 0, { ab(it) / 4.0 }),
        PidDef(0x0D, "Vehicle speed", "km/h", 0, { it[0].toDouble() }),
        PidDef(0x0E, "Ignition timing advance", "°", 1, { it[0] / 2.0 - 64.0 }),
        PidDef(0x0F, "Intake air temperature", "°C", 0, ::temp),
        PidDef(0x10, "MAF air flow", "g/s", 2, { ab(it) / 100.0 }),
        PidDef(0x11, "Throttle position", "%", 1, ::pct),
        PidDef(0x13, "O2 sensors present", "", 0, text = { d -> "0x%02X".format(d[0]) }),
        PidDef(0x14, "O2 B1S1 voltage", "V", 3, { it[0] / 200.0 }),
        PidDef(0x15, "O2 B1S2 voltage", "V", 3, { it[0] / 200.0 }),
        PidDef(0x16, "O2 B1S3 voltage", "V", 3, { it[0] / 200.0 }),
        PidDef(0x17, "O2 B1S4 voltage", "V", 3, { it[0] / 200.0 }),
        PidDef(0x1C, "OBD standard", "", 0, text = { d -> obdStd[d[0]] ?: "Type ${d[0]}" }),
        PidDef(0x1F, "Engine run time", "s", 0, ::ab),
        PidDef(0x21, "Distance with MIL on", "km", 0, ::ab),
        PidDef(0x22, "Fuel rail pressure (rel.)", "kPa", 1, { ab(it) * 0.079 }),
        PidDef(0x23, "Fuel rail pressure (gauge)", "kPa", 0, { ab(it) * 10.0 }),
        PidDef(0x24, "O2 S1 lambda (wide)", "λ", 3, { ab(it) * 2.0 / 65536.0 }),
        PidDef(0x25, "O2 S2 lambda (wide)", "λ", 3, { ab(it) * 2.0 / 65536.0 }),
        PidDef(0x2C, "Commanded EGR", "%", 1, ::pct),
        PidDef(0x2D, "EGR error", "%", 1, ::trim),
        PidDef(0x2E, "Commanded evap purge", "%", 1, ::pct),
        PidDef(0x2F, "Fuel tank level", "%", 1, ::pct),
        PidDef(0x30, "Warm-ups since codes cleared", "", 0, { it[0].toDouble() }),
        PidDef(0x31, "Distance since codes cleared", "km", 0, ::ab),
        PidDef(0x32, "Evap system vapour pressure", "Pa", 1, { ab(it).toInt().toShort() / 4.0 }),
        PidDef(0x33, "Barometric pressure", "kPa", 0, { it[0].toDouble() }),
        PidDef(0x34, "O2 S1 lambda (current)", "λ", 3, { ab(it) * 2.0 / 65536.0 }),
        PidDef(0x38, "O2 S5 lambda (current)", "λ", 3, { ab(it) * 2.0 / 65536.0 }),
        PidDef(0x3C, "Catalyst temp B1S1", "°C", 0, { ab(it) / 10.0 - 40.0 }),
        PidDef(0x3D, "Catalyst temp B2S1", "°C", 0, { ab(it) / 10.0 - 40.0 }),
        PidDef(0x3E, "Catalyst temp B1S2", "°C", 0, { ab(it) / 10.0 - 40.0 }),
        PidDef(0x41, "Monitor status this drive", "", 0, text = { d -> d.joinToString(" ") { "%02X".format(it) } }),
        PidDef(0x42, "Control module voltage", "V", 2, { ab(it) / 1000.0 }),
        PidDef(0x43, "Absolute load", "%", 1, { ab(it) * 100.0 / 255.0 }),
        PidDef(0x44, "Commanded lambda", "λ", 3, { ab(it) * 2.0 / 65536.0 }),
        PidDef(0x45, "Relative throttle position", "%", 1, ::pct),
        PidDef(0x46, "Ambient air temperature", "°C", 0, ::temp),
        PidDef(0x47, "Absolute throttle B", "%", 1, ::pct),
        PidDef(0x48, "Absolute throttle C", "%", 1, ::pct),
        PidDef(0x49, "Accelerator pedal D", "%", 1, ::pct),
        PidDef(0x4A, "Accelerator pedal E", "%", 1, ::pct),
        PidDef(0x4B, "Accelerator pedal F", "%", 1, ::pct),
        PidDef(0x4C, "Commanded throttle actuator", "%", 1, ::pct),
        PidDef(0x4D, "Time run with MIL on", "min", 0, ::ab),
        PidDef(0x4E, "Time since codes cleared", "min", 0, ::ab),
        PidDef(0x50, "Max MAF value", "g/s", 0, { it[0] * 10.0 }),
        PidDef(0x51, "Fuel type", "", 0, text = { d -> fuelTypes[d[0]] ?: "Type ${d[0]}" }),
        PidDef(0x52, "Ethanol fuel", "%", 1, ::pct),
        PidDef(0x53, "Absolute evap pressure", "kPa", 2, { ab(it) / 200.0 }),
        PidDef(0x55, "Short term O2 trim B1 (sec.)", "%", 1, ::trim),
        PidDef(0x56, "Long term O2 trim B1 (sec.)", "%", 1, ::trim),
        PidDef(0x59, "Fuel rail absolute pressure", "kPa", 0, { ab(it) * 10.0 }),
        PidDef(0x5A, "Relative accelerator pedal", "%", 1, ::pct),
        PidDef(0x5C, "Engine oil temperature", "°C", 0, ::temp),
        PidDef(0x5D, "Fuel injection timing", "°", 2, { ab(it) / 128.0 - 210.0 }),
        PidDef(0x5E, "Engine fuel rate", "L/h", 2, { ab(it) / 20.0 }),
        PidDef(0x61, "Driver demand torque", "%", 0, { it[0] - 125.0 }),
        PidDef(0x62, "Actual engine torque", "%", 0, { it[0] - 125.0 }),
        PidDef(0x63, "Engine reference torque", "Nm", 0, ::ab),
        PidDef(0x67, "Coolant temp (sensor 1)", "°C", 0, { it[1] - 40.0 }),
        PidDef(0x68, "Intake air temp (sensor 1)", "°C", 0, { it[1] - 40.0 }),
        PidDef(0x8E, "Engine friction torque", "%", 0, { it[0] - 125.0 }),
        PidDef(0xA4, "Transmission actual gear", "", 0, { d ->
            ((d[2] * 256 + d[3]) / 1000.0)
        }) { d -> if (d[0] and 0x02 != 0) "Gear ratio %.3f".format((d[2] * 256 + d[3]) / 1000.0) else "n/a" },
        PidDef(0xA6, "Odometer", "km", 1, { d ->
            ((d[0].toLong() shl 24) + (d[1].toLong() shl 16) + (d[2].toLong() shl 8) + d[3]) / 10.0
        })
    )

    val byId: Map<Int, PidDef> = list.associateBy { it.pid }

    /** PIDs that are support bitmaps rather than data. */
    fun isBitmap(p: Int) = p % 0x20 == 0

    fun nameOf(p: Int) = byId[p]?.name ?: "PID %02X".format(p)
}
