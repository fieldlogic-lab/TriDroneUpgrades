package com.massisolutions.tridrone

/**
 * Hardware-neutral contracts. Implementations must label their provenance;
 * demo providers must never be accepted as live survey observations.
 */
enum class DataOrigin { LIVE_HARDWARE, DEMO_SYNTHETIC }

data class GnssObservation(
    val utcMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val fixQuality: Int?,
    val origin: DataOrigin
)

data class DepthObservation(
    val utcMillis: Long,
    val depthMeters: Double,
    val origin: DataOrigin
)

interface GnssProvider {
    val origin: DataOrigin
    fun connect()
    fun disconnect()
    fun setListener(listener: ((GnssObservation) -> Unit)?)
}

interface DepthProvider {
    val origin: DataOrigin
    fun connect()
    fun disconnect()
    fun setListener(listener: ((DepthObservation) -> Unit)?)
}

/**
 * Gate for future synchronized capture. Does not assert that hardware
 * protocols, antenna offsets, or vertical datum are verified.
 */
object ObservationGate {
    fun sameSource(gnss: GnssObservation, depth: DepthObservation): Boolean =
        gnss.origin == depth.origin

    fun validForLiveSurvey(gnss: GnssObservation, depth: DepthObservation): Boolean =
        sameSource(gnss, depth) &&
            gnss.origin == DataOrigin.LIVE_HARDWARE &&
            gnss.fixQuality == 4 &&
            gnss.latitude in -90.0..90.0 &&
            gnss.longitude in -180.0..180.0 &&
            depth.depthMeters.isFinite() && depth.depthMeters >= 0.0
}
