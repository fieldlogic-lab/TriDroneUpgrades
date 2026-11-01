package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HydroSurveyMapTest {
    private fun gnss(time: Long = 1000, quality: Int = 4, lat: Double = 40.8, lon: Double = -73.05) =
        HydroGnss("raw,\"GGA\"", time, time * 1_000_000, lat, lon, quality, 10.0, 0.01, 0.02, "120000")
    private fun depth(time: Long = 1000) = HydroLiteDecoder.decode("1 0.93 0 0 0 11 100 0", time, time * 1_000_000)
    private fun row(time: Long = 1000, quality: Int = 4, lat: Double = 40.8, lon: Double = -73.05) =
        HydroCsv.row(depth(time), gnss(time, quality, lat, lon), EquipmentSettings(), "UNKNOWN")
    private fun withSurvey(action: (File) -> Unit) {
        val folder = Files.createTempDirectory("map-read-only").toFile()
        try { action(File(folder, "hydro_1000.csv").apply { writeText(HydroCsv.header) }) }
        finally { folder.deleteRecursively() }
    }
    private fun sentence(body: String) = "$" + body + "*" + body.fold(0) { sum, ch -> sum xor ch.code }.toString(16).padStart(2, '0')

    @Test fun activeHydrographicPairingAcceptsFixAndFloatWithoutChangingLegacyGate() {
        val state = HydroGnssState(); state.connection(true)
        for (quality in listOf(4, 5)) {
            state.accept(gnss(quality = quality))
            val paired = state.paired(depth(), 1000)
            assertNotNull("Active HydroLive.start/frame pairing must accept quality $quality", paired)
            assertEquals(quality, paired!!.quality)
            val values = HydroCsv.columns.zip(parseCsvRecord(HydroCsv.row(depth(), paired, EquipmentSettings(), "UNKNOWN").trimEnd())!!).toMap()
            assertEquals(quality.toString(), values["gnss_quality"])
            assertEquals("0.93", values["raw_depth_m"])
            assertEquals("", values["bottom_navd88_usft"])
        }
        state.connection(false); assertNull(state.paired(depth(), 1000))
        state.connection(true); assertNull(state.paired(depth(), 1000))
        state.accept(gnss(quality = 5)); assertNotNull(state.paired(depth(), 1000))
    }

    @Test fun soundingsUseRecordedCoordinatesUnitsQualityAndOriginalMetadata() = withSurvey { file ->
        file.appendText(row() + row(2000, 5, 40.81, -73.06))
        val before = file.readBytes()
        val data = HydroSurveyMapReader(file).refresh()
        assertEquals(listOf(1000L, 2000L), data.track.map { it.utcMillis })
        val sounding = data.soundings[1]
        assertEquals(40.81, sounding.position.latitude, 0.0)
        assertEquals(-73.06, sounding.position.longitude, 0.0)
        assertEquals("RTK FLOAT", sounding.qualityLabel)
        assertEquals("RTK FIX", data.soundings[0].qualityLabel)
        assertTrue(sounding.details().contains("0.930 m • 3.051 ftUS"))
        assertTrue(sounding.details().contains("not a bottom elevation"))
        assertTrue(sounding.details().contains("0.010 m / 0.020 m"))
        assertEquals("raw,\"GGA\"", sounding.fields["raw_gnss"])
        assertArrayEquals("Map reads never rewrite CSV", before, file.readBytes())
    }

    @Test fun invalidCoordinatesAndMissingDepthNeverCreateSoundings() = withSurvey { file ->
        file.appendText(row(lat = Double.NaN) + row(2000, lat = 91.0) +
            HydroCsv.row(depth().copy(meters = null, validity = "INVALID_MALFORMED"), gnss(), EquipmentSettings(), "UNKNOWN") +
            HydroCsv.row(depth(), null, EquipmentSettings(), "UNKNOWN"))
        val data = HydroSurveyMapReader(file).refresh()
        assertTrue(data.soundings.isEmpty())
        assertEquals(4, data.omittedEvents)
        assertEquals(1, data.track.size) // valid paired GNSS without depth remains a track vertex.
    }

    @Test fun incrementalPartialWritesDisconnectAndReopenRetainEarlierRecords() = withSurvey { file ->
        file.appendText(row())
        val reader = HydroSurveyMapReader(file)
        assertEquals(1, reader.refresh().soundings.size)
        val next = row(2000, 5, 40.82)
        file.appendText(next.take(next.length / 2))
        assertEquals(1, reader.refresh().soundings.size)
        file.appendText(next.drop(next.length / 2))
        val data = reader.refresh()
        assertEquals(2, data.soundings.size)
        assertSame("No writes/disconnect need no extra projection allocation", data, reader.refresh())
        assertEquals(data, HydroSurveyMapReader(file).refresh())
    }

    @Test fun rawRecordedGnssDrivesCompleteChronologicalTrackIncludingUnpairedPositions() = withSurvey { file ->
        file.appendText(row())
        val raw = File(file.parentFile, "hydro_1000.gnss.csv")
        fun line(time: Long, latitude: String, quality: Int = 5) = "$time,${time * 1_000_000}," + HydroCsv.cell(
            sentence("GPGGA,120000,$latitude,N,07303.0000,W,$quality,12,0.7,10.2,M,-30.0,M,,")) + "\n"
        raw.writeText("received_utc_ms,elapsed_realtime_ns,raw_sentence\n" + line(3000, "4049.0000") +
            line(1000, "4048.0000") + line(2000, "4048.0000") + line(4000, "9948.0000") + line(5000, "4050.0000", 0))
        val data = HydroSurveyMapReader(file).refresh()
        assertEquals(listOf(1000L, 3000L), data.track.map { it.utcMillis })
        assertEquals(1, data.soundings.size) // never interpolate synthetic soundings between GNSS epochs.
    }

    @Test fun partialRawGnssLogDoesNotHideRecordedSoundingExtent() = withSurvey { file ->
        file.appendText(row() + row(2000, 5, 41.0, -74.0))
        val raw = File(file.parentFile, "hydro_1000.gnss.csv")
        val gga = sentence("GPGGA,120000,4048.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,,")
        raw.writeText("received_utc_ms,elapsed_realtime_ns,raw_sentence\n1000,1000000000,${HydroCsv.cell(gga)}\n")
        val data = HydroSurveyMapReader(file).refresh()
        assertEquals(2, data.track.size)
        assertTrue(data.track.any { it.latitude == 41.0 && it.longitude == -74.0 })
        val view = SurveyViewport(); view.receive(data.track, null)
        assertEquals(40.9, view.latitude, 1e-8)
    }

    @Test fun quotedMultilineRawObservationsAndIncompleteCsvAreSafe() = withSurvey { file ->
        file.appendText(HydroCsv.row(depth().copy(raw = "line1,\"quoted\"\nline2"), gnss(), EquipmentSettings(), "UNKNOWN"))
        val sounding = HydroSurveyMapReader(file).refresh().soundings.single()
        assertEquals("line1,\"quoted\"\nline2", sounding.fields["raw_depth"])
        assertNull(parseCsvRecord("a,\"incomplete"))
    }

    @Test fun simulated1001000And10000RecordsHaveNoDataLossAndStableIdleSnapshots() {
        for (count in listOf(100, 1000, 10000)) withSurvey { file ->
            file.bufferedWriter().use { out -> out.write(HydroCsv.header)
                repeat(count) { i -> out.write(row(1000L + i, if (i % 2 == 0) 4 else 5, 40.8 + i * 0.000001)) }
            }
            val start = System.nanoTime()
            val reader = HydroSurveyMapReader(file)
            val data = reader.refresh()
            assertEquals(count, data.soundings.size)
            assertEquals(count, data.track.size)
            repeat(10) { assertSame(data, reader.refresh()) }
            println("MAP_READER soundings=$count elapsed_ms=${(System.nanoTime() - start) / 1_000_000}")
        }
    }
}
