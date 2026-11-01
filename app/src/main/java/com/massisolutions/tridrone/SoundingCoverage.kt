package com.massisolutions.tridrone

/**
 * Read-only interpretation of the existing HydroCsv export for drawing coverage.
 * Never changes the writer, session meaning, or archived bytes.
 */
object SoundingCoverage {
    data class Point(
        val latitude: Double,
        val longitude: Double,
        val gnssQuality: Int,
        val depthUsft: Double?,
        val depthValidity: String,
        val pairingStatus: String
    ) {
        val paired: Boolean get() = pairingStatus == "PAIRED_RECEIPT_TIME"
        val usableDepth: Boolean get() = paired && depthValidity == "VALID_PROVISIONAL" &&
            depthUsft != null && depthUsft.isFinite() && depthUsft > 0
        val fix: Boolean get() = gnssQuality == 4
        val float: Boolean get() = gnssQuality == 5
    }

    /** RFC4180-style quoted cells, including escaped quotes. Returns null on incomplete input. */
    fun cells(line: String): List<String>? {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    result.add(current.toString()); current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        if (quoted) return null
        result.add(current.toString().trimEnd('\r'))
        return result
    }

    fun read(header: String, line: String): Point? {
        val columns = cells(header) ?: return null
        val values = cells(line) ?: return null
        if (columns.size != values.size) return null
        fun field(name: String): String? =
            columns.indexOf(name).takeIf { it >= 0 }?.let { values[it] }
        val lat = field("latitude_deg")?.toDoubleOrNull() ?: return null
        val lon = field("longitude_deg")?.toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val quality = field("gnss_quality")?.toIntOrNull() ?: return null
        if (quality !in 1..5) return null
        return Point(lat, lon, quality, field("depth_usft")?.toDoubleOrNull(),
            field("depth_validity").orEmpty(), field("pairing_status").orEmpty())
    }
}
