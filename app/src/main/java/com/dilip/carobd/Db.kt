package com.dilip.carobd

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File

/* ---------------- Records ---------------- */

class Trip(var start: Long, var vehicleId: Long) {
    var id = 0L
    var end = 0L
    var distanceKm = 0.0
    var fuel = 0.0              // litres (kg for CNG), after correction factor
    var idleS = 0.0
    var idleFuel = 0.0
    var movingS = 0.0
    var maxSpeed = 0.0
    var maxRpm = 0.0
    var maxCoolant = 0.0
    var fuelStartPct = -1.0
    var fuelEndPct = -1.0
    var odoStart = 0.0
    var rapidAcc = 0
    var hardBrake = 0
    var suddenBrake = 0
    var corner = 0
    var overspeed = 0
    var overspeedS = 0.0
    var overRev = 0
    var coldRev = 0
    var longIdle = 0
    var best0100 = 0.0
    var best060 = 0.0
    var gpsKm = 0.0
    var sumObdSpd = 0.0
    var sumGpsSpd = 0.0
    var fuelPrice = 0.0
    var co2PerUnit = 2.31

    val durationS: Double get() = ((if (end > 0) end else System.currentTimeMillis()) - start) / 1000.0
    val avgSpeed: Double get() = if (durationS > 0) distanceKm / (durationS / 3600.0) else 0.0
    val movingAvgSpeed: Double get() = if (movingS > 0) distanceKm / (movingS / 3600.0) else 0.0
    val kmpl: Double? get() = if (fuel > 0.01 && distanceKm > 0.1) distanceKm / fuel else null
    val cost: Double get() = fuel * fuelPrice
    val co2Kg: Double get() = fuel * co2PerUnit
    val events: Int get() = rapidAcc + hardBrake + suddenBrake + corner + overspeed + overRev + coldRev + longIdle

    /** Driver score 0-100: weighted penalties normalised per 10 km. */
    val score: Int
        get() {
            val penalty = rapidAcc * 2.0 + hardBrake * 3.0 + suddenBrake * 6.0 + corner * 2.0 +
                    overspeedS / 10.0 + overRev * 1.0 + coldRev * 1.0 + longIdle * 1.0
            return (100.0 - penalty * 10.0 / maxOf(distanceKm, 5.0)).coerceIn(0.0, 100.0).toInt()
        }

    /** Speedometer error in % (OBD vs GPS), positive = speedometer reads high. */
    val speedoErrorPct: Double? get() = if (sumGpsSpd > 1000) (sumObdSpd / sumGpsSpd - 1.0) * 100.0 else null

    fun toJson(): String = JSONObject().apply {
        put("end", end); put("dist", distanceKm); put("fuel", fuel); put("idle", idleS); put("idleFuel", idleFuel)
        put("moving", movingS); put("maxSpd", maxSpeed); put("maxRpm", maxRpm); put("maxCool", maxCoolant)
        put("fs", fuelStartPct); put("fe", fuelEndPct); put("odo", odoStart)
        put("ra", rapidAcc); put("hb", hardBrake); put("sb", suddenBrake); put("co", corner); put("os", overspeed)
        put("osS", overspeedS); put("or", overRev); put("cr", coldRev); put("li", longIdle)
        put("b100", best0100); put("b60", best060); put("gpsKm", gpsKm); put("sObd", sumObdSpd); put("sGps", sumGpsSpd)
        put("price", fuelPrice); put("co2", co2PerUnit)
    }.toString()

    fun loadJson(s: String) {
        val o = try { JSONObject(s) } catch (_: Exception) { return }
        end = o.optLong("end"); distanceKm = o.optDouble("dist", 0.0); fuel = o.optDouble("fuel", 0.0)
        idleS = o.optDouble("idle", 0.0); idleFuel = o.optDouble("idleFuel", 0.0); movingS = o.optDouble("moving", 0.0)
        maxSpeed = o.optDouble("maxSpd", 0.0); maxRpm = o.optDouble("maxRpm", 0.0); maxCoolant = o.optDouble("maxCool", 0.0)
        fuelStartPct = o.optDouble("fs", -1.0); fuelEndPct = o.optDouble("fe", -1.0); odoStart = o.optDouble("odo", 0.0)
        rapidAcc = o.optInt("ra"); hardBrake = o.optInt("hb"); suddenBrake = o.optInt("sb"); corner = o.optInt("co")
        overspeed = o.optInt("os"); overspeedS = o.optDouble("osS", 0.0); overRev = o.optInt("or"); coldRev = o.optInt("cr")
        longIdle = o.optInt("li"); best0100 = o.optDouble("b100", 0.0); best060 = o.optDouble("b60", 0.0)
        gpsKm = o.optDouble("gpsKm", 0.0); sumObdSpd = o.optDouble("sObd", 0.0); sumGpsSpd = o.optDouble("sGps", 0.0)
        fuelPrice = o.optDouble("price", 0.0); co2PerUnit = o.optDouble("co2", 2.31)
    }
}

