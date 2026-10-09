package com.massisolutions.tridrone

/**
 * CSV projection output is explicitly provisional. Source coordinates must be confirmed
 * NAD83(2011) and independently checked before accepting a survey deliverable.
 * WGS84-to-NAD83 transformation and NAVD88 conversion are NOT implemented.
 */
object RtkCoordinateExport {
    const val header = "received_utc_ms,utc_time,latitude_deg,longitude_deg,fix_quality,satellites,hdop,altitude_m,geoid_separation_m,correction_age_s,station_id,easting_usft,northing_usft,projection_status\n"

    fun row(received: Long, fix: List<String>, config: SurveySettings): String {
        require(fix.size == 10)
        val latitude = fix[1].toDoubleOrNull()
        val longitude = fix[2].toDoubleOrNull()
        val projected = if (config.horizontal == "EPSG:6539" &&
            config.source == "EPSG:6318" && fix[3] == "4" &&
            latitude != null && longitude != null)
            StatePlane6539.projectNad83_2011(latitude, longitude)
        else null
        val east = projected?.first?.toString() ?: ""
        val north = projected?.second?.toString() ?: ""
        val status = when {
            config.horizontal != "EPSG:6539" -> "NOT_APPLICABLE_CRS"
            config.source != "EPSG:6318" -> "SOURCE_DATUM_UNVERIFIED"
            fix[3] != "4" -> "RTK_NOT_FIXED"
            projected == null -> "OUTSIDE_AREA_OR_INVALID"
            else -> "PROVISIONAL_CONTROL_CHECK_REQUIRED"
        }
        return "$received,${fix.joinToString(",")},$east,$north,$status\n"
    }
}
