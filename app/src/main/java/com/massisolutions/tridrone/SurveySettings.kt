package com.massisolutions.tridrone

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/** Persisted survey configuration. The output CRS is not an assertion about input datum. */
data class SurveySettings(
    val project: String = "",
    val horizontal: String = "EPSG:6539",
    val vertical: String = "EPSG:6360",
    val units: String = "usft",
    val source: String = "UNKNOWN",
    val favorites: Set<String> = setOf("EPSG:6539", "EPSG:4326"),
    val sessionName: String = ""
) {
    fun normalized(): SurveySettings = copy(project = SessionIdentity.safeLabel(project),
        sessionName = SessionIdentity.safeLabel(sessionName), favorites = favorites.toSet())

    fun preferences(): Map<String, String> = mapOf("project" to project, "horizontal" to horizontal,
        "vertical" to vertical, "units" to units, "source" to source,
        "favorites" to favorites.sorted().joinToString(","), "session_name" to sessionName)

    fun metadata(): Map<String, String> {
        val unit = DistanceUnit.fromCode(units)
        return linkedMapOf("project" to project, "session_name" to sessionName,
            "horizontal_crs" to horizontal, "vertical_crs" to vertical,
            "display_units" to units, "display_unit_definition" to (unit?.definition ?: "UNSUPPORTED"),
            "meters_per_display_unit" to (unit?.metersPerUnit?.toString() ?: "UNAVAILABLE"),
            "receiver_native_distance_units" to "m", "receiver_precision_confidence" to "GST 1 sigma",
            "source_geodetic_crs" to source,
            "horizontal_transformation_status" to "requires verified source datum and control check",
            "vertical_transformation_status" to "not implemented: NAVD88 elevations null")
    }
    fun json() = JSONObject().apply { metadata().forEach { (key, value) -> put(key, value) } }

    companion object {
        fun fromPreferences(values: Map<String, String>): SurveySettings = SurveySettings(
            project = values["project"] ?: "", horizontal = values["horizontal"] ?: "EPSG:6539",
            vertical = values["vertical"] ?: "EPSG:6360", units = values["units"] ?: "usft",
            source = values["source"] ?: "UNKNOWN",
            favorites = (values["favorites"] ?: "EPSG:6539,EPSG:4326").split(',').filter { it.isNotBlank() }.toSet(),
            sessionName = values["session_name"] ?: "").normalized()
    }
}
object SurveyConfig {
    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("survey_settings", Context.MODE_PRIVATE)
    fun load(c: Context): SurveySettings {
        val p = prefs(c)
        return SurveySettings.fromPreferences(p.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap())
    }
    fun save(c: Context, s: SurveySettings) {
        val editor = prefs(c).edit()
        s.normalized().preferences().forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }
    /** Snapshot settings per session so later edits cannot rewrite historical metadata. */
    fun writeSnapshot(c: Context, basename: String, settings: SurveySettings = load(c)) {
        val file = File(File(c.filesDir, "surveys").apply { mkdirs() }, "$basename.metadata.json")
        file.writeText(settings.json().put("captured_utc_ms", System.currentTimeMillis()).toString(2))
    }

    /** Companion CSV lets field exports retain job/session identity without changing raw schemas. */
    fun writeSessionMetadata(directory: File, basename: String, settings: SurveySettings,
        sessionId: String, capturedUtc: Long, acquisition: String) {
        require(basename.matches(Regex("[A-Za-z0-9_-]+"))) { "Unsafe metadata filename" }
        directory.mkdirs()
        val snapshot = SessionMetadata(settings, sessionId, capturedUtc, acquisition)
        File(directory, "$basename.metadata.json").writeText(JSONObject().apply {
            snapshot.values.forEach { (key, value) -> put(key, value) }
            put("captured_utc_ms", capturedUtc)
        }.toString(2))
        snapshot.writeCsv(File(directory, "$basename.metadata.csv"))
    }
}

/** Immutable metadata shared by JSON and CSV export; independent of later preference edits. */
class SessionMetadata(settings: SurveySettings, sessionId: String, capturedUtc: Long, acquisition: String) {
    val values: Map<String, String> = linkedMapOf("session_id" to sessionId,
        "captured_utc_ms" to capturedUtc.toString(), "acquisition_kind" to acquisition,
        "collection_status" to "UNAVAILABLE_UNVERIFIED_HARDWARE").apply {
        putAll(settings.normalized().metadata())
    }.toMap()
    fun writeCsv(file: File) { file.writeText(Csv.row(values.keys.toList()) + Csv.row(values.values.toList())) }
}
