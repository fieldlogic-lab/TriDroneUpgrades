package com.massisolutions.tridrone

import java.io.IOException
import java.io.InputStream

/** The byte sink is outside the transport retry boundary so storage errors remain visible. */
internal fun captureHydroLite(
    active: () -> Boolean,
    connect: () -> InputStream,
    onConnected: () -> Unit,
    onDisconnected: (IOException) -> Unit,
    closeConnection: () -> Unit,
    waitToRetry: () -> Unit,
    onBytes: (ByteArray, Int, List<String>) -> Unit
) {
    val buffer = ByteArray(1024)
    while (active()) {
        try {
            val input = try {
                connect()
            } catch (e: IOException) {
                if (active()) onDisconnected(e)
                null
            }
            if (input != null && active()) {
                onConnected()
                // Partial frames on a failed link must never mix with bytes from the next link.
                val decoder = DepthStreamDecoder()
                while (active()) {
                    val count = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        if (active()) onDisconnected(e)
                        break
                    }
                    if (count < 0) {
                        if (active()) onDisconnected(IOException("Bluetooth stream ended"))
                        break
                    }
                    if (count > 0 && active()) onBytes(buffer, count, decoder.accept(buffer, count))
                }
            }
        } finally {
            closeConnection()
        }
        if (active()) waitToRetry()
    }
}
