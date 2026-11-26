package com.dilip.carobd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import javax.net.SocketFactory

/** Connect to the last-used adapter without any screen (used by the activity and by receivers). */
object AutoStart {
    fun hasBtPerms(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun wifiFactory(ctx: Context): SocketFactory? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n.socketFactory
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun connectLast(ctx: Context): Boolean {
        ObdManager.init(ctx)
        if (ObdManager.state != ObdManager.State.DISCONNECTED) return false
        val app = ctx.applicationContext
        when (Prefs.lastType) {
            2 -> {
                val fac = wifiFactory(app)
                ObdManager.connect(app) { WifiTransport(Prefs.wifiHost, Prefs.wifiPort, fac) }
                return true
            }
            else -> {
                val addr = Prefs.lastAddr
                val ad = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
                if (addr.isEmpty() || !hasBtPerms(app) || !ad.isEnabled) return false
                return try {
                    val d = ad.getRemoteDevice(addr)
                    if (Prefs.lastType == 0) ObdManager.connect(app) { ClassicBtTransport(d, ad) }
                    else ObdManager.connect(app) { BleTransport(app, d) }
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }
}

/** Re-schedules the maintenance reminder job after a phone restart. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            Prefs.init(ctx)
            Db.init(ctx)
            Maint.schedule(ctx)
        } catch (_: Exception) {
        }
    }
}

/**
 * When the phone connects to the car's own Bluetooth (stereo / hands-free) the car has just been
 * switched on – start the OBD connection and automatic trip logging in the background.
 */
class CarBtReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            Prefs.init(ctx)
            if (!Prefs.autoStartCarBt || Prefs.carBtAddr.isEmpty()) return
            @Suppress("DEPRECATION")
            val dev: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (!dev.address.equals(Prefs.carBtAddr, ignoreCase = true)) return
            if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                val app = ctx.applicationContext
                val pending = goAsync()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        ObdManager.init(app)
                        if (AutoStart.connectLast(app)) ObdManager.log("Auto-start: car Bluetooth connected")
                    } catch (_: Exception) {
                    } finally {
                        pending.finish()
                    }
                }, 4000)
            }
        } catch (_: Exception) {
        }
    }
}