class DriveEvent(
    val type: String,
    val value: Double,
    val speed: Double,
    val t: Long = System.currentTimeMillis(),
    var lat: Double? = null,
    var lon: Double? = null,
    var tripId: Long = 0,
    var vehicleId: Long = 0,
    var id: Long = 0
) {
    val label: String
        get() = when (type) {
            EV_ACC, EV_BRAKE, EV_SUDDEN, EV_CORNER -> "%s %.2f g @ %.0f km/h".format(type, value, speed)
            EV_OVERSPEED -> "%s peak %.0f km/h".format(type, value)
            EV_OVERREV, EV_COLDREV -> "%s %.0f rpm".format(type, value)
            EV_IDLE -> "%s %.0f min".format(type, value / 60)
            else -> "$type %.1f".format(value)
        }

    companion object {
        const val EV_ACC = "Rapid acceleration"
        const val EV_BRAKE = "Hard braking"
        const val EV_SUDDEN = "Sudden braking"
        const val EV_CORNER = "Harsh cornering"
        const val EV_OVERSPEED = "Overspeed"
        const val EV_OVERREV = "Over-rev"
        const val EV_COLDREV = "Cold engine high RPM"
        const val EV_IDLE = "Long idle"
        val ALL = listOf(EV_ACC, EV_BRAKE, EV_SUDDEN, EV_CORNER, EV_OVERSPEED, EV_OVERREV, EV_COLDREV, EV_IDLE)
    }
}

class TrackPoint(val t: Long, val lat: Double?, val lon: Double?, val speed: Double, val rpm: Double, val gpsSpeed: Double?)

class FuelLog(
    var id: Long = 0, var vehicleId: Long = 0, var t: Long = System.currentTimeMillis(),
    var odo: Double = 0.0, var qty: Double = 0.0, var cost: Double = 0.0, var full: Boolean = true, var note: String = ""
)

class MaintItem(
    var id: Long = 0, var vehicleId: Long = 0, var name: String = "", var intervalKm: Double = 0.0,
    var intervalDays: Int = 0, var lastKm: Double = 0.0, var lastT: Long = System.currentTimeMillis(), var enabled: Boolean = true
)

class MaintRecord(
    var id: Long = 0, var vehicleId: Long = 0, var itemId: Long = 0, var name: String = "",
    var t: Long = System.currentTimeMillis(), var odo: Double = 0.0, var cost: Double = 0.0, var note: String = ""
)

class DtcRec(val t: Long, val code: String, val ecu: String, val kind: String)

/* ---------------- Database ---------------- */

