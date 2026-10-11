package com.massisolutions.tridrone

/** Bounded framing of unknown ASCII sounder data. No depth values are inferred. */
class DepthStreamDecoder(private val maxFrameBytes: Int = 512) {
    init { require(maxFrameBytes in 16..8192) }
    private val buffer = StringBuilder()
    private var dropping = false

    fun accept(bytes: ByteArray, size: Int = bytes.size): List<String> {
        require(size in 0..bytes.size)
        val frames = mutableListOf<String>()
        for (i in 0 until size) {
            val c = bytes[i].toInt() and 255
            if (c == 10 || c == 13) {
                if (!dropping && buffer.isNotEmpty()) frames.add(buffer.toString())
                buffer.setLength(0)
                dropping = false
            } else if (!dropping) {
                if (buffer.length >= maxFrameBytes || c !in 32..126) {
                    buffer.setLength(0)
                    dropping = true
                } else buffer.append(c.toChar())
            }
        }
        return frames
    }

    fun reset() {
        buffer.setLength(0)
        dropping = false
    }
}

interface VerifiedDepthProtocol {
    val protocolId: String
    fun decode(frame: String, receivedUtcMillis: Long): DepthObservation?
}

object UnverifiedHydroLiteProtocol : VerifiedDepthProtocol {
    override val protocolId = "HYDROLITE_UNVERIFIED"
    override fun decode(frame: String, receivedUtcMillis: Long): DepthObservation? = null
}
