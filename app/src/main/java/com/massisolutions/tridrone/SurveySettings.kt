package com.massisolutions.tridrone

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Persisted survey configuration. The output CRS is not an assertion about input datum. */
data class SurveySettings(
    val project: String = "",
    val horizontal: String = "EPSG:6539",
    val vertical: String = "EPSG:6360",
    val units: String = "usft",
    val source: String = "UNKNOWN",
    val favorites: Set<String> = setOf("EPSG:6539", "EPSG:4326")
) {
    fun json() = JSONObject().apply {
        put("project", project); put("horizontal_crs", horizontal); put("vertical_crs", vertical)
        put("display_units", units); put("source_geodetic_crs", source)
        put("horizontal_transformation_status", "requires verified source datum and control check")
        put("vertical_transformation_status", "not implemented: NAVD88 elevations null")
    }
}
object SurveyConfig {
    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("survey_settings", Context.MODE_PRIVATE)
    fun load(c: Context): SurveySettings {
        val p = prefs(c)
        return SurveySettings(
            p.getString("project", "") ?: "",
            p.getString("horizontal", "EPSG:6539") ?: "EPSG:6539",
            p.getString("vertical", "EPSG:6360") ?: "EPSG:6360",
            p.getString("units", "usft") ?: "usft",
            p.getString("source", "UNKNOWN") ?: "UNKNOWN",
            (p.getString("favorites", "EPSG:6539,EPSG:4326") ?: "").split(",").filter { it.isNotBlank() }.toSet()
        )
    }
    fun save(c: Context, s: SurveySettings) {
        prefs(c).edit().putString("project", s.project).putString("horizontal", s.horizontal)
            .putString("vertical", s.vertical).putString("units", s.units)
            .putString("source", s.source).putString("favorites", s.favorites.sorted().joinToString(",")).apply()
    }
    /** Snapshot settings per session so later edits cannot rewrite historical metadata. */
    fun writeSnapshot(c: Context, basename: String) {
        val file = File(File(c.filesDir, "surveys").apply { mkdirs() }, "$basename.metadata.json")
        file.writeText(load(c).json().put("captured_utc_ms", System.currentTimeMillis()).toString(2))
    }
}
