package com.massisolutions.tridrone

import java.io.File

/**
 * Single authority for session discovery and CSV parsing.
 * LIVE and DEMO use different directories and incompatible schemas by design.
 * No caller should construct its own session path.
 */
class SessionRepository(private val root: File) {
    enum class Kind(val directory: String) { LIVE("surveys"), DEMO("demo_surveys") }
    data class Sample(val latitude: Double, val longitude: Double, val depthMeters: Double?)
    data class Session(val file: File, val kind: Kind, val observations: Int)

    fun list(kind: Kind): List<Session> =
        File(root, kind.directory).listFiles { f -> f.isFile && f.extension.equals("csv", true) &&\n                (kind != Kind.LIVE || f.name.startsWith("gps_")) }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            ?.map { Session(it, kind, countRows(it)) } ?: emptyList()

    fun latest(kind: Kind): File? = list(kind).firstOrNull()?.file

    fun read(session: Session): List<Sample> {
        require(session.file.parentFile?.canonicalFile == File(root, session.kind.directory).canonicalFile) {
            "Session outside selected storage"
        }
        return session.file.useLines { lines ->
            val rows = lines.iterator()
            if (!rows.hasNext()) return@useLines emptyList()
            val header = rows.next().trim()
            val expected = when (session.kind) {
                Kind.DEMO -> "event_utc_ms,latitude_deg,longitude_deg,simulated_depth_m,source,usage"
                Kind.LIVE -> "utc_epoch_ms,elapsed_realtime_ns,latitude_deg,longitude_deg,horizontal_accuracy_m,altitude_m,vertical_accuracy_m,speed_mps,bearing_deg,provider"
            }
            require(header == expected) { "Unexpected session CSV schema" }
            val output = mutableListOf<Sample>()
            while (rows.hasNext()) {
                val row = rows.next()
                if (row.isBlank()) continue
                val c = row.split(',')
                val lat = c.getOrNull(if (session.kind == Kind.DEMO) 1 else 2)?.toDoubleOrNull()
                val lon = c.getOrNull(if (session.kind == Kind.DEMO) 2 else 3)?.toDoubleOrNull()
                val depth = if (session.kind == Kind.DEMO) c.getOrNull(3)?.toDoubleOrNull() else null
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                if (session.kind == Kind.DEMO &&
                    (depth == null || c.getOrNull(4) != "DEMO_SYNTHETIC" || c.getOrNull(5) != "NOT_FOR_SURVEY")) continue
                output.add(Sample(lat, lon, depth))
            }
            output
        }
    }

    private fun countRows(file: File): Int = try {
        file.useLines { it.drop(1).count { row -> row.isNotBlank() } }
    } catch (_: Exception) { 0 }
}
