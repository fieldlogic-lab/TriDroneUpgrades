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
}

/** Bluetooth serial baud is a physical adapter setting; RFCOMM does not configure its UART. */
data class ProfileConnection(val sounderAddress: String = "", val gnssAddress: String = "",
    val gnssTransport: String = "BLUETOOTH", val gnssHost: String = "", val gnssPort: Int = 9001,
    val baud: Int = 9600)

/** Calibration is applied to the acoustic range, never to the waterline draft. */
data class DepthCalibration(val depthReference: String = "ACOUSTIC_CENTER", val scale: Double = 1.0,
    val offsetUsft: Double = 0.0, val confirmed: Boolean = false) {
    val usable get() = confirmed && depthReference in listOf("ACOUSTIC_CENTER", "WATERLINE") &&
        scale.isFinite() && scale > 0 && offsetUsft.isFinite()
}

/** Profile changes are forbidden during a live acquisition, even if callers bypass the UI. */
data class SounderProfile(val id: String, val name: String, val model: String,
    val transport: String, val geometry: VerticalGeometry, val checkedUtcMs: Long = 0L,
    val connection: ProfileConnection = ProfileConnection(),
    val calibration: DepthCalibration = DepthCalibration(), val pairToleranceMillis: Long = 1000) {
    val protocol get() = if (model == "HYDROLITE_PLUS") HydroLiteDecoder.PROTOCOL else "UNSUPPORTED_CEE_LINE"
    fun equipmentSettings() = EquipmentSettings(geometry.gpsToSounderUsft, geometry.reference,
        geometry.usable, pairToleranceMillis)
}

