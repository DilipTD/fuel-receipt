package com.dilip.carobd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Minimal MQTT 3.1.1 publisher (QoS 0) with Home Assistant MQTT discovery. No third-party libraries. */
class MqttClient(private val host: String, private val port: Int, private val tls: Boolean,
                 private val user: String, private val pass: String, private val clientId: String,
                 private val willTopic: String) {
    private var sock: Socket? = null
    private var out: DataOutputStream? = null
    private var inp: DataInputStream? = null
    @Volatile var lastWrite = 0L

    private fun str(b: ByteArrayOutputStream, s: String) { val d = s.toByteArray(Charsets.UTF_8); b.write(d.size shr 8); b.write(d.size and 255); b.write(d) }
    private fun remLen(n: Int): ByteArray { val o = ByteArrayOutputStream(); var x = n; do { var d = x % 128; x /= 128; if (x > 0) d = d or 128; o.write(d) } while (x > 0); return o.toByteArray() }

    /** Returns null on success or a readable error. */
    fun connect(): String? {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 8000)
            s.soTimeout = 8000; s.tcpNoDelay = true
            var use: Socket = s
            if (tls) {
                val ss = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, port, true) as SSLSocket
                ss.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ss.session)) { ss.close(); return "TLS certificate does not match the host name" }
                use = ss
            }
            sock = use; out = DataOutputStream(use.getOutputStream()); inp = DataInputStream(use.getInputStream())
            val vh = ByteArrayOutputStream()
            str(vh, "MQTT"); vh.write(4)
            var flags = 0x02 or 0x04 or 0x20
            if (user.isNotEmpty()) flags = flags or 0x80
            if (user.isNotEmpty() && pass.isNotEmpty()) flags = flags or 0x40
            vh.write(flags); vh.write(0); vh.write(60)
            str(vh, clientId); str(vh, willTopic); str(vh, "offline")
            if (user.isNotEmpty()) str(vh, user)
            if (user.isNotEmpty() && pass.isNotEmpty()) str(vh, pass)
            val body = vh.toByteArray()
            val o = out!!
            o.write(0x10); o.write(remLen(body.size)); o.write(body); o.flush()
            val h = ByteArray(4); inp!!.readFully(h)
            if (h[0].toInt() != 0x20) return "Not an MQTT broker (unexpected reply)"
            return when (h[3].toInt()) {
                0 -> { lastWrite = System.currentTimeMillis(); null }
                1 -> "Broker refused the protocol version"
                2 -> "Broker rejected the client id"
                3 -> "Broker unavailable"
                4 -> "Wrong user name or password"
                5 -> "Not authorised – check the broker user's permissions"
                else -> "Broker refused (code ${h[3]})"
            }
        } catch (e: java.net.UnknownHostException) { close(); return "Unknown host \"$host\"" }
        catch (e: java.net.SocketTimeoutException) { close(); return "Timed out connecting to $host:$port" }
        catch (e: javax.net.ssl.SSLException) { close(); return "TLS error: ${e.message}" }
        catch (e: Exception) { close(); return e.message ?: e.javaClass.simpleName }
    }

    @Synchronized fun publish(topic: String, payload: String, retain: Boolean = false) {
        val b = ByteArrayOutputStream(); str(b, topic); b.write(payload.toByteArray(Charsets.UTF_8))
        val body = b.toByteArray(); val o = out ?: throw java.io.IOException("not connected")
        o.write(0x30 or (if (retain) 1 else 0)); o.write(remLen(body.size)); o.write(body); o.flush()
        lastWrite = System.currentTimeMillis()
    }

    @Synchronized fun ping() { val o = out ?: throw java.io.IOException("not connected"); o.write(0xC0); o.write(0); o.flush(); lastWrite = System.currentTimeMillis() }

    /** Throw away incoming PINGRESP etc. */
    fun drain() { try { val i = inp ?: return; val n = i.available(); if (n > 0) i.skipBytes(n) } catch (_: Exception) {} }

    fun close(polite: Boolean = false) {
        try { if (polite) { out?.write(0xE0); out?.write(0); out?.flush() } } catch (_: Exception) {}
        try { sock?.close() } catch (_: Exception) {}
        sock = null; out = null; inp = null
    }
}

