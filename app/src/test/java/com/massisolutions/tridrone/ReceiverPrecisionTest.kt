package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class ReceiverPrecisionTest {
    private fun sentence(body: String): String = "$" + body + "*" +
        body.fold(0) { sum, c -> sum xor c.code }.toString(16).padStart(2, '0')
    private fun ready() = OperatorReadiness.State("connected", "4", 100, "connected",
        horizontalMeters = 0.02, verticalMeters = 0.03, gstAgeMs = 100, correctionAgeSeconds = 1.0)

    @Test fun greenRequiresFreshFixedPositionAndBothMatchingReceiverSigmas() {
        assertEquals(OperatorReadiness.Lamp.GREEN, OperatorReadiness.evaluate(ready()).gnssLamp)
        for (state in listOf(ready().copy(gstAgeMs = 5001), ready().copy(fixAgeMs = 5001),
            ready().copy(receiverState = "disconnected"), ready().copy(precisionEpochAligned = false))) {
            val display = OperatorReadiness.evaluate(state)
            assertNotEquals(OperatorReadiness.Lamp.GREEN, display.gnssLamp)
            assertNull(display.horizontalMeters)
            assertNull(display.verticalMeters)
        }
        for (state in listOf(ready().copy(fixQuality = "5"), ready().copy(verticalMeters = null),
            ready().copy(horizontalMeters = Double.NaN), ready().copy(correctionAgeSeconds = 11.0))) {
            assertNotEquals(OperatorReadiness.Lamp.GREEN, OperatorReadiness.evaluate(state).gnssLamp)
        }
        assertFalse(OperatorReadiness.evaluate(ready()).canRecord)
        assertNotEquals(OperatorReadiness.Lamp.GREEN, OperatorReadiness.evaluate(ready()).depthLamp)
    }

    @Test fun gstChecksChecksumTimeAndFiniteCompleteSigmas() {
        val good = sentence("GNGST,123519,1.0,1.0,1.0,0.0,0.03,0.04,0.06")
        assertEquals(0.05, NmeaGst.parse(good)!!.first, 0.000001)
        assertEquals(0.06, NmeaGst.parse(good)!!.second, 0.000001)
        assertNull(NmeaGst.parse(good.dropLast(2) + "ZZ"))
        assertNull(NmeaGst.parse(sentence("GNGST,256100,1,1,1,0,0.03,0.04,0.06")))
        assertNull(NmeaGst.parse(sentence("GNGST,123519,1,1,1,0,0.03,0.04,")))
        assertNull(NmeaGst.parse(sentence("GNGST,123519,1,1,1,0,NaN,0.04,0.06")))
    }

    @Test fun ggaRejectsInvalidHemisphereMinutesEpochAndCorrectionAge() {
        val body = "GPGGA,123519,4048.0000,N,07303.0000,W,4,12,0.7,10.2,M,-30.0,M,1.0,"
        assertNotNull(NmeaGga.parse(sentence(body)))
        for (bad in listOf(body.replace(",N,", ",Q,"), body.replace("4048.0000", "4061.0000"),
            body.replace("123519", "123599"), body.replace(",1.0,", ",-1.0,"))) {
            assertNull(NmeaGga.parse(sentence(bad)))
        }
    }

    @Test fun assertedDatumNeverPopulatesUnverifiedProjectedCoordinates() {
        val fields = listOf("123519", "40.8", "-73.05", "4", "12", "0.7", "10", "-30", "1", "station,quoted")
        val row = Csv.parse(RtkCoordinateExport.row(123, fields, SurveySettings(source = "EPSG:6318")).trim())!!
        assertEquals(14, row.size)
        assertEquals("station,quoted", row[10])
        assertEquals("", row[11])
        assertEquals("", row[12])
        assertEquals("TRANSFORMATION_AND_CONTROL_UNVERIFIED", row[13])
    }
}
