package com.massisolutions.tridrone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import java.io.Closeable
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Transport-neutral raw acquisition. No phone GPS, mock data, or synchronized survey capture. */
abstract class GnssAcquisitionService : Service() {
    protected abstract val transport: String
    protected abstract val notificationId: Int
    protected abstract fun validate(intent: Intent)
    protected abstract fun connect(intent: Intent): InputStream
    protected abstract fun validatedProfile(intent: Intent)
    @Volatile private var connection: Closeable? = null
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    @Volatile private var lastInput = 0L
    @Volatile private var timeout = false
    private var worker: Thread? = null
    private var lease = ""
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    protected val prefs by lazy { getSharedPreferences("rtk_status", MODE_PRIVATE) }
    protected fun register(value: Closeable) { connection = value }
    private fun closeConnection() { try { connection?.close() } catch (_: Exception) {} }
    private fun clearFix() {
        prefs.edit().remove("last_fix_ms").remove("last_fix_elapsed_ms").remove("gst_received_ms")
            .remove("gst_elapsed_ms").remove("h_sigma_m").remove("v_sigma_m").remove("quality")
            .remove("lat").remove("lon").remove("correction_age_s").remove("gga_epoch").remove("gst_epoch").apply()
    }
    private fun status(state: String, detail: String) = GnssConnectionOwner.withCurrent(lease) {
        if (state != "connected") clearFix()
        prefs.edit().putString("state", state).putString("detail", detail).putString("transport", transport).apply()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("gnss_$transport", "Reach receiver diagnostics", NotificationManager.IMPORTANCE_LOW))
        startForeground(notificationId, Notification.Builder(this, "gnss_$transport")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("TriDrone Reach RS2+")
            .setContentText("$transport raw GNSS diagnostics — not survey recording").setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        // One acquisition per Service instance: a dying worker cannot stop a newer session.
        if (!started.compareAndSet(false, true)) return START_NOT_STICKY
        running.set(true)
        lease = UUID.randomUUID().toString()
        GnssConnectionOwner.claim(lease) { running.set(false); closeConnection(); worker?.interrupt(); stopSelf() }
        status("connecting", "Check receiver power and NMEA output")
        if (intent == null) { status("error", "Reconnect from Equipment to select a receiver"); running.set(false); stopSelf(); return START_NOT_STICKY }
        try { validate(intent) } catch (e: Exception) {
            status("error", e.message ?: "Check connection parameters"); running.set(false); stopSelf(); return START_NOT_STICKY
        }
        lastInput = SystemClock.elapsedRealtime()
        watchdog.scheduleAtFixedRate({
            if (running.get() && SystemClock.elapsedRealtime() - lastInput > ConnectionPolicy.INPUT_TIMEOUT_MS) {
                timeout = true; closeConnection()
            }
        }, 1, 1, TimeUnit.SECONDS)
        worker = Thread {
            try {
                GnssSessionLog(this, if (transport == "wifi") "wifi" else "rtk").use { log ->
                    val ggaEpochs = ReceiverEpochTracker()
                    val gstEpochs = ReceiverEpochTracker()
                    var count = 0
                    var fixes = 0
                    var rejected = 0
                    GnssConnectionOwner.withCurrent(lease) { prefs.edit().putString("session", log.id)
                        .putInt("sentences", 0).putInt("fixes", 0).putInt("rejected", 0).apply() }
                    for (attempt in 1..ConnectionPolicy.MAX_ATTEMPTS) {
                        if (!running.get() || !GnssConnectionOwner.current(lease)) break
                        timeout = false
                        lastInput = SystemClock.elapsedRealtime()
                        status(if (attempt == 1) "connecting" else "reconnecting", "Attempt $attempt/${ConnectionPolicy.MAX_ATTEMPTS}; check receiver network and power")
                        log.event("connecting", "$transport attempt $attempt")
                        try {
                            val input = connect(intent)
                            if (!running.get() || !GnssConnectionOwner.current(lease)) break
                            lastInput = SystemClock.elapsedRealtime()
                            status("connected", "$transport connected; waiting for valid GGA and GST NMEA")
                            log.event("connected", transport)
                            val framer = DepthStreamDecoder(256)
                            val buffer = ByteArray(1024)
                            while (running.get() && GnssConnectionOwner.current(lease)) {
                                val bytes = input.read(buffer)
                                if (bytes < 0) throw java.io.EOFException("Receiver stream ended")
                                if (bytes == 0) continue
                                log.bytes(buffer, bytes)
                                for (sentence in framer.accept(buffer, bytes)) {
                                    val utc = System.currentTimeMillis()
                                    val elapsed = SystemClock.elapsedRealtime()
                                    log.sentence(sentence, utc, elapsed)
                                    if (!NmeaGga.valid(sentence)) { rejected++; continue }
                                    lastInput = elapsed
                                    count++
                                    NmeaGst.parse(sentence)?.let { gst ->
                                        val epoch = NmeaEpoch.seconds(sentence.substringBefore('*').split(',')[1])!!
                                        if (gstEpochs.accept(epoch)) GnssConnectionOwner.withCurrent(lease) {
                                            prefs.edit().putString("h_sigma_m", gst.first.toString()).putString("v_sigma_m", gst.second.toString())
                                                .putLong("gst_received_ms", utc).putLong("gst_elapsed_ms", elapsed).putString("gst_epoch", epoch.toString()).apply()
                                        }
                                    }
                                    NmeaGga.parse(sentence)?.let { fix ->
                                        log.fix(utc, fix); fixes++
                                        val epoch = NmeaEpoch.seconds(fix[0])!!
                                        if (ggaEpochs.accept(epoch)) GnssConnectionOwner.withCurrent(lease) {
                                            prefs.edit().putString("quality", fix[3]).putString("satellites", fix[4])
                                                .putString("lat", fix[1]).putString("lon", fix[2]).putString("hdop", fix[5])
                                                .putString("correction_age_s", fix[8]).putString("gga_epoch", epoch.toString())
                                                .putLong("last_fix_ms", utc).putLong("last_fix_elapsed_ms", elapsed).apply()
                                            if (fix[3] != "0") validatedProfile(intent)
                                        }
                                    }
                                    GnssConnectionOwner.withCurrent(lease) { prefs.edit().putInt("sentences", count).putInt("fixes", fixes).putInt("rejected", rejected).apply() }
                                }
                            }
                        } catch (e: Exception) {
                            if (!running.get() || !GnssConnectionOwner.current(lease)) break
                            val reason = if (timeout) "No valid NMEA within 12 seconds — enable GGA/GST output; check the receiver network, IP/port and power" else e.message ?: "Receiver connection lost"
                            log.event("connection_lost", reason)
                            if (ConnectionPolicy.retry(attempt)) {
                                status("reconnecting", "$reason. Reconnect ${attempt + 1}/${ConnectionPolicy.MAX_ATTEMPTS}")
                            } else status("error", "$reason. Automatic attempts exhausted — check setup and reconnect manually")
                        } finally { closeConnection(); connection = null }
                        if (!running.get() || !ConnectionPolicy.retry(attempt)) break
                        Thread.sleep(2000L * attempt)
                    }
                    log.event("stopped", "Acquisition stopped; raw records retained")
                }
            } catch (_: InterruptedException) { /* Explicit stop/switch closes and retains this session. */ }
            catch (e: Exception) { if (running.get()) status("error", "Raw logging failed: ${e.message}; check available storage") }
            finally { running.set(false); closeConnection(); stopSelf() }
        }.also { it.name = "TriDrone-$transport"; it.start() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        running.set(false); closeConnection(); worker?.interrupt(); watchdog.shutdownNow()
        GnssConnectionOwner.withCurrent(lease) {
            clearFix()
            if (prefs.getString("state", "") != "error") prefs.edit().putString("state", "disconnected").putString("detail", "Receiver stopped — reconnect from Equipment").apply()
        }
        GnssConnectionOwner.release(lease)
        super.onDestroy()
    }
}