/** Compare each transport's actual configuration without coupling Bluetooth connection order. */
object ProfileBinding {
    private fun sameSetup(a: SounderProfile?, b: SounderProfile?) = when {
        a == null || b == null -> a == b
        else -> a.id == b.id && a.model == b.model && a.transport == b.transport &&
            a.geometry == b.geometry && a.calibration == b.calibration && a.pairToleranceMillis == b.pairToleranceMillis
    }
    fun sounderMatches(selected: SounderProfile?, bound: SounderProfile?) = sameSetup(selected, bound) &&
        selected?.connection?.sounderAddress == bound?.connection?.sounderAddress &&
        selected?.connection?.baud == bound?.connection?.baud
    fun gnssMatches(selected: SounderProfile?, bound: SounderProfile?) = sameSetup(selected, bound) &&
        selected?.connection?.let { listOf(it.gnssAddress, it.gnssTransport, it.gnssHost, it.gnssPort.toString()) } ==
        bound?.connection?.let { listOf(it.gnssAddress, it.gnssTransport, it.gnssHost, it.gnssPort.toString()) }
}

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
            p.getProperty(prefix + "checked_ms")?.toLongOrNull() ?: 0L,
            ProfileConnection(p.getProperty(prefix + "sounder_address", ""),
                p.getProperty(prefix + "gnss_address", ""), p.getProperty(prefix + "gnss_transport", "BLUETOOTH"),
                p.getProperty(prefix + "gnss_host", ""), p.getProperty(prefix + "gnss_port")?.toIntOrNull() ?: 9001,
                p.getProperty(prefix + "baud")?.toIntOrNull() ?: 9600),
            DepthCalibration(p.getProperty(prefix + "depth_reference", "ACOUSTIC_CENTER"),
                p.getProperty(prefix + "depth_scale")?.let { it.toDoubleOrNull() ?: Double.NaN } ?: 1.0,
                p.getProperty(prefix + "depth_offset_usft")?.let { it.toDoubleOrNull() ?: Double.NaN } ?: 0.0,
                p.getProperty(prefix + "calibration_confirmed") == "true"),
            p.getProperty(prefix + "pair_tolerance_ms")?.toLongOrNull() ?: 1000)
    }
    fun active(): SounderProfile? {
        val p = read()
        return profile(p.getProperty("active", ""), p)
    }
    private fun validate(profile: SounderProfile) {
        require(profile.id.matches(Regex("[a-zA-Z0-9_-]{1,48}"))) { "Invalid profile ID" }
        require(profile.name.isNotBlank() && profile.name.length <= 100) { "Invalid name" }
        require(profile.geometry.gpsToSounderUsft == null ||
            (profile.geometry.gpsToSounderUsft.isFinite() && profile.geometry.gpsToSounderUsft >= 0))
        require(profile.geometry.sounderBelowWaterlineUsft == null ||
            (profile.geometry.sounderBelowWaterlineUsft.isFinite() && profile.geometry.sounderBelowWaterlineUsft >= 0))
        require(profile.connection.gnssTransport in listOf("BLUETOOTH", "TCP")) { "Unsupported GNSS transport" }
        require(profile.connection.gnssPort in 1..65535 && profile.connection.baud > 0) { "Invalid port or serial baud" }
        require(profile.model != "CEE_LINE" || profile.connection.baud == 9600) { "CEE-LINE / Parani SD1000 profile requires 9600 baud" }
        require(profile.connection.sounderAddress.isEmpty() || profile.connection.sounderAddress.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) { "Invalid sounder Bluetooth address" }
        require(profile.connection.gnssAddress.isEmpty() || profile.connection.gnssAddress.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) { "Invalid GNSS Bluetooth address" }
        require(profile.calibration.depthReference in listOf("ACOUSTIC_CENTER", "WATERLINE", "UNKNOWN")) { "Unknown depth reference" }
        require(profile.calibration.scale.isFinite() && profile.calibration.scale > 0 && profile.calibration.offsetUsft.isFinite()) { "Invalid depth calibration" }
        require(profile.pairToleranceMillis in 1..5000) { "Pairing tolerance must be 1–5000 ms" }
    }
    fun save(profile: SounderProfile) {
        check(!recording()) { "Stop recording before modifying equipment" }
        validate(profile)
        val p = read()
        writeProfile(p, profile)
        if (p.getProperty("active") == null) p.setProperty("active", profile.id)
        persist(p)
    }
    private fun writeProfile(p: Properties, profile: SounderProfile) {
        val prefix = "profile.${profile.id}."
        p.setProperty(prefix + "name", profile.name)
        p.setProperty(prefix + "model", profile.model)
        p.setProperty(prefix + "transport", profile.transport)
        p.setProperty(prefix + "a_usft", profile.geometry.gpsToSounderUsft?.toString().orEmpty())
        p.setProperty(prefix + "b_usft", profile.geometry.sounderBelowWaterlineUsft?.toString().orEmpty())
        p.setProperty(prefix + "reference", profile.geometry.reference)
        p.setProperty(prefix + "verified", profile.geometry.verified.toString())
        p.setProperty(prefix + "checked_ms", profile.checkedUtcMs.toString())
        p.setProperty(prefix + "sounder_address", profile.connection.sounderAddress)
        p.setProperty(prefix + "gnss_address", profile.connection.gnssAddress)
        p.setProperty(prefix + "gnss_transport", profile.connection.gnssTransport)
        p.setProperty(prefix + "gnss_host", profile.connection.gnssHost)
        p.setProperty(prefix + "gnss_port", profile.connection.gnssPort.toString())
        p.setProperty(prefix + "baud", profile.connection.baud.toString())
        p.setProperty(prefix + "depth_reference", profile.calibration.depthReference)
        p.setProperty(prefix + "depth_scale", profile.calibration.scale.toString())
        p.setProperty(prefix + "depth_offset_usft", profile.calibration.offsetUsft.toString())
        p.setProperty(prefix + "calibration_confirmed", profile.calibration.confirmed.toString())
        p.setProperty(prefix + "pair_tolerance_ms", profile.pairToleranceMillis.toString())
    }
    fun exportProfiles(): String = java.io.StringWriter().also { writer ->
        read().apply { setProperty("schema_version", "2") }.store(writer,
            "TriDrone portable equipment profiles; remeasure and reconfirm after importing")
    }.toString()

    /** Import is additive and atomic. Existing profiles and active selection are never overwritten. */
    fun importProfiles(text: String): List<SounderProfile> {
        check(!recording()) { "Stop recording before importing equipment" }
        require(text.length <= 128_000) { "Profile transfer is too large" }
        val incoming = Properties().apply { load(java.io.StringReader(text)) }
        require(incoming.getProperty("schema_version") == "2") { "Unsupported profile transfer version" }
        val ids = incoming.stringPropertyNames().filter { it.startsWith("profile.") && it.endsWith(".name") }
            .map { it.removePrefix("profile.").removeSuffix(".name") }.sorted()
        require(ids.isNotEmpty() && ids.size <= 100) { "Transfer must contain 1–100 profiles" }
        val p = read()
        val imported = ids.map { sourceId ->
            val source = profile(sourceId, incoming) ?: error("Invalid imported profile")
            validate(source)
            var id = source.id
            var index = 1
            while (p.containsKey("profile.$id.name")) {
                id = source.id.take(36) + "_import" + index++
            }
            // A portable file cannot verify the physical installation on this phone.
            val copy = source.copy(id = id, geometry = source.geometry.copy(verified = false),
                calibration = source.calibration.copy(confirmed = false), checkedUtcMs = 0)
            writeProfile(p, copy)
            copy
        }
        persist(p)
        return imported
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
        val legacy = EquipmentSettings.load(File(directory, "equipment.properties"))
        save(SounderProfile("tridrone_hydrolite", "TriDrone / HydroLite Plus",
            "HYDROLITE_PLUS", "BLUETOOTH_UNVERIFIED",
            // Legacy A is only a proposed measurement; B and the physical installation need confirmation.
            VerticalGeometry(legacy.offsetUsft, null, legacy.antennaReference, false),
            pairToleranceMillis = legacy.pairToleranceMillis.takeIf { it in 1..5000 } ?: 1000))
        save(SounderProfile("pole_cee_line", "Pole / CEE-LINE",
            "CEE_LINE", "PARANI_SD1000_9600_BAUD_NOT_FIELD_VERIFIED",
            VerticalGeometry(null, null)))
    }
}
