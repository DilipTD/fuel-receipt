package com.dilip.carobd

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/* ---------------- Voice / beep alerts and notifications ---------------- */

object Alerts : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var tone: ToneGenerator? = null
    private val last = HashMap<String, Long>()
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (tts == null) {
            try {
                tts = TextToSpeech(app, this)
            } catch (_: Exception) {
            }
        }
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) try {
            tts?.language = Locale.getDefault()
        } catch (_: Exception) {
        }
    }

    /** Speaks (or beeps) at most once per [cooldownMs] for each key. */
    fun say(key: String, text: String, cooldownMs: Long = 30_000) {
        if (!Prefs.eventAlerts) return
        val now = System.currentTimeMillis()
        synchronized(last) {
            if (now - (last[key] ?: 0L) < cooldownMs) return
            last[key] = now
        }
        if (Prefs.voiceAlerts && ready) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, key)
        } else if (Prefs.beepAlerts) beep()
    }

    /** Short beep for driving events when voice is off or busy. */
    fun beepIfQuiet() {
        if (Prefs.eventAlerts && Prefs.beepAlerts) beep()
    }

    fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
        } catch (_: Exception) {
        }
    }

    fun notify(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("alerts", "Car alerts & reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "alerts") else Notification.Builder(ctx)
        val n = b.setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}

/* ---------------- GPS ---------------- */

object Gps : LocationListener {
    @Volatile var last: Location? = null
        private set
    private var lm: LocationManager? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile var running = false
        private set

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context) {
        if (!Prefs.gpsEnabled || !hasPermission(ctx) || running) return
        val m = ctx.getSystemService(LocationManager::class.java) ?: return
        lm = m
        main.post {
            try {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                running = true
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        main.post {
            try {
                lm?.removeUpdates(this)
            } catch (_: Exception) {
            }
            running = false
            last = null
        }
    }

    /** Latest fix if it is less than 5 s old. */
    fun fresh(): Location? {
        val l = last ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        return if (ageMs < 5000) l else null
    }

    override fun onLocationChanged(location: Location) {
        last = location
        ObdManager.onGps(location)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }
}

/* ---------------- Maintenance schedule ---------------- */

object Maint {
    /** name, km interval, day interval (0 = not used). Universal defaults; edit per vehicle. */
    val defaults = listOf(
        Triple("Engine oil & oil filter", 10000.0, 365),
        Triple("Air filter", 20000.0, 730),
        Triple("Cabin / AC filter", 15000.0, 365),
        Triple("Spark plugs (petrol/CNG)", 30000.0, 1095),
        Triple("Fuel filter", 40000.0, 1460),
        Triple("Coolant replacement", 40000.0, 1095),
        Triple("Brake fluid", 40000.0, 730),
        Triple("Brake pads inspection", 10000.0, 365),
        Triple("Transmission / gearbox oil", 40000.0, 1460),
        Triple("Tyre rotation & balancing", 10000.0, 180),
        Triple("Wheel alignment", 10000.0, 365),
        Triple("Battery health check", 0.0, 180),
        Triple("Wiper blades", 0.0, 365),
        Triple("Drive belt inspection", 40000.0, 1095),
        Triple("Insurance renewal", 0.0, 365),
        Triple("Emission (PUC) certificate", 0.0, 180),
        Triple("General service", 10000.0, 365)
    )

    fun loadDefaults(v: Vehicle) {
        val now = System.currentTimeMillis()
        for ((n, km, d) in defaults) Db.saveMaint(MaintItem(0, v.id, n, km, d, v.odoKm, now, true))
    }

    class Due(val item: MaintItem, val kmLeft: Double?, val daysLeft: Int?) {
        val overdue get() = (kmLeft != null && kmLeft < 0) || (daysLeft != null && daysLeft < 0)
        val soon get() = !overdue && ((kmLeft != null && kmLeft < 500) || (daysLeft != null && daysLeft < 15))
        val text: String
            get() {
                val parts = mutableListOf<String>()
                kmLeft?.let { parts += if (it < 0) "%.0f km overdue".format(-it) else "%.0f km left".format(it) }
                daysLeft?.let { parts += if (it < 0) "${-it} days overdue" else "$it days left" }
                return parts.joinToString(" · ")
            }
    }

    fun due(item: MaintItem, odo: Double, now: Long = System.currentTimeMillis()): Due {
        val km = if (item.intervalKm > 0) item.lastKm + item.intervalKm - odo else null
        val days = if (item.intervalDays > 0) ((item.lastT + item.intervalDays * 86_400_000L - now) / 86_400_000L).toInt() else null
        return Due(item, km, days)
    }

    fun dueList(v: Vehicle): List<Due> =
        Db.maintItems(v.id).filter { it.enabled }.map { due(it, v.odoKm) }
            .sortedBy { minOf(it.kmLeft?.div(50.0) ?: 99999.0, (it.daysLeft ?: 99999).toDouble()) }

    fun checkAndNotify(ctx: Context) {
        for (v in Db.vehicles()) {
            val list = dueList(v).filter { it.overdue || it.soon }
            if (list.isEmpty()) continue
            val text = list.take(6).joinToString("\n") { "• ${it.item.name}: ${it.text}" }
            Alerts.notify(ctx, 3000 + v.id.toInt(), "Service due – ${v.name}", text)
        }
    }

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.allPendingJobs.any { it.id == 4242 }) return
        val job = JobInfo.Builder(4242, ComponentName(ctx, MaintJobService::class.java))
            .setPeriodic(12 * 3600_000L)
            .setPersisted(true)
            .build()
        try {
            js.schedule(job)
        } catch (_: Exception) {
        }
    }
}

/** Periodic check (twice a day) for due maintenance and document renewals. */
class MaintJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            Prefs.init(this)
            Db.init(this)
            Maint.checkAndNotify(this)
        } catch (_: Exception) {
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

/* ---------------- Foreground service ---------------- */

/** Keeps the app alive (logging trip, GPS) while the screen is off. */
class ObdService : Service() {
    private var wl: PowerManager.WakeLock? = null

    companion object {
        @Volatile var running = false
            private set

        fun build(ctx: Context, title: String, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("obd", "OBD connection & live trip", NotificationManager.IMPORTANCE_LOW))
            }
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "obd") else Notification.Builder(ctx)
            return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(Color.parseColor("#2F7FD0"))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        /** Refreshes the ongoing notification with live trip figures. */
        fun update(ctx: Context, title: String, text: String) {
            if (!running) return
            try {
                ctx.getSystemService(NotificationManager::class.java)?.notify(1, build(ctx, title, text))
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, AppInfo.NAME, "Connected – logging live data, trip & GPS")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (Gps.hasPermission(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(1, n, type)
            } else {
                startForeground(1, n)
            }
            running = true
        } catch (e: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                else throw e
                running = true
            } catch (e2: Exception) {
                ObdManager.log("Foreground service not allowed: ${e2.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (wl == null) {
            val pm = getSystemService(PowerManager::class.java)
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarOBD:link").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            wl?.release()
        } catch (_: Exception) {
        }
        wl = null
        super.onDestroy()
    }
}
