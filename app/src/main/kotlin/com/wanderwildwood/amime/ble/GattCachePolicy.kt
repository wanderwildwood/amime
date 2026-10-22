package com.wanderwildwood.amime.ble

/**
 * When a radio's service list on the phone may be stale. Android keeps the attribute table of a
 * bonded device; a radio re-flashed with other firmware under the same address answers with
 * "invalid handle" (ATT status 1) to the old handles, or lacks the service the cache promised.
 * The cure is to drop the cache and connect again, once; a second failure is the user's to fix
 * (forget the radio in Bluetooth settings).
 */
object GattCachePolicy {
    /** ATT error 1, ATTERR_INVALID_HANDLE. */
    const val INVALID_HANDLE = 1

    enum class Verdict { RETRY, GIVE_UP_WITH_HINT, GIVE_UP }

    /**
     * [status] is the GATT status of the failed step, or null when the service or its
     * characteristics were missing after discovery. [retriesUsed] counts refresh-and-reconnect
     * attempts already made on this connection.
     */
    fun decide(status: Int?, retriesUsed: Int): Verdict {
        val stale = status == null || status == INVALID_HANDLE
        return when {
            !stale -> Verdict.GIVE_UP
            retriesUsed < 1 -> Verdict.RETRY
            else -> Verdict.GIVE_UP_WITH_HINT
        }
    }
}
