package com.massisolutions.tridrone

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raw diagnostic capture only. No HydroLite protocol is assumed and no
 * observations are written to survey or demo session directories.
 */
class SounderDiagnosticService : Service() {
    private val running = AtomicBoolean(false)
    private var socket: BluetoothSocket? = null
    private val prefs by lazy { getSharedPreferences("sounder_diagnostics", MODE_PRIVATE) }
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private fun status(state: String, detail: String = "") {
        prefs.edit().putString("state", state).putString("detail", detail).apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("sounder_diagnostics", "Sounder diagnostics", NotificationManager.IMPORTANCE_LOW))
        startForeground(1004, Notification.Builder(this, "sounder_diagnostics")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("TriDrone sounder diagnostics")
            .setContentText("Recording raw Bluetooth diagnostic data")
            .setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            running.set(false)
            try { socket?.close() } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        val address = intent?.getStringExtra("address").orEmpty()
        Thread {
            var logFile: File? = null
            try {
                if (Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    throw SecurityException("Nearby devices permission required")
                require(address.isNotBlank()) { "Select a paired sounder" }
                val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: throw IllegalStateException("Bluetooth unavailable")
                val device = adapter.getRemoteDevice(address)
                status("connecting", "Bluetooth RFCOMM SPP; protocol unverified")
                val connection = device.createRfcommSocketToServiceRecord(spp)
                socket = connection
                connection.connect()
                val directory = File(filesDir, "sounder_diagnostics").apply { mkdirs() }
                logFile = File(directory, "sounder_raw_${System.currentTimeMillis()}.bin")
                prefs.edit().putString("file", logFile.absolutePath)
                    .putLong("bytes", 0L).putInt("frames", 0).remove("preview").apply()
                status("connected", "Raw capture only; no survey depths")
                val framer = DepthStreamDecoder()
                var bytesTotal = 0L
                var framesTotal = 0
                val buffer = ByteArray(1024)
                logFile.outputStream().buffered().use { output ->
                    connection.inputStream.use { input ->
                        while (running.get()) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            output.flush()
                            bytesTotal += count
                            val frames = framer.accept(buffer, count)
                            framesTotal += frames.size
                            prefs.edit().putLong("bytes", bytesTotal)
                                .putInt("frames", framesTotal)
                                .putString("preview", frames.lastOrNull()?.take(160) ?: "")
                                .apply()
                        }
                    }
                }
                if (running.get()) status("disconnected", "Stream ended")
            } catch (e: Exception) {
                if (running.get()) status("error", e.message ?: "Bluetooth capture failed")
            } finally {
                running.set(false)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        if (prefs.getString("state", "") != "error") status("disconnected", "Capture stopped")
        super.onDestroy()
    }
}
