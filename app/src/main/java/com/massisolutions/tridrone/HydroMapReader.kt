package com.massisolutions.tridrone

import java.io.File

/** Bounded-memory streaming map preview. Call on a worker; audit records remain complete. */
object HydroMapReader {
    fun read(file: File): List<Pair<Double, Double>> = try {
        val points = ArrayList<Pair<Double, Double>>()
        file.bufferedReader().use { reader ->
            val header = reader.readLine()?.split(',').orEmpty()
            val latIndex = header.indexOf("latitude_deg")
            val lonIndex = header.indexOf("longitude_deg")
            val validity = header.indexOf("depth_validity")
            val pairing = header.indexOf("pairing_status")
            reader.forEachLine { line ->
                // Generated sounder/GNSS raw strings are single ASCII frames; quoted commas are preserved.
                val row = HydroPnezd.parse(line).firstOrNull().orEmpty()
                val lat = row.getOrNull(latIndex)?.toDoubleOrNull()
                val lon = row.getOrNull(lonIndex)?.toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 &&
                    row.getOrNull(validity) == "VALID_PROVISIONAL" && row.getOrNull(pairing) == "PAIRED_RECEIPT_TIME") {
                    if (points.size >= 2000) {
                        val reduced = points.filterIndexed { index, _ -> index % 2 == 0 }
                        points.clear(); points.addAll(reduced)
                    }
                    points.add(Pair(lat, lon))
                }
            }
        }
        points
    } catch (_: Exception) { emptyList() }
}
