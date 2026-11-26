package com.dilip.carobd

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.TextView

class DiagPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var out: TextView

    override fun build(): View {
        val c = ui.col()
        c.addView(ui.row(ui.btn("Read DTCs") { readDtcs() }, ui.btn("Clear DTCs", Ui.BAD) { confirmClear() }), ui.lp(10))
        c.addView(ui.row(ui.btn("Deep scan (UDS)") { udsScan() }, ui.btn("Freeze frame") { freezeFrame() }), ui.lp(8))
        c.addView(ui.row(
            ui.btn("Readiness") { ObdManager.runDiag("Reading readiness monitors") { e -> Health.readiness(e.pid01(0x01)) } },
            ui.btn("Vehicle info") { vehicleInfo() }
        ), ui.lp(8))
        c.addView(ui.row(ui.btn("Health analysis", Ui.GOOD) { healthReport() }, ui.btn("Code history", Ui.CARD) { history() }), ui.lp(8))
        c.addView(ui.btn("Share report", Ui.CARD) {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "${AppInfo.NAME} report – ${ObdManager.vehicle.title}")
                putExtra(Intent.EXTRA_TEXT, ObdManager.diagText + AppInfo.footer)
            }
            a.startActivity(Intent.createChooser(i, "Share report"))
        }, ui.lp(8))
        out = ui.tv("", 13f).apply {
            setTextIsSelectable(true)
            background = ui.rounded(Ui.CARD)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        }
        c.addView(out, ui.lp(12))
        return ui.scroll(c)
    }

    private fun appendDtc(sb: StringBuilder, d: Dtc) {
        sb.append("  ${d.code}  ${DtcDb.describe(d.code)}\n")
        sb.append("     ↳ ${d.ecu}")
        if (d.extra.isNotEmpty()) sb.append(" · ${d.extra}")
        sb.append("\n")
        DtcDb.hint(d.code)?.let { sb.append("     💡 $it\n") }
    }

    private fun readDtcs() = ObdManager.runDiag("Reading fault codes") { e ->
        val vid = ObdManager.vehicle.id
        val sb = StringBuilder()
        e.pid01(0x01)?.let { sb.append(if (it[0] and 0x80 != 0) "Check-engine lamp: ON\n" else "Check-engine lamp: off\n") }
        var total = 0
        for ((mode, kind) in listOf("03" to "Stored", "07" to "Pending", "0A" to "Permanent")) {
            val l = e.readDtcs(mode, kind)
            total += l.size
            l.forEach { Db.insertDtc(vid, it) }
            sb.append("\n■ $kind codes: ${l.size}\n")
            l.forEach { appendDtc(sb, it) }
        }
        if (total == 0) sb.append("\nNo OBD fault codes stored. 👍\n")
        sb.append("\nTip: 'Deep scan (UDS)' reads manufacturer codes inside engine, gearbox and other modules.")
        sb.toString()
    }

    private fun udsScan() = ObdManager.runDiag("Scanning modules (UDS 19 02)") { e ->
        val vid = ObdManager.vehicle.id
        val sb = StringBuilder("Deep scan – UDS ReadDTCInformation\n")
        val targets = listOf(
            "7E0" to "Engine ECU", "7E1" to "Transmission (TCU)", "7E2" to "Module 7E2",
            "7E3" to "Module 7E3", "7E4" to "Module 7E4", "7E5" to "Module 7E5", "7E6" to "Module 7E6", "7E7" to "Module 7E7"
        )
        for ((h, label) in targets) {
            val (l, st) = e.readUdsDtcs(h, label)
            sb.append("\n■ $label ($h): ${if (st == "ok") "${l.size} code(s)" else st}\n")
            l.forEach { appendDtc(sb, it); Db.insertDtc(vid, it) }
        }
        sb.append("\nFTB = failure-type byte. 'history' = stored but not failing now.\nABS/airbag/body modules on many brands use other addresses – add them as custom PIDs or use a brand tool.")
        sb.toString()
    }

    private fun confirmClear() {
        AlertDialog.Builder(a)
            .setTitle("Clear fault codes?")
            .setMessage("Turns off the check-engine lamp and resets readiness monitors and freeze-frame data. Do it with ignition ON and engine OFF. Codes come back if the fault is still present.")
            .setPositiveButton("Clear OBD codes") { _, _ ->
                ObdManager.runDiag("Clearing codes") { e -> if (e.clearDtcs()) "OBD codes cleared ✅" else "ECU did not confirm clear." }
            }
            .setNeutralButton("OBD + modules (UDS)") { _, _ ->
                ObdManager.runDiag("Clearing codes") { e ->
                    val x = e.clearDtcs()
                    val y = e.clearUds("7E0")
                    val z = e.clearUds("7E1")
                    "OBD clear: ${if (x) "ok" else "no reply"}\nEngine UDS clear: ${if (y) "ok" else "no reply"}\nGearbox UDS clear: ${if (z) "ok" else "no reply"}"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun freezeFrame() = ObdManager.runDiag("Reading freeze frame") { e ->
        val trig = e.freeze(0x02)
        if (trig == null || trig.size < 2 || (trig[0] == 0 && trig[1] == 0)) return@runDiag "No freeze frame stored."
        val code = DtcDb.decode(trig[0], trig[1])
        val sb = StringBuilder("Freeze frame captured for $code\n${DtcDb.describe(code)}\n\n")
        for (p in listOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x1F, 0x42, 0x46)) {
            try {
                val d = e.freeze(p) ?: continue
                val def = Pids.byId[p] ?: continue
                val txt = def.text?.invoke(d) ?: def.decode?.invoke(d)?.let { "%.${def.decimals}f %s".format(it, def.unit) } ?: continue
                sb.append("${def.name}: $txt\n")
            } catch (_: Exception) {
            }
        }
        sb.toString()
    }

    private fun vehicleInfo() = ObdManager.runDiag("Reading vehicle info") { e ->
        val m = ObdManager
        val sb = StringBuilder()
        sb.append("Vehicle profile: ${m.vehicle.title} (${m.vehicle.fuel})\n")
        sb.append("VIN: ${e.info09(0x02).ifEmpty { "n/a" }}\n")
        sb.append("Calibration ID: ${e.info09(0x04).ifEmpty { "n/a" }}\n")
        sb.append("ECU name: ${e.info09(0x0A).ifEmpty { "n/a" }}\n")
        sb.append("Adapter: ${m.elmVersion}\n")
        sb.append("Protocol: ${e.protocol}\n")
        sb.append("Battery (adapter): ${ui.f(e.batteryVoltage(), 2, " V")}\n")
        e.pid01(0x1C)?.let { sb.append("OBD standard: ${Pids.byId[0x1C]?.text?.invoke(it)}\n") }
        e.pid01(0x51)?.let { sb.append("Fuel type (ECU): ${Pids.byId[0x51]?.text?.invoke(it)}\n") }
        e.pid01(0xA6)?.let { sb.append("Odometer (ECU): ${ui.f(Pids.byId[0xA6]?.decode?.invoke(it), 1, " km")}\n") }
        sb.append("Odometer (app): ${ui.f(m.vehicle.odoKm, 0, " km")}\n")
        sb.append("Supported mode-01 PIDs (${m.supported.size}): ")
        sb.append(m.supported.joinToString(" ") { "%02X".format(it) })
        sb.toString()
    }

    private fun healthReport() {
        val list = Health.analyze()
        val sb = StringBuilder("Health analysis – ${ObdManager.vehicle.title} – ${ui.date(System.currentTimeMillis(), "dd MMM HH:mm")}\n")
        if (ObdManager.state != ObdManager.State.CONNECTED) sb.append("(Not connected – using last known values)\n")
        for (fi in list) {
            sb.append("\n${Health.icon(fi.level)} ${fi.title}\n")
            if (fi.detail.isNotEmpty()) sb.append("    ${fi.detail}\n")
        }
        val due = Maint.dueList(ObdManager.vehicle).filter { it.overdue || it.soon }
        if (due.isNotEmpty()) {
            sb.append("\n🔧 Maintenance due:\n")
            due.forEach { sb.append("    ${it.item.name}: ${it.text}\n") }
        }
        ObdManager.diagText = sb.toString()
    }

    private fun history() {
        val l = Db.dtcHistory(ObdManager.vehicle.id)
        ObdManager.diagText = if (l.isEmpty()) "No fault codes recorded for ${ObdManager.vehicle.name}." else
            "Fault code history – ${ObdManager.vehicle.name}\n\n" + l.joinToString("\n\n") {
                "${ui.date(it.t)}  ${it.code} (${it.kind})\n   ${DtcDb.describe(it.code)}\n   ${it.ecu}"
            }
    }

    override fun refresh() {
        out.text = ObdManager.diagText + if (ObdManager.busy) "\n\n⏳ working…" else ""
    }
}
