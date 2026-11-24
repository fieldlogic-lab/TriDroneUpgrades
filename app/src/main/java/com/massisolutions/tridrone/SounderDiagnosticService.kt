package com.massisolutions.tridrone

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raw Bluetooth capture plus provisional eight-field decoder. Original bytes are retained.
 * Numeric validity does not establish manufacturer protocol or hardware accuracy.
 */
class SounderDiagnosticService : Service() {
    private val hydroSource = UUID.randomUUID().toString()
    private val running = AtomicBoolean(false)
    // Android invokes start commands on the main thread. Never replace a worker
    // while the prior worker is closing this service instance.
    private var started = false
    private val socketLock = Any()
    private var socket: BluetoothSocket? = null

    private fun closeConnection() {
        synchronized(socketLock) {
            val connection = socket
            socket = null
            try { connection?.close() } catch (_: IOException) {}
        }
    }

    private fun active() = running.get() && HydroLive.ownsSounder(hydroSource)
    private val prefs by lazy { getSharedPreferences("sounder_diagnostics", MODE_PRIVATE) }
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private fun status(state: String, detail: String = "") {
        synchronized(HydroLive) {
            if (!HydroLive.ownsSounder(hydroSource) || state == "connected" && !running.get()) return
            HydroLive.sounderConnection(hydroSource, state == "connected")
            prefs.edit().putString("state", state).putString("detail", detail).apply()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("sounder_diagnostics", "Sounder diagnostics", NotificationManager.IMPORTANCE_LOW))
        startForeground(1004, Notification.Builder(this, "sounder_diagnostics")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("HydroField sounder diagnostics")
            .setContentText("Recording raw Bluetooth diagnostic data")
            .setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            running.set(false)
            closeConnection()
            stopSelf()
            return START_NOT_STICKY
        }
        if (HydroLive.selectedProtocol(this) != HydroLiteDecoder.PROTOCOL) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        running.set(true)
        HydroLive.claimSounder(hydroSource)
        val address = intent?.getStringExtra("address").orEmpty()
        Thread {
            try {
                if (Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    throw SecurityException("Nearby devices permission required")
                require(address.isNotBlank()) { "Select a paired sounder" }
                val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: throw IllegalStateException("Bluetooth unavailable")
                val device = adapter.getRemoteDevice(address)
                val directory = File(filesDir, "sounder_diagnostics").apply { mkdirs() }
                val logFile = File(directory, "sounder_raw_${System.currentTimeMillis()}_$hydroSource.bin")
                logFile.outputStream().buffered().use { output ->
                    synchronized(HydroLive) {
                        if (HydroLive.ownsSounder(hydroSource)) prefs.edit().putString("file", logFile.absolutePath)
                            .putLong("bytes", 0L).putInt("frames", 0).remove("preview").apply()
                    }
                    status("connecting", "Bluetooth RFCOMM SPP; protocol unverified")
                    var bytesTotal = 0L
                    var framesTotal = 0
                    captureHydroLite(
                        active = ::active,
                        connect = {
                            val connection = device.createRfcommSocketToServiceRecord(spp)
                            synchronized(socketLock) {
                                if (running.get()) socket = connection
                                else {
                                    connection.close()
                                    throw IOException("Capture stopped")
                                }
                            }
                            connection.connect()
                            connection.inputStream
                        },
                        onConnected = {
                            status("connected", "Provisional field-2 meters decoder; hardware verification required")
                        },
                        onDisconnected = { error ->
                            status("reconnecting", error.message ?: "Bluetooth connection lost")
                        },
                        closeConnection = ::closeConnection,
                        waitToRetry = { SystemClock.sleep(3000) }
                    ) { buffer, count, frames ->
                        output.write(buffer, 0, count)
                        output.flush()
                        bytesTotal += count
                        HydroLive.rawDepth(hydroSource, buffer, count)
                        framesTotal += frames.size
                        frames.forEach { frame ->
                            HydroLive.frame(hydroSource, frame, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
                        }
                        synchronized(HydroLive) {
                            if (HydroLive.ownsSounder(hydroSource)) prefs.edit().putLong("bytes", bytesTotal)
                                .putInt("frames", framesTotal)
                                .putString("preview", frames.lastOrNull()?.take(160) ?: "")
                                .apply()
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) status("error", e.message ?: "Bluetooth capture failed")
            } finally {
                running.set(false)
                closeConnection()
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        closeConnection()
        if (prefs.getString("state", "") != "error") status("disconnected", "Capture stopped")
        super.onDestroy()
    }
}

