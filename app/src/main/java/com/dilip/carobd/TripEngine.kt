package com.dilip.carobd

import android.location.Location
import kotlin.math.abs

/**
 * Trip computer + driving-behaviour detector.
 * Acceleration thresholds in g (defaults 0.22 g rapid accel / 0.24 g hard brake, from published telematics
 * calibration), sudden braking 0.40 g, cornering from GPS bearing rate.
 */
class TripEngine {
    @Volatile var current: Trip? = null
        private set
    @Volatile var instantKmpl: Double? = null
    @Volatile var instantRate: Double? = null      // L/h (kg/h for CNG)
    @Volatile var liveAccelG: Double = 0.0
    @Volatile var liveLatG: Double = 0.0
    @Volatile var liveLatAt = 0L
    @Volatile var overspeedNow = false
    var onEvent: ((DriveEvent) -> Unit)? = null

    private var lastT = 0L
    private val spdHist = ArrayDeque<Pair<Long, Double>>()
    private var accPeak = 0.0
    private var brkPeak = 0.0
    private var inAcc = false
    private var inBrk = false
    private var osPeak = 0.0
    private var osStart = 0L
    private var revArmed = true
    private var lastColdRev = 0L
    private var idleStart = 0L
    private var idleReported = false
    private var zeroT = 0L
    private var lastGps: Location? = null
    private var cornerArmed = true
    private var vehicle: Vehicle? = null

    fun start(v: Vehicle, fuelPct: Double?): Trip {
        vehicle = v
        val t = Trip(System.currentTimeMillis(), v.id).apply {
            fuelStartPct = fuelPct ?: -1.0
            odoStart = v.odoKm
            fuelPrice = v.fuelPrice
            co2PerUnit = v.spec.co2PerUnit
        }
        current = t
        lastT = 0L
        spdHist.clear()
        inAcc = false; inBrk = false; osStart = 0L; idleStart = 0L; zeroT = 0L; lastGps = null
        overspeedNow = false
        return t
    }

    /** Continue a trip that ended only a few minutes ago (fuel stop, signal, short errand). */
    fun resume(v: Vehicle, t: Trip): Trip {
        vehicle = v
        t.end = 0L
        current = t
        lastT = 0L
        spdHist.clear()
        inAcc = false; inBrk = false; osStart = 0L; idleStart = 0L; zeroT = 0L; lastGps = null
        overspeedNow = false
        return t
    }

    fun stop(fuelPct: Double?): Trip? {
        val t = current ?: return null
        finishOverspeed(t, System.currentTimeMillis())
        finishIdle(t, System.currentTimeMillis())
        t.end = System.currentTimeMillis()
        t.fuelEndPct = fuelPct ?: -1.0
        current = null
        overspeedNow = false
        return t
    }

    private fun emit(t: Trip, type: String, value: Double, speed: Double) {
        when (type) {
            DriveEvent.EV_ACC -> t.rapidAcc++
            DriveEvent.EV_BRAKE -> t.hardBrake++
            DriveEvent.EV_SUDDEN -> t.suddenBrake++
            DriveEvent.EV_CORNER -> t.corner++
            DriveEvent.EV_OVERSPEED -> t.overspeed++
            DriveEvent.EV_OVERREV -> t.overRev++
            DriveEvent.EV_COLDREV -> t.coldRev++
            DriveEvent.EV_IDLE -> t.longIdle++
        }
        onEvent?.invoke(DriveEvent(type, value, speed, tripId = t.id, vehicleId = t.vehicleId))
    }

