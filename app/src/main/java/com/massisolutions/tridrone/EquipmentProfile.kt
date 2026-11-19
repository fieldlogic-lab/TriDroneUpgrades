package com.massisolutions.tridrone

import android.content.Context
import java.io.File

/** Keep the operational filenames and selected-sounder preference for in-place upgrades. */
enum class EquipmentProfile(
    val source: String,
    val protocol: String,
    val defaultName: String,
    val sounder: String,
    private val geometryFile: String,
    private val receiverPreferences: String
) {
    TRIDRONE("HYDROLITE", HydroLiteDecoder.PROTOCOL, "TriDrone + HydroLite", "HydroLite Plus",
        "equipment.properties", "wifi_rtk_settings"),
    BOAT(CeeLineUsbService.SOURCE, CeeLineDbt.PROTOCOL, "Boat + CEE-LINE", "CEE-LINE USB",
        "cee-line-equipment.properties", "boat_gnss_settings");

    fun file(context: Context) = File(context.filesDir, geometryFile)

    fun settings(context: Context): EquipmentSettings = EquipmentSettings.load(file(context),
        if (this == TRIDRONE) EquipmentSettings(offsetUsft = 6.75) else EquipmentSettings())

    fun name(settings: EquipmentSettings) = settings.name ?: defaultName

    @Synchronized fun receiver(context: Context): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences(receiverPreferences, Context.MODE_PRIVATE)
        if (this == BOAT && !prefs.getBoolean("copied_shared_wifi_settings", false)) {
            val sharedWifi = TRIDRONE.receiver(context)
            prefs.edit().apply {
                if (!prefs.contains("host")) putString("host", sharedWifi.getString("host", null))
                if (!prefs.contains("port") && sharedWifi.contains("port")) putInt("port", sharedWifi.getInt("port", 0))
                putBoolean("copied_shared_wifi_settings", true)
            }.apply()
        }
        return prefs
    }

    companion object {
        private fun selection(context: Context) = context.getSharedPreferences("sounder_profile", Context.MODE_PRIVATE)

        fun active(context: Context): EquipmentProfile =
            if (selection(context).getString("profile", "") == BOAT.source) BOAT else TRIDRONE

        fun forProtocol(protocol: String): EquipmentProfile = if (protocol == BOAT.protocol) BOAT else TRIDRONE

        fun select(context: Context, profile: EquipmentProfile) {
            selection(context).edit().putString("profile", profile.source).apply()
        }
    }
}
