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
                // Paired RFCOMM connection does not require discovery.
                if (Build.VERSION.SDK_INT < 31 ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
                }
                connection.connect()
                val folder = File(filesDir, "surveys").apply { mkdirs() }
                val session = System.currentTimeMillis().toString()
                SurveyConfig.writeSnapshot(this, "rtk_$session")
                rawWriter = BufferedWriter(FileWriter(File(folder, "rtk_nmea_$session.csv")))
                fixWriter = BufferedWriter(FileWriter(File(folder, "rtk_fixes_$session.csv")))
                rawWriter?.write("received_utc_ms,nmea_sentence\n")
                fixWriter?.write(RtkCoordinateExport.header)
                rawWriter?.flush(); fixWriter?.flush()
                prefs.edit().putString("session", session).putInt("sentences", 0).putInt("fixes", 0).remove("last_fix_ms").remove("h_sigma_m").remove("v_sigma_m").apply()
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
                            val gst = NmeaGst.parse(sentence)
                            if (gst != null) prefs.edit().putString("h_sigma_m", gst.first.toString()).putString("v_sigma_m", gst.second.toString()).putLong("gst_received_ms", received).apply()
                            val fix = NmeaGga.parse(sentence)
                            if (fix != null) {
                                fixes++
                                fixWriter?.write(RtkCoordinateExport.row(received, fix, SurveyConfig.load(this)))
                                fixWriter?.flush()
                                prefs.edit().putString("quality", fix[3]).putString("satellites", fix[4])
                                    .putString("lat", fix[1]).putString("lon", fix[2]).putString("hdop", fix[5])
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

object NmeaGst {
    fun parse(sentence: String): Pair<Double, Double>? {
        val fields = sentence.substringBefore('*').removePrefix("$").split(',')
        if (!fields.firstOrNull().orEmpty().endsWith("GST") || fields.size < 9) return null
        val lat = fields[6].toDoubleOrNull() ?: return null
        val lon = fields[7].toDoubleOrNull() ?: return null
        val vertical = fields[8].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() || !vertical.isFinite() || lat < 0 || lon < 0 || vertical < 0) return null
        return Pair(kotlin.math.hypot(lat, lon), vertical)
    }
}

/**
 * Wi-Fi NMEA TCP client for Reach receivers configured to expose a TCP server.
 * The host/port are operator configured; no assumption about Emlid network mode.
 * Never marks an RTK fix until a fresh, valid GGA sentence arrives.
 */
class WifiRtkService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var tcp: java.net.Socket? = null
    private val prefs by lazy { getSharedPreferences("rtk_status", MODE_PRIVATE) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel("tridrone_wifi_rtk", "Wi-Fi GNSS streaming", NotificationManager.IMPORTANCE_LOW))
        startForeground(1003, Notification.Builder(this, "tridrone_wifi_rtk")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TriDrone Wi-Fi GNSS")
            .setContentText("Receiving RTK NMEA over TCP")
            .setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (running.getAndSet(true)) return START_NOT_STICKY
        val host = intent?.getStringExtra("host").orEmpty()
        val port = intent?.getIntExtra("port", 0) ?: 0
        if (host.isBlank() || port !in 1..65535) {
            prefs.edit().putString("state", "error").putString("detail", "Configure receiver IP and TCP port").apply()
            running.set(false); stopSelf(); return START_NOT_STICKY
        }
        worker = Thread {
            val folder = File(filesDir, "surveys").apply { mkdirs() }
            val session = System.currentTimeMillis().toString()
            var count = 0
            var fixes = 0
            try {
                SurveyConfig.writeSnapshot(this, "wifi_rtk_$session")
                BufferedWriter(FileWriter(File(folder, "wifi_nmea_$session.csv"))).use { raw ->
                    BufferedWriter(FileWriter(File(folder, "wifi_fixes_$session.csv"))).use { output ->
                        raw.write("received_utc_ms,nmea_sentence\\n")
                        output.write(RtkCoordinateExport.header)
                        while (running.get()) {
                            try {
                                prefs.edit().putString("state", "connecting")
                                    .putString("detail", "$host:$port")
                                    .remove("last_fix_ms").apply()
                                val socket = java.net.Socket()
                                tcp = socket
                                socket.connect(java.net.InetSocketAddress(host, port), 7000)
                                socket.soTimeout = 12000
                                prefs.edit().putString("state", "connected").putString("detail", "Wi-Fi TCP $host:$port")
                                    .putString("transport", "wifi").putString("session", session)
                                    .remove("last_fix_ms").apply()
                                socket.getInputStream().bufferedReader().use { reader ->
                                    while (running.get()) {
                                        val sentence = reader.readLine() ?: throw java.io.EOFException("NMEA stream closed")
                                        if (sentence.length > 256 || !sentence.startsWith("$") || !NmeaGga.valid(sentence)) continue
                                        val received = System.currentTimeMillis()
                                        raw.write("$received,${sentence.replace(",", "\\,")}\\n")
                                        raw.flush()
                                        count++
                                        NmeaGst.parse(sentence)?.let { gst ->
                                            prefs.edit().putString("h_sigma_m", gst.first.toString())
                                                .putString("v_sigma_m", gst.second.toString())
                                                .putLong("gst_received_ms", received).apply()
                                        }
                                        NmeaGga.parse(sentence)?.let { fix ->
                                            fixes++
                                            output.write(RtkCoordinateExport.row(received, fix, SurveyConfig.load(this)))
                                            output.flush()
                                            prefs.edit().putString("quality", fix[3]).putString("satellites", fix[4])
                                                .putString("lat", fix[1]).putString("lon", fix[2])
                                                .putString("hdop", fix[5]).putLong("last_fix_ms", received).apply()
                                        }
                                        prefs.edit().putInt("sentences", count).putInt("fixes", fixes).apply()
                                    }
                                }
                            } catch (e: Exception) {
                                if (running.get()) prefs.edit().putString("state", "reconnecting")
                                    .putString("detail", e.message ?: "Connection lost").remove("last_fix_ms").apply()
                            } finally {
                                try { tcp?.close() } catch (_: Exception) {}
                                tcp = null
                            }
                            if (running.get()) Thread.sleep(3000)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) prefs.edit().putString("state", "error")
                    .putString("detail", e.message ?: "Wi-Fi RTK error").apply()
            } finally {
                running.set(false)
                stopSelf()
            }
        }.also { it.start() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        running.set(false)
        try { tcp?.close() } catch (_: Exception) {}
        prefs.edit().putString("state", "disconnected").remove("last_fix_ms").apply()
        super.onDestroy()
    }
}
