package com.wanderwildwood.amime.ble

import java.util.UUID

/**
 * The Nordic UART Service, which is what MeshCore tunnels the companion protocol over.
 *
 * Named from the radio's point of view, which is the opposite of the phone's: the
 * characteristic called RX is the one this app *writes* to, and the one called TX is the one
 * it *receives* notifications on. Renaming them to match the phone would be clearer here and
 * wrong against every other implementation, so the firmware's names are kept.
 */
object NordicUart {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Written by this app, read by the radio. */
    val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Notified by the radio, received by this app. */
    val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Client Characteristic Configuration. Enabling notifications means writing to this. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * The MTU to ask for.
     *
     * A frame is one characteristic value — the firmware calls `setValue` then `notify` and
     * never chunks — so the negotiated MTU has to hold the largest frame whole. The default
     * ATT MTU of 23 leaves 20 bytes of payload, which would cut a contact frame at 148 bytes
     * down to a seventh of itself. The radio asks for 176; 247 is the usual Android ceiling
     * and costs nothing to request.
     *
     * A truncated frame is not silent corruption: it arrives short and decodes to
     * `Frame.Malformed`, which is worth watching for if this is ever not honoured.
     */
    const val DESIRED_MTU = 247

    /**
     * The PIN the firmware ships with.
     *
     * Only a fallback for telling the user what to type: the radio reports its actual PIN in
     * the reply to the device query, and it can be changed with `CMD_SET_DEVICE_PIN`.
     */
    const val DEFAULT_PIN = 123456
}
