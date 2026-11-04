package com.massisolutions.tridrone

/** Injectable transport boundary for serial setup, read errors, and connection cleanup. */
interface CeeSerialTransport {
    fun open()
    fun configure(baud: Int, dataBits: Int, stopBits: Int, parity: Int, flowControl: Boolean)
    fun read(bytes: ByteArray, timeoutMillis: Int): Int
    fun close()
}

class UsbSerialSession {
    @Volatile private var transport: CeeSerialTransport? = null
    @Synchronized fun connect(next: CeeSerialTransport) {
        close()
        transport = next
        try {
            next.open()
            next.configure(9600, 8, 1, 0, false)
        } catch (e: Exception) {
            close()
            throw e
        }
    }
    fun read(bytes: ByteArray, timeoutMillis: Int): Int {
        val current = transport ?: error("USB serial disconnected")
        return try { current.read(bytes, timeoutMillis) }
        catch (e: Exception) { close(); throw e }
    }
    @Synchronized fun close() {
        val previous = transport
        transport = null
        try { previous?.close() } catch (_: Exception) { }
    }
}
