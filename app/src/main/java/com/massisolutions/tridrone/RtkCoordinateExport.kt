package com.massisolutions.tridrone

/**
 * Historical projection columns are preserved but empty until a validated transformation
 * and independent control checks exist. Operator CRS settings cannot authorize output.
 * WGS84-to-NAD83 transformation and NAVD88 conversion are NOT implemented.
 */
object RtkCoordinateExport {
    const val header = "received_utc_ms,utc_time,latitude_deg,longitude_deg,fix_quality,satellites,hdop,altitude_m,geoid_separation_m,correction_age_s,station_id,easting_usft,northing_usft,projection_status\n"

    fun row(received: Long, fix: List<String>, config: SurveySettings): String {
        require(fix.size == 10)
        val latitude = fix[1].toDoubleOrNull()
        val longitude = fix[2].toDoubleOrNull()
        // An operator datum setting is not evidence of a validated transformation/control.
        // Preserve the historical schema, but fail closed until an independently validated
        // transformation pipeline is installed. NAVD88 bottom elevations remain absent.
        val east = ""
        val north = ""
        val status = when {
            config.horizontal != "EPSG:6539" -> "NOT_APPLICABLE_CRS"
            config.source != "EPSG:6318" -> "SOURCE_DATUM_UNVERIFIED"
            fix[3] != "4" -> "RTK_NOT_FIXED"
            latitude == null || longitude == null -> "INVALID_POSITION"
            else -> "TRANSFORMATION_AND_CONTROL_UNVERIFIED"
        }
        return Csv.row(listOf(received.toString()) + fix + listOf(east, north, status))
    }
}
