package com.massisolutions.tridrone

import org.junit.Assert.*
import org.junit.Test

class UsbConnectionPolicyTest {
    @Test fun automaticallyChoosesOnlyOneAdapter() {
        val p = UsbConnectionPolicy()
        assertNull(p.choose(emptyList()))
        assertEquals(7, p.choose(listOf(7)))
        assertNull(p.choose(listOf(7, 8)))
        p.select(8)
        assertEquals(8, p.choose(listOf(7, 8)))
        assertNull(p.choose(listOf(7)))
    }
    @Test fun permissionDenialDoesNotLoopAndExplicitConnectRetries() {
        val p = UsbConnectionPolicy()
        p.permissionDenied(7)
        assertFalse(p.mayRequestPermission(7))
        assertTrue(p.mayRequestPermission(8))
        p.select(null)
        assertTrue(p.mayRequestPermission(7))
    }
    @Test fun detachPermitsFreshPermissionAndClearsBackoff() {
        val p = UsbConnectionPolicy()
        p.select(7)
        p.permissionDenied(7)
        p.failed(100)
        p.detached(7)
        assertTrue(p.mayRequestPermission(7))
        assertTrue(p.canAttempt(100))
        assertEquals(9, p.choose(listOf(9))) // reattachment can allocate a new Android device ID
    }
    @Test fun failuresUseBoundedBackoffAndSuccessResetsIt() {
        val p = UsbConnectionPolicy()
        p.failed(100)
        assertFalse(p.canAttempt(1099))
        assertTrue(p.canAttempt(1100))
        repeat(20) { p.failed(100) }
        assertFalse(p.canAttempt(15099))
        assertTrue(p.canAttempt(15100))
        p.connected()
        assertTrue(p.canAttempt(100))
    }
    @Test fun reconnectMustDiscardPartialOldSentence() {
        val decoder = DepthStreamDecoder()
        assertTrue(decoder.accept("\$SDDBT,4.2".toByteArray()).isEmpty())
        decoder.reset()
        assertEquals(listOf("\$SDDBT,new*00"), decoder.accept("\$SDDBT,new*00\r\n".toByteArray()))
    }
}
