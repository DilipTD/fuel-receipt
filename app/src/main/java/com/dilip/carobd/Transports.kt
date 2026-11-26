package com.dilip.carobd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.SocketFactory
import kotlin.concurrent.withLock

/** Byte pipe to an ELM327. Subclasses push received bytes with [feed]. */
abstract class ObdTransport {
    private val buf = StringBuilder()
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    @Volatile
    var connected = false
        protected set

    abstract val name: String
    abstract fun open()
    protected abstract fun writeBytes(data: ByteArray)
    abstract fun close()

    protected fun feed(data: ByteArray, len: Int = data.size) {
        lock.withLock {
            for (i in 0 until len) {
                val c = (data[i].toInt() and 0xFF).toChar()
                if (c != '\u0000') buf.append(c)
            }
            cond.signalAll()
        }
    }

    protected fun signalLost() {
        connected = false
        lock.withLock { cond.signalAll() }
    }

    fun write(s: String) = writeBytes(s.toByteArray(Charsets.US_ASCII))

    fun clearInput() {
        lock.withLock { buf.setLength(0) }
    }

    /** Returns text before the '>' prompt, or null on timeout. Throws if the link dropped. */
    fun readUntilPrompt(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        lock.withLock {
            while (true) {
                val i = buf.indexOf(">")
                if (i >= 0) {
                    val r = buf.substring(0, i)
                    buf.delete(0, i + 1)
                    return r
                }
                if (!connected) throw IOException("Connection lost")
                val rem = deadline - System.currentTimeMillis()
                if (rem <= 0) return null
                cond.await(rem, TimeUnit.MILLISECONDS)
            }
        }
    }
}

/** Shared code for socket-style transports (Bluetooth SPP, WiFi TCP). */
abstract class StreamTransport : ObdTransport() {
    protected var input: InputStream? = null
    protected var output: OutputStream? = null

    protected fun startReader() {
        connected = true
        val t = Thread({
            val b = ByteArray(1024)
            try {
                while (connected) {
                    val n = input?.read(b) ?: -1
                    if (n < 0) break
                    if (n > 0) feed(b, n)
                }
            } catch (_: IOException) {
            }
            signalLost()
        }, "obd-reader")
        t.isDaemon = true
        t.start()
    }

    override fun writeBytes(data: ByteArray) {
        val o = output ?: throw IOException("Not connected")
        o.write(data)
        o.flush()
    }
}