object Mqtt {
    @Volatile var status = "Off"
    @Volatile var lastOkT = 0L
    @Volatile var sent = 0L
    @Volatile private var resend = false
    @Volatile private var reconnect = false
    private var th: Thread? = null
    private val lock = Object()

    private class Ent(val kind: String, val key: String, val name: String, val unit: String? = null, val dc: String? = null,
                      val icon: String? = null, val sc: String? = null, val diag: Boolean = false, val expire: Boolean = true)

    private val ents = listOf(
        Ent("sensor", "speed", "Speed", "km/h", "speed", "mdi:speedometer", "measurement"),
        Ent("sensor", "rpm", "Engine speed", "rpm", null, "mdi:engine", "measurement"),
        Ent("sensor", "coolant", "Coolant temperature", "°C", "temperature", null, "measurement"),
        Ent("sensor", "intake_temp", "Intake air temperature", "°C", "temperature", null, "measurement"),
        Ent("sensor", "ambient_temp", "Ambient temperature", "°C", "temperature", null, "measurement"),
        Ent("sensor", "throttle", "Throttle", "%", null, "mdi:car-cruise-control", "measurement"),
        Ent("sensor", "load", "Engine load", "%", null, "mdi:engine-outline", "measurement"),
        Ent("sensor", "fuel_level", "Fuel level", "%", null, "mdi:gas-station", "measurement"),
        Ent("sensor", "battery_voltage", "Battery voltage", "V", "voltage", null, "measurement"),
        Ent("sensor", "range", "Range", "km", "distance", "mdi:map-marker-distance", "measurement"),
        Ent("sensor", "trip_distance", "Trip distance", "km", "distance", "mdi:road-variant", "measurement", expire = false),
        Ent("sensor", "economy", "Instant economy", "km/L", null, "mdi:leaf", "measurement"),
        Ent("sensor", "trip_economy", "Trip economy", "km/L", null, "mdi:leaf", "measurement", expire = false),
        Ent("sensor", "fuel_rate", "Fuel rate", "L/h", null, "mdi:gas-station-outline", "measurement"),
        Ent("sensor", "odometer", "Odometer", "km", "distance", "mdi:counter", "total_increasing", expire = false),
        Ent("sensor", "trip_score", "Driving score", null, null, "mdi:steering", "measurement", expire = false),
        Ent("sensor", "health_score", "Car health score", null, null, "mdi:car-wrench", "measurement", true, false),
        Ent("sensor", "dtc_count", "Fault codes", null, null, "mdi:alert-circle-outline", "measurement", true, false),
        Ent("sensor", "road_limit", "Road speed limit", "km/h", null, "mdi:speed-limit", "measurement"),
        Ent("binary_sensor", "engine", "Engine running", null, "running"),
        Ent("binary_sensor", "connected", "OBD adapter connected", null, "connectivity", null, null, true),
        Ent("binary_sensor", "mil", "Check-engine lamp", null, "problem", null, null, true),
        Ent("binary_sensor", "overspeed", "Over speed", null, "problem")
    )

    fun deviceId() = "carobd_${ObdManager.vehicle.id}"
    private fun base() = Prefs.mqttBase.trim().trim('/').ifEmpty { "carobd" } + "/" + deviceId()

    fun ensureStarted(ctx: Context) {
        synchronized(lock) { if (th?.isAlive == true) return; th = Thread({ loop() }, "mqtt").apply { isDaemon = true; start() } }
    }
    /** Re-read settings and reconnect now. */
    fun apply(ctx: Context) { reconnect = true; ensureStarted(ctx); synchronized(lock) { lock.notifyAll() } }
    fun resendDiscovery(ctx: Context) { resend = true; ensureStarted(ctx); synchronized(lock) { lock.notifyAll() } }

