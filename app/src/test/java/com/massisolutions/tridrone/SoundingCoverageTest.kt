package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class SoundingCoverageTest {
    private val header = HydroCsv.header.trimEnd()

    private fun row(quality: String, validity: String = "VALID_PROVISIONAL",
                    pairing: String = "PAIRED_RECEIPT_TIME", lat: String = "40.75",
                    lon: String = "-73.11", depth: String = "5.12"): String =
        HydroCsv.columns.joinToString(",") {
            when (it) {
                "latitude_deg" -> lat
                "longitude_deg" -> lon
                "gnss_quality" -> quality
                "depth_validity" -> validity
                "pairing_status" -> pairing
                "depth_usft" -> depth
                "raw_depth" -> "\"raw,fragment\""
                else -> ""
            }
        }

    @Test fun fixedAndFloatAreBothVisibleAndDifferentiated() {
        val fixed = SoundingCoverage.read(header, row("4"))!!
        val floating = SoundingCoverage.read(header, row("5"))!!
        assertTrue(fixed.fix)
        assertTrue(floating.float)
        assertTrue(fixed.usableDepth)
        assertTrue(floating.usableDepth)
    }

    @Test fun unpairedOrInvalidDepthNeverCountsAsCoverage() {
        assertFalse(SoundingCoverage.read(header, row("4", pairing = "MISSING_OR_STALE_GNSS"))!!.usableDepth)
        assertFalse(SoundingCoverage.read(header, row("5", validity = "INVALID"))!!.usableDepth)
        assertFalse(SoundingCoverage.read(header, row("4", depth = ""))!!.usableDepth)
    }

    @Test fun missingAndInvalidCoordinatesAreNeverFabricated() {
        assertNull(SoundingCoverage.read(header, row("4", lat = "")))
        assertNull(SoundingCoverage.read(header, row("4", lon = "NaN")))
        assertNull(SoundingCoverage.read(header, row("0")))
    }

    @Test fun handlesEscapedQuotesAndRejectsIncompleteRow() {
        assertEquals(listOf("a", "b,\"c\""), SoundingCoverage.cells("a,\"b,\"\"c\"\"\""))
        assertNull(SoundingCoverage.cells("\"unterminated"))
    }
}
