package com.wanderwildwood.amime.meshtastic

import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings

/**
 * The link Meshtastic uses to share channels: `https://meshtastic.org/e/#` and then a
 * [ChannelSet] in base64url, no padding. The QR code on the official app is that link.
 *
 * Read the way the official app reads it: the host may be `www.` and any case, the path is
 * `/e` or `/e/`, and `?add=true` may sit before the `#` or inside the fragment after it
 * (older clients wrote it there). `add=true` means channels only, so the radio settings
 * part is dropped. Nothing here goes near the network; it is only text.
 */
object ChannelUrl {
    private const val HOST = "meshtastic.org"

    /** What a link holds, or null when it is not a channel link. The first channel is the sender's primary. */
    fun parse(text: String): ChannelSet? {
        val cleaned = text.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        val hash = cleaned.indexOf('#')
        if (hash < 0) return null
        val before = cleaned.substring(0, hash)
        val fragment = cleaned.substring(hash + 1)

        // Before the '#': [scheme://]host[/path][?query]
        val noScheme = before.replace(Regex("^[A-Za-z][A-Za-z0-9+.-]*://"), "")
        val query = noScheme.substringAfter('?', "")
        val locator = noScheme.substringBefore('?')
        val host = locator.substringBefore('/').lowercase()
        val path = locator.substringAfter('/', "").trim('/').lowercase()
        if (host != HOST && host != "www.$HOST") return null
        if (path.split('/').none { it == "e" }) return null

        val data = fragment.substringBefore('?')
        if (data.isEmpty()) return null
        val bytes = data.decodeBase64() ?: return null
        val set = runCatching { ChannelSet.ADAPTER.decode(bytes) }.getOrNull() ?: return null
        if (set.settings.isEmpty()) return null

        val add = addFlag(query) ?: addFlag(fragment.substringAfter('?', "")) ?: false
        return if (add) set.copy(lora_config = null) else set
    }

    private fun addFlag(query: String): Boolean? = query.split('&')
        .firstOrNull { it.substringBefore('=').equals("add", ignoreCase = true) }
        ?.substringAfter('=', "true")
        ?.equals("true", ignoreCase = true)

    /**
     * The link for [set]. [channelsOnly] drops the radio settings and says so with
     * `?add=true`, which tells the reader to add the channels and change nothing else.
     */
    fun encode(set: ChannelSet, channelsOnly: Boolean = false): String {
        val body = if (channelsOnly) set.copy(lora_config = null) else set
        val data = ChannelSet.ADAPTER.encode(body).toByteString().base64Url().trimEnd('=')
        val query = if (channelsOnly) "?add=true" else ""
        return "https://$HOST/e/$query#$data"
    }
}

/** How a channel is locked, said without showing the key. */
enum class KeyKind {
    /** No key: anyone listening reads it. */
    NONE,

    /** The well-known default key (the one byte 1): anyone with a Meshtastic radio reads it. */
    DEFAULT,

    /** One of the well-known variants of the default key (the bytes 2 to 10). */
    DEFAULT_VARIANT,

    /** A 128-bit key of its own. */
    OWN_128,

    /** A 256-bit key of its own. */
    OWN_256,

    /** A length the firmware does not use. */
    ODD,
    ;

    /** True when the key is the one everybody knows. */
    val isPublic: Boolean get() = this == NONE || this == DEFAULT || this == DEFAULT_VARIANT

    companion object {
        fun of(psk: ByteString): KeyKind = when (psk.size) {
            0 -> NONE
            1 -> when (psk[0].toInt() and 0xFF) {
                0 -> NONE
                1 -> DEFAULT
                in 2..10 -> DEFAULT_VARIANT
                else -> ODD
            }
            16 -> OWN_128
            32 -> OWN_256
            else -> ODD
        }
    }
}

/** What the screens show of one channel slot. Never the key itself. */
data class MtChannelInfo(
    val index: Int,
    /** As stored: empty on the primary channel means the modem preset's name. */
    val name: String,
    /** 0 disabled, 1 primary, 2 secondary: the proto's role values. */
    val role: Int,
    val key: KeyKind,
    val uplink: Boolean,
    val downlink: Boolean,
) {
    val enabled: Boolean get() = role != 0
    val primary: Boolean get() = role == 1
}

/** Choosing a key for a channel: what the form offers, and the bytes each gives. */
object ChannelKeys {
    /** The default key's one byte. */
    val DEFAULT: ByteString = ByteString.of(1)
    val NONE: ByteString = ByteString.EMPTY

    /** Thirty-two random bytes: a 256-bit key, which is what the official app makes. */
    fun random(fill: (ByteArray) -> Unit = { java.security.SecureRandom().nextBytes(it) }): ByteString {
        val bytes = ByteArray(32)
        fill(bytes)
        return bytes.toByteString()
    }

    /** A pasted key, in base64 either alphabet and with or without padding. Null when it is not a usable key. */
    fun fromText(text: String): ByteString? {
        val bytes = text.filterNot { it.isWhitespace() }.decodeBase64() ?: return null
        return bytes.takeIf { it.size == 0 || it.size == 1 || it.size == 16 || it.size == 32 }
    }

    /** A key as text to copy out: standard base64 with padding, the way the official app shows it. */
    fun toText(psk: ByteString): String = psk.base64()
}

/** The most bytes the firmware keeps of a channel name. */
const val CHANNEL_NAME_BYTES = 11

fun ChannelSettings.info(index: Int, role: Int) = MtChannelInfo(
    index = index, name = name, role = role, key = KeyKind.of(psk),
    uplink = uplink_enabled, downlink = downlink_enabled,
)
