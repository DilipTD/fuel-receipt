package com.dilip.carobd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.LinearLayout

/** Central place to switch location / background features on and off. */
object Perms {
    fun has(a: MainActivity, p: String) = a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun locationOk(a: MainActivity) = has(a, Manifest.permission.ACCESS_FINE_LOCATION) || has(a, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun bluetoothOk(a: MainActivity) = Build.VERSION.SDK_INT < 31 || (has(a, Manifest.permission.BLUETOOTH_SCAN) && has(a, Manifest.permission.BLUETOOTH_CONNECT))
    fun notifyOk(a: MainActivity) = Build.VERSION.SDK_INT < 33 || has(a, Manifest.permission.POST_NOTIFICATIONS)

    fun setBackground(a: MainActivity, on: Boolean) {
        Prefs.roadAssist = on; Prefs.backgroundWatch = on
        if (on) {
            if (!locationOk(a)) a.requestPerms()
            try { ObdService.start(a) } catch (_: Exception) {}
            try { Gps.start(a) } catch (_: Exception) {}
        } else {
            try { Gps.stop() } catch (_: Exception) {}
            try { a.stopService(Intent(a, ObdService::class.java)) } catch (_: Exception) {}
        }
    }

    fun openSettings(a: MainActivity) {
        try { a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))) } catch (_: Exception) {}
    }
}

/** More → Permissions: status of every Android permission and the app's own feature switches. */
class PermissionsPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout
    override fun build(): View { box = ui.col(); return ui.scroll(box) }
    override fun onShow() = render()

    private fun permRow(ic: Ic, title: String, why: String, ok: Boolean, req: List<String>) {
        val st = if (ok) "ON – granted" else "OFF – tap to allow"
        box.addView(ui.iconCard(ic, if (ok) Ui.GOOD else Ui.ORANGE, title, "$why\n$st", Ui.CARD) {
            if (ok) { ui.toast("Android lets you turn a permission off only in system settings"); Perms.openSettings(a) }
            else {
                val missing = req.filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
                if (missing.isNotEmpty() && missing.any { a.shouldShowRequestPermissionRationale(it) } || missing.isNotEmpty() && !Prefs.askedPerm(title)) {
                    Prefs.markAskedPerm(title); a.requestPermissions(missing, 1)
                } else Perms.openSettings(a)
            }
        }, ui.lp(6))
    }

    private fun feature(title: String, desc: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = ui.check(title, get())
        sw.setOnCheckedChangeListener { _, v -> set(v); render() }
        box.addView(sw, ui.lp(2))
        box.addView(ui.tv(desc, 11f, Ui.DIM).apply { setPadding(ui.dp(8), 0, ui.dp(8), 0) }, ui.lp(2))
    }

    private fun render() {
        box.removeAllViews()
        box.addView(ui.tv("Permissions & features", 22f, Ui.WHITE, true), ui.lp(4))
        box.addView(ui.tv("Turn things on or off. Android permissions are changed through Android's own screen; the switches below are the app's own features.", 12f, Ui.DIM), ui.lp(2))

        box.addView(ui.section("Android permissions", Ic.SHIELD))
        permRow(Ic.PIN, "Location", "Trip routes, speed, road assistant, parking, fuel station", Perms.locationOk(a),
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        if (Build.VERSION.SDK_INT >= 31)
            permRow(Ic.BLUETOOTH, "Nearby devices (Bluetooth)", "Find and connect to your OBD adapter", Perms.bluetoothOk(a),
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        if (Build.VERSION.SDK_INT >= 33)
            permRow(Ic.BELL, "Notifications", "Fault, over-speed and reminder alerts, running-service notice", Perms.notifyOk(a),
                listOf(Manifest.permission.POST_NOTIFICATIONS))
        permRow(Ic.SCAN, "Camera", "Sign camera only – reads road signs on the phone, pictures are not saved or sent",
            a.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED, listOf(Manifest.permission.CAMERA))
        box.addView(ui.iconCard(Ic.GEAR, Ui.CYAN, "Open Android app settings", "Files and other system-level controls", Ui.CARD) { Perms.openSettings(a) }, ui.lp(6))

        box.addView(ui.section("Location features", Ic.NAV))
        feature("Road assistant", "Speed limit, direction and hazard announcements; uses GPS in the background with a notification.",
            { Prefs.roadAssist }, { v -> Prefs.roadAssist = v; if (v) { if (!Perms.locationOk(a)) a.requestPerms(); try { ObdService.start(a); Gps.start(a) } catch (_: Exception) {} } else if (!Prefs.backgroundWatch) try { Gps.stop() } catch (_: Exception) {} })
        feature("Announce speed limits & hazards", "Spoken messages for limit changes, speed breakers, cameras and school zones.",
            { Prefs.roadAnnounce }, { Prefs.roadAnnounce = it })
        feature("Hazard warnings", "Warn about speed breakers, cameras, school and hospital zones ahead.",
            { Prefs.roadHazards }, { Prefs.roadHazards = it })
        feature("Background watching", "Keeps the OBD connection and trip recording alive when the app is closed.",
            { Prefs.backgroundWatch }, { v -> Prefs.backgroundWatch = v; if (v) try { ObdService.start(a) } catch (_: Exception) {} else if (!Prefs.roadAssist) try { a.stopService(Intent(a, ObdService::class.java)) } catch (_: Exception) {} })

        box.addView(ui.section("Connection & alerts", Ic.PLUG))
        feature("Auto-connect to the adapter", "Reconnect automatically to the last adapter when the app opens.",
            { Prefs.autoConnect }, { Prefs.autoConnect = it })
        feature("Voice alerts", "Speak warnings such as over-heating, low battery and over-speed.",
            { Prefs.voiceAlerts }, { Prefs.voiceAlerts = it })
        feature("Driving-event notifications", "Notify on hard braking, rapid acceleration and similar events.",
            { Prefs.notifyDrivingEvents }, { Prefs.notifyDrivingEvents = it })

        box.addView(ui.section("Data", Ic.DATABASE))
        feature("Daily automatic backup", "Saves a backup once a day to the cloud/file you linked in Backup.",
            { Prefs.autoBackup }, { Prefs.autoBackup = it })
        box.addView(ui.iconCard(Ic.TIMER, Ui.VIOLET, "Run in background without limits", "Opens the system battery settings so Android does not stop the app", Ui.CARD) {
            try { a.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }, ui.lp(8))
        box.addView(ui.iconCard(Ic.SHIELD, Ui.GOOD, "Privacy & legal", "Policy, terms, delete all my data", Ui.CARD) { a.show("Legal") }, ui.lp(6))
    }
}