    fun update(speed: Double?, rpm: Double?, rate: Double?, coolant: Double?) {
        val now = System.currentTimeMillis()
        instantRate = rate
        instantKmpl = if (speed != null && rate != null && rate > 0.05 && speed > 2) speed / rate else null
        val t = current ?: return
        val v = vehicle
        if (lastT == 0L) {
            lastT = now
            return
        }
        val dt = (now - lastT) / 1000.0
        lastT = now
        if (dt <= 0 || dt > 5) return
        val spd = speed ?: 0.0
        val r = rpm ?: 0.0
        val running = r > 300

        t.distanceKm += spd * dt / 3600.0
        if (running && rate != null) t.fuel += rate * dt / 3600.0
        if (spd < 1 && running) {
            t.idleS += dt
            if (rate != null) t.idleFuel += rate * dt / 3600.0
            if (idleStart == 0L) { idleStart = now; idleReported = false }
            if (!idleReported && now - idleStart > Prefs.longIdleS * 1000L) {
                idleReported = true
                Alerts.say("idle", "Long idle. Consider switching off the engine.", 300_000)
            }
        } else {
            finishIdle(t, now)
            if (spd >= 1) t.movingS += dt
        }
        if (spd > t.maxSpeed) t.maxSpeed = spd
        if (r > t.maxRpm) t.maxRpm = r
        if (coolant != null && coolant > t.maxCoolant) t.maxCoolant = coolant

        // --- longitudinal acceleration over a ~1 s window (OBD speed is 1 km/h resolution) ---
        if (speed != null) {
            spdHist.addLast(now to speed)
            while (spdHist.size > 2 && now - spdHist.first().first > 3000) spdHist.removeFirst()
            val ref = spdHist.lastOrNull { now - it.first >= 900 }
            if (ref != null) {
                val acc = (speed - ref.second) / 3.6 / ((now - ref.first) / 1000.0) / 9.81 // in g
                liveAccelG = acc
                // rapid acceleration
                if (acc >= Prefs.accelG) {
                    inAcc = true; accPeak = maxOf(accPeak, acc)
                } else if (inAcc && acc < Prefs.accelG * 0.6) {
                    emit(t, DriveEvent.EV_ACC, accPeak, speed); inAcc = false; accPeak = 0.0
                }
                // hard / sudden braking (rapid deceleration)
                val dec = -acc
                if (dec >= Prefs.brakeG) {
                    inBrk = true; brkPeak = maxOf(brkPeak, dec)
                } else if (inBrk && dec < Prefs.brakeG * 0.6) {
                    val sudden = brkPeak >= Prefs.suddenG
                    emit(t, if (sudden) DriveEvent.EV_SUDDEN else DriveEvent.EV_BRAKE, brkPeak, speed)
                    if (sudden) Alerts.say("sudden", "Sudden braking detected", 20_000)
                    inBrk = false; brkPeak = 0.0
                }
            }
        }

        // --- overspeed ---
        val limit = (v?.speedLimit ?: 90).toDouble()
        if (spd > limit) {
            if (osStart == 0L) {
                osStart = now; osPeak = spd
                Alerts.say("overspeed", "Overspeed. Limit ${limit.toInt()}", 45_000)
            }
            osPeak = maxOf(osPeak, spd)
            t.overspeedS += dt
            overspeedNow = true
        } else if (spd < limit - 2) finishOverspeed(t, now)

        // --- over-rev and cold engine high RPM ---
        val rpmLimit = (v?.rpmLimit ?: 4500).toDouble()
        if (r > rpmLimit && revArmed) {
            revArmed = false
            emit(t, DriveEvent.EV_OVERREV, r, spd)
            Alerts.say("overrev", "High RPM", 30_000)
        } else if (r < rpmLimit - 500) revArmed = true
        if (coolant != null && coolant < 60 && r > 3000 && now - lastColdRev > 60_000) {
            lastColdRev = now
            emit(t, DriveEvent.EV_COLDREV, r, spd)
            Alerts.say("coldrev", "Engine is cold. Keep RPM low.", 120_000)
        }

        // --- 0-60 / 0-100 km/h timing ---
        if (spd <= 1) zeroT = now
        else if (zeroT > 0 && speed != null) {
            val el = (now - zeroT) / 1000.0
            if (el > 30) zeroT = 0
            else {
                if (spd >= 60 && (t.best060 == 0.0 || el < t.best060) && el > 1.5) t.best060 = el
                if (spd >= 100 && el > 3) {
                    if (t.best0100 == 0.0 || el < t.best0100) t.best0100 = el
                    zeroT = 0
                }
            }
        }
    }

    private fun finishOverspeed(t: Trip, now: Long) {
        if (osStart != 0L) {
            if (now - osStart >= 3000) emit(t, DriveEvent.EV_OVERSPEED, osPeak, osPeak)
            osStart = 0L
        }
        overspeedNow = false
    }

    private fun finishIdle(t: Trip, now: Long) {
        if (idleStart != 0L) {
            val d = (now - idleStart) / 1000.0
            if (d > Prefs.longIdleS) emit(t, DriveEvent.EV_IDLE, d, 0.0)
            idleStart = 0L
        }
    }

    /** GPS fix: distance, speedometer comparison, harsh cornering from bearing rate. */
    fun onGps(l: Location, obdSpeed: Double?) {
        val t = current ?: run { lastGps = l; return }
        val p = lastGps
        lastGps = l
        if (p == null || !l.hasSpeed() || l.accuracy > 25) return
        val dt = (l.time - p.time) / 1000.0
        if (dt <= 0 || dt > 4) return
        val d = p.distanceTo(l)
        if (d < 200) t.gpsKm += d / 1000.0
        val gpsKmh = l.speed * 3.6
        if (obdSpeed != null && gpsKmh > 30 && obdSpeed > 30) {
            t.sumObdSpd += obdSpeed; t.sumGpsSpd += gpsKmh
        }
        if (l.hasBearing() && p.hasBearing() && l.speed > 5.5f) {
            val signed = ((l.bearing - p.bearing + 540.0) % 360.0) - 180.0
            val db = abs(signed)
            val lat = l.speed * Math.toRadians(db) / dt / 9.81
            liveLatG = if (signed >= 0) lat else -lat
            liveLatAt = System.currentTimeMillis()
            if (lat >= Prefs.cornerG && cornerArmed) {
                cornerArmed = false
                emit(t, DriveEvent.EV_CORNER, lat, gpsKmh)
            } else if (lat < Prefs.cornerG * 0.6) cornerArmed = true
        }
    }
}
