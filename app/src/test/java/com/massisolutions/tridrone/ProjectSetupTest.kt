package com.massisolutions.tridrone

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProjectSetupTest {
    @Test fun exactFootDefinitionsDifferAndNativeMetersRemainUnchanged() {
        val meters = 1200.0 / 3937.0
        assertEquals(1.0, DistanceUnit.US_SURVEY_FEET.fromMeters(meters), 1e-12)
        assertEquals(1.0, DistanceUnit.INTERNATIONAL_FEET.fromMeters(0.3048), 1e-12)
        assertNotEquals(DistanceUnit.US_SURVEY_FEET.fromMeters(1000.0),
            DistanceUnit.INTERNATIONAL_FEET.fromMeters(1000.0), 0.001)
        assertEquals("1.000 ftUS", DisplayUnits.formatPrecision(meters, "usft"))
        assertEquals("1.000 ft", DisplayUnits.formatPrecision(0.3048, "ift"))
        assertEquals("1.000 ft", DisplayUnits.formatPrecision(0.3048, "ft"))
        assertEquals("0.305 m", DisplayUnits.formatPrecision(0.3048, "m"))
        assertEquals(1200.0 / 3937.0, meters, 0.0)
        assertEquals("UNAVAILABLE", DisplayUnits.formatPrecision(Double.NaN, "usft"))
        assertEquals("UNAVAILABLE", DisplayUnits.formatPrecision(Double.POSITIVE_INFINITY, "usft"))
        assertEquals("UNAVAILABLE", DisplayUnits.formatPrecision(null, "usft"))
        assertEquals("UNAVAILABLE", DisplayUnits.formatPrecision(-1.0, "usft"))
        assertEquals("UNAVAILABLE", DisplayUnits.formatPrecision(1.0, "unknown"))
    }

    @Test fun projectAndNextSessionPersistSeparatelyAndOldPreferencesMigrate() {
        val preferences = SurveySettings(project = " Job A ", units = "ift", sessionName = " Transect 2 ").normalized().preferences()
        val restarted = SurveySettings.fromPreferences(preferences.toMap())
        assertEquals("Job A", restarted.project)
        assertEquals("Transect 2", restarted.sessionName)
        assertEquals("ift", restarted.units)
        val old = SurveySettings.fromPreferences(mapOf("project" to "Historical job", "units" to "m"))
        assertEquals("", old.sessionName)
        assertEquals("m", old.units)
        assertEquals("EPSG:6539", old.horizontal)
        assertEquals("EPSG:6360", old.vertical)
        assertEquals("UNKNOWN", old.source)
        assertEquals("usft", SurveySettings.fromPreferences(emptyMap()).units)
    }

    @Test fun metadataCsvFreezesIdentityCrsUnitsAndEscapesHumanNames() {
        val root = Files.createTempDirectory("tridrone-setup").toFile()
        try {
            var settings = SurveySettings(project = "Harbor, \"North\"", sessionName = "Line, \"2\"", source = "EPSG:6318")
            val originalFix = listOf("123519", "40.8", "-73.05", "4", "12", "0.7", "10.25", "-30", "1", "station")
            val snapshot = SessionMetadata(settings, "unique-id", 123, "GNSS_RAW_DIAGNOSTIC_wifi")
            settings = settings.copy(project = "Different", sessionName = "Next", units = "m", horizontal = "EPSG:4326")
            val file = root.resolve("metadata.csv")
            snapshot.writeCsv(file)
            val lines = file.readLines()
            val exported = Csv.parse(lines[0])!!.zip(Csv.parse(lines[1])!!).toMap()
            assertEquals("Harbor, \"North\"", exported["project"])
            assertEquals("Line, \"2\"", exported["session_name"])
            assertEquals("usft", exported["display_units"])
            assertEquals("1 ftUS = 1200/3937 meters (exact)", exported["display_unit_definition"])
            assertEquals("EPSG:6539", exported["horizontal_crs"])
            assertEquals("EPSG:6360", exported["vertical_crs"])
            assertEquals("m", exported["receiver_native_distance_units"])
            assertEquals("unique-id", exported["session_id"])
            val directory = root.resolve("surveys").apply { mkdirs() }
            val fixes = directory.resolve("wifi_fixes_unique-id.csv")
            fixes.writeText(RtkCoordinateExport.header)
            snapshot.writeCsv(directory.resolve("wifi_unique-id.metadata.csv"))
            val repository = SessionRepository(root)
            assertEquals("Line, \"2\"", repository.displayName(repository.list(SessionRepository.Kind.LIVE).single()))
            directory.resolve("wifi_unique-id.metadata.csv").writeText("broken\nmetadata\n")
            assertEquals(fixes.name, repository.displayName(repository.list(SessionRepository.Kind.LIVE).single()))
            directory.resolve("wifi_unique-id.metadata.csv").delete()
            assertEquals(fixes.name, repository.displayName(repository.list(SessionRepository.Kind.LIVE).single()))
            val raw = Csv.parse(RtkCoordinateExport.row(123, originalFix, settings).trim())!!
            assertEquals("10.25", raw[7])
            assertEquals("", raw[11])
            assertEquals("", raw[12])
            assertEquals("Job".length, SessionIdentity.safeLabel("\nJob\r").length)
        } finally { root.deleteRecursively() }
    }

    @Test fun labelsAreBoundedAndNeverUsedForUniqueInternalIds() {
        assertEquals(120, SessionIdentity.safeLabel("x".repeat(200)).length)
        assertEquals("Job", SessionIdentity.safeLabel("\nJob\r\u0000"))
        val a = SessionIdentity.newId(123)
        val b = SessionIdentity.newId(123)
        assertNotEquals(a, b)
        assertTrue(a.matches(Regex("[0-9a-f_-]+")))
    }
}
