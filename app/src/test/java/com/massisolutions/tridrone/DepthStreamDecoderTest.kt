package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class DepthStreamDecoderTest {
    @Test fun fragmentedMessagesAreReassembled() {
        val decoder = DepthStreamDecoder()
        assertTrue(decoder.accept("ABC".toByteArray()).isEmpty())
        assertEquals(listOf("ABC123"), decoder.accept("123\r\n".toByteArray()))
    }

    @Test fun oversizedAndBinaryFramesAreRejected() {
        val decoder = DepthStreamDecoder(16)
        assertTrue(decoder.accept("12345678901234567\n".toByteArray()).isEmpty())
        assertTrue(decoder.accept(byteArrayOf(65, 0, 66, 10)).isEmpty())
        assertEquals(listOf("OK"), decoder.accept("OK\n".toByteArray()))
    }

    @Test fun unknownProtocolNeverProducesSurveyDepth() {
        assertNull(UnverifiedHydroLiteProtocol.decode("2.50", 123L))
        assertEquals("HYDROLITE_UNVERIFIED", UnverifiedHydroLiteProtocol.protocolId)
    }
}
