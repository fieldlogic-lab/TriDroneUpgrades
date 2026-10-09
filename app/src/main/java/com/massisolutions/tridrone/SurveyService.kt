package com.massisolutions.tridrone

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class SurveyService : Service(), LocationListener {
    private val channel = "tridrone_gps"
    private lateinit var locations: LocationManager
    private var writer: BufferedWriter? = null
    private val csvHeader = "utc_epoch_ms,elapsed_realtime_ns,latitude_deg,longitude_deg,horizontal_accuracy_m,altitude_m,vertical_accuracy_m,speed_mps,bearing_deg,provider\n"

    private val prefs by lazy { getSharedPreferences("logger_status", MODE_PRIVATE) }
    private var pointCount = 0
    private fun state(value: String, error: String = "") {
        prefs.edit().putString("state", value).putString("error", error).apply()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel(channel, "Survey recording", NotificationManager.IMPORTANCE_LOW))
        val notice = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TriDrone recording GNSS")
            .setContentText("Saving location data locally")
            .setOngoing(true)
            .build()
        startForeground(1001, notice)
        pointCount = 0
        prefs.edit().putInt("points", 0).putLong("started_at_ms", System.currentTimeMillis()).remove("last_fix_ms").remove("lat").remove("lon").apply()
        state("waiting")
        val folder = File(filesDir, "surveys").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC).format(Instant.now())
        writer = BufferedWriter(FileWriter(File(folder, "gps_$stamp.csv"), true))
        writer?.write(csvHeader)
        writer?.flush()
        locations = getSystemService(LOCATION_SERVICE) as LocationManager
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            } else { state("error", "GPS disabled"); stopSelf() }
        } else { state("error", "Location permission missing"); stopSelf() }
    }

    override fun onLocationChanged(location: Location) {
        val safeProvider = (location.provider ?: "").replace(",", "_")
        val row = listOf(
            location.time.toString(),
            location.elapsedRealtimeNanos.toString(),
            location.latitude.toString(),
            location.longitude.toString(),
            if (location.hasAccuracy()) location.accuracy.toString() else "",
            if (location.hasAltitude()) location.altitude.toString() else "",
            if (Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toString() else "",
            if (location.hasSpeed()) location.speed.toString() else "",
            if (location.hasBearing()) location.bearing.toString() else "",
            safeProvider
        ).joinToString(",") + "\n"
        try {
            writer?.write(row)
            writer?.flush()
            pointCount++
            prefs.edit().putInt("points", pointCount).putString("lat", location.latitude.toString())
                .putString("lon", location.longitude.toString())
                .putFloat("accuracy", if (location.hasAccuracy()) location.accuracy else -1f)
                .putLong("last_fix_ms", System.currentTimeMillis())
                .putString("state", "recording").apply()
        } catch (e: Exception) {
            state("error", e.message ?: "Write failed")
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (::locations.isInitialized) locations.removeUpdates(this)
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        if (prefs.getString("state", "") != "error") state("idle")
        prefs.edit().putLong("stopped_at_ms", System.currentTimeMillis()).apply()
        super.onDestroy()
    }
}
