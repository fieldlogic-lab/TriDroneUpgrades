package com.massisolutions.tridrone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.atomic.AtomicBoolean

/** A single USB serial owner; no terminal app or phone location is involved. */
class CeeLineUsbService : Service() {
    companion object {
        const val SOURCE = "CEE_LINE_USB"
        const val EXTRA_DEVICE_ID = "device_id"
        private const val PERMISSION = "com.massisolutions.tridrone.CEE_USB_PERMISSION"
        fun devices(context: Context): List<Pair<Int, String>> {
            val manager = context.getSystemService(USB_SERVICE) as UsbManager
            return UsbSerialProber.getDefaultProber().findAllDrivers(manager).map { driver ->
                driver.device.deviceId to "${driver.device.productName ?: "USB serial"} (${driver.device.vendorId}:${driver.device.productId})"
            }
        }
    }
    private val active = AtomicBoolean(false)
    private val policy = UsbConnectionPolicy()
    private val manager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val prefs by lazy { getSharedPreferences("cee_usb_status", MODE_PRIVATE) }
    private val framer = DepthStreamDecoder()
    private val session = UsbSerialSession()
    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var pendingPermission: Int? = null
    @Volatile private var attachedDevice: Int? = null
    private var worker: Thread? = null

    private fun report(state: String, detail: String) {
        prefs.edit().putString("state", state).putString("detail", detail).apply()
    }
    private fun invalidate() {
        HydroLive.sounderConnection(SOURCE, false)
        synchronized(framer) { framer.reset() }
        session.close()
        port = null
        try { connection?.close() } catch (_: Exception) { }
        connection = null
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            when (intent.action) {
                PERMISSION -> {
                    if (pendingPermission != device.deviceId) return
                    pendingPermission = null
                    if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        synchronized(policy) { policy.permissionDenied(device.deviceId) }
                        report("permission_denied", "USB permission denied. Tap Connect to try again.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    synchronized(policy) { policy.detached(device.deviceId) }
                    if (pendingPermission == device.deviceId) pendingPermission = null
                    if (attachedDevice == device.deviceId) {
                        invalidate()
                        report("disconnected", "USB detached; waiting to reconnect")
                    }
                }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel("cee_usb", "CEE-LINE USB", NotificationManager.IMPORTANCE_LOW))
        startForeground(1005, Notification.Builder(this, "cee_usb")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("TriDrone CEE-LINE USB")
            .setContentText("Receiving sounder NMEA; reconnecting when attached")
            .setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        synchronized(policy) {
            policy.select(if (intent?.hasExtra(EXTRA_DEVICE_ID) == true) intent.getIntExtra(EXTRA_DEVICE_ID, -1) else null)
        }
        pendingPermission = null
        if (!active.compareAndSet(false, true)) {
            invalidate()
            return START_NOT_STICKY
        }
        HydroLive.claimSounder(SOURCE)
        worker = Thread({ acquire() }, "cee-line-usb").also { it.start() }
        return START_NOT_STICKY
    }
    private fun acquire() {
        val buffer = ByteArray(1024)
        try {
            while (active.get() && HydroLive.ownsSounder(SOURCE)) {
                try {
                    val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
                    prefs.edit().putString("devices", drivers.joinToString(",") { it.device.deviceId.toString() }).apply()
                    val id = synchronized(policy) { policy.choose(drivers.map { it.device.deviceId }) }
                    val driver = drivers.firstOrNull { it.device.deviceId == id }
                    if (driver == null) {
                        invalidate()
                        report(if (drivers.size > 1) "select_device" else "waiting_device",
                            if (drivers.size > 1) "Multiple USB adapters. Select the CEE-LINE adapter." else "Attach the CEE-LINE USB serial adapter")
                        Thread.sleep(500)
                        continue
                    }
                    val device = driver.device
                    attachedDevice = device.deviceId
                    if (!manager.hasPermission(device)) {
                        val allowed = synchronized(policy) { policy.mayRequestPermission(device.deviceId) }
                        if (pendingPermission == null && allowed) {
                            pendingPermission = device.deviceId
                            report("permission_pending", "Allow TriDrone to access the USB adapter")
                            val permissionIntent = Intent(PERMISSION).setPackage(packageName)
                            val pending = PendingIntent.getBroadcast(this, device.deviceId, permissionIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                            manager.requestPermission(device, pending)
                        }
                        Thread.sleep(300)
                        continue
                    }
                    if (!synchronized(policy) { policy.canAttempt(SystemClock.elapsedRealtime()) }) {
                        Thread.sleep(250)
                        continue
                    }
                    report("connecting", "9600 baud • 8N1 • no flow control")
                    val opened = manager.openDevice(device) ?: error("Cannot open USB adapter")
                    connection = opened
                    val serial = driver.ports.firstOrNull() ?: error("USB adapter has no serial ports")
                    port = serial
                    session.connect(object : CeeSerialTransport {
                        override fun open() = serial.open(opened)
                        override fun configure(baud: Int, dataBits: Int, stopBits: Int, parity: Int, flowControl: Boolean) {
                            serial.setParameters(baud, dataBits, stopBits, parity)
                            check(!flowControl)
                            serial.setFlowControl(UsbSerialPort.FlowControl.NONE)
                        }
                        override fun read(bytes: ByteArray, timeoutMillis: Int) = serial.read(bytes, timeoutMillis)
                        override fun close() = serial.close()
                    })
                    synchronized(framer) { framer.reset() }
                    HydroLive.sounderConnection(SOURCE, true)
                    synchronized(policy) { policy.connected() }
                    report("connected", "CEE-LINE USB • 9600 8N1 • waiting for valid DBT")
                    while (active.get() && HydroLive.ownsSounder(SOURCE) && port === serial) {
                        val count = session.read(buffer, 500)
                        if (count <= 0) continue
                        val utc = System.currentTimeMillis()
                        val nanos = SystemClock.elapsedRealtimeNanos()
                        HydroLive.rawDepth(SOURCE, buffer, count)
                        val frames = synchronized(framer) { framer.accept(buffer, count) }
                        frames.forEach { HydroLive.frame(SOURCE, it, utc, nanos) }
                        prefs.edit().putString("preview", frames.lastOrNull()?.take(160).orEmpty())
                            .putLong("last_bytes_ms", utc).apply()
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    invalidate()
                    if (active.get()) {
                        synchronized(policy) { policy.failed(SystemClock.elapsedRealtime()) }
                        report("reconnecting", e.message ?: "USB connection lost")
                    }
                } finally {
                    invalidate()
                }
            }
        } finally {
            invalidate()
            active.set(false)
            stopSelf()
        }
    }
    override fun onDestroy() {
        active.set(false)
        worker?.interrupt()
        invalidate()
        unregisterReceiver(receiver)
        report("disconnected", "USB connection stopped")
        super.onDestroy()
    }
}
