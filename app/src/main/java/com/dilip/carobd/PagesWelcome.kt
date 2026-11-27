package com.dilip.carobd

import android.annotation.SuppressLint
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView

/** One-time setup shown after installation: restore an old backup, vehicle, units, permissions and offline maps. */
@SuppressLint("SetTextI18n")
class WelcomePage(a: MainActivity) : Page(a) {
    private lateinit var box: LinearLayout
    private lateinit var dlBar: ProgressBar
    private lateinit var dlTv: TextView
    private lateinit var mapTv: TextView
    private lateinit var permTv: TextView
    private val vtypes = listOf("Car", "Truck", "Bus")
    private val radii = listOf(10, 25, 50, 100, 200)
    private var nameE: android.widget.EditText? = null
    private var typeSp: Spinner? = null
    private var fuelSp: Spinner? = null
    private var distSp: Spinner? = null

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    override fun onShow() {
        box.removeAllViews()
        val v = ObdManager.vehicle
        box.addView(ui.iconCard(Ic.CHECK, Ui.GOOD, "Welcome to ${AppInfo.NAME}", "A one-minute setup. You see this only once, and everything can be changed later in Settings."), ui.lp(8))

        box.addView(ui.section("Used the app before?", Ic.DATABASE))
        box.addView(ui.tv("Restore a backup (.carobd file, from Google Drive, OneDrive, Dropbox or this phone) to bring back vehicles, trips, fuel log, service records, settings, taught signs and your offline maps. Backups from older versions of the app work too.", 12.5f, Ui.DIM))
        box.addView(ui.btn("Restore from a backup", Ui.GOOD) { pickBackup() }, ui.lp(8))

        box.addView(ui.section("Your vehicle", Ic.CAR))
        nameE = ui.edit("My car", if (v.name == "My car") "" else v.name)
        box.addView(ui.labeled("Name", nameE!!), ui.lp(4))
        typeSp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, listOf("Car / SUV / van", "Truck / heavy goods", "Bus"))
            setSelection(vtypes.indexOf(Prefs.vehicleClass(v.id)).coerceAtLeast(0))
        }
        box.addView(ui.labeled("Vehicle type (road speed limits)", typeSp!!), ui.lp(4))
        fuelSp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, Fuels.all.map { it.name })
            setSelection(Fuels.all.indexOfFirst { it.name == v.fuel }.coerceAtLeast(0))
        }
        box.addView(ui.labeled("Fuel type", fuelSp!!), ui.lp(4))
        distSp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, listOf("Kilometres (km, km/h)", "Miles (mi, mph)"))
            setSelection(if (Prefs.unitDist == "mi") 1 else 0)
        }
        box.addView(ui.labeled("Distance and speed", distSp!!), ui.lp(4))
        box.addView(ui.tv("Engine size, tank, price and the rest are in Garage.", 11.5f, Ui.DIM), ui.lp(2))

        box.addView(ui.section("Permissions", Ic.SHIELD))
        permTv = ui.tv("", 12.5f, Ui.DIM)
        box.addView(permTv)
        box.addView(ui.btn("Allow Bluetooth, location and notifications", Ui.ACC) { a.requestPerms(); refresh() }, ui.lp(6))

        box.addView(ui.section("Road assistant", Ic.ROUTE))
        box.addView(ui.check("Speed limit and hazard announcements while driving", Prefs.roadAssist).apply {
            setOnCheckedChangeListener { _, on ->
                Prefs.roadAssist = on
                if (on) { if (!Perms.locationOk(a)) a.requestPerms(); try { ObdService.start(a); Gps.start(a) } catch (_: Exception) {} }
                else if (!Prefs.backgroundWatch) try { Gps.stop() } catch (_: Exception) {}
            }
        }, ui.lp(4))

        box.addView(ui.section("Offline maps", Ic.DOWNLOAD))
        box.addView(ui.tv("Download the roads around you now so speed limits work with no internet and nothing waits to load. It runs in the background; you can carry on.", 12.5f, Ui.DIM))
        val sp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, radii.map { "Map radius $it km  (about ${RoadAssist.areasFor(it)} areas)" })
            setSelection(radii.indexOf(Prefs.mapRadiusKm).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) { Prefs.mapRadiusKm = radii[pos] }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        box.addView(sp, ui.lp(4))
        dlBar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        box.addView(dlBar, ui.lp(4))
        dlTv = ui.tv("", 12.5f, Ui.WHITE, true).apply { visibility = View.GONE }
        box.addView(dlTv, ui.lp(2))
        mapTv = ui.tv(RoadAssist.savedInfo(), 12f, Ui.DIM)
        box.addView(mapTv)
        box.addView(ui.row(
            ui.btn("Download map now", Ui.ACC) {
                RoadAssist.downloadWide(Prefs.mapRadiusKm) { m -> ui.toast(m); if (::mapTv.isInitialized) mapTv.text = RoadAssist.savedInfo() }
                refresh()
            },
            ui.btn("Stop", Ui.CARD) { RoadAssist.cancelDownload() }
        ), ui.lp(6))
        box.addView(ui.check("Keep highway maps up to date ahead of me automatically", Prefs.mapAuto).apply { setOnCheckedChangeListener { _, on -> Prefs.mapAuto = on } }, ui.lp(4))

        box.addView(ui.btn("Finish setup", Ui.GOOD) { finish(true) }, ui.lp(14))
        box.addView(ui.btn("Skip for now", Ui.CARD) { finish(false) }, ui.lp(6))
        box.addView(ui.tv("You can reopen this guide any time from More → Setup guide.", 11.5f, Ui.DIM), ui.lp(12))
        refresh()
    }

    private fun pickBackup() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "*/*" }
        @Suppress("DEPRECATION") a.startActivityForResult(i, BackupPage.REQ_CLOUD_OPEN)
    }

    private fun finish(save: Boolean) {
        if (save) try {
            val v = ObdManager.vehicle
            nameE?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { v.name = it }
            fuelSp?.let { v.fuel = Fuels.all[it.selectedItemPosition.coerceAtLeast(0)].name }
            Db.saveVehicle(v)
            typeSp?.let { Prefs.setVehicleClass(v.id, vtypes[it.selectedItemPosition.coerceAtLeast(0)]) }
            distSp?.let { Prefs.unitDist = if (it.selectedItemPosition == 1) "mi" else "km" }
            ObdManager.reloadVehicle(); RoadAssist.reloadUserData()
        } catch (_: Throwable) {}
        Prefs.setupDone = true
        a.show(if (Prefs.lastAddr.isEmpty() && ObdManager.state != ObdManager.State.CONNECTED) "Connect" else "Home")
    }

    override fun refresh() {
        if (!::permTv.isInitialized) return
        val bt = if (android.os.Build.VERSION.SDK_INT >= 31) a.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED else true
        permTv.text = "Bluetooth: ${if (bt) "allowed" else "not yet"} · Location: ${if (Perms.locationOk(a)) "allowed" else "not yet"}. Location is used for trips and the road assistant; nothing leaves your phone."
        val r = RoadAssist
        if (r.dlBusy) { dlBar.visibility = View.VISIBLE; dlTv.visibility = View.VISIBLE; dlBar.progress = r.dlPct; dlTv.text = r.dlText }
        else if (::dlBar.isInitialized) { dlBar.visibility = View.GONE; dlTv.visibility = View.GONE }
    }
}
