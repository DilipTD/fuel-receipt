package com.dilip.carobd

import java.io.IOException

class EcuResponse(val ecu: String, val data: IntArray)

data class Dtc(val code: String, val ecu: String, val kind: String, val extra: String = "")

class Elm327(private val t: ObdTransport, private val log: (String) -> Unit, private val forcedProtocol: String = "0") {
    var version = ""
    var protocol = ""
    var protocolNum = ""
    var isCan = false
    var headerLen = 3
    private var currentHeader: String? = null
    private var lastGood = ""

    /** Sends a command and returns the cleaned reply (lines separated by \n). */
    fun raw(cmd: String, timeout: Long = 2500): String {
        t.clearInput()
        t.write(cmd + "\r")
        val r = t.readUntilPrompt(timeout)
        if (r == null) {
            log("> $cmd   [timeout]")
            return "TIMEOUT"
        }
        val cmdNs = cmd.replace(" ", "").uppercase()
        val clean = r.replace("\r", "\n").split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.replace(" ", "").uppercase() != cmdNs }
            .filter { !it.startsWith("SEARCHING") }
            .joinToString("\n")
        log("> $cmd\n$clean")
        return clean
    }

    fun init() {
        raw("ATZ", 5000)
        Thread.sleep(400)
        raw("ATE0")
        version = raw("ATI").lines().lastOrNull() ?: ""
        raw("ATL0")
        raw("ATS0")
        raw("ATH0")
        raw("ATAT1")
        // Re-use the protocol found last time (fast wake-up), else the forced one / auto search
        val start = if (forcedProtocol == "0" && lastGood.isNotEmpty()) lastGood else forcedProtocol
        raw("ATSP$start")
        var r = raw("0100", 15000)
        if (!looksOk(r) && start == "0") {
            // Most cars from ~2008 use ISO 15765-4 CAN 11-bit 500 kbps
            raw("ATSP6")
            r = raw("0100", 8000)
        }
        if (!looksOk(r)) throw IOException("ECU not responding. Switch ignition ON / start engine. ($r)")
        val dpn = raw("ATDPN").trim()
        protocolNum = if (dpn.length == 2 && dpn.startsWith("A")) dpn.substring(1) else dpn
        protocol = raw("ATDP").removePrefix("AUTO, ").trim()
        lastGood = protocolNum
        isCan = protocolNum in listOf("6", "7", "8", "9")
        headerLen = if (protocolNum == "7" || protocolNum == "9") 8 else 3
        if (isCan) raw("ATH1")
        currentHeader = null
    }

    private fun looksOk(r: String): Boolean {
        val u = r.uppercase()
        return u.contains("41") && !u.contains("UNABLE") && !u.contains("ERROR") && u != "TIMEOUT"
    }

    private val defaultHeader get() = if (headerLen == 8) "18DB33F1" else "7DF"

    fun setHeader(h: String?) {
        if (!isCan) return
        if (h.isNullOrBlank()) {
            if (currentHeader != null) {
                raw("ATSH $defaultHeader")
                currentHeader = null
            }
        } else if (h != currentHeader) {
            raw("ATSH $h")
            currentHeader = h
        }
    }

    fun request(hex: String, header: String? = null, timeout: Long = 2500): List<EcuResponse> {
        setHeader(header)
        return parse(raw(hex, timeout))
    }

    private fun hexToInts(s: String): List<Int> =
        (0 until s.length / 2).mapNotNull { s.substring(it * 2, it * 2 + 2).toIntOrNull(16) }

    fun parse(r: String): List<EcuResponse> {
        val lines = r.split("\n").map { it.replace(" ", "").uppercase() }
            .filter { l -> l.isNotEmpty() && l.all { it in "0123456789ABCDEF" } }
        if (!isCan) return lines.map { EcuResponse("ECU", hexToInts(it).toIntArray()) }

        class Asm(val total: Int, val bytes: MutableList<Int>)
        val result = mutableListOf<EcuResponse>()
        val asm = LinkedHashMap<String, Asm>()
        for (l in lines) {
            if (l.length < headerLen + 2) continue
            val hdr = l.substring(0, headerLen)
            val rest = l.substring(headerLen)
            when (rest[0]) {
                '0' -> {
                    val n = rest.substring(1, 2).toInt(16)
                    result.add(EcuResponse(hdr, hexToInts(rest.substring(2)).take(n).toIntArray()))
                }
                '1' -> {
                    if (rest.length < 4) continue
                    val n = rest.substring(1, 4).toInt(16)
                    asm[hdr] = Asm(n, hexToInts(rest.substring(4)).toMutableList())
                }
                '2' -> asm[hdr]?.bytes?.addAll(hexToInts(rest.substring(2)))
            }
        }
        for ((h, a) in asm) result.add(EcuResponse(h, a.bytes.take(a.total).toIntArray()))
        return result.sortedBy { it.ecu }
    }

    /** Mode 01 data bytes (A, B, C...) for a PID, from the engine ECU first. */
    fun pid01(pid: Int): IntArray? {
        val rs = request("01%02X".format(pid))
        val r = rs.firstOrNull { it.data.size >= 2 && it.data[0] == 0x41 && it.data[1] == pid } ?: return null
        return r.data.copyOfRange(2, r.data.size)
    }

    fun supportedPids(): Set<Int> {
        val s = sortedSetOf<Int>()
        var base = 0
        while (base <= 0xC0) {
            val d = pid01(base) ?: break
            if (d.size < 4) break
            val bits = (d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()
            for (i in 0 until 32) if (bits and (1L shl (31 - i)) != 0L) s.add(base + i + 1)
            if (!s.contains(base + 0x20)) break
            base += 0x20
        }
        return s
    }

    fun batteryVoltage(): Double? =
        raw("ATRV").replace("V", "").trim().toDoubleOrNull()

    fun ecuName(h: String): String {
        val src = if (h.length == 8) h.substring(6) else h
        return when (src.uppercase()) {
            "7E8", "10" -> "Engine ECU ($h)"
            "7E9", "18" -> "Transmission ($h)"
            "ECU" -> "ECU"
            else -> "Module $h"
        }
    }

    /** OBD mode 03 (stored), 07 (pending) or 0A (permanent). */
    fun readDtcs(mode: String, kind: String): List<Dtc> {
        val out = mutableListOf<Dtc>()
        val respMode = mode.toInt(16) + 0x40
        for (r in request(mode, timeout = 5000)) {
            val d = r.data
            if (d.isEmpty() || d[0] != respMode) continue
            var i = if (isCan) 2 else 1
            while (i + 1 < d.size) {
                val a = d[i]
                val b = d[i + 1]
                i += 2
                if (a == 0 && b == 0) continue
                out.add(Dtc(DtcDb.decode(a, b), ecuName(r.ecu), kind))
            }
        }
        return out.distinct()
    }

    fun clearDtcs(): Boolean {
        val rs = request("04", timeout = 5000)
        return rs.any { it.data.isNotEmpty() && it.data[0] == 0x44 }
    }

    /**
     * UDS ReadDTCInformation (0x19 02 FF) sent physically to one module.
     * Reads manufacturer-specific codes (e.g. gearbox / TCU codes) that mode 03 may not show.
     */
    fun readUdsDtcs(reqHeader: String, label: String): Pair<List<Dtc>, String> {
        if (!isCan || headerLen != 3) return Pair(emptyList(), "UDS scan needs an 11-bit CAN bus")
        val rs = request("1902FF", reqHeader, 4000)
        setHeader(null)
        val r = rs.firstOrNull { it.data.isNotEmpty() && (it.data[0] == 0x59 || it.data[0] == 0x7F) }
            ?: return Pair(emptyList(), "no reply")
        if (r.data[0] == 0x7F) return Pair(emptyList(), "not supported (NRC %02X)".format(r.data.getOrElse(2) { 0 }))
        val out = mutableListOf<Dtc>()
        var i = 3
        while (i + 3 < r.data.size) {
            val a = r.data[i]
            val b = r.data[i + 1]
            val ftb = r.data[i + 2]
            val st = r.data[i + 3]
            i += 4
            if (a == 0 && b == 0) continue
            val stTxt = buildList {
                if (st and 0x01 != 0) add("active")
                if (st and 0x04 != 0) add("pending")
                if (st and 0x08 != 0) add("confirmed")
                if (st and 0x80 != 0) add("MIL")
            }.ifEmpty { listOf("history") }.joinToString(",")
            out.add(Dtc(DtcDb.decode(a, b), "$label ($reqHeader)", "UDS", "FTB %02X · %s".format(ftb, stTxt)))
        }
        return Pair(out, "ok")
    }

    fun clearUds(reqHeader: String): Boolean {
        val rs = request("14FFFFFF", reqHeader, 4000)
        setHeader(null)
        return rs.any { it.data.isNotEmpty() && it.data[0] == 0x54 }
    }

    /** Mode 02 freeze-frame value bytes for a PID (frame 0). */
    fun freeze(pid: Int): IntArray? {
        val rs = request("02%02X00".format(pid))
        val r = rs.firstOrNull { it.data.size >= 3 && it.data[0] == 0x42 && it.data[1] == pid } ?: return null
        return r.data.copyOfRange(3, r.data.size)
    }

    /** Mode 09 text info: 02 = VIN, 0A = ECU name, 04 = calibration ID. */
    fun info09(pid: Int): String {
        val rs = request("09%02X".format(pid), timeout = 5000)
        val sb = StringBuilder()
        for (r in rs) {
            val d = r.data
            if (d.size < 3 || d[0] != 0x49 || d[1] != pid) continue
            for (k in 3 until d.size) {
                val c = d[k]
                if (c in 0x20..0x7E) sb.append(c.toChar())
            }
            if (isCan) sb.append(if (rs.size > 1) " [${ecuName(r.ecu)}] " else "")
        }
        return sb.toString().trim()
    }
}
