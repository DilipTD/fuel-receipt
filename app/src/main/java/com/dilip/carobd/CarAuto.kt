package com.dilip.carobd

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Android Auto (the Android counterpart of CarPlay): live readings on the car display. The phone app keeps the OBD link. */
class DiagCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = LiveCarScreen(carContext)
    }
}

class LiveCarScreen(ctx: CarContext) : Screen(ctx) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 1500) }
    }

    init {
        ObdManager.init(ctx.applicationContext)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { handler.post(tick) }
            override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(tick) }
        })
    }

    private fun row(t: String, v: String) = Row.Builder().setTitle(t).addText(v).build()

    override fun onGetTemplate(): Template {
        val m = ObdManager
        val v = m.values
        val pane = Pane.Builder()
        if (m.state != ObdManager.State.CONNECTED) {
            pane.addRow(row("Not connected", "Open ${AppInfo.NAME} on the phone and connect to the adapter."))
        } else {
            val spd = v[0x0D]; val rpm = v[0x0C]
            pane.addRow(row("Speed  ·  Engine", "${spd?.let { "%.0f %s".format(Units.spd(it), Units.spdLabel) } ?: "--"}   ·   ${rpm?.let { "%.0f rpm".format(it) } ?: "--"}"))
            pane.addRow(row("Coolant  ·  Intake air",
                "${v[0x05]?.let { "%.0f%s".format(Units.temp(it), Units.tempLabel) } ?: "--"}   ·   ${v[0x0F]?.let { "%.0f%s".format(Units.temp(it), Units.tempLabel) } ?: "--"}"))
            val fuel = v[0x2F]
            pane.addRow(row("12 V battery  ·  Fuel level",
                "${(m.batteryV ?: v[0x42])?.let { "%.1f V".format(it) } ?: "--"}   ·   ${fuel?.let { "%.0f %%".format(it) } ?: "--"}"))
            val t = m.engine.current
            pane.addRow(row("Economy  ·  Range",
                "${Units.econ(m.engine.instantKmpl ?: t?.kmpl ?: 0.0).takeIf { (m.engine.instantKmpl ?: t?.kmpl) != null } ?: "--"}   ·   ${m.rangeKm()?.let { "%.0f %s".format(Units.km(it), Units.distLabel) } ?: "--"}"))
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle(AppInfo.NAME)
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
