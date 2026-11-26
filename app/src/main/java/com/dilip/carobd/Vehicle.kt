package com.dilip.carobd

import org.json.JSONObject

/** Fuel properties used for consumption and CO2. density = grams per sale unit (litre, or kg for CNG). */
class FuelSpec(
    val name: String,
    val afr: Double,
    val density: Double,
    val co2PerUnit: Double,
    val lambda: Double,
    val unit: String
)

object Fuels {
    val all = listOf(
        FuelSpec("Petrol", 14.7, 745.0, 2.31, 1.0, "L"),
        FuelSpec("Diesel", 14.5, 832.0, 2.68, 1.8, "L"),
        FuelSpec("E20 petrol", 14.0, 755.0, 2.20, 1.0, "L"),
        FuelSpec("CNG", 17.2, 1000.0, 2.75, 1.0, "kg"),
        FuelSpec("LPG", 15.5, 540.0, 1.51, 1.0, "L"),
        FuelSpec("Hybrid (petrol)", 14.7, 745.0, 2.31, 1.0, "L"),
        FuelSpec("Electric (EV)", 0.0, 0.0, 0.71, 1.0, "kWh")
    )

    fun get(n: String) = all.firstOrNull { it.name == n } ?: all[0]
}

class Vehicle(var id: Long = 0) {
    var name = "My car"
    var make = ""
    var model = ""
    var year = 0
    var fuel = "Petrol"
    var dispL = 1.2
    var tankL = 40.0
    var ve = 0.85
    var fuelPrice = 100.0
    var fuelCorr = 1.0
    var odoKm = 0.0
    var speedLimit = 90
    var rpmLimit = 4500
    var vin = ""
    var regNo = ""
    var insurer = ""
    var policyNo = ""
    var policyExpiry = 0L

    val isEv: Boolean get() = fuel.startsWith("Electric")
    val spec: FuelSpec get() = Fuels.get(fuel)
    val title: String get() = listOf(name, listOf(make, model).filter { it.isNotBlank() }.joinToString(" "))
        .filter { it.isNotBlank() }.distinct().joinToString(" · ")

    fun toJson(): String = JSONObject().apply {
        put("name", name); put("make", make); put("model", model); put("year", year); put("fuel", fuel)
        put("disp", dispL); put("tank", tankL); put("ve", ve); put("price", fuelPrice); put("corr", fuelCorr)
        put("odo", odoKm); put("spd", speedLimit); put("rpm", rpmLimit); put("vin", vin)
        put("reg", regNo); put("ins", insurer); put("pol", policyNo); put("polExp", policyExpiry)
    }.toString()

    companion object {
        fun fromJson(id: Long, s: String): Vehicle {
            val o = try { JSONObject(s) } catch (_: Exception) { JSONObject() }
            return Vehicle(id).apply {
                name = o.optString("name", name); make = o.optString("make"); model = o.optString("model")
                year = o.optInt("year"); fuel = o.optString("fuel", fuel); dispL = o.optDouble("disp", dispL)
                tankL = o.optDouble("tank", tankL); ve = o.optDouble("ve", ve); fuelPrice = o.optDouble("price", fuelPrice)
                fuelCorr = o.optDouble("corr", 1.0); odoKm = o.optDouble("odo", 0.0)
                speedLimit = o.optInt("spd", 90); rpmLimit = o.optInt("rpm", 4500); vin = o.optString("vin")
                regNo = o.optString("reg"); insurer = o.optString("ins"); policyNo = o.optString("pol"); policyExpiry = o.optLong("polExp")
            }
        }
    }
}
