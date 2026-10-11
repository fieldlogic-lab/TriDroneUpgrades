package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SessionRepositoryTest {
    @Test fun demoAndLiveSessionsNeverMix() {
        val root = Files.createTempDirectory("tridrone-test").toFile()
        try {
            val demo = root.resolve("demo_surveys").apply { mkdirs() }
            val live = root.resolve("surveys").apply { mkdirs() }
            demo.resolve("DEMO_1.csv").writeText(
                "event_utc_ms,latitude_deg,longitude_deg,simulated_depth_m,source,usage\n" +
                "123,40.8,-73.05,2.500,DEMO_SYNTHETIC,NOT_FOR_SURVEY\n"
            )
            live.resolve("gps_1.csv").writeText(
                "utc_epoch_ms,elapsed_realtime_ns,latitude_deg,longitude_deg,horizontal_accuracy_m,altitude_m,vertical_accuracy_m,speed_mps,bearing_deg,provider\n" +
                "123,456,40.8,-73.05,1.0,,,,,gps\n"
            )
            live.resolve("rtk_nmea_1.csv").writeText("received_utc_ms,nmea_sentence\\n")
            live.resolve("rtk_fixes_1.csv").writeText("different,schema\\n")
            val repo = SessionRepository(root)
            assertEquals(1, repo.list(SessionRepository.Kind.DEMO).size)
            assertEquals(1, repo.list(SessionRepository.Kind.LIVE).size)
            assertEquals(2.5, repo.read(repo.list(SessionRepository.Kind.DEMO).single()).single().depthMeters!!, 0.0001)
            assertNull(repo.read(repo.list(SessionRepository.Kind.LIVE).single()).single().depthMeters)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectIncorrectDemoSourceAndSchema() {
        val root = Files.createTempDirectory("tridrone-test").toFile()
        try {
            val demo = root.resolve("demo_surveys").apply { mkdirs() }
            val file = demo.resolve("DEMO_1.csv")
            file.writeText("event_utc_ms,latitude_deg,longitude_deg,simulated_depth_m,source,usage\n" +
                "123,40.8,-73.05,2.5,LIVE,NOT_FOR_SURVEY\n")
            val repo = SessionRepository(root)
            assertTrue(repo.read(repo.list(SessionRepository.Kind.DEMO).single()).isEmpty())
            file.writeText("unexpected,columns\n123,40.8,-73.05\n")
            assertThrows(IllegalArgumentException::class.java) {
                repo.read(repo.list(SessionRepository.Kind.DEMO).single())
            }
        } finally { root.deleteRecursively() }
    }
}