    private fun sleepMs(ms: Long) { synchronized(lock) { try { lock.wait(ms) } catch (_: InterruptedException) {} } }

    private fun newClient() = MqttClient(Prefs.mqttHost.trim(), Prefs.mqttPort, Prefs.mqttTls, Prefs.mqttUser, Prefs.mqttPass,
        "carobd-" + Integer.toHexString((Prefs.mqttHost + deviceId()).hashCode()) + "-" + (System.nanoTime() % 1000), base() + "/availability")

    /** Quick check used by the "Test connection" button. */
    fun test(cb: (String) -> Unit) {
        Thread {
            if (Prefs.mqttHost.isBlank()) { cb("Enter the broker address first"); return@Thread }
            val c = newClient(); val e = c.connect()
            if (e == null) { try { c.publish(base() + "/test", "ok"); } catch (_: Exception) {}; c.close(true); cb("Connected to ${Prefs.mqttHost}:${Prefs.mqttPort} ✓") } else cb("Failed: $e")
        }.start()
    }

    private fun f(v: Double?, d: Int = 0): String? = v?.let { if (d == 0) "%.0f".format(java.util.Locale.US, it) else "%.${d}f".format(java.util.Locale.US, it) }

    private var healthAt = 0L; private var healthV: Int? = null
    private fun snapshot(): Map<String, String> {
        val m = ObdManager; val v = m.values; val e = m.engine; val t = e.current; val veh = m.vehicle
        val connected = m.state == ObdManager.State.CONNECTED
        val out = LinkedHashMap<String, String?>()
        out["connected"] = if (connected) "ON" else "OFF"
        out["engine"] = if (connected && m.engineOn) "ON" else "OFF"
        if (connected) {
            out["speed"] = f(v[0x0D]); out["rpm"] = f(v[0x0C]); out["coolant"] = f(v[0x05]); out["intake_temp"] = f(v[0x0F])
            out["ambient_temp"] = f(v[0x46]); out["throttle"] = f(v[0x11] ?: v[0x45]); out["load"] = f(v[0x04]); out["fuel_level"] = f(v[0x2F])
            out["battery_voltage"] = f(m.batteryV ?: v[0x42], 2); out["range"] = f(m.rangeKm()); out["economy"] = f(e.instantKmpl, 1)
            out["fuel_rate"] = f(m.fuelRate(), 2)
            m.monitorRaw?.let { d -> out["mil"] = if (d[0] and 0x80 != 0) "ON" else "OFF"; out["dtc_count"] = (d[0] and 0x7F).toString() }
            out["overspeed"] = if (e.overspeedNow) "ON" else "OFF"
            val now = System.currentTimeMillis()
            if (now - healthAt > 60_000) { healthAt = now; healthV = try { HealthScore.compute().score } catch (_: Exception) { null } }
            out["health_score"] = healthV?.toString()
        }
        out["trip_distance"] = f(t?.distanceKm, 2); out["trip_economy"] = f(t?.kmpl, 1); out["trip_score"] = t?.score?.toString()
        out["odometer"] = if (veh.odoKm > 0) f(veh.odoKm, 1) else null
        out["road_limit"] = RoadAssist.info?.limit?.toString()
        return out.filterValues { it != null }.mapValues { it.value!! }
    }