object Db {
    private const val NAME = "carobd.db"
    private lateinit var helper: Helper

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE vehicles(id INTEGER PRIMARY KEY AUTOINCREMENT, data TEXT)")
            db.execSQL("CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, vid INTEGER, start INTEGER, data TEXT)")
            db.execSQL("CREATE INDEX trips_v ON trips(vid, start)")
            db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, trip INTEGER, vid INTEGER, t INTEGER, type TEXT, value REAL, speed REAL, lat REAL, lon REAL)")
            db.execSQL("CREATE INDEX events_t ON events(trip)")
            db.execSQL("CREATE TABLE track(trip INTEGER, t INTEGER, lat REAL, lon REAL, speed REAL, rpm REAL, gps REAL)")
            db.execSQL("CREATE INDEX track_t ON track(trip)")
            db.execSQL("CREATE TABLE fuel(id INTEGER PRIMARY KEY AUTOINCREMENT, vid INTEGER, t INTEGER, odo REAL, qty REAL, cost REAL, full INTEGER, note TEXT)")
            db.execSQL("CREATE TABLE maint(id INTEGER PRIMARY KEY AUTOINCREMENT, vid INTEGER, name TEXT, ikm REAL, idays INTEGER, lkm REAL, lt INTEGER, enabled INTEGER)")
            db.execSQL("CREATE TABLE maint_rec(id INTEGER PRIMARY KEY AUTOINCREMENT, vid INTEGER, item INTEGER, name TEXT, t INTEGER, odo REAL, cost REAL, note TEXT)")
            db.execSQL("CREATE TABLE dtc(id INTEGER PRIMARY KEY AUTOINCREMENT, vid INTEGER, t INTEGER, code TEXT, ecu TEXT, kind TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    fun init(ctx: Context) {
        if (!::helper.isInitialized) helper = Helper(ctx.applicationContext)
    }

    fun file(ctx: Context): File = ctx.getDatabasePath(NAME)

    @Synchronized
    fun reopen(ctx: Context) {
        try { helper.close() } catch (_: Exception) {}
        helper = Helper(ctx.applicationContext)
    }

    @Synchronized
    fun close() {
        try { helper.close() } catch (_: Exception) {}
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private inline fun <T> query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> {
        val out = mutableListOf<T>()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(map(c)) }
        return out
    }

    private fun Cursor.dOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    /* vehicles */
    fun vehicles(): List<Vehicle> = query("SELECT id, data FROM vehicles ORDER BY id", arrayOf()) {
        Vehicle.fromJson(it.getLong(0), it.getString(1) ?: "{}")
    }

    fun vehicle(id: Long): Vehicle? = query("SELECT id, data FROM vehicles WHERE id=?", arrayOf(id.toString())) {
        Vehicle.fromJson(it.getLong(0), it.getString(1) ?: "{}")
    }.firstOrNull()

    @Synchronized
    fun saveVehicle(v: Vehicle): Long {
        val cv = ContentValues().apply { put("data", v.toJson()) }
        if (v.id == 0L) v.id = db.insert("vehicles", null, cv)
        else db.update("vehicles", cv, "id=?", arrayOf(v.id.toString()))
        return v.id
    }

    @Synchronized
    fun deleteVehicle(id: Long) {
        val a = arrayOf(id.toString())
        for (tid in query("SELECT id FROM trips WHERE vid=?", a) { it.getLong(0) }) deleteTrip(tid)
        db.delete("vehicles", "id=?", a)
        db.delete("fuel", "vid=?", a)
        db.delete("maint", "vid=?", a)
        db.delete("maint_rec", "vid=?", a)
        db.delete("dtc", "vid=?", a)
    }

    /** The active vehicle, creating a default one (with a default service schedule) if needed. */
    @Synchronized
    fun activeVehicle(): Vehicle {
        vehicle(Prefs.activeVehicle)?.let { return it }
        val v = vehicles().firstOrNull() ?: Vehicle().also {
            saveVehicle(it)
            Maint.loadDefaults(it)
        }
        Prefs.activeVehicle = v.id
        return v
    }

    /* trips */
    @Synchronized
    fun insertTrip(t: Trip): Long {
        val cv = ContentValues().apply {
            put("vid", t.vehicleId); put("start", t.start); put("data", t.toJson())
        }
        t.id = db.insert("trips", null, cv)
        return t.id
    }

    @Synchronized
    fun updateTrip(t: Trip) {
        if (t.id == 0L) { insertTrip(t); return }
        db.update("trips", ContentValues().apply { put("data", t.toJson()) }, "id=?", arrayOf(t.id.toString()))
    }

    private fun tripFrom(c: Cursor) = Trip(c.getLong(2), c.getLong(1)).apply {
        id = c.getLong(0); loadJson(c.getString(3) ?: "{}")
    }

    fun trips(vid: Long, since: Long = 0, limit: Int = 1000): List<Trip> =
        query("SELECT id, vid, start, data FROM trips WHERE vid=? AND start>=? ORDER BY start DESC LIMIT $limit",
            arrayOf(vid.toString(), since.toString())) { tripFrom(it) }

    fun trip(id: Long): Trip? =
        query("SELECT id, vid, start, data FROM trips WHERE id=?", arrayOf(id.toString())) { tripFrom(it) }.firstOrNull()

    @Synchronized
    fun deleteTrip(id: Long) {
        val a = arrayOf(id.toString())
        db.delete("trips", "id=?", a)
        db.delete("events", "trip=?", a)
        db.delete("track", "trip=?", a)
    }

    /* events */
    @Synchronized
    fun insertEvent(e: DriveEvent) {
        val cv = ContentValues().apply {
            put("trip", e.tripId); put("vid", e.vehicleId); put("t", e.t); put("type", e.type)
            put("value", e.value); put("speed", e.speed); put("lat", e.lat); put("lon", e.lon)
        }
        e.id = db.insert("events", null, cv)
    }

    private fun eventFrom(c: Cursor) = DriveEvent(
        c.getString(4), c.getDouble(5), c.getDouble(6), c.getLong(3), c.dOrNull(7), c.dOrNull(8),
        c.getLong(1), c.getLong(2), c.getLong(0)
    )

    fun events(tripId: Long): List<DriveEvent> =
        query("SELECT id, trip, vid, t, type, value, speed, lat, lon FROM events WHERE trip=? ORDER BY t", arrayOf(tripId.toString())) { eventFrom(it) }

    fun eventsSince(vid: Long, since: Long): List<DriveEvent> =
        query("SELECT id, trip, vid, t, type, value, speed, lat, lon FROM events WHERE vid=? AND t>=? ORDER BY t DESC",
            arrayOf(vid.toString(), since.toString())) { eventFrom(it) }

    /* track */
    @Synchronized
    fun insertTrack(tripId: Long, p: TrackPoint) {
        val cv = ContentValues().apply {
            put("trip", tripId); put("t", p.t); put("lat", p.lat); put("lon", p.lon)
            put("speed", p.speed); put("rpm", p.rpm); put("gps", p.gpsSpeed)
        }
        db.insert("track", null, cv)
    }

    fun track(tripId: Long): List<TrackPoint> =
        query("SELECT t, lat, lon, speed, rpm, gps FROM track WHERE trip=? ORDER BY t", arrayOf(tripId.toString())) {
            TrackPoint(it.getLong(0), it.dOrNull(1), it.dOrNull(2), it.getDouble(3), it.getDouble(4), it.dOrNull(5))
        }

    /* fuel log */
    @Synchronized
    fun saveFuel(f: FuelLog) {
        val cv = ContentValues().apply {
            put("vid", f.vehicleId); put("t", f.t); put("odo", f.odo); put("qty", f.qty); put("cost", f.cost)
            put("full", if (f.full) 1 else 0); put("note", f.note)
        }
        if (f.id == 0L) f.id = db.insert("fuel", null, cv) else db.update("fuel", cv, "id=?", arrayOf(f.id.toString()))
    }

    fun fuelLogs(vid: Long): List<FuelLog> =
        query("SELECT id, vid, t, odo, qty, cost, full, note FROM fuel WHERE vid=? ORDER BY t DESC", arrayOf(vid.toString())) {
            FuelLog(it.getLong(0), it.getLong(1), it.getLong(2), it.getDouble(3), it.getDouble(4), it.getDouble(5),
                it.getInt(6) == 1, it.getString(7) ?: "")
        }

    @Synchronized
    fun deleteFuel(id: Long) {
        db.delete("fuel", "id=?", arrayOf(id.toString()))
    }

    /* maintenance */
    @Synchronized
    fun saveMaint(m: MaintItem) {
        val cv = ContentValues().apply {
            put("vid", m.vehicleId); put("name", m.name); put("ikm", m.intervalKm); put("idays", m.intervalDays)
            put("lkm", m.lastKm); put("lt", m.lastT); put("enabled", if (m.enabled) 1 else 0)
        }
        if (m.id == 0L) m.id = db.insert("maint", null, cv) else db.update("maint", cv, "id=?", arrayOf(m.id.toString()))
    }

    fun maintItems(vid: Long): List<MaintItem> =
        query("SELECT id, vid, name, ikm, idays, lkm, lt, enabled FROM maint WHERE vid=? ORDER BY id", arrayOf(vid.toString())) {
            MaintItem(it.getLong(0), it.getLong(1), it.getString(2) ?: "", it.getDouble(3), it.getInt(4),
                it.getDouble(5), it.getLong(6), it.getInt(7) == 1)
        }

    @Synchronized
    fun deleteMaint(id: Long) {
        db.delete("maint", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun insertRecord(r: MaintRecord) {
        val cv = ContentValues().apply {
            put("vid", r.vehicleId); put("item", r.itemId); put("name", r.name); put("t", r.t)
            put("odo", r.odo); put("cost", r.cost); put("note", r.note)
        }
        r.id = db.insert("maint_rec", null, cv)
    }

    fun records(vid: Long): List<MaintRecord> =
        query("SELECT id, vid, item, name, t, odo, cost, note FROM maint_rec WHERE vid=? ORDER BY t DESC", arrayOf(vid.toString())) {
            MaintRecord(it.getLong(0), it.getLong(1), it.getLong(2), it.getString(3) ?: "", it.getLong(4),
                it.getDouble(5), it.getDouble(6), it.getString(7) ?: "")
        }

    @Synchronized
    fun deleteRecord(id: Long) {
        db.delete("maint_rec", "id=?", arrayOf(id.toString()))
    }

    /* DTC history */
    @Synchronized
    fun insertDtc(vid: Long, d: Dtc): Boolean {
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        val seen = query("SELECT id FROM dtc WHERE vid=? AND code=? AND t>=?", arrayOf(vid.toString(), d.code, dayAgo.toString())) { it.getLong(0) }
        if (seen.isNotEmpty()) return false
        val cv = ContentValues().apply {
            put("vid", vid); put("t", System.currentTimeMillis()); put("code", d.code); put("ecu", d.ecu); put("kind", d.kind)
        }
        db.insert("dtc", null, cv)
        return true
    }

    fun dtcHistory(vid: Long): List<DtcRec> =
        query("SELECT t, code, ecu, kind FROM dtc WHERE vid=? ORDER BY t DESC LIMIT 300", arrayOf(vid.toString())) {
            DtcRec(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getString(3) ?: "")
        }
}
