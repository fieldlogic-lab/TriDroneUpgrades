package com.massisolutions.tridrone

import java.io.File

/** PNEZD is withheld when a verified vertical solution is absent; CSV retains every event. */
object HydroPnezd {
    fun export(input: File, output: File): Int {
        val rows = parse(input.readText())
        if (rows.isEmpty()) { output.writeText(""); return 0 }
        val header = rows.first()
        fun value(row: List<String>, name: String) = row.getOrNull(header.indexOf(name)).orEmpty()
        var count = 0
        output.bufferedWriter().use { writer -> rows.drop(1).forEach { row ->
            val east = value(row, "easting_usft").toDoubleOrNull()
            val north = value(row, "northing_usft").toDoubleOrNull()
            val z = value(row, "bottom_navd88_usft").toDoubleOrNull()
            if (east != null && east.isFinite() && north != null && north.isFinite() && z != null && z.isFinite() &&
                value(row, "vertical_status") == "VERIFIED" && value(row, "depth_validity") == "VALID_PROVISIONAL" &&
                value(row, "pairing_status") == "PAIRED_RECEIPT_TIME" && value(row, "gnss_quality") in listOf("4", "5")) {
                count++
                writer.write("$count,$north,$east,$z,Sounding RTK ${value(row, "gnss_quality")}\n")
            }
        } }
        return count
    }
    /** RFC4180 quoted cells, including newlines, for both legacy and extended sessions. */
    internal fun parse(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < csv.length) {
            val c = csv[i]
            when {
                c == '"' && quoted && csv.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { row.add(cell.toString()); cell.setLength(0) }
                c == '\n' && !quoted -> { row.add(cell.toString().trimEnd('\r')); rows.add(row.toList()); row.clear(); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row.toList()) }
        return rows
    }
}
