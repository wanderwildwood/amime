package com.wanderwildwood.amime.meshtastic

import com.wanderwildwood.amime.link.RadioKind
import java.util.UUID

/**
 * The Bluetooth service Meshtastic firmware offers, from its client API documentation and
 * the firmware's NimbleBluetooth.cpp.
 */
object MtUuids {
    val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

    /** Written by this app: one ToRadio message per write. */
    val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")

    /** Read by this app: one FromRadio message per read, and an empty read means drained. */
    val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")

    /** Notified: a counter that says something is waiting to be read. */
    val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

    /** The size to ask for. The firmware asks for 517 and the documentation recommends 512. */
    const val DESIRED_MTU = 512
}

/** Telling the two kinds of radio apart from what they advertise. */
object RadioKinds {
    private val NORDIC_UART: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /**
     * The kind from an advertisement, or null when it is neither.
     *
     * The service is the evidence. Nordic UART is a generic service other gadgets use too,
     * so a MeshCore name is a hint that never makes a radio out of something without the
     * service; a name is only used when there is no advertisement to look at, which is the
     * case for a radio that is already paired and listed without a scan.
     */
    fun detect(services: Collection<UUID>?, name: String?): RadioKind? = when {
        services != null && MtUuids.SERVICE in services -> RadioKind.MESHTASTIC
        services != null && NORDIC_UART in services -> RadioKind.MESHCORE
        services != null && services.isNotEmpty() -> null
        else -> byName(name)
    }

    /** `MeshCore-…`, or `Meshtastic_…` / `<short name>_abcd` for Meshtastic. */
    fun byName(name: String?): RadioKind? = when {
        name == null -> null
        name.startsWith("MeshCore-") -> RadioKind.MESHCORE
        name.startsWith("Meshtastic") || Regex("^.*_[0-9a-fA-F]{4}$").matches(name) -> RadioKind.MESHTASTIC
        else -> null
    }
}
