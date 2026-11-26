package com.dilip.carobd

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import kotlin.math.abs

/** One entry in the alert log. level: 0 info, 1 warning, 2 critical. */
class AlertRec(
    val vehicleId: Long,
    val t: Long,
    val level: Int,
    val key: String,
    val title: String,
    val text: String,
    val id: Long = 0
)

/**
 * Rule-based vehicle alert engine. Called from the OBD worker every poll cycle; each rule is
 * edge-triggered with hysteresis plus a cooldown, so you get one alert per problem, not a flood.
 * Alerts are logged (More → Alerts), spoken (if voice is on) and posted as notifications.
 */
object AlertCenter {
    const val INFO = 0
    const val WARN = 1
    const val CRIT = 2

    private val last = HashMap<String, Long>()
    private val latched = HashSet<String>()
    @Volatile var latest: AlertRec? = null
        private set
    @Volatile var latestAt = 0L
        private set
    private var runningSince = 0L
    private var lastCheck = 0L

    fun resetSession() {
        synchronized(latched) { latched.clear() }
        runningSince = 0L
    }

    fun levelColor(level: Int) = when (level) {
        CRIT -> Color.parseColor("#D32F2F")
        WARN -> Color.parseColor("#F57C00")
        else -> Color.parseColor("#1976D2")
    }

    fun levelIcon(level: Int) = when (level) {
        CRIT -> "⛔"
        WARN -> "⚠️"
        else -> "ℹ️"
    }

    fun raise(
        ctx: Context, key: String, level: Int, title: String, text: String,
        cooldownMs: Long = 10 * 60_000L, speak: String? = null, notify: Boolean = level >= WARN
    ) {
        val now = System.currentTimeMillis()
        synchronized(last) {
            if (now - (last[key] ?: 0L) < cooldownMs) return
            last[key] = now
        }
        val rec = AlertRec(ObdManager.vehicle.id, now, level, key, title, text)
        try { Db.insertAlert(rec) } catch (_: Exception) {}
        latest = rec
        latestAt = now
        ObdManager.log("ALERT: $title")
        if (speak != null) Alerts.say("al_$key", speak, minOf(cooldownMs, 120_000L))
        if (notify && Prefs.alertNotify) post(ctx, rec)
    }

    /** true on the rising edge of [on]; re-arms when [off] becomes true. */
    private fun edge(key: String, on: Boolean, off: Boolean): Boolean = synchronized(latched) {
        if (on && key !in latched) {
            latched.add(key); true
        } else {
            if (off) latched.remove(key)
            false
        }
    }

    fun check(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 2000) return
        lastCheck = now
        val m = ObdManager
        val v = m.values
        val rpm = v[0x0C] ?: 0.0
        val running = m.engineOn && rpm > 450
        runningSince = if (running) (if (runningSince == 0L) now else runningSince) else 0L
        val runS = if (runningSince > 0) (now - runningSince) / 1000 else 0L

        v[0x05]?.let { c ->
            if (edge("cool", c >= Prefs.coolantMax, c < Prefs.coolantMax - 5))
                raise(ctx, "cool", CRIT, "Engine overheating – %.0f °C".format(c),
                    "Coolant is above %.0f °C. Stop safely, let the engine idle briefly, then switch off. When cool, check coolant level, radiator fan and leaks.".format(Prefs.coolantMax),
                    5 * 60_000L, "Warning. Engine overheating.")
        }
        v[0x5C]?.let { o ->
            if (edge("oil", o >= 130, o < 120))
                raise(ctx, "oil", CRIT, "Engine oil hot – %.0f °C".format(o),
                    "Oil temperature is very high. Reduce load and speed; check oil level.", 5 * 60_000L, "Engine oil temperature high")
        }
        v[0x0F]?.let { i ->
            if (edge("iat", i >= 70, i < 60))
                raise(ctx, "iat", INFO, "High intake air temperature – %.0f °C".format(i),
                    "Heat-soak reduces power and economy. Common after idling in traffic.", 30 * 60_000L)
        }
        v[0x3C]?.let { cat ->
            if (edge("cat", cat >= 900, cat < 850))
                raise(ctx, "cat", WARN, "Catalyst temperature high – %.0f °C".format(cat),
                    "Possible misfire or rich running. Avoid heavy load; scan for fault codes.", 15 * 60_000L)
        }

