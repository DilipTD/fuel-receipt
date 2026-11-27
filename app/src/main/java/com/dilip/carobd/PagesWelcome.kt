package com.dilip.carobd

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView

/**
 * One-time setup after installation, in 4 short steps:
 *  1 Welcome & privacy (new setup or restore a backup)  2 Vehicle  3 Permissions  4 Road assistant & offline map.
 * Nothing pops up by itself: every Android permission is asked only when you tap its button here.
 * After a restore, only the permissions step is shown (everything else came back from the backup).
 */
@SuppressLint("SetTextI18n")
class WelcomePage(a: MainActivity) : Page(a) {
    private lateinit var box: LinearLayout
    private var dlBar: ProgressBar? = null
    private var dlTv: TextView? = null
    private var mapTv: TextView? = null
    private val vtypes = listOf("Car", "Truck", "Bus")
    private val radii = listOf(10, 25, 50, 100, 200)
    private var nameE: android.widget.EditText? = null
    private var typeSp: Spinner? = null
    private var fuelSp: Spinner? = null
    private var distSp: Spinner? = null
    private var permSig = ""

    /** 0 welcome, 1 vehicle, 2 permissions, 3 road & maps. */
    var step: Int
        get() = Prefs.setupStep.coerceIn(0, 3)
        set(v) { Prefs.setupStep = v.coerceIn(0, 3) }

    private val restored get() = Prefs.setupAfterRestore

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    override fun onShow() {
        // reopened later from More → Setup guide: no need to agree again
        if (Prefs.setupDone && !restored && step == 0) step = 1
        if (restored) step = 2
        render()
    }

    private fun render() {
        box.removeAllViews()
        dlBar = null; dlTv = null; mapTv = null
        if (!restored) box.addView(stepper(), ui.lp(2))
        when (step) {
            0 -> stepWelcome()
            1 -> stepVehicle()
            2 -> stepPerms()
            else -> stepRoad()
        }
        refresh()
        (box.parent as? android.widget.ScrollView)?.scrollTo(0, 0)
    }

