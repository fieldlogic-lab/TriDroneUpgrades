package com.massisolutions.tridrone

/** Survey-point export requires a verified elevation; the ordinary CSV retains every row. */
internal class HydroPnezd(columns: List<String>) {
    private val index = columns.withIndex().associate { it.value to it.index }
    var count = 0
        private set

    fun point(row: List<String>): String? {
        fun cell(name: String) = index[name]?.let(row::getOrNull).orEmpty()
        val easting = cell("easting_usft").toDoubleOrNull()
        val northing = cell("northing_usft").toDoubleOrNull()
        val elevation = cell("bottom_navd88_usft").toDoubleOrNull()
        val quality = cell("gnss_quality")
        if (easting == null || !easting.isFinite() || northing == null || !northing.isFinite() ||
            elevation == null || !elevation.isFinite() || cell("vertical_status") != "VERIFIED" ||
            cell("depth_validity") != "VALID_PROVISIONAL" || cell("pairing_status") != "PAIRED_RECEIPT_TIME" ||
            quality !in setOf("4", "5")) return null
        count++
        return "$count,$northing,$easting,$elevation,Sounding RTK $quality"
    }
}
