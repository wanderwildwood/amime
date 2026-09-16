package com.wanderwildwood.amime.protocol

/**
 * Little-endian reads and writes over a frame.
 *
 * Every multi-byte number in the companion protocol is little-endian. The one exception in the
 * wider MeshCore world is CayenneLPP telemetry, which is big-endian, and which nothing here
 * touches yet — if that changes, it does not get to borrow these.
 */

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.i8(at: Int): Int = this[at].toInt()

internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

/** Returned as a [Long] because a `uint32` does not fit an [Int] and the top bit is used. */
internal fun ByteArray.u32(at: Int): Long =
    (u8(at).toLong()) or
        (u8(at + 1).toLong() shl 8) or
        (u8(at + 2).toLong() shl 16) or
        (u8(at + 3).toLong() shl 24)

internal fun ByteArray.i32(at: Int): Int =
    u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)

/**
 * A fixed-width string field, read up to the first NUL.
 *
 * The firmware pads these with NULs but does not always terminate them: a name that fills all
 * 32 bytes has no NUL at all, so reading to the end of the field is the correct answer rather
 * than an off-by-one. Anything past the first NUL is padding and is dropped.
 */
internal fun ByteArray.strz(at: Int, width: Int): String {
    val end = (at until minOf(at + width, size)).firstOrNull { this[it] == 0.toByte() }
        ?: minOf(at + width, size)
    return String(this, at, end - at, Charsets.UTF_8)
}

/** The rest of the frame as text. Used for message bodies, which have no length prefix. */
internal fun ByteArray.tail(at: Int): String =
    if (at >= size) "" else String(this, at, size - at, Charsets.UTF_8)

internal class FrameWriter(initial: Int = 32) {
    private var buf = ByteArray(initial)
    private var len = 0

    private fun room(extra: Int) {
        if (len + extra <= buf.size) return
        var target = buf.size * 2
        while (target < len + extra) target *= 2
        buf = buf.copyOf(target)
    }

    fun u8(value: Int) = apply {
        room(1)
        buf[len++] = (value and 0xFF).toByte()
    }

    fun u32(value: Long) = apply {
        room(4)
        for (shift in 0..24 step 8) buf[len++] = ((value shr shift) and 0xFF).toByte()
    }

    fun i32(value: Int) = apply {
        room(4)
        for (shift in 0..24 step 8) buf[len++] = ((value shr shift) and 0xFF).toByte()
    }

    fun bytes(value: ByteArray) = apply {
        room(value.size)
        value.copyInto(buf, len)
        len += value.size
    }

    /** Zero padding, for the reserved stretches the firmware reads past but does not use. */
    fun zeros(count: Int) = apply {
        room(count)
        len += count
    }

    fun text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))

    fun build(): ByteArray = buf.copyOf(len)
}