    private fun stepper(): View {
        val names = listOf("Welcome", "Vehicle", "Permissions", "Road & maps")
        return LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.tv("Step ${step + 1} of 4 · ${names[step]}", 12.5f, Ui.DIM, true))
            addView(LinearLayout(a).apply {
                for (i in 0..3) addView(View(a).apply { background = ui.rounded(if (i <= step) Ui.CYAN else Ui.STROKE, 3) },
                    LinearLayout.LayoutParams(0, ui.dp(5), 1f).apply { if (i > 0) leftMargin = ui.dp(5) })
            }, ui.lp(6))
        }
    }

    private fun nav(back: Boolean, nextLabel: String, next: () -> Unit) {
        box.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (back) addView(ui.btn("Back", Ui.CARD) { step -= 1; render() }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = ui.dp(8) })
            addView(ui.btn(nextLabel, Ui.GOOD) { next() }, LinearLayout.LayoutParams(0, Ui.WRAP, 2f))
        }, ui.lp(16))
    }

    /* ---------------- 1. Welcome & privacy ---------------- */

    private fun stepWelcome() {
        box.addView(ui.iconCard(Ic.CAR, Ui.CYAN, "Welcome to ${AppInfo.NAME}", "Four short steps. Nothing will pop up by itself – you choose what to allow on step 3."), ui.lp(8))
        box.addView(ui.section("Your privacy", Ic.SHIELD))
        box.addView(ui.card(
            "• Your data stays on your phone. No account, no ads, no tracking; the developer collects nothing.\n" +
            "• Location is used for trips, speed and road speed-limit warnings, also in the background while the road assistant runs (a notification always shows).\n" +
            "• Bluetooth / Wi-Fi only talk to your OBD adapter.\n" +
            "• Camera (optional, ADAS) is analysed on the phone; pictures are never saved or sent.\n" +
            "• Map and road data come from OpenStreetMap using your coordinates.\n" +
            "• Do not use the app while driving. Diagnostics and ADAS are a guide only.", 12.5f, Ui.WHITE), ui.lp(4))
        box.addView(ui.btn("Read privacy policy and terms", Ui.CARD) {
            Legal.show(a, "Privacy Policy", LegalText.privacy) { Legal.show(a, "Terms of Use", LegalText.terms) }
        }, ui.lp(6))
        box.addView(ui.section("How do you want to start?", Ic.GRID))
        box.addView(ui.btn("I agree – set up as new", Ui.GOOD) { agree(); step = 1; render() }, ui.lp(6))
        box.addView(ui.btn("I agree – restore my backup", Ui.VIOLET) { agree(); pickBackup() }, ui.lp(6))
        box.addView(ui.tv("Restore brings back vehicles, trips, fuel log, service records, settings, taught signs and offline maps from a .carobd file on this phone, Google Drive, OneDrive or Dropbox. Older backups work too. After a restore only the permissions step is left.", 11.5f, Ui.DIM), ui.lp(4))
        box.addView(ui.btn("Exit", Ui.CARD) { a.finishAffinity() }, ui.lp(14))
    }

    private fun agree() { Prefs.consentVersion = LegalText.VERSION }

    private fun pickBackup() {
        Prefs.restoreFromSetup = true
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "*/*" }
        @Suppress("DEPRECATION") a.startActivityForResult(i, BackupPage.REQ_CLOUD_OPEN)
    }

    /* ---------------- 2. Vehicle ---------------- */

    private fun stepVehicle() {
        val v = ObdManager.vehicle
        box.addView(ui.section("Your vehicle", Ic.CAR))
        nameE = ui.edit("My car", if (v.name == "My car") "" else v.name)
        box.addView(ui.labeled("Name", nameE!!), ui.lp(4))
        typeSp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, listOf("Car / SUV / van", "Truck / heavy goods", "Bus"))
            setSelection(vtypes.indexOf(Prefs.vehicleClass(v.id)).coerceAtLeast(0))
        }
        box.addView(ui.labeled("Vehicle type (sets road speed limits)", typeSp!!), ui.lp(4))
        fuelSp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, Fuels.all.map { it.name })
            setSelection(Fuels.all.indexOfFirst { it.name == v.fuel }.coerceAtLeast(0))
        }
        box.addView(ui.labeled("Fuel type", fuelSp!!), ui.lp(4))
        distSp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, listOf("Kilometres (km, km/h)", "Miles (mi, mph)"))
            setSelection(if (Prefs.unitDist == "mi" || (!Prefs.setupDone && milesCountry())) 1 else 0)
        }
        box.addView(ui.labeled("Distance and speed", distSp!!), ui.lp(4))
        box.addView(ui.tv("Engine size, tank, fuel price and more can be added later in Garage. The adapter is connected after setup.", 11.5f, Ui.DIM), ui.lp(2))
        nav(true, "Next") { saveVehicle(); step = 2; render() }
    }

    /** US, UK, Liberia and Myanmar use miles on the road. */
    private fun milesCountry() = java.util.Locale.getDefault().country in setOf("US", "GB", "LR", "MM")

    private fun saveVehicle() {
        try {
            val v = ObdManager.vehicle
            nameE?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { v.name = it }
            fuelSp?.let { v.fuel = Fuels.all[it.selectedItemPosition.coerceAtLeast(0)].name }
            Db.saveVehicle(v)
            typeSp?.let { Prefs.setVehicleClass(v.id, vtypes[it.selectedItemPosition.coerceAtLeast(0)]) }
            distSp?.let { Prefs.unitDist = if (it.selectedItemPosition == 1) "mi" else "km" }
            ObdManager.reloadVehicle(); RoadAssist.reloadUserData()
        } catch (_: Throwable) {}
    }

    /* ---------------- 3. Permissions ---------------- */

    class Perm(val key: String, val title: String, val why: String, val perms: List<String>, val optional: Boolean)

    fun permList(): List<Perm> {
        val l = ArrayList<Perm>()
        if (Build.VERSION.SDK_INT >= 31) l += Perm("bt", "Nearby devices (Bluetooth)", "Find and connect to your Bluetooth OBD adapter.",
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), false)
        l += Perm("loc", "Location (precise)", "Trips, speed, road speed limits, hazards and fuel-station positions.",
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), false)
        if (Build.VERSION.SDK_INT >= 33) l += Perm("notif", "Notifications", "Fault alerts, reminders and the drive notification with quick hazard buttons.",
            listOf(Manifest.permission.POST_NOTIFICATIONS), false)
        l += Perm("cam", "Camera (optional)", "Only for ADAS: signs, lanes and the vehicle ahead. Skip it if you will not use ADAS.",
            listOf(Manifest.permission.CAMERA), true)
        return l
    }

    fun granted(p: Perm) = if (p.key == "loc") Perms.locationOk(a) else p.perms.all { a.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Asked before and Android will not show the question again → only the settings screen can change it. */
    private fun blocked(p: Perm) = !granted(p) && Prefs.permAsked(p.key) && p.perms.none { a.shouldShowRequestPermissionRationale(it) }

    private fun ask(p: Perm) {
        if (blocked(p)) { Perms.openSettings(a); ui.toast("Open Permissions and allow ${p.title.substringBefore(" (")}"); return }
        Prefs.setPermAsked(p.key)
        a.requestPermissions(p.perms.toTypedArray(), 60 + permList().indexOf(p))
    }

    private fun stepPerms() {
        if (restored) {
            box.addView(ui.iconCard(Ic.CHECK, Ui.GOOD, "Backup restored", "Vehicles, trips, settings and maps are back. One last step: allow what the app needs on this phone."), ui.lp(8))
        }
        box.addView(ui.section("Permissions", Ic.SHIELD))
        box.addView(ui.tv("Tap Allow on each one. Android then asks once. Nothing is asked without you tapping here.", 12f, Ui.DIM), ui.lp(2))
        for (p in permList()) {
            val ok = granted(p)
            box.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.outlined(Ui.CARD, if (ok) Ui.GOOD else if (p.optional) Ui.STROKE else Ui.WARN, 14, 1)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(10), ui.dp(10))
                addView(ui.vbox().apply {
                    addView(ui.tv(p.title, 14f, Ui.WHITE, true))
                    addView(ui.tv(p.why, 11.5f, Ui.DIM))
                }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                if (ok) addView(ui.tv("✓ Allowed", 13f, Ui.GOOD, true).apply { setPadding(ui.dp(8), 0, ui.dp(4), 0) })
                else addView(ui.btn(if (blocked(p)) "Settings" else "Allow", if (p.optional) Ui.CARD else Ui.ACC) { ask(p) }, LinearLayout.LayoutParams(ui.dp(104), Ui.WRAP))
            }, ui.lp(6))
        }
        val missing = permList().filter { !it.optional && !granted(it) }
        val allNeeded = permList().filter { !it.optional && !granted(it) && !blocked(it) }
        if (allNeeded.size > 1) box.addView(ui.btn("Allow all needed at once", Ui.ACC) {
            allNeeded.forEach { Prefs.setPermAsked(it.key) }
            a.requestPermissions(allNeeded.flatMap { it.perms }.toTypedArray(), 69)
        }, ui.lp(8))
        if (missing.isNotEmpty()) box.addView(ui.tv(
            (if (missing.any { it.key == "loc" }) "Without location there are no trips on the map and no speed-limit warnings. " else "") +
            (if (missing.any { it.key == "bt" }) "Without nearby devices only Wi-Fi adapters can connect. " else "") +
            "You can allow these later in More → Permissions.", 11.5f, Ui.WARN), ui.lp(6))
        if (restored) nav(false, if (missing.isEmpty()) "Finish" else "Finish anyway") { finishSetup() }
        else nav(true, if (missing.isEmpty()) "Next" else "Skip for now") { step = 3; render() }
        permSig = sig()
    }

    private fun sig() = permList().joinToString("") { if (granted(it)) "1" else "0" }

    /** Called after Android's permission answer. */
    fun onPermsResult() { if (step == 2 && ::box.isInitialized) render() }

    /* ---------------- 4. Road assistant & maps ---------------- */

    private fun stepRoad() {
        val loc = Perms.locationOk(a)
        box.addView(ui.section("Road assistant", Ic.ROUTE))
        if (!Prefs.setupDone && loc && !Prefs.roadAssistChosen) Prefs.roadAssist = true
        box.addView(ui.check("Speed limits, hazards and ADAS warnings while driving", Prefs.roadAssist && loc).apply {
            isEnabled = loc
            setOnCheckedChangeListener { _, on -> Prefs.roadAssistChosen = true; Prefs.roadAssist = on }
        }, ui.lp(4))
        if (!loc) box.addView(ui.tv("Needs location – go Back and allow it, or turn this on later on the Road page.", 11.5f, Ui.WARN), ui.lp(2))

        box.addView(ui.section("Offline map", Ic.DOWNLOAD))
        box.addView(ui.tv("Save the roads around you so speed limits work with no internet. It downloads in the background; you can finish setup now.", 12.5f, Ui.DIM))
        val sp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, radii.map { "Radius $it km  (about ${RoadAssist.areasFor(it)} areas)" })
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
        box.addView(ui.btn(if (loc) "Download map around me" else "Download needs location", if (loc) Ui.ACC else Ui.CARD) {
            if (!loc) { ui.toast("Allow location on the previous step first"); return@btn }
            RoadAssist.downloadWide(Prefs.mapRadiusKm) { m -> a.runOnUiThread { ui.toast(m); mapTv?.text = RoadAssist.savedInfo() } }
            refresh()
        }, ui.lp(6))
        box.addView(ui.check("Keep highway maps ahead of me up to date automatically", Prefs.mapAuto).apply { setOnCheckedChangeListener { _, on -> Prefs.mapAuto = on } }, ui.lp(4))
        nav(true, "Finish setup") { finishSetup() }
    }

    /* ---------------- finish ---------------- */

    fun finishSetup() {
        Prefs.setupDone = true
        Prefs.setupAfterRestore = false
        Prefs.permsPrompted = true
        step = 0
        if (Prefs.roadAssist && Perms.locationOk(a)) try { ObdService.start(a); Gps.start(a) } catch (_: Exception) {}
        a.show(if (Prefs.lastAddr.isEmpty() && ObdManager.state != ObdManager.State.CONNECTED) "Connect" else "Home")
    }

    override fun refresh() {
        if (!::box.isInitialized) return
        if (step == 2 && permSig.isNotEmpty() && sig() != permSig) { render(); return }
        val bar = dlBar ?: return
        val r = RoadAssist
        if (r.dlBusy) { bar.visibility = View.VISIBLE; dlTv?.visibility = View.VISIBLE; bar.progress = r.dlPct; dlTv?.text = r.dlText }
        else { bar.visibility = View.GONE; dlTv?.visibility = View.GONE }
    }
}
