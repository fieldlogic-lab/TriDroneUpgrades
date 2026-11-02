package com.massisolutions.tridrone

import java.io.File
import java.util.Properties

/**
 * Installation-specific sensor geometry. No inferred NAVD88 or tidal datum conversion.
 * A: downward from declared GNSS height reference to acoustic center.
 * B: downward from water surface to acoustic center (positive when submerged).
 */
data class VerticalGeometry(
    val gpsToSounderUsft: Double?,
    val sounderBelowWaterlineUsft: Double?,
    val reference: String = "UNSET",
    val verified: Boolean = false
) {
    val usable get() = gpsToSounderUsft != null && gpsToSounderUsft.isFinite() &&
        gpsToSounderUsft >= 0.0 && sounderBelowWaterlineUsft != null &&
        sounderBelowWaterlineUsft.isFinite() && sounderBelowWaterlineUsft >= 0.0 &&
        reference in EquipmentSettings.REFERENCES && verified
    fun gpsAboveWaterlineUsft(): Double? =
        if (usable) gpsToSounderUsft!! - sounderBelowWaterlineUsft!! else null
    fun bottomBelowWaterlineUsft(rawDepthMeters: Double?): Double? =
        if (usable && rawDepthMeters != null && rawDepthMeters.isFinite() && rawDepthMeters > 0)
            sounderBelowWaterlineUsft!! + SurveyUnits.feet(rawDepthMeters) else null
    fun bottomElevationUsft(verifiedReferenceHeightUsft: Double?, rawDepthMeters: Double?): Double? =
        if (usable && verifiedReferenceHeightUsft != null && verifiedReferenceHeightUsft.isFinite() &&
            rawDepthMeters != null && rawDepthMeters.isFinite() && rawDepthMeters > 0)
            verifiedReferenceHeightUsft - gpsToSounderUsft!! - SurveyUnits.feet(rawDepthMeters) else null
}

/** Profile changes are forbidden during a live acquisition, even if callers bypass the UI. */
data class SounderProfile(val id: String, val name: String, val model: String,
    val transport: String, val geometry: VerticalGeometry, val checkedUtcMs: Long = 0L)

class EquipmentProfiles(private val directory: File, private val recording: () -> Boolean) {
    private val file = File(directory, "equipment_profiles.properties")
    private fun read(): Properties = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }
    private fun persist(p: Properties) {
        directory.mkdirs()
        val tmp = File(directory, "equipment_profiles.properties.tmp")
        tmp.outputStream().use { p.store(it, "Installation-specific profiles; ftUS; do not edit while recording") }
        check(tmp.renameTo(file)) { "Could not save profiles" }
    }
    fun all(): List<SounderProfile> {
        val p = read()
        return p.stringPropertyNames().filter { it.startsWith("profile.") && it.endsWith(".name") }
            .mapNotNull { key -> profile(key.removePrefix("profile.").removeSuffix(".name"), p) }
            .sortedBy { it.name }
    }
    private fun profile(id: String, p: Properties): SounderProfile? {
        if (!id.matches(Regex("[a-zA-Z0-9_-]{1,48}"))) return null
        val prefix = "profile.$id."
        val name = p.getProperty(prefix + "name") ?: return null
        return SounderProfile(id, name, p.getProperty(prefix + "model", "UNKNOWN"),
            p.getProperty(prefix + "transport", "UNVERIFIED"),
            VerticalGeometry(p.getProperty(prefix + "a_usft")?.toDoubleOrNull(),
                p.getProperty(prefix + "b_usft")?.toDoubleOrNull(),
                p.getProperty(prefix + "reference", "UNSET"),
                p.getProperty(prefix + "verified") == "true"),
            p.getProperty(prefix + "checked_ms")?.toLongOrNull() ?: 0L)
    }
    fun active(): SounderProfile? {
        val p = read()
        return profile(p.getProperty("active", ""), p)
    }
    fun save(profile: SounderProfile) {
        check(!recording()) { "Stop recording before modifying equipment" }
        require(profile.id.matches(Regex("[a-zA-Z0-9_-]{1,48}"))) { "Invalid profile ID" }
        require(profile.name.isNotBlank() && profile.name.length <= 100) { "Invalid name" }
        require(profile.geometry.gpsToSounderUsft == null ||
            (profile.geometry.gpsToSounderUsft.isFinite() && profile.geometry.gpsToSounderUsft >= 0))
        require(profile.geometry.sounderBelowWaterlineUsft == null ||
            (profile.geometry.sounderBelowWaterlineUsft.isFinite() && profile.geometry.sounderBelowWaterlineUsft >= 0))
        val p = read()
        val prefix = "profile.${profile.id}."
        p.setProperty(prefix + "name", profile.name)
        p.setProperty(prefix + "model", profile.model)
        p.setProperty(prefix + "transport", profile.transport)
        p.setProperty(prefix + "a_usft", profile.geometry.gpsToSounderUsft?.toString().orEmpty())
        p.setProperty(prefix + "b_usft", profile.geometry.sounderBelowWaterlineUsft?.toString().orEmpty())
        p.setProperty(prefix + "reference", profile.geometry.reference)
        p.setProperty(prefix + "verified", profile.geometry.verified.toString())
        p.setProperty(prefix + "checked_ms", profile.checkedUtcMs.toString())
        if (p.getProperty("active") == null) p.setProperty("active", profile.id)
        persist(p)
    }
    fun select(id: String) {
        check(!recording()) { "Stop recording before switching equipment" }
        val p = read()
        require(profile(id, p) != null) { "Unknown equipment profile" }
        p.setProperty("active", id)
        persist(p)
    }
    fun seedDefaults() {
        if (all().isNotEmpty()) return
        save(SounderProfile("tridrone_hydrolite", "TriDrone / HydroLite Plus",
            "HYDROLITE_PLUS", "BLUETOOTH_UNVERIFIED",
            VerticalGeometry(null, null)))
        save(SounderProfile("pole_cee_line", "Pole / CEE-LINE",
            "CEE_LINE", "PARANI_SD1000_9600_BAUD_NOT_FIELD_VERIFIED",
            VerticalGeometry(null, null)))
    }
}
