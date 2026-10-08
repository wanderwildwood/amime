package com.wanderwildwood.amime.protocol

import java.security.MessageDigest

/**
 * How a channel's key is arrived at.
 *
 * The radio itself only ever sees a name and a 16-byte secret (`CMD_SET_CHANNEL` in
 * `examples/companion_radio/MyMesh.cpp`); where the secret comes from is a convention the apps
 * share, written down in `docs/companion_protocol.md` under "Channel Types":
 *
 * - **Public** has one fixed key, the one every companion radio is given at boot
 *   (`PUBLIC_GROUP_PSK`, `izOH6cXN6mrJ5e26oRXNcg==` in `MyMesh.cpp`).
 * - **A hashtag channel**'s key is the first 16 bytes of SHA-256 over its name *with* the `#`.
 *   Anyone who knows the name has the key, so it is a topic, not a secret.
 * - **A private channel** has a key somebody made up and shared.
 *
 * ⚠ The name is not folded to lower case. MeshCore's own Python library and the open phone app
 * both hash it exactly as typed, `#`-prefixed and trimmed, so `#MeshAVL` and `#meshavl` are two
 * different channels on everybody's radio, and folding it here would put this one on neither.
 */
object Channels {

    /** A channel key is 128 bits. The firmware stores 32 bytes and refuses to use more than 16. */
    const val SECRET = 16

    /** The name field is 32 bytes and the firmware keeps a terminator inside it. */
    const val MAX_NAME_BYTES = 31

    /** `8b3387e9c5cdea6ac9e5edbaa115cd72`: Public's key, decoded from the firmware's base64. */
    val PUBLIC_KEY: ByteArray = hex("8b3387e9c5cdea6ac9e5edbaa115cd72")

    /** The name a hashtag channel is stored and hashed under: trimmed, with one `#` in front. */
    fun hashtagName(typed: String): String {
        val bare = typed.trim().removePrefix("#").trim()
        return "#$bare"
    }

    /** The key everybody derives from a hashtag channel's name. */
    fun hashtagKey(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(hashtagName(name).toByteArray(Charsets.UTF_8))
            .copyOf(SECRET)

    /**
     * A private key as people pass them around: 32 hexadecimal characters. Spaces are allowed
     * and ignored, since a key read out or copied by hand tends to arrive in groups.
     * Returns null for anything that is not exactly that.
     */
    fun parseKey(typed: String): ByteArray? {
        val clean = typed.filterNot { it.isWhitespace() }
        if (clean.length != SECRET * 2 || clean.any { it.digitToIntOrNull(16) == null }) return null
        return hex(clean)
    }

    /** What somebody typed to join a channel, turned into what the radio is given. */
    sealed interface Join {
        class Ok(val name: String, val secret: ByteArray) : Join
        data object NoName : Join
        data object NameTooLong : Join
        data object BadKey : Join
    }

    /**
     * A name alone is a hashtag channel; a name and a key is a private one, kept under the
     * name as typed, since nothing is derived from it.
     */
    fun join(typedName: String, typedKey: String): Join {
        val private = typedKey.isNotBlank()
        val name = if (private) typedName.trim() else hashtagName(typedName)
        if (name.removePrefix("#").isEmpty()) return Join.NoName
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return Join.NameTooLong
        val secret = if (private) parseKey(typedKey) ?: return Join.BadKey else hashtagKey(name)
        return Join.Ok(name, secret)
    }

    fun isPublic(secret: ByteArray): Boolean = secret.contentEquals(PUBLIC_KEY)

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
