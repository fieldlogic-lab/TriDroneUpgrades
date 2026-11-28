package com.massisolutions.tridrone

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class HydroLiteCaptureTest {
    @Test fun retriesTransportFailuresAndRetainsRawBytesAcrossLinks() {
        val events = mutableListOf<String>()
        val received = StringBuilder()
        val frames = mutableListOf<String>()
        var attempts = 0
        captureHydroLite(
            active = { frames.size < 2 },
            connect = {
                when (++attempts) {
                    1 -> throw IOException("Connect failed")
                    2 -> "first\npartial".byteInputStream()
                    3 -> object : InputStream() {
                        override fun read(): Int = throw EOFException("Connection lost")
                    }
                    else -> "second\n".byteInputStream()
                }
            },
            onConnected = { events += "connected" },
            onDisconnected = { events += checkNotNull(it.message) },
            closeConnection = { events += "closed" },
            waitToRetry = { events += "wait" }
        ) { bytes, count, decoded ->
            received.append(String(bytes, 0, count, Charsets.US_ASCII))
            frames += decoded
        }

        assertEquals("first\npartialsecond\n", received.toString())
        assertEquals(listOf("first", "second"), frames)
        assertEquals(
            listOf(
                "Connect failed", "closed", "wait",
                "connected", "Bluetooth stream ended", "closed", "wait",
                "connected", "Connection lost", "closed", "wait",
                "connected", "closed"
            ),
            events
        )
    }

    @Test fun storageFailureClosesConnectionAndPropagatesWithoutRetry() {
        val failure = IOException("Storage full")
        var closed = false
        try {
            captureHydroLite(
                active = { true },
                connect = { "reading\n".byteInputStream() },
                onConnected = {},
                onDisconnected = { fail("Storage errors are not disconnects") },
                closeConnection = { closed = true },
                waitToRetry = { fail("Storage errors must not retry transport") }
            ) { _, _, _ -> throw failure }
            fail("The storage error did not propagate")
        } catch (e: IOException) {
            assertSame(failure, e)
        }
        assertEquals(true, closed)
    }
}
