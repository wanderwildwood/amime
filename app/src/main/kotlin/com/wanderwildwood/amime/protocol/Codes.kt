package com.wanderwildwood.amime.protocol

/**
 * The companion protocol's numbers, transcribed from the firmware rather than from the
 * documentation.
 *
 * `docs/companion_protocol.md` in the MeshCore tree is a partial account and in places a
 * misleading one — it presents the channel-message opcode as though it were the direct-message
 * one, which would make this app send every private message to a group. These constants come
 * from `examples/companion_radio/MyMesh.cpp`, which is what the radio actually runs.
 *
 * The reference checkout is at /opt/projects/amime-reference.
 */
object Cmd {
    const val APP_START = 1
    const val SEND_TXT_MSG = 2
    const val SEND_CHANNEL_TXT_MSG = 3
    const val GET_CONTACTS = 4
    const val GET_DEVICE_TIME = 5
    const val SET_DEVICE_TIME = 6
    const val SEND_SELF_ADVERT = 7
    const val SET_ADVERT_NAME = 8
    const val ADD_UPDATE_CONTACT = 9
    const val SYNC_NEXT_MESSAGE = 10
    const val SET_RADIO_PARAMS = 11
    const val SET_RADIO_TX_POWER = 12
    const val RESET_PATH = 13
    const val SET_ADVERT_LATLON = 14
    const val REMOVE_CONTACT = 15
    const val SHARE_CONTACT = 16
    const val EXPORT_CONTACT = 17
    const val IMPORT_CONTACT = 18
    const val REBOOT = 19
    const val GET_BATT_AND_STORAGE = 20
    const val DEVICE_QUERY = 22
    const val GET_CONTACT_BY_KEY = 30
    const val GET_CHANNEL = 31
    const val SET_CHANNEL = 32
    const val SEND_TRACE_PATH = 36
    const val SET_DEVICE_PIN = 37
}

/** Replies. A frame that opens with one of these answers the command just sent. */
object Resp {
    const val OK = 0
    const val ERR = 1
    const val CONTACTS_START = 2
    const val CONTACT = 3
    const val END_OF_CONTACTS = 4
    const val SELF_INFO = 5
    const val SENT = 6
    const val CONTACT_MSG_RECV = 7
    const val CHANNEL_MSG_RECV = 8
    const val CURR_TIME = 9
    const val NO_MORE_MESSAGES = 10
    const val BATT_AND_STORAGE = 12
    const val DEVICE_INFO = 13
    const val DISABLED = 15
    const val CONTACT_MSG_RECV_V3 = 16
    const val CHANNEL_MSG_RECV_V3 = 17
    const val CHANNEL_INFO = 18
}

/**
 * Unprompted frames. Every push code has the high bit set, which is the only thing that
 * separates a push from a reply: they arrive on the same characteristic, interleaved with
 * whatever was asked for, so a reader that assumes the next frame answers its last command
 * will eventually mis-attribute one.
 */
object Push {
    const val ADVERT = 0x80
    const val PATH_UPDATED = 0x81
    const val SEND_CONFIRMED = 0x82
    const val MSG_WAITING = 0x83
    const val RAW_DATA = 0x84
    const val LOGIN_SUCCESS = 0x85
    const val LOGIN_FAIL = 0x86
    const val STATUS_RESPONSE = 0x87
    const val LOG_RX_DATA = 0x88
    const val TRACE_DATA = 0x89
    const val NEW_ADVERT = 0x8A
    const val TELEMETRY_RESPONSE = 0x8B
    const val BINARY_RESPONSE = 0x8C
    const val PATH_DISCOVERY_RESPONSE = 0x8D
    const val CONTACT_DELETED = 0x8F
    const val CONTACTS_FULL = 0x90
}

object Err {
    const val UNSUPPORTED_CMD = 1
    const val NOT_FOUND = 2
    const val TABLE_FULL = 3
    const val BAD_STATE = 4
    const val FILE_IO_ERROR = 5
    const val ILLEGAL_ARG = 6
}

object TxtType {
    const val PLAIN = 0
    const val CLI_DATA = 1
    const val SIGNED_PLAIN = 2
}

/** What a node's advert says it is. A repeater and a person are told apart only by this. */
object AdvType {
    const val NONE = 0
    const val CHAT = 1
    const val REPEATER = 2
    const val ROOM = 3
    const val SENSOR = 4
}

object Sizes {
    const val PUB_KEY = 32

    /**
     * A contact is matched by the first six bytes of its key, not the whole of it. The radio
     * takes a six-byte prefix when addressing a message and hands back the same prefix on
     * receipt, so a contact table keyed on the full 32 bytes cannot answer "who sent this"
     * without a prefix index.
     */
    const val PUB_KEY_PREFIX = 6
    const val MAX_PATH = 64
    const val NAME = 32

    /** 176 bytes, transport codes included. Longer text is truncated by the radio, silently. */
    const val MAX_FRAME = 176
}

/**
 * The protocol version this app claims to understand, sent in [Cmd.DEVICE_QUERY].
 *
 * 13 is the firmware version code shared by 1.16.0 through 1.17.1, which covers the node here.
 */
const val APP_PROTOCOL_VERSION = 13

/** No path: the message came direct rather than by flood, so there is no route to record. */
const val PATH_LEN_DIRECT = 0xFF