    private fun discovery(c: MqttClient) {
        val veh = ObdManager.vehicle; val id = deviceId(); val b = base(); val disc = Prefs.mqttDisc.trim().trim('/').ifEmpty { "homeassistant" }
        val dev = JSONObject().put("identifiers", JSONArray().put(id)).put("name", veh.name.ifBlank { "Car" })
            .put("manufacturer", veh.make.ifBlank { "Dr. Vittu OBDII Pro" }).put("model", veh.model.ifBlank { "OBD-II vehicle" }).put("sw_version", AppInfo.VERSION)
        for (e in ents) {
            val o = JSONObject().put("name", e.name).put("unique_id", "${id}_${e.key}").put("object_id", "${id}_${e.key}").put("has_entity_name", true)
                .put("state_topic", "$b/${e.key}").put("availability_topic", "$b/availability").put("device", dev)
            if (e.kind == "binary_sensor") o.put("payload_on", "ON").put("payload_off", "OFF")
            e.unit?.let { o.put("unit_of_measurement", it) }; e.dc?.let { o.put("device_class", it) }; e.icon?.let { o.put("icon", it) }
            e.sc?.let { o.put("state_class", it) }
            if (e.diag) o.put("entity_category", "diagnostic")
            if (e.kind == "sensor" && e.expire) o.put("expire_after", 180)
            c.publish("$disc/${e.kind}/$id/${e.key}/config", o.toString(), true)
        }
        val tr = JSONObject().put("name", "Location").put("unique_id", "${id}_location").put("object_id", "${id}_location").put("has_entity_name", true)
            .put("json_attributes_topic", "$b/location").put("availability_topic", "$b/availability").put("source_type", "gps").put("icon", "mdi:car").put("device", dev)
        c.publish("$disc/device_tracker/$id/location/config", tr.toString(), true)
    }

    private fun loop() {
        var c: MqttClient? = null; var backoff = 3000L; var discVid = -1L
        val last = HashMap<String, String>(); val lastT = HashMap<String, Long>(); var lastLoc = ""; var lastLocT = 0L
        while (true) {
            try {
                val on = Prefs.mqttOn && Prefs.mqttHost.isNotBlank()
                if (reconnect || !on) {
                    reconnect = false
                    if (c != null) { try { c.publish(base() + "/availability", "offline", true) } catch (_: Exception) {}; c.close(true); c = null }
                    if (!on) { status = if (Prefs.mqttOn) "Enter the broker address" else "Off"; sleepMs(2000); continue }
                }
                if (c == null) {
                    status = "Connecting to ${Prefs.mqttHost}…"
                    val n = newClient(); val err = n.connect()
                    if (err != null) { status = "Error: $err – retrying"; sleepMs(backoff); backoff = minOf(backoff * 2, 60_000); continue }
                    c = n; backoff = 3000; last.clear(); lastT.clear(); discVid = -1; lastLoc = ""
                }
                val cl = c!!
                if (discVid != ObdManager.vehicle.id || resend) {
                    resend = false; discovery(cl); discVid = ObdManager.vehicle.id; cl.publish(base() + "/availability", "online", true)
                }
                val now = System.currentTimeMillis(); val b = base()
                for ((k, v) in snapshot()) {
                    if (last[k] != v || now - (lastT[k] ?: 0L) > 60_000) { cl.publish("$b/$k", v, false); last[k] = v; lastT[k] = now; sent++ }
                }
                Gps.last?.let { l ->
                    if (now - l.time < 120_000) {
                        val js = JSONObject().put("latitude", l.latitude).put("longitude", l.longitude).put("gps_accuracy", l.accuracy.toDouble())
                            .put("speed", (l.speed * 3.6).toInt()).toString()
                        val key = "%.4f,%.4f".format(l.latitude, l.longitude)
                        if (key != lastLoc || now - lastLocT > 60_000) { cl.publish("$b/location", js, false); lastLoc = key; lastLocT = now; sent++ }
                    }
                }
                if (now - cl.lastWrite > 30_000) cl.ping()
                cl.drain()
                lastOkT = now; status = "Connected to ${Prefs.mqttHost}:${Prefs.mqttPort}"
            } catch (e: Exception) {
                status = "Disconnected: ${e.message ?: e.javaClass.simpleName} – reconnecting"
                try { c?.close() } catch (_: Exception) {}; c = null
                sleepMs(3000); continue
            }
            sleepMs(Prefs.mqttSec.coerceIn(1, 60) * 1000L)
        }
    }
}
