package com.dilip.carobd

import android.app.AlertDialog
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/** One saved ECU reset command (what to send, to which ECU). */
class ResetProfile(val type: String, var header: String, var hex: String, var session: Boolean)

object ServiceReset {
    class Kind(val name: String, val ic: Ic, val color: Int, val maintKey: String, val manual: String)

    val kinds = listOf(
        Kind("Engine oil service", Ic.DROP, Ui.ORANGE, "oil",
            "Typical cluster method: ignition ON (engine off) → press and hold the trip/reset stalk button → switch ignition OFF → hold the button and switch ON until the service light resets. Exact steps differ by model – check the owner's manual."),
        Kind("Brake pad / brake service", Ic.STOP, Ui.BAD, "brake",
            "Usually needs the electronic parking brake in service mode (or a scan tool) after fitting new pads. Never use service mode while driving."),
        Kind("General service interval", Ic.WRENCH, Ui.WARN, "general",
            "Many cars reset through the instrument cluster menu: Settings → Service → Reset, or with the trip-reset button as for oil."),
        Kind("Battery registration (BMS)", Ic.BATTERY, Ui.GOOD, "battery",
            "Needed after replacing the 12 V battery on cars with a battery sensor. Often a scan-tool function – some cars relearn by themselves after a few drive cycles."),
        Kind("Throttle body adaptation", Ic.GEAR, Ui.ACC, "",
            "After cleaning the throttle body: ignition ON for 30 s without starting, then start and idle 5–10 min. Many ECUs relearn automatically."),
        Kind("Steering angle / ABS calibration", Ic.GAUGE, Ui.VIOLET, "",
            "Normally a scan-tool routine with the wheels straight. Needed after alignment or steering work."),
        Kind("DPF / particulate filter", Ic.WIND, Ui.PINK, "",
            "Forced regeneration needs a scan tool and a warm engine, parked outdoors. Follow your service manual."),
        Kind("Tyre pressure (TPMS) relearn", Ic.GAUGE, Ui.CYAN, "tyre",
            "Set pressures, then use the TPMS reset button or the cluster menu; some cars relearn after driving above 25 km/h for several minutes.")
    )

    fun load(): MutableList<ResetProfile> {
        val out = mutableListOf<ResetProfile>()
        try {
            val a = JSONArray(Prefs.resetProfiles)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(ResetProfile(o.getString("t"), o.getString("h"), o.getString("x"), o.optBoolean("s")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(l: List<ResetProfile>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.type).put("h", it.header).put("x", it.hex).put("s", it.session)) }
        Prefs.resetProfiles = a.toString()
    }

    fun cleanHex(s: String) = s.replace(" ", "").uppercase()
    fun validHex(s: String) = s.length >= 4 && s.length % 2 == 0 && s.all { it in "0123456789ABCDEF" }

    private val nrc = mapOf(
        0x10 to "general reject", 0x11 to "service not supported", 0x12 to "sub-function not supported",
        0x13 to "wrong message length", 0x22 to "conditions not correct (engine must be off, ignition ON, car stationary)",
        0x24 to "request sequence error", 0x31 to "request out of range", 0x33 to "security access denied (needs seed-key unlock)",
        0x35 to "invalid key", 0x78 to "response pending", 0x7E to "sub-function not supported in this session",
        0x7F to "service not supported in this session (try the extended-session option)"
    )

    /** Sends the command and returns a readable result. Runs on the OBD thread. */
    fun run(e: Elm327, p: ResetProfile): String {
        val sb = StringBuilder()
        val hdr = p.header.ifBlank { "7E0" }
        if (p.session) {
            val rs = e.request("1003", hdr, 3000)
            val ok = rs.any { it.data.size >= 2 && it.data[0] == 0x50 }
            sb.append("Extended session: ${if (ok) "opened" else "no positive reply"}\n")
        }
        val req = cleanHex(p.hex)
        val rs = e.request(req, hdr, 6000)
        e.setHeader(null)
        if (rs.isEmpty()) return sb.append("No reply from $hdr. Command not supported by this ECU, wrong address, or ignition is off.").toString()
        val svc = req.substring(0, 2).toInt(16)
        for (r in rs) {
            val d = r.data
            if (d.isEmpty()) continue
            if (d[0] == svc + 0x40) {
                sb.append("✅ ECU accepted the command (positive reply ${d.joinToString("") { "%02X".format(it) }}).")
                return sb.toString()
            }
            if (d[0] == 0x7F && d.size >= 3) {
                sb.append("❌ ECU refused: ${nrc[d[2]] ?: "code %02X".format(d[2])}.")
                return sb.toString()
            }
        }
        return sb.append("Unrecognised reply: ${rs.first().data.joinToString("") { "%02X".format(it) }}").toString()
    }
}

class ServiceResetPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: LinearLayout
    private lateinit var out: android.widget.TextView

    override fun build(): View {
        box = ui.col()
        return ui.scroll(box)
    }

    override fun onShow() = draw()

    override fun refresh() {
        if (::out.isInitialized && ObdManager.busy.not() && ObdManager.diagText.isNotEmpty()) out.text = ObdManager.diagText
    }

