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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Raw diagnostic capture only. No HydroLite protocol is assumed and no
 * observations are written to survey or demo session directories.
 */
class SounderDiagnosticService : Service() {
    companion object {
        private val captureActive = AtomicBoolean(false)
        fun active(): Boolean = captureActive.get()
    }
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    @Volatile private var socket: BluetoothSocket? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var timedOut = false
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
        if (!started.compareAndSet(false, true)) return START_NOT_STICKY
        running.set(true)
        captureActive.set(true)
        val address = intent?.getStringExtra("address").orEmpty()
        Thread {
            var logFile: File? = null
            try {
                if (Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    throw SecurityException("Nearby devices permission required")
                require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) { "Select a paired sounder" }
                val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: throw IllegalStateException("Bluetooth unavailable")
                check(adapter.isEnabled) { "Turn on Bluetooth and pair the sounder in Android Settings" }
                val device = adapter.getRemoteDevice(address)
                status("connecting", "Bluetooth RFCOMM SPP; protocol unverified")
                val connection = device.createRfcommSocketToServiceRecord(spp)
                socket = connection
                val connectTimeout = watchdog.schedule({
                    timedOut = true
                    try { connection.close() } catch (_: Exception) {}
                }, 12, TimeUnit.SECONDS)
                connection.connect()
                connectTimeout.cancel(false)
                val directory = File(filesDir, "sounder_diagnostics").apply { mkdirs() }
                logFile = File(directory, "sounder_raw_${System.currentTimeMillis()}_${UUID.randomUUID()}.bin")
                prefs.edit().putString("file", logFile.absolutePath)
                    .putLong("bytes", 0L).putInt("frames", 0).remove("preview").apply()
                status("connected", "Raw diagnostic capture only; SPP support and depth protocol unverified")
                val framer = DepthStreamDecoder()
                var bytesTotal = 0L
                var framesTotal = 0
                val buffer = ByteArray(1024)
                File(directory, "${logFile.nameWithoutExtension}_chunks.csv").bufferedWriter().use { timing ->
                timing.write("received_utc_ms,received_elapsed_ms,byte_offset,byte_count\n")
                timing.flush()
                logFile.outputStream().buffered().use { output ->
                    connection.inputStream.use { input ->
                        while (running.get()) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            output.flush()
                            timing.write(Csv.row(listOf(System.currentTimeMillis().toString(), SystemClock.elapsedRealtime().toString(), bytesTotal.toString(), count.toString())))
                            timing.flush()
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
                }
                if (running.get()) status("disconnected", "Stream ended")
            } catch (e: Exception) {
                if (running.get()) status("error", if (timedOut) "Connection timed out; confirm pairing, power and supported Bluetooth interface. SPP is not verified." else e.message ?: "Bluetooth capture failed")
            } finally {
                running.set(false)
                captureActive.set(false)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        captureActive.set(false)
        watchdog.shutdownNow()
        try { socket?.close() } catch (_: Exception) {}
        if (prefs.getString("state", "") != "error") status("disconnected", "Capture stopped")
        super.onDestroy()
    }
}
