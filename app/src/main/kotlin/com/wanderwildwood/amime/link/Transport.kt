package com.wanderwildwood.amime.link

/**
 * Something that carries whole frames to and from a radio.
 *
 * Deliberately not Bluetooth-shaped. The companion protocol is the same bytes over BLE, USB
 * serial and TCP, and the only part that differs is the framing underneath — which is the
 * transport's problem, not the session's.
 */
interface Transport {
    /** Hand one whole frame to the radio. */
    fun send(frame: ByteArray)
}

/** A transport that collects frames instead of sending them. For tests. */
class RecordingTransport : Transport {
    val sent = mutableListOf<ByteArray>()
    override fun send(frame: ByteArray) {
        sent += frame
    }

    fun opcodes(): List<Int> = sent.map { it[0].toInt() and 0xFF }
    fun clear() = sent.clear()
}
