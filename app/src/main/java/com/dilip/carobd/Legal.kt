package com.dilip.carobd

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView

/** Policy text helpers + first-run consent. */
object Legal {
    fun contact(): String = if (AppInfo.SUPPORT_EMAIL.isNotBlank()) "Contact: ${AppInfo.SUPPORT_EMAIL}" else "Contact: through the developer contact shown on the Google Play listing."

    fun render(secs: List<Pair<String, String>>): String =
        secs.joinToString("\n\n") { (t, b) -> "■ " + t.uppercase() + "\n" + b.replace("{CONTACT}", contact()) }

    fun show(a: MainActivity, title: String, secs: List<Pair<String, String>>, then: (() -> Unit)? = null) {
        val tv = TextView(a).apply {
            text = render(secs); textSize = 13.5f; setTextColor(Ui.WHITE); setLineSpacing(0f, 1.15f)
            setPadding(a.ui.dp(18), a.ui.dp(8), a.ui.dp(18), a.ui.dp(8))
        }
        val sv = ScrollView(a).apply { addView(tv) }
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setView(sv)
            .setPositiveButton(if (then != null) "Back" else "Close") { _, _ -> then?.invoke() }
            .setOnCancelListener { then?.invoke() }.show()
    }

    fun needsConsent(ctx: Context) = Prefs.consentVersion < LegalText.VERSION

    /** Prominent disclosure shown before any permission is requested. */
    fun consent(a: MainActivity, onDone: () -> Unit) {
        val msg = "Dr. Vittu OBDII Pro connects to your OBD adapter and records your drives.\n\n" +
            "• Location: your precise location is used for trip routes, speed, road speed-limit announcements and fuel-station positions – including in the background while the road assistant or trip recording is running (a notification is always shown).\n" +
            "• Bluetooth / Wi-Fi: to find and talk to your OBD adapter.\n" +
            "• Internet: map tiles and road data are fetched from OpenStreetMap and Overpass using your coordinates.\n\n" +
            "All your data stays on your phone. The developer collects nothing, shows no ads and sells no data. Backups only go to a place you choose.\n\n" +
            "Do not use the app while driving. Diagnostics are a guide only."
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle("Your privacy").setMessage(msg).setCancelable(false)
            .setPositiveButton("Agree & continue") { _, _ -> Prefs.consentVersion = LegalText.VERSION; onDone() }
            .setNegativeButton("Exit") { _, _ -> a.finishAffinity() }
            .setNeutralButton("Read policy") { _, _ ->
                show(a, "Privacy Policy", LegalText.privacy) { show(a, "Terms of Use", LegalText.terms) { consent(a, onDone) } }
            }.show()
    }

    fun deleteAll(a: MainActivity) {
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle("Delete all my data?")
            .setMessage("This permanently erases every trip, fill-up, receipt, service record, alert, setting and vehicle stored by the app on this phone, then closes the app. Backups you saved elsewhere are not touched.")
            .setPositiveButton("Delete everything") { _, _ ->
                try { a.stopService(Intent(a, ObdService::class.java)) } catch (_: Exception) {}
                (a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
            }.setNegativeButton("Cancel", null).show()
    }
}

class LegalPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: android.widget.LinearLayout
    override fun build(): View { box = ui.col(); return ui.scroll(box) }
    override fun onShow() = render()

    private fun render() {
        val c = box; c.removeAllViews()
        c.addView(ui.tv("Your privacy", 22f, Ui.WHITE, true), ui.lp(4))
        c.addView(ui.tv("Everything stays on your phone. No ads, no accounts, no analytics.  ·  Updated ${LegalText.UPDATED}", 12f, Ui.DIM), ui.lp(2))
        c.addView(ui.section("Policies", Ic.SHIELD))
        c.addView(ui.iconCard(Ic.SHIELD, Ui.GOOD, "Privacy policy", "What is stored, what is sent, your rights", Ui.CARD) { Legal.show(a, "Privacy Policy", LegalText.privacy) }, ui.lp(6))
        c.addView(ui.iconCard(Ic.DOC, Ui.ACC, "Terms of use", "Licence, safety notice, disclaimers", Ui.CARD) { Legal.show(a, "Terms of Use", LegalText.terms) }, ui.lp(6))
        c.addView(ui.iconCard(Ic.DATABASE, Ui.VIOLET, "Data safety summary", "What data the app handles and shares", Ui.CARD) { Legal.show(a, "Data safety", LegalText.dataSafety) }, ui.lp(6))
        c.addView(ui.iconCard(Ic.PIN, Ui.ORANGE, "Permissions explained", "Why each permission is needed", Ui.CARD) { Legal.show(a, "Permissions", LegalText.permissions) }, ui.lp(6))
        c.addView(ui.iconCard(Ic.INFO, Ui.CYAN, "Open data & credits", "OpenStreetMap, trademarks", Ui.CARD) { Legal.show(a, "Credits", LegalText.credits) }, ui.lp(6))
        c.addView(ui.section("Your data", Ic.DATABASE))
        c.addView(ui.iconCard(Ic.UPLOAD, Ui.VIOLET, "Export / backup my data", "Save everything to a file or your own cloud", Ui.CARD) { a.show("Backup") }, ui.lp(6))
        val on = Prefs.roadAssist || Prefs.backgroundWatch
        if (on) c.addView(ui.iconCard(Ic.PIN, Ui.GOOD, "Stop location use", "Switches off the road assistant and background watching. Turn it back on here or in More → Permissions.", Ui.CARD) {
            Perms.setBackground(a, false); ui.toast("Background location and watching switched off"); render()
        }, ui.lp(6))
        else c.addView(ui.iconCard(Ic.PIN, Ui.ORANGE, "Location use is OFF – tap to turn on", "Road assistant, speed-limit announcements and background trip watching will start again.", Ui.CARD) {
            Perms.setBackground(a, true); ui.toast("Road assistant and background watching are on"); render()
        }, ui.lp(6))
        c.addView(ui.iconCard(Ic.SLIDERS, Ui.ORANGE, "Permissions & features", "See and change every permission and feature switch", Ui.CARD) { a.show("Perms") }, ui.lp(6))
        c.addView(ui.iconCard(Ic.TRASH, Ui.BAD, "Delete all my data", "Erase everything stored by the app and close it", Ui.CARD) { Legal.deleteAll(a) }, ui.lp(6))
        c.addView(ui.btn("Share privacy policy text", Ui.CARD) {
            val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Dr. Vittu OBDII Pro – Privacy Policy\n\n" + Legal.render(LegalText.privacy)) }
            a.startActivity(Intent.createChooser(i, "Share"))
        }, ui.lp(12))
        c.addView(ui.tv(Legal.contact() + "\n© 2026 ${AppInfo.DEVELOPER}", 11f, Ui.DIM).apply { gravity = android.view.Gravity.CENTER }, ui.lp(12))
    }
}