    private fun draw() {
        box.removeAllViews()
        box.addView(ui.iconCard(Ic.WARN, Ui.WARN, "Use with care",
            "Resets write to the car's ECUs. Do them with ignition ON, engine OFF, car stationary. Wrong commands can set faults or lock a module – only use commands from your service manual or a trusted source."))
        out = ui.tv("Result appears here.", 13f, Ui.DIM)
        val profiles = ServiceReset.load()
        for (k in ServiceReset.kinds) {
            val has = profiles.any { it.type == k.name }
            box.addView(ui.iconCard(k.ic, k.color, k.name, if (has) "ECU command saved · tap for options" else "Tap for options") { options(k) }, ui.lp(8))
        }
        box.addView(ui.section("Last result", Ic.TERMINAL))
        box.addView(out, ui.lp(6))
        box.addView(ui.tv("The app cannot know each car's private reset command (they are brand-specific and mostly unpublished). Enter one you have for your car; the app sends it over the ELM327, shows the ECU's answer and marks the service done in Maintenance.", 12f, Ui.DIM), ui.lp(12))
    }

    private fun options(k: ServiceReset.Kind) {
        val prof = ServiceReset.load().firstOrNull { it.type == k.name }
        val items = mutableListOf("Mark done in app", "How to reset in the car")
        if (prof != null) { items.add("Send ECU reset command"); items.add("Edit ECU command"); items.add("Remove ECU command") }
        else items.add("Set ECU command")
        AlertDialog.Builder(a).setTitle(k.name).setItems(items.toTypedArray()) { _, i ->
            when (items[i]) {
                "Mark done in app" -> markApp(k)
                "How to reset in the car" -> AlertDialog.Builder(a).setTitle(k.name).setMessage(k.manual).setPositiveButton("OK", null).show()
                "Send ECU reset command" -> confirmSend(k, prof!!)
                "Remove ECU command" -> { ServiceReset.save(ServiceReset.load().filter { it.type != k.name }); draw() }
                else -> edit(k, prof)
            }
        }.show()
    }

    private fun markApp(k: ServiceReset.Kind) {
        if (k.maintKey.isEmpty()) { ui.toast("No maintenance item for this – nothing to mark"); return }
        val v = ObdManager.vehicle
        val hits = Db.maintItems(v.id).filter { it.name.lowercase().contains(k.maintKey) && !(k.maintKey == "oil" && (it.name.lowercase().contains("gear") || it.name.lowercase().contains("transmission"))) }
        if (hits.isEmpty()) { ui.toast("No matching item in Maintenance"); return }
        hits.forEach { it.lastKm = v.odoKm; it.lastT = System.currentTimeMillis(); Db.saveMaint(it) }
        ui.toast("Marked done: " + hits.joinToString { it.name })
    }

    private fun edit(k: ServiceReset.Kind, old: ResetProfile?) {
        val hdr = ui.edit("ECU address e.g. 7E0", old?.header ?: "7E0")
        val hex = ui.edit("Request hex e.g. 31010203", old?.hex ?: "")
        val ses = CheckBox(a).apply { text = "Open extended session (10 03) first"; isChecked = old?.session ?: false; setTextColor(Ui.WHITE) }
        formDialog("ECU command – ${k.name}", listOf("ECU address (CAN header)" to hdr, "Request bytes (hex)" to hex, "" to ses)) {
            val x = ServiceReset.cleanHex(hex.text.toString())
            val h = hdr.text.toString().trim().uppercase()
            if (!ServiceReset.validHex(x) || h.isEmpty() || !h.all { it in "0123456789ABCDEF" }) { ui.toast("Enter valid hex"); return@formDialog }
            val l = ServiceReset.load().filter { it.type != k.name }.toMutableList()
            l.add(ResetProfile(k.name, h, x, ses.isChecked)); ServiceReset.save(l); draw()
        }
    }

    private fun confirmSend(k: ServiceReset.Kind, p: ResetProfile) {
        if (ObdManager.state != ObdManager.State.CONNECTED) { ui.toast("Connect to the car first"); return }
        val spd = ObdManager.values[0x0D] ?: 0.0
        if (spd > 1.0) { ui.toast("Stop the car first – reset only when stationary"); return }
        AlertDialog.Builder(a).setTitle("Send ${k.name} reset?")
            .setMessage("To ${p.header}: ${p.hex}${if (p.session) " (extended session)" else ""}\n\nIgnition ON, engine OFF, car stationary. This writes to the ECU.")
            .setPositiveButton("Send") { _, _ ->
                ObdManager.log("SERVICE RESET ${k.name}: ${p.header} ${p.hex}")
                ObdManager.runDiag("Sending reset") { e ->
                    val r = ServiceReset.run(e, p)
                    ObdManager.log("SERVICE RESET result: $r")
                    r
                }
                if (k.maintKey.isNotEmpty()) ui.toast("Check the result; then use 'Mark done in app'")
            }.setNegativeButton("Cancel", null).show()
    }
}
