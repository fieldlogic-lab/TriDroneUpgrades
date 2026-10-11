package com.massisolutions.tridrone

/**
 * Pure parser adapter for GGA messages from the Emlid NMEA stream.
 * Does not connect to hardware or assert that a synchronized survey fix exists.
 */
object NmeaGnssAdapter {
    fun parseGga(sentence: String, receivedUtcMillis: Long): GnssObservation? {
        if (!NmeaGga.valid(sentence)) return null
        val fields = NmeaGga.parse(sentence) ?: return null
        val latitude = fields.getOrNull(1)?.toDoubleOrNull() ?: return null
        val longitude = fields.getOrNull(2)?.toDoubleOrNull() ?: return null
        val quality = fields.getOrNull(3)?.toIntOrNull() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            quality !in 0..8) return null
        val altitude = fields.getOrNull(6)?.toDoubleOrNull()
        if (altitude != null && !altitude.isFinite()) return null
        return GnssObservation(
            utcMillis = receivedUtcMillis,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitude,
            fixQuality = quality,
            origin = DataOrigin.LIVE_HARDWARE
        )
    }
}