@SuppressLint("MissingPermission")
class ClassicBtTransport(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter?
) : StreamTransport() {
    override val name: String get() = "Bluetooth " + (device.name ?: device.address)
    private var socket: BluetoothSocket? = null

    override fun open() {
        try {
            adapter?.cancelDiscovery()
        } catch (_: Exception) {
        }
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            }
        )
        var last: Exception? = null
        for (make in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                startReader()
                return
            } catch (e: Exception) {
                last = e
                try {
                    s?.close()
                } catch (_: Exception) {
                }
            }
        }
        throw IOException("Bluetooth connect failed (is the adapter paired and the ignition ON?): ${last?.message}")
    }

    override fun close() {
        connected = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

class WifiTransport(
    private val host: String,
    private val port: Int,
    private val factory: SocketFactory?
) : StreamTransport() {
    override val name: String get() = "WiFi $host:$port"
    private var sock: Socket? = null

    override fun open() {
        val s = factory?.createSocket() ?: Socket()
        val addr = InetSocketAddress(host, port)
        if (addr.isUnresolved) {
            throw IOException("Cannot find '$host'. For WiCAN join its WiFi and use 192.168.80.1 (mDNS .local names often don't work on Android – use the IP shown in WiCAN's Status tab).")
        }
        try {
            s.connect(addr, 6000)
        } catch (e: IOException) {
            throw IOException("WiFi connect failed to $host:$port (WiCAN = port 3333, clones = 35000; check you joined the adapter's WiFi): ${e.message}")
        }
        s.keepAlive = true
        s.tcpNoDelay = true
        sock = s
        input = s.getInputStream()
        output = s.getOutputStream()
        startReader()
    }

    override fun close() {
        connected = false
        try {
            sock?.close()
        } catch (_: Exception) {
        }
    }
}

@SuppressLint("MissingPermission")
class BleTransport(private val ctx: Context, private val device: BluetoothDevice) : ObdTransport() {
    override val name: String get() = "BLE " + (device.name ?: device.address)

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val connectLatch = CountDownLatch(1)
    private val servicesLatch = CountDownLatch(1)
    private val descLatch = CountDownLatch(1)
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var linkUp = false
    private val mtuLatch = CountDownLatch(1)
    @Volatile private var chunk = 20

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                linkUp = true
                connectLatch.countDown()
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                linkUp = false
                connectLatch.countDown()
                servicesLatch.countDown()
                descLatch.countDown()
                writeLatch?.countDown()
                signalLost()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) chunk = mtu - 3
            mtuLatch.countDown()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesLatch.countDown()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descLatch.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeLatch?.countDown()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { feed(it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            feed(value)
        }
    }

    @Suppress("DEPRECATION")
    override fun open() {
        gatt = if (Build.VERSION.SDK_INT >= 23)
            device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(ctx, false, cb)
        if (!connectLatch.await(15, TimeUnit.SECONDS) || !linkUp) fail("BLE connect timeout")
        if (!servicesLatch.await(15, TimeUnit.SECONDS) || !linkUp) fail("BLE service discovery failed")
        val g = gatt ?: fail("No GATT")
        if (Build.VERSION.SDK_INT >= 21 && g.requestMtu(247)) mtuLatch.await(3, TimeUnit.SECONDS)

        var notify: BluetoothGattCharacteristic? = null
        var write: BluetoothGattCharacteristic? = null
        for ((svc, n, w) in KNOWN) {
            val s = g.getService(UUID.fromString(svc)) ?: continue
            notify = s.getCharacteristic(UUID.fromString(n))
            write = s.getCharacteristic(UUID.fromString(w))
            if (notify != null && write != null) break
        }
        if (notify == null || write == null) {
            // Generic fallback: first service that has both a notify and a write characteristic
            for (s in g.services) {
                val sid = s.uuid.toString().lowercase()
                if (sid.startsWith("00001800") || sid.startsWith("00001801") || sid.startsWith("0000180a")) continue
                val n = s.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }
                val w = s.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                if (n != null && w != null) {
                    notify = n; write = w; break
                }
            }
        }
        if (notify == null || write == null) fail("No ELM327 UART service found on this BLE device")
        writeChar = write
        g.setCharacteristicNotification(notify, true)
        val d = notify.getDescriptor(CCCD)
        if (d != null) {
            val indicate = notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            d.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
            descLatch.await(5, TimeUnit.SECONDS)
        }
        connected = true
    }

    private fun fail(msg: String): Nothing {
        close()
        throw IOException(msg)
    }

    @Suppress("DEPRECATION")
    override fun writeBytes(data: ByteArray) {
        val ch = writeChar ?: throw IOException("Not connected")
        val g = gatt ?: throw IOException("Not connected")
        ch.writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        var i = 0
        while (i < data.size) {
            val part = data.copyOfRange(i, minOf(i + chunk, data.size))
            i += part.size
            val l = CountDownLatch(1)
            writeLatch = l
            ch.value = part
            var ok = g.writeCharacteristic(ch)
            if (!ok) {
                Thread.sleep(30)
                ok = g.writeCharacteristic(ch)
            }
            if (!ok) throw IOException("BLE write failed")
            l.await(1, TimeUnit.SECONDS)
        }
    }

    override fun close() {
        connected = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    companion object {
        private fun u16(s: String) = "0000$s-0000-1000-8000-00805f9b34fb"
        val CCCD: UUID = UUID.fromString(u16("2902"))

        // (service, notify characteristic, write characteristic) used by common BLE ELM327 clones
        val KNOWN = listOf(
            Triple(u16("fff0"), u16("fff1"), u16("fff2")),
            Triple(u16("ffe0"), u16("ffe1"), u16("ffe1")),
            Triple(u16("18f0"), u16("2af0"), u16("2af1")),
            Triple(
                "e7810a71-73ae-499d-8c15-faa9aef0c3f2",
                "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
                "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"
            )
        )
    }
}
