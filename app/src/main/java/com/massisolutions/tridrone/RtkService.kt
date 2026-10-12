package com.massisolutions.tridrone

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Reach Bluetooth alternative only when receiver supports paired RFCOMM NMEA. */
class RtkService : GnssAcquisitionService() {
    override val transport = "bluetooth"
    override val notificationId = 1002
    override fun validate(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Allow Nearby devices, then pair the Reach RS2+ in Android settings")
        require(BluetoothAdapter.checkBluetoothAddress(intent.getStringExtra("address").orEmpty())) { "Select a paired Reach RS2+ receiver" }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        require(adapter != null && adapter.isEnabled) { "Enable Bluetooth and pair the receiver" }
    }
    override fun connect(intent: Intent): InputStream {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter!!
        val device = adapter.getRemoteDevice(intent.getStringExtra("address")!!)
        val socket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
        register(socket) // Watchdog can close a blocked connect/read.
        if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try { adapter.cancelDiscovery() } catch (_: SecurityException) { }
        }
        socket.connect()
        return socket.inputStream
    }
    override fun validatedProfile(intent: Intent) {
        getSharedPreferences("rtk_bluetooth_settings", MODE_PRIVATE).edit()
            .putString("address", intent.getStringExtra("address")).putBoolean("validated", true).apply()
    }
}

/** Explicit operator-configured Reach TCP NMEA endpoint; no network mode is assumed. */
class WifiRtkService : GnssAcquisitionService() {
    override val transport = "wifi"
    override val notificationId = 1003
    override fun validate(intent: Intent) {
        require(ConnectionProfile.validIpv4(intent.getStringExtra("host").orEmpty()) && intent.getIntExtra("port", 0) in 1..65535) {
            "Enter the receiver IPv4 address and its configured TCP NMEA port; join the receiver network first"
        }
    }
    override fun connect(intent: Intent): InputStream {
        val socket = Socket()
        register(socket)
        socket.connect(InetSocketAddress(intent.getStringExtra("host"), intent.getIntExtra("port", 0)), 7000)
        socket.soTimeout = ConnectionPolicy.INPUT_TIMEOUT_MS.toInt()
        return socket.getInputStream()
    }
    override fun validatedProfile(intent: Intent) {
        getSharedPreferences("wifi_rtk_settings", MODE_PRIVATE).edit()
            .putString("host", intent.getStringExtra("host")).putInt("port", intent.getIntExtra("port", 0))
            .putBoolean("validated", true).apply()
    }
}
