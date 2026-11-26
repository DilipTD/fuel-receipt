package com.dilip.carobd

import java.util.Locale

/** User-selectable units. Everything is stored internally as km, km/h, litres, °C, kPa, Nm. */
object Units {
    val distances = listOf("km", "mi")
    val fuels = listOf("Litre (L)", "Gallon (US)", "Gallon (UK)")
    val temps = listOf("°C", "°F")
    val pressures = listOf("kPa", "bar", "psi")
    val torques = listOf("Nm", "lb-ft")
    val economies = listOf("km/L", "L/100km", "MPG (US)", "MPG (UK)")
    val currencies = listOf("₹", "$", "€", "£", "¥", "AED", "SAR", "PKR", "BDT", "LKR", "NPR", "ZAR", "R$", "₩", "Rs")

    val mi get() = Prefs.unitDist == "mi"
    val distLabel get() = if (mi) "mi" else "km"
    val spdLabel get() = if (mi) "mph" else "km/h"
    fun km(k: Double) = if (mi) k * 0.621371 else k
    fun spd(kmh: Double) = if (mi) kmh * 0.621371 else kmh

    val tempF get() = Prefs.unitTemp == "°F"
    fun temp(c: Double) = if (tempF) c * 9.0 / 5.0 + 32.0 else c
    val tempLabel get() = if (tempF) "°F" else "°C"

    fun press(kpa: Double) = when (Prefs.unitPress) { "bar" -> kpa / 100.0; "psi" -> kpa * 0.145038; else -> kpa }
    val pressLabel get() = Prefs.unitPress
    val pressDec get() = when (Prefs.unitPress) { "bar" -> 2; "psi" -> 1; else -> 0 }

    fun torque(nm: Double) = if (Prefs.unitTorque == "lb-ft") nm * 0.737562 else nm
    val torqueLabel get() = Prefs.unitTorque

    /** Volume/mass of fuel: only litres are converted. */
    private fun litres(spec: String) = spec == "L"
    fun vol(l: Double, spec: String = ObdManager.vehicle.spec.unit): Double =
        if (!litres(spec)) l else when (Prefs.unitFuel) { "Gallon (US)" -> l * 0.264172; "Gallon (UK)" -> l * 0.219969; else -> l }
    fun volLabel(spec: String = ObdManager.vehicle.spec.unit): String =
        if (!litres(spec)) spec else when (Prefs.unitFuel) { "Gallon (US)" -> "gal"; "Gallon (UK)" -> "gal"; else -> "L" }
    /** Inverse of [vol] – user entry back to litres. */
    fun toLitres(v: Double, spec: String = ObdManager.vehicle.spec.unit): Double =
        if (!litres(spec)) v else when (Prefs.unitFuel) { "Gallon (US)" -> v / 0.264172; "Gallon (UK)" -> v / 0.219969; else -> v }
    fun toKm(v: Double) = if (mi) v / 0.621371 else v

    /** Convert a PID value to the chosen unit: returns (value, label). */
    fun conv(unit: String, v: Double): Pair<Double, String> = when (unit) {
        "°C" -> temp(v) to tempLabel
        "km/h" -> spd(v) to spdLabel
        "km" -> km(v) to distLabel
        "kPa" -> press(v) to pressLabel
        "Nm" -> torque(v) to torqueLabel
        "L/h" -> vol(v, "L") to (volLabel("L") + "/h")
        else -> v to unit
    }
    fun label(unit: String) = conv(unit, 0.0).second

    fun fmt(v: Double, dec: Int, unit: String = "") = "%.${dec}f%s".format(Locale.US, v, unit)

    /** km per litre → text in the chosen economy unit. */
    fun econ(kmpl: Double, spec: String = ObdManager.vehicle.spec.unit): String {
        if (!litres(spec)) return if (Prefs.economyUnit == "L/100km") "%.1f %s/100km".format(Locale.US, 100.0 / kmpl, spec) else "%.1f %s/%s".format(Locale.US, km(kmpl), distLabel, spec)
        return when (Prefs.economyUnit) {
            "L/100km" -> "%.1f L/100km".format(Locale.US, 100.0 / kmpl)
            "MPG (US)" -> "%.1f MPG".format(Locale.US, kmpl * 2.352145)
            "MPG (UK)" -> "%.1f MPG UK".format(Locale.US, kmpl * 2.824809)
            else -> "%.1f ${distLabel}/${volLabel(spec)}".format(Locale.US, km(kmpl) / vol(1.0, spec))
        }
    }
}
