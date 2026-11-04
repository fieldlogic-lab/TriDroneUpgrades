package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class UsbSerialSessionTest {
    private class Fake : CeeSerialTransport {
        val events = mutableListOf<String>()
        var failConfigure = false
        var failRead = false
        override fun open() { events.add("open") }
        override fun configure(baud: Int, dataBits: Int, stopBits: Int, parity: Int, flowControl: Boolean) {
            events.add("$baud,$dataBits,$stopBits,$parity,$flowControl")
            if (failConfigure) error("Configuration failure")
        }
        override fun read(bytes: ByteArray, timeoutMillis: Int): Int {
            if (failRead) error("Detached")
            val sentence = "\$SDDBT,0.00,f,0.00,M,0.00,F*36\r\n".toByteArray()
            sentence.copyInto(bytes)
            return sentence.size
        }
        override fun close() { events.add("close") }
    }
    @Test fun connectsWithVerifiedSettingsAndReceivesSerialData() {
        val transport = Fake()
        val session = UsbSerialSession()
        session.connect(transport)
        assertEquals(listOf("open", "9600,8,1,0,false"), transport.events)
        val bytes = ByteArray(100)
        assertTrue(session.read(bytes, 500) > 0)
        session.close()
        session.close()
        assertEquals(1, transport.events.count { it == "close" })
    }
    @Test fun failedConfigurationClosesPort() {
        val transport = Fake().apply { failConfigure = true }
        val session = UsbSerialSession()
        try { session.connect(transport); fail("Expected configuration failure") } catch (_: IllegalStateException) { }
        assertEquals("close", transport.events.last())
    }
    @Test fun unplugReadClosesThenReconnectsWithFreshTransport() {
        val first = Fake().apply { failRead = true }
        val second = Fake()
        val session = UsbSerialSession()
        session.connect(first)
        try { session.read(ByteArray(100), 500); fail("Expected detach") } catch (_: IllegalStateException) { }
        assertEquals("close", first.events.last())
        session.connect(second)
        assertTrue(session.read(ByteArray(100), 500) > 0)
        session.close()
        assertEquals("close", second.events.last())
    }
}
