package com.dilip.carobd

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------------- Custom PID ---------------- */

class CustomPid(
    val name: String,
    val header: String,
    val request: String,
    val formula: String,
    val unit: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("header", header).put("request", request)
        .put("formula", formula).put("unit", unit)

    companion object {
        fun fromJson(o: JSONObject) = CustomPid(
            o.optString("name"), o.optString("header"), o.optString("request"),
            o.optString("formula"), o.optString("unit")
        )
    }
}

/* ---------------- Global settings ---------------- */

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("car_obd", Context.MODE_PRIVATE)
    }

    private fun dbl(k: String, def: Double) = sp.getString(k, null)?.toDoubleOrNull() ?: def
    private fun putD(k: String, v: Double) = sp.edit().putString(k, v.toString()).apply()
    private fun b(k: String, def: Boolean) = sp.getBoolean(k, def)
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var consentVersion: Int get() = sp.getInt("consentV", 0)
        set(v) = sp.edit().putInt("consentV", v).apply()

    // v3.9 – road assistant & backup
    var roadAssist: Boolean get() = b("roadAssist", true)
        set(v) = putB("roadAssist", v)
    var roadAnnounce: Boolean get() = b("roadAnnounce", true)
        set(v) = putB("roadAnnounce", v)
    var roadHazards: Boolean get() = b("roadHazards", true)
        set(v) = putB("roadHazards", v)
    var roadEstWarn: Boolean get() = b("roadEstWarn", false)
        set(v) = putB("roadEstWarn", v)
    var speedMargin: Int
        get() = sp.getInt("speedMargin", 5)
        set(v) = sp.edit().putInt("speedMargin", v).apply()
    var cloudUri: String
        get() = sp.getString("cloudUri", "") ?: ""
        set(v) = sp.edit().putString("cloudUri", v).apply()
    var cloudName: String
        get() = sp.getString("cloudName", "") ?: ""
        set(v) = sp.edit().putString("cloudName", v).apply()
    var autoBackup: Boolean get() = b("autoBackup", true)
        set(v) = putB("autoBackup", v)
    var lastBackupT: Long
        get() = sp.getLong("lastBackupT", 0L)
        set(v) = sp.edit().putLong("lastBackupT", v).apply()
    var lastBackupWhere: String
        get() = sp.getString("lastBackupWhere", "") ?: ""
        set(v) = sp.edit().putString("lastBackupWhere", v).apply()

    var activeVehicle: Long
        get() = sp.getLong("activeVehicle", 0L)
        set(v) = sp.edit().putLong("activeVehicle", v).apply()
    var pollDelayMs: Int
        get() = sp.getInt("pollDelay", 0)
        set(v) = sp.edit().putInt("pollDelay", v).apply()
    var protocol: String
        get() = sp.getString("protocol", "0") ?: "0"
        set(v) = sp.edit().putString("protocol", v).apply()
    var economyUnit: String   // "km/L" or "L/100km"
        get() = sp.getString("econ", "km/L") ?: "km/L"
        set(v) = sp.edit().putString("econ", v).apply()

    var csvLogging: Boolean get() = b("csv", false)
        set(v) = putB("csv", v)
    var autoTrip: Boolean get() = b("autoTrip", true)
        set(v) = putB("autoTrip", v)
    var keepScreenOn: Boolean get() = b("screenOn", true)
        set(v) = putB("screenOn", v)
    var gpsEnabled: Boolean get() = b("gps", true)
        set(v) = putB("gps", v)
    var voiceAlerts: Boolean get() = b("voice", true)
        set(v) = putB("voice", v)
    var beepAlerts: Boolean get() = b("beep", true)
        set(v) = putB("beep", v)
    var eventAlerts: Boolean get() = b("eventAlerts", true)
        set(v) = putB("eventAlerts", v)
    var autoConnect: Boolean get() = b("autoConnect", true)
        set(v) = putB("autoConnect", v)
    var backgroundWatch: Boolean get() = b("bgWatch", true)
        set(v) = putB("bgWatch", v)
    var autoReconnect: Boolean get() = b("autoReconnect", true)
        set(v) = putB("autoReconnect", v)
    var autoDtc: Boolean get() = b("autoDtc", true)
        set(v) = putB("autoDtc", v)
    var tripNotify: Boolean get() = b("tripNotify", true)
        set(v) = putB("tripNotify", v)

    // v3 – alerts
    var alertNotify: Boolean get() = b("alertNotify", true)
        set(v) = putB("alertNotify", v)
    var notifyDrivingEvents: Boolean get() = b("notifyDrive", false)
        set(v) = putB("notifyDrive", v)
    var coolantMax: Double get() = dbl("coolMax", 105.0)
        set(v) = putD("coolMax", v)
    fun askedPerm(k: String) = sp.getBoolean("asked_$k", false)
    fun markAskedPerm(k: String) = sp.edit().putBoolean("asked_$k", true).apply()
    var mqttOn: Boolean get() = b("mqttOn", false)
        set(v) = putB("mqttOn", v)
    var mqttHost: String get() = sp.getString("mqttHost", "") ?: ""
        set(v) = sp.edit().putString("mqttHost", v).apply()
    var mqttPort: Int get() = sp.getInt("mqttPort", 1883)
        set(v) = sp.edit().putInt("mqttPort", v).apply()
    var mqttUser: String get() = sp.getString("mqttUser", "") ?: ""
        set(v) = sp.edit().putString("mqttUser", v).apply()
    var mqttPass: String get() = sp.getString("mqttPass", "") ?: ""
        set(v) = sp.edit().putString("mqttPass", v).apply()
    var mqttTls: Boolean get() = b("mqttTls", false)
        set(v) = putB("mqttTls", v)
    var mqttBase: String get() = sp.getString("mqttBase", "carobd") ?: "carobd"
        set(v) = sp.edit().putString("mqttBase", v).apply()
    var mqttDisc: String get() = sp.getString("mqttDisc", "homeassistant") ?: "homeassistant"
        set(v) = sp.edit().putString("mqttDisc", v).apply()
    var mqttSec: Int get() = sp.getInt("mqttSec", 5)
        set(v) = sp.edit().putInt("mqttSec", v).apply()
    var tripFilter: Int get() = sp.getInt("tripFilter", 0)
        set(v) = sp.edit().putInt("tripFilter", v).apply()
    var unitDist: String get() = sp.getString("uDist", "km") ?: "km"
        set(v) = sp.edit().putString("uDist", v).apply()
    var unitFuel: String get() = sp.getString("uFuel", "Litre (L)") ?: "Litre (L)"
        set(v) = sp.edit().putString("uFuel", v).apply()
    var unitTemp: String get() = sp.getString("uTemp", "°C") ?: "°C"
        set(v) = sp.edit().putString("uTemp", v).apply()
    var unitPress: String get() = sp.getString("uPress", "kPa") ?: "kPa"
        set(v) = sp.edit().putString("uPress", v).apply()
    var unitTorque: String get() = sp.getString("uTorque", "Nm") ?: "Nm"
        set(v) = sp.edit().putString("uTorque", v).apply()
    var currency: String get() = sp.getString("currency", "₹") ?: "₹"
        set(v) = sp.edit().putString("currency", v).apply()
    var time24: Boolean get() = b("time24", true)
        set(v) = putB("time24", v)
    var language: String get() = sp.getString("lang", "system") ?: "system"
        set(v) = sp.edit().putString("lang", v).apply()
    var theme: String get() = sp.getString("theme", "dark") ?: "dark"
        set(v) = sp.edit().putString("theme", v).apply()
    var speedCorrPct: Double get() = dbl("spdCorr", 0.0)
        set(v) = putD("spdCorr", v)
    var distMethod: String get() = sp.getString("distMethod", "ECU") ?: "ECU"
        set(v) = sp.edit().putString("distMethod", v).apply()
    var voltMethod: String get() = sp.getString("voltMethod", "ADAPTER") ?: "ADAPTER"
        set(v) = sp.edit().putString("voltMethod", v).apply()
    var purchasePrice: Double get() = dbl("buyPrice", 0.0)
        set(v) = putD("buyPrice", v)
    var currentValue: Double get() = dbl("curValue", 0.0)
        set(v) = putD("curValue", v)
    var healthSnap: String get() = sp.getString("healthSnap", "") ?: ""
        set(v) = sp.edit().putString("healthSnap", v).apply()
    var battType: String get() = sp.getString("battType", "Lead-acid (flooded)") ?: "Lead-acid (flooded)"
        set(v) = sp.edit().putString("battType", v).apply()
    var battCca: Int get() = sp.getInt("battCca", 0)
        set(v) = sp.edit().putInt("battCca", v).apply()
    var battAh: Int get() = sp.getInt("battAh", 0)
        set(v) = sp.edit().putInt("battAh", v).apply()
    var battSince: Long get() = sp.getLong("battSince", 0L)
        set(v) = sp.edit().putLong("battSince", v).apply()
    var perfKg: Int get() = sp.getInt("perfKg", 1300)
        set(v) = sp.edit().putInt("perfKg", v).apply()
    var chargeMinV: Double get() = dbl("chgMin", 13.0)
        set(v) = putD("chgMin", v)
    var chargeMaxV: Double get() = dbl("chgMax", 15.2)
        set(v) = putD("chgMax", v)
    var parkLowV: Double get() = dbl("parkLow", 12.2)
        set(v) = putD("parkLow", v)
    var crankLowV: Double get() = dbl("crankLow", 9.6)
        set(v) = putD("crankLow", v)
    var lowFuelPct: Double get() = dbl("lowFuel", 12.0)
        set(v) = putD("lowFuel", v)

    // v3 – automation
    var autoStartCarBt: Boolean get() = b("autoCarBt", false)
        set(v) = putB("autoCarBt", v)
    var carBtAddr: String
        get() = sp.getString("carBtAddr", "") ?: ""
        set(v) = sp.edit().putString("carBtAddr", v).apply()
    var carBtName: String
        get() = sp.getString("carBtName", "") ?: ""
        set(v) = sp.edit().putString("carBtName", v).apply()
    var tripMergeMin: Int
        get() = sp.getInt("tripMerge", 3)
        set(v) = sp.edit().putInt("tripMerge", v).apply()

    // v3 – stored state
    var parkLat: Double get() = dbl("parkLat", 0.0)
        set(v) = putD("parkLat", v)
    var parkLon: Double get() = dbl("parkLon", 0.0)
        set(v) = putD("parkLon", v)
    var parkT: Long
        get() = sp.getLong("parkT", 0L)
        set(v) = sp.edit().putLong("parkT", v).apply()
    var crankHistory: String
        get() = sp.getString("crankHist", "[]") ?: "[]"
        set(v) = sp.edit().putString("crankHist", v).apply()
    var perfRuns: String
        get() = sp.getString("perfRuns", "[]") ?: "[]"
        set(v) = sp.edit().putString("perfRuns", v).apply()

    var accelG: Double get() = dbl("accG", 0.22)
        set(v) = putD("accG", v)
    var brakeG: Double get() = dbl("brkG", 0.24)
        set(v) = putD("brkG", v)
    var suddenG: Double get() = dbl("sudG", 0.40)
        set(v) = putD("sudG", v)
    var cornerG: Double get() = dbl("corG", 0.30)
        set(v) = putD("corG", v)
    var longIdleS: Int
        get() = sp.getInt("idleS", 180)
        set(v) = sp.edit().putInt("idleS", v).apply()

    var lastType: Int
        get() = sp.getInt("lastType", 0)
        set(v) = sp.edit().putInt("lastType", v).apply()
    var lastAddr: String
        get() = sp.getString("lastAddr", "") ?: ""
        set(v) = sp.edit().putString("lastAddr", v).apply()
    var wifiHost: String
        get() = sp.getString("host", "192.168.0.10") ?: "192.168.0.10"
        set(v) = sp.edit().putString("host", v).apply()
    var wifiPort: Int
        get() = sp.getInt("port", 35000)
        set(v) = sp.edit().putInt("port", v).apply()

    var customPids: List<CustomPid>
        get() {
            val a = try { JSONArray(sp.getString("custom", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            return (0 until a.length()).map { CustomPid.fromJson(a.getJSONObject(it)) }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(it.toJson()) }
            sp.edit().putString("custom", a.toString()).apply()
        }
}

/* ---------------- Formula evaluator for custom PIDs ---------------- */

/** Evaluates expressions like "(A*256+B)/4 - 40" with variables A..H. Supports + - * / % ( ). */
class Expr(private val s: String, private val vars: Map<Char, Double>) {
    private var i = 0

    fun eval(): Double {
        val v = expr()
        skip()
        if (i < s.length) throw IllegalArgumentException("Unexpected '${s[i]}'")
        return v
    }

    private fun skip() {
        while (i < s.length && s[i] == ' ') i++
    }

    private fun peek(): Char? {
        skip(); return if (i < s.length) s[i] else null
    }

    private fun expr(): Double {
        var v = term()
        while (true) {
            when (peek()) {
                '+' -> { i++; v += term() }
                '-' -> { i++; v -= term() }
                else -> return v
            }
        }
    }

    private fun term(): Double {
        var v = factor()
        while (true) {
            when (peek()) {
                '*' -> { i++; v *= factor() }
                '/' -> { i++; v /= factor() }
                '%' -> { i++; v %= factor() }
                else -> return v
            }
        }
    }

    private fun factor(): Double {
        val c = peek() ?: throw IllegalArgumentException("Unexpected end")
        if (c == '-') { i++; return -factor() }
        if (c == '(') {
            i++
            val v = expr()
            if (peek() != ')') throw IllegalArgumentException("Missing )")
            i++
            return v
        }
        if (c.isLetter()) {
            i++
            return vars[c.uppercaseChar()] ?: throw IllegalArgumentException("Unknown variable $c")
        }
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        if (st == i) throw IllegalArgumentException("Bad character '$c'")
        return s.substring(st, i).toDouble()
    }

    companion object {
        fun varsFrom(data: IntArray): Map<Char, Double> =
            data.withIndex().filter { it.index < 8 }.associate { ('A' + it.index) to it.value.toDouble() }
    }
}

/* ---------------- Helpers ---------------- */

fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/** Writes files to Downloads/CarOBD (no storage permission needed on Android 10+). */
object Exporter {
    fun open(ctx: Context, name: String, mime: String = "text/csv"): OutputStream? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CarOBD")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            uri?.let { ctx.contentResolver.openOutputStream(it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CarOBD")
            dir.mkdirs()
            FileOutputStream(File(dir, name))
        }
    } catch (_: Exception) {
        null
    }

    /** Writes text, returns the user-visible path or null. */
    fun writeText(ctx: Context, name: String, mime: String, text: String): String? {
        val os = open(ctx, name, mime) ?: return null
        return try {
            os.bufferedWriter().use { it.write(text) }
            "Downloads/CarOBD/$name"
        } catch (_: Exception) {
            null
        }
    }
}