        val bv = m.batteryV
        if (bv != null) {
            if (running && runS > 30) {
                if (edge("chgLow", (bv < Prefs.chargeMinV), (bv > Prefs.chargeMinV + 0.3)))
                    raise(ctx, "chgLow", WARN, "Battery not charging – %.1f V".format(bv),
                        "With the engine running the voltage should be about 13.5–14.8 V. Check alternator, drive belt and battery terminals.",
                        15 * 60_000L, "Battery not charging")
                if (edge("chgHigh", bv > Prefs.chargeMaxV, bv < Prefs.chargeMaxV - 0.3))
                    raise(ctx, "chgHigh", CRIT, "Charging over-voltage – %.1f V".format(bv),
                        "Charging voltage is too high – possible regulator fault. This can damage the battery and electronics.",
                        15 * 60_000L, "Charging voltage too high")
            } else if (!m.engineOn) {
                if (edge("park", (bv < Prefs.parkLowV), (bv > Prefs.parkLowV + 0.3)))
                    raise(ctx, "park", WARN, "Battery low while parked – %.1f V".format(bv),
                        "Resting voltage is low. Start the engine or charge the battery. Unplug the OBD adapter if the car will stand for days.",
                        60 * 60_000L)
            }
        }

        v[0x2F]?.let { f ->
            if (running && edge("fuel", f <= Prefs.lowFuelPct, f > Prefs.lowFuelPct + 5)) {
                val rng = m.rangeKm()
                raise(ctx, "fuel", WARN, "Low fuel – %.0f%%".format(f),
                    "Estimated range ${rng?.let { "%.0f km".format(it) } ?: "--"}. Refuel soon.", 30 * 60_000L, "Low fuel")
            }
        }

        m.monitorRaw?.let { d ->
            if (d.isNotEmpty()) {
                val mil = (d[0] and 0x80) != 0
                val n = d[0] and 0x7F
                if (edge("mil", mil, !mil))
                    raise(ctx, "mil", CRIT, "Check-engine light is ON",
                        "$n fault code(s) stored. Open Diag → Read DTCs for details and fix hints.", 30 * 60_000L,
                        "Check engine light is on")
            }
        }
    }

    /** Called from the trip logic ~90 s into a trip with the previous trip's end fuel level. */
    fun checkFuelJump(ctx: Context, prevEndPct: Double, nowPct: Double, tankL: Double, unit: String) {
        if (prevEndPct < 0) return
        val d = nowPct - prevEndPct
        if (d >= 10) {
            raise(ctx, "refuel", INFO, "Refuel detected: +%.0f%% (≈ %.0f %s)".format(d, d * tankL / 100, unit),
                "Add it in More → Fuel log so real mileage stays accurate.", 30 * 60_000L, notify = true)
        } else if (d <= -8) {
            raise(ctx, "fueldrop", WARN, "Fuel dropped %.0f%% while parked".format(abs(d)),
                "Fuel level fell by about %.0f %s since the last trip without driving. Check for leaks or fuel theft (gauge noise on slopes is possible).".format(abs(d) * tankL / 100, unit),
                60 * 60_000L)
        }
    }

    fun post(ctx: Context, a: AlertRec) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = when (a.level) {
            CRIT -> "crit_v3"
            WARN -> "warn_v3"
            else -> "info_v3"
        }
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("crit_v3", "Critical car alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400); enableLights(true); lightColor = Color.RED
            })
            nm.createNotificationChannel(NotificationChannel("warn_v3", "Car warnings", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel("info_v3", "Car information", NotificationManager.IMPORTANCE_LOW))
        }
        val i = Intent(ctx, MainActivity::class.java).putExtra("open", "Alerts")
        val pi = PendingIntent.getActivity(ctx, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch) else Notification.Builder(ctx).apply {
            setPriority(if (a.level == CRIT) Notification.PRIORITY_HIGH else Notification.PRIORITY_DEFAULT)
            if (a.level == CRIT) setDefaults(Notification.DEFAULT_ALL)
        }
        val n = b.setContentTitle("${levelIcon(a.level)} ${a.title}")
            .setContentText(a.text)
            .setStyle(Notification.BigTextStyle().bigText(a.text + "\n" + ObdManager.vehicle.name))
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(levelColor(a.level))
            .setSubText(AppInfo.NAME)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(5000 + (abs(a.key.hashCode()) % 900), n)
    }
}
