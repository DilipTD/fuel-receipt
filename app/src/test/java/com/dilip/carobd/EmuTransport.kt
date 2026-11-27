package com.dilip.carobd

/**
 * Software ELM327 + car. Speaks ISO 15765-4 CAN 11-bit (protocol 6) with headers, multi-frame ISO-TP,
 * mode 01/02/03/04/07/09/0A, ATRV, and a scripted drive profile driven by wall-clock time.
 */
class EmuTransport(
    private val profile: (Double) -> Car,
    private val supported: Set<Int>,
    var monitor: IntArray = intArrayOf(0x82, 0x07, 0x65, 0x00),
    var stored: List<String> = listOf("P0420", "P0301"),
    var pending: List<String> = listOf("P0171"),
    private val vin: String = "MAT627018NLA12345"
) : ObdTransport() {
    class Car(val rpm: Double?, val speed: Double, val coolant: Double = 88.0, val throttle: Double = 20.0,
              val load: Double = 30.0, val maf: Double = 8.0, val fuelPct: Double = 62.0, val volt: Double = 14.1)

    override val name = "Emulator"
    /** Ignition switch: when false the ECU stops answering (adapter still answers AT commands). */
    @Volatile var ignition = true
    private val t0 = System.currentTimeMillis()
    private var headers = false
    private var spaces = true
    private var header = "7DF"
    val log = ArrayList<String>()
    /** Fault injection: fraction of ECU replies replaced by adapter errors or garbage. */
    @Volatile var chaos = 0.0
    @Volatile var delayMs = 0L
    private val rnd = java.util.Random(7)
    private val junk = listOf("NO DATA", "?", "CAN ERROR", "BUS INIT: ...ERROR", "STOPPED", "BUFFER FULL", "41 0C", "41", "7E8 10 14 49 02", "", "41 0C ZZ QQ", "UNABLE TO CONNECT", "SEARCHING...", "41 0D FF FF FF FF FF FF FF", "ELM327 v1.5")

    override fun open() { connected = true }
    override fun close() { connected = false }

    private fun now() = (System.currentTimeMillis() - t0) / 1000.0
    private fun hex(b: Int) = "%02X".format(b and 0xFF)

    private fun frames(payload: List<Int>, src: String = "7E8"): String {
        val sep = if (spaces) " " else ""
        fun line(bytes: List<Int>) = (if (headers) src + sep else "") + bytes.joinToString(sep) { hex(it) }
        if (!headers) return payload.joinToString(sep) { hex(it) }
        if (payload.size <= 7) return line(listOf(payload.size) + payload)
        val out = ArrayList<String>()
        out += line(listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6))
        var i = 6; var sn = 1
        while (i < payload.size) { out += line(listOf(0x20 or (sn and 0x0F)) + payload.subList(i, minOf(i + 7, payload.size))); i += 7; sn++ }
        return out.joinToString("\r")
    }

    private fun dtcBytes(c: String): List<Int> {
        val t = "PCBU".indexOf(c[0]); val v = c.substring(1).toInt(16)
        return listOf((t shl 6) or (v shr 8), v and 0xFF)
    }

    private fun mode01(p: Int, car: Car): List<Int>? {
        if (p % 0x20 == 0) {
            var bits = 0L
            for (i in 1..32) if ((p + i) in supported) bits = bits or (1L shl (32 - i))
            return listOf((bits shr 24).toInt(), (bits shr 16).toInt(), (bits shr 8).toInt(), bits.toInt())
        }
        if (p !in supported) return null
        fun ab(v: Double) = v.toInt().coerceIn(0, 65535).let { listOf(it shr 8, it and 0xFF) }
        fun a(v: Double) = listOf(v.toInt().coerceIn(0, 255))
        return when (p) {
            0x01 -> monitor.toList()
            0x04 -> a(car.load * 255 / 100); 0x05 -> a(car.coolant + 40)
            0x06 -> a(128 + 2.0 * 1.28); 0x07 -> a(128 + 4.0 * 1.28)
            0x0B -> a(35 + car.load * 0.6); 0x0C -> car.rpm?.let { ab(it * 4) }
            0x0D -> a(car.speed); 0x0F -> a(34.0 + 40); 0x10 -> ab(car.maf * 100); 0x11 -> a(car.throttle * 255 / 100)
            0x1C -> a(6.0); 0x1F -> ab(now()); 0x21 -> ab(0.0); 0x2F -> a(car.fuelPct * 255 / 100)
            0x30 -> a(12.0); 0x31 -> ab(850.0); 0x33 -> a(100.0); 0x3C -> ab((620.0 + 40) * 10)
            0x42 -> ab(car.volt * 1000 - 120); 0x46 -> a(31.0 + 40); 0x4E -> ab(900.0); 0x5C -> a(95.0 + 40)
            0x0E -> a((10 + 64) * 2.0); 0x44 -> ab(1.0 * 32768); 0x5E -> ab(car.maf * 0.33 * 20)
            else -> null
        }
    }

    private fun reply(cmdRaw: String): String {
        val cmd = cmdRaw.replace(" ", "").uppercase()
        val car0 = profile(now())
        val car = if (ignition) car0 else EmuTransport.Car(null, 0.0, volt = 12.5)
        if (cmd.startsWith("AT")) {
            val a = cmd.substring(2)
            return when {
                a == "Z" -> { headers = false; spaces = true; "ELM327 v1.5" }
                a == "I" -> "ELM327 v1.5"
                a == "RV" -> "%.1fV".format(java.util.Locale.US, if (car.rpm != null && car.rpm > 300) car.volt else 12.6)
                a == "DPN" -> "A6"
                a == "DP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
                a == "H1" -> { headers = true; "OK" }
                a == "H0" -> { headers = false; "OK" }
                a == "S0" -> { spaces = false; "OK" }
                a == "S1" -> { spaces = true; "OK" }
                a.startsWith("SH") -> { header = a.substring(2); "OK" }
                else -> "OK"
            }
        }
        if (cmd.length < 2 || !cmd.all { it in "0123456789ABCDEF" }) return "?"
        // physically addressed UDS to other modules: not supported
        if (header != "7DF" && header != "7E0" && cmd.startsWith("19")) return frames(listOf(0x7F, 0x19, 0x11), "7E" + (header.last().digitToIntOrNull()?.plus(8) ?: 8))
        if (cmd.startsWith("22")) return frames(listOf(0x7F, 0x22, 0x31))
        if (cmd.startsWith("14")) return frames(listOf(0x54))
        val mode = cmd.substring(0, 2)
        val engineAwake = car.rpm != null || 0x0C !in supported
        if (!engineAwake && mode != "09") return "NO DATA"
        return when (mode) {
            "01" -> { val p = cmd.substring(2, 4).toInt(16); mode01(p, car)?.let { frames(listOf(0x41, p) + it) } ?: "NO DATA" }
            "02" -> { val p = cmd.substring(2, 4).toInt(16); if (p == 0x02) frames(listOf(0x42, 0x02, 0x00) + dtcBytes(stored.firstOrNull() ?: "P0000"))
                      else mode01(p, car)?.let { frames(listOf(0x42, p, 0x00) + it) } ?: "NO DATA" }
            "03" -> frames(listOf(0x43, stored.size) + stored.flatMap { dtcBytes(it) })
            "07" -> frames(listOf(0x47, pending.size) + pending.flatMap { dtcBytes(it) })
            "0A" -> frames(listOf(0x4A, 0x00))
            "04" -> { stored = emptyList(); pending = emptyList(); monitor = intArrayOf(0x00, 0x07, 0x65, 0x65); frames(listOf(0x44)) }
            "09" -> when (cmd.substring(2, 4)) {
                "02" -> frames(listOf(0x49, 0x02, 0x01) + vin.map { it.code })
                "0A" -> frames(listOf(0x49, 0x0A, 0x01) + "ECM-EngineControl".map { it.code })
                "04" -> frames(listOf(0x49, 0x04, 0x01) + "CAL123456789".map { it.code })
                else -> "NO DATA"
            }
            else -> "NO DATA"
        }
    }

    override fun writeBytes(data: ByteArray) {
        val cmd = String(data).trim()
        var r = try { reply(cmd) } catch (e: Exception) { "?" }
        if (delayMs > 0) try { Thread.sleep(delayMs) } catch (_: Exception) {}
        if (chaos > 0 && !cmd.startsWith("AT", true) && rnd.nextDouble() < chaos) r = junk[rnd.nextInt(junk.size)]
        synchronized(log) { log += "> $cmd  <  ${r.replace("\r", " | ")}" ; if (log.size > 400) log.removeAt(0) }
        feed(("$r\r\r>").toByteArray())
    }
}
