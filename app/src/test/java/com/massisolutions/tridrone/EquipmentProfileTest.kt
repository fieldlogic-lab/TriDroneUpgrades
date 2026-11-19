package com.massisolutions.tridrone

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EquipmentProfileTest {
    @Test fun defaultsAndExistingSettingsRemainSeparate() {
        val context = RuntimeEnvironment.getApplication()
        val hydro = EquipmentProfile.TRIDRONE
        val boat = EquipmentProfile.BOAT
        val initial = hydro.settings(context)
        assertEquals(81.0 / 12.0, initial.offsetUsft!!, 0.0)
        assertEquals("UNSET", initial.antennaReference)
        assertNull(initial.sounderReference)
        assertFalse(initial.confirmed)
        assertNull(boat.settings(context).offsetUsft)

        writeLegacySettings(context)
        assertEquals(5.1, hydro.settings(context).offsetUsft!!, 0.0)
        assertTrue(hydro.settings(context).confirmed)
        assertEquals("TriDrone + HydroLite", hydro.name(hydro.settings(context)))
        assertEquals("legacy-receiver", hydro.receiver(context).getString("host", null))
        assertEquals("legacy-receiver", boat.receiver(context).getString("host", null))
        assertEquals(9001, boat.receiver(context).getInt("port", 0))

        val boatSetup = EquipmentSettings(3.1, "RECEIVER_OUTPUT_REFERENCE", false, 950,
            0.75, "Survey skiff", "Transducer face")
        boatSetup.save(boat.file(context))
        boat.receiver(context).edit().putString("host", "boat-receiver").apply()
        assertTrue(HydroLive.selectProfile(context, boat))
        assertEquals(boat, EquipmentProfile.active(context))
        assertEquals(boatSetup, EquipmentProfile.active(context).settings(context))
        assertEquals("boat-receiver", boat.receiver(context).getString("host", null))
        assertEquals("legacy-receiver", hydro.receiver(context).getString("host", null))
        assertEquals(5.1, hydro.settings(context).offsetUsft!!, 0.0)
    }

    private fun writeLegacySettings(context: Context) {
        EquipmentProfile.TRIDRONE.file(context).writeText(
            "offset_usft=5.1\nantenna_reference=ANTENNA_REFERENCE_POINT\nconfirmed=true\n")
        context.getSharedPreferences("wifi_rtk_settings", Context.MODE_PRIVATE)
            .edit().putString("host", "legacy-receiver").putInt("port", 9001).apply()
    }
}
