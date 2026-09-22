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
    const val SEND_RAW_DATA = 25

    /** Log in to a repeater or room server. Addressed by the whole key, not the prefix. */
    const val SEND_LOGIN = 26
    const val SEND_STATUS_REQ = 27
    const val HAS_CONNECTION = 28

    /** Drop a logged-in connection. Also the whole key. */
    const val LOGOUT = 29
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

    /** 176 bytes, transport codes included. A frame longer than this is the radio's limit. */
    const val MAX_FRAME = 176

    /**
     * The longest message the radio will send, in **bytes** of UTF-8 rather than characters.
     *
     * `MAX_TEXT_LEN` in `BaseChatMesh.h`, which is ten cipher blocks of sixteen. A longer
     * message is not shortened and not queued: `composeMsgPacket` returns null and the
     * companion answers with a table-full error, which arrives here as a flat refusal with
     * nothing in it about length. So it is worth refusing before the radio does, in a place
     * that can say why.
     *
     * Thirteen bytes of command frame plus this is 173, which is exactly what a 176-byte MTU
     * carries — the two limits were chosen against each other.
     */
    const val MAX_TEXT = 160
}

/**
 * The protocol version this app claims to understand, sent in [Cmd.DEVICE_QUERY].
 *
 * 13 is the firmware version code shared by 1.16.0 through 1.17.1, which covers the node here.
 */
const val APP_PROTOCOL_VERSION = 13

/** No path: the message came direct rather than by flood, so there is no route to record. */
const val PATH_LEN_DIRECT = 0xFF

/**
 * What a contact's `out_path_len` says when the radio has no route to it.
 *
 * `OUT_PATH_UNKNOWN` in `ContactInfo.h`, and the value every contact starts at
 * (`BaseChatMesh.cpp` sets it when a contact is created). It is the same 0xFF that means
 * *came direct* in a received message, and it means close to the opposite here: there, the
 * message did not have to be flooded; here, the radio has never learnt a way to reach them.
 *
 * ⚠ It is also not a length. A path length is an encoding — the low six bits are how many
 * hops, the top two are how many bytes each hop's hash takes — so 0xFF decodes to 63 hops of
 * four bytes, which is the one combination the firmware marks invalid, which is why it could
 * be spared as the sentinel. See [pathBytes].
 */
const val OUT_PATH_UNKNOWN = 0xFF

/**
 * How many bytes of path a `path_len` byte actually describes.
 *
 * `Packet::isValidPathLen` in the firmware: `hash_count = len and 63`, and each hop's hash is
 * `(len shr 6) + 1` bytes. Reading the byte as a count of bytes is right only while every
 * hash is one byte, which is the ordinary case and therefore the one that hides the bug.
 * Returns 0 where the firmware would call the encoding invalid.
 */
fun pathBytes(pathLen: Int): Int {
    val hops = pathLen and 63
    val hashSize = (pathLen shr 6) + 1
    if (hashSize == 4) return 0 // reserved by the firmware, and what 0xFF decodes to
    val bytes = hops * hashSize
    return if (bytes <= Sizes.MAX_PATH) bytes else 0
}
