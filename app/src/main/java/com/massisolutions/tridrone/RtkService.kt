package com.massisolutions.tridrone

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Bluetooth Classic RFCOMM NMEA receiver; does not inject mock Android locations. */
class RtkService : Service() {
    private val channel = "tridrone_rtk"
    private val running = AtomicBoolean(false)
    private var socket: BluetoothSocket? = null
    private var worker: Thread? = null
    private var rawWriter: BufferedWriter? = null
    private var fixWriter: BufferedWriter? = null
    private val prefs by lazy { getSharedPreferences("rtk_status", MODE_PRIVATE) }
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private fun report(state: String, detail: String = "") {
        prefs.edit().putString("state", state).putString("detail", detail).apply()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channel, "RTK NMEA streaming", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TriDrone RTK receiver")
            .setContentText("Bluetooth NMEA acquisition")
            .setOngoing(true).build()
        startForeground(1002, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (running.getAndSet(true)) return START_NOT_STICKY
        val address = intent?.getStringExtra("address") ?: ""
        worker = Thread {
            try {
                if (Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    throw SecurityException("Nearby devices permission required")
                val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: throw IllegalStateException("Bluetooth unavailable")
                if (!adapter.isEnabled) throw IllegalStateException("Enable Bluetooth")
                report("connecting", address)
                val device = adapter.getRemoteDevice(address)
                val connection = device.createRfcommSocketToServiceRecord(spp)
                socket = connection
                adapter.cancelDiscovery()
                connection.connect()
                val folder = File(filesDir, "surveys").apply { mkdirs() }
                val session = System.currentTimeMillis().toString()
                rawWriter = BufferedWriter(FileWriter(File(folder, "rtk_nmea_$session.csv")))
                fixWriter = BufferedWriter(FileWriter(File(folder, "rtk_fixes_$session.csv")))
                rawWriter?.write("received_utc_ms,nmea_sentence\n")
                fixWriter?.write("received_utc_ms,utc_time,latitude_deg,longitude_deg,fix_quality,satellites,hdop,altitude_m,geoid_separation_m,correction_age_s,station_id\n")
                rawWriter?.flush(); fixWriter?.flush()
                prefs.edit().putString("session", session).putInt("sentences", 0).putInt("fixes", 0).apply()
                report("connected", address)
                val input = connection.inputStream
                val line = StringBuilder()
                val buffer = ByteArray(1024)
                var count = 0
                var fixes = 0
                while (running.get()) {
                    val bytes = input.read(buffer)
                    if (bytes < 0) break
                    for (i in 0 until bytes) {
                        val c = buffer[i].toInt().toChar()
                        if (c == '\n') {
                            val sentence = line.toString().trimEnd('\r')
                            line.setLength(0)
                            if (!sentence.startsWith("$") || sentence.length > 256 || !NmeaGga.valid(sentence)) continue
                            val received = System.currentTimeMillis()
                            rawWriter?.write("$received,${sentence.replace(",", "\\,")}\n")
                            rawWriter?.flush()
                            count++
                            val fix = NmeaGga.parse(sentence)
                            if (fix != null) {
                                fixes++
                                fixWriter?.write("$received,${fix.joinToString(",")}\n")
                                fixWriter?.flush()
                                prefs.edit().putString("quality", fix[3]).putString("satellites", fix[4])
                                    .putString("lat", fix[1]).putString("lon", fix[2])
                                    .putLong("last_fix_ms", received).apply()
                            }
                            prefs.edit().putInt("sentences", count).putInt("fixes", fixes).apply()
                        } else if (c.code in 32..126 && line.length < 256) line.append(c)
                        else if (line.length >= 256) line.setLength(0)
                    }
                }
                report("disconnected", "Receiver stream ended")
            } catch (e: Exception) {
                if (running.get()) report("error", e.message ?: "Bluetooth stream failed")
            } finally {
                running.set(false)
                try { socket?.close() } catch (_: Exception) {}
                try { rawWriter?.close() } catch (_: Exception) {}
                try { fixWriter?.close() } catch (_: Exception) {}
                stopSelf()
            }
        }.also { it.start() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        if (prefs.getString("state", "") != "error") report("disconnected", "Stopped")
        super.onDestroy()
    }
}

object NmeaGga {
    fun valid(sentence: String): Boolean {
        val star = sentence.indexOf('*')
        if (star < 0 || star + 3 != sentence.length) return false
        val expected = sentence.substring(star + 1).toIntOrNull(16) ?: return false
        var checksum = 0
        for (i in 1 until star) checksum = checksum xor sentence[i].code
        return checksum == expected
    }
    /** GGA fix quality: 4=RTK fixed, 5=RTK float. Keep raw data for audit. */
    fun parse(sentence: String): List<String>? {
        val fields = sentence.substringBefore('*').removePrefix("$").split(',')
        if (!fields.firstOrNull().orEmpty().endsWith("GGA") || fields.size < 15) return null
        fun degrees(raw: String, hemisphere: String): String {
            val v = raw.toDoubleOrNull() ?: return ""
            val d = kotlin.math.floor(v / 100)
            val decimal = d + (v - d * 100) / 60.0
            return (if (hemisphere == "S" || hemisphere == "W") -decimal else decimal).toString()
        }
        return listOf(fields[1], degrees(fields[2], fields[3]), degrees(fields[4], fields[5]),
            fields[6], fields[7], fields[8], fields[9], fields[11], fields[13], fields[14])
    }
}
