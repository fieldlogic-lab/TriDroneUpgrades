package com.massisolutions.tridrone

import kotlin.math.*

/**
 * EPSG:6539 Lambert Conformal Conic (2SP), GRS80, US survey feet.
 * This is a projection ONLY, not a WGS84 -> NAD83(2011) datum transformation.
 * Never call without verified NAD83(2011) input and independent control check.
 */
object StatePlane6539 {
    private const val a = 6378137.0
    private const val invF = 298.257222101
    private const val feetPerMeter = 3937.0 / 1200.0
    private val e = sqrt(2 / invF - 1 / (invF * invF))
    private fun rad(d: Double) = Math.toRadians(d)
    private fun m(p: Double) = cos(p) / sqrt(1 - e * e * sin(p).pow(2))
    private fun t(p: Double): Double {
        val s = e * sin(p)
        return tan(Math.PI / 4 - p / 2) / ((1 - s) / (1 + s)).pow(e / 2)
    }
    private val p1 = rad(41.0333333333333)
    private val p2 = rad(40.6666666666667)
    private val p0 = rad(40.1666666666667)
    private val lon0 = rad(-74.0)
    private val n = ln(m(p1) / m(p2)) / ln(t(p1) / t(p2))
    private val f = m(p1) / (n * t(p1).pow(n))
    private val rho0 = a * f * t(p0).pow(n)
    fun projectNad83_2011(latitude: Double, longitude: Double): Pair<Double, Double>? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in 40.47..41.3 || longitude !in -74.26..-71.8) return null
        val rho = a * f * t(rad(latitude)).pow(n)
        val theta = n * (rad(longitude) - lon0)
        return Pair(984250.0 + rho * sin(theta) * feetPerMeter,
            (rho0 - rho * cos(theta)) * feetPerMeter)
    }
}
