package com.wanderwildwood.amime.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Checks every protocol number in this app against MeshCore's own source.
 *
 * None of MeshCore can be vendored here — it is C++ firmware for an ESP32, and the official
 * phone app is Flutter — so the protocol is implemented rather than borrowed. What can still
 * come from upstream instead of from somebody's fingers is whether the numbers are right, and
 * that is what this is: `tools/update-meshcore-constants.py` reads the `#define`s out of a
 * MeshCore checkout into `meshcore-constants.txt`, and this fails if anything here disagrees.
 *
 * The constants stay hand-written on purpose. What surrounds them in `Codes.kt` — which
 * opcode the documentation gets wrong, which byte is a sentinel rather than a length — is the
 * part that took the longest to learn and the part a generator would delete.
 */
class UpstreamConstantsTest {

    /** Each object of ours, and the prefix its members carry upstream. */
    private val families = listOf(
        Triple("Cmd", "CMD_", Cmd::class.java),
        Triple("Resp", "RESP_CODE_", Resp::class.java),
        Triple("Push", "PUSH_CODE_", Push::class.java),
        Triple("Err", "ERR_CODE_", Err::class.java),
        Triple("TxtType", "TXT_TYPE_", TxtType::class.java),
        Triple("AdvType", "ADV_TYPE_", AdvType::class.java),
    )

    /** Names that do not follow their family's prefix upstream. */
    private val spellings = mapOf(
        "RESP_CODE_ALLOWED_REPEAT_FREQ" to "RESP_ALLOWED_REPEAT_FREQ",
    )

    private val upstream: Map<String, Int> by lazy {
        val text = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("meshcore-constants.txt"),
        ) { "meshcore-constants.txt is missing; run tools/update-meshcore-constants.py" }
            .bufferedReader().readText()

        text.lineSequence()
            .filterNot { it.isBlank() || it.startsWith("#") }
            .map { it.substringBefore('=') to it.substringAfter('=').trim().toInt() }
            .toMap()
    }

    /**
     * The control.
     *
     * Every assertion below is of the form "ours equals upstream's", and an empty or
     * truncated manifest would make all of them vacuous — nothing to disagree with. This is
     * the check that the check could fail.
     */
    @Test
    fun `the manifest was actually read`() {
        assertTrue(
            "only ${upstream.size} constants read from the manifest",
            upstream.size > 100,
        )
        assertEquals(2, upstream["CMD_SEND_TXT_MSG"])
    }

    @Test
    fun `every opcode matches the firmware`() {
        val checked = mutableListOf<String>()
        val missing = mutableListOf<String>()

        for ((family, prefix, type) in families) {
            for (field in type.declaredFields) {
                if (field.type != Int::class.javaPrimitiveType) continue
                // The Compose compiler adds a `$stable` int to every class it touches. It
                // is not protocol, and no name upstream has a dollar in it.
                if ('$' in field.name || !Modifier.isStatic(field.modifiers)) continue
                val name = prefix + field.name
                val theirs = upstream[spellings[name] ?: name]
                if (theirs == null) {
                    missing += "$family.${field.name} (looked for $name)"
                    continue
                }
                field.isAccessible = true
                assertEquals("$family.${field.name}", theirs, field.getInt(null))
                checked += name
            }
        }

        // A name this app has that upstream does not is not a wrong value, it is a constant
        // that has been renamed or removed — which is a protocol change to read rather than
        // something to let a passing test hide.
        assertEquals("not found upstream: $missing", emptyList<String>(), missing)
        assertTrue("only ${checked.size} constants checked", checked.size > 60)
    }

    /**
     * The three sizes the app makes decisions with, rather than merely transcribes.
     *
     * [Sizes.MAX_TEXT] is the one that matters most: it caps what the message field will
     * accept, and upstream writes it as `(10*CIPHER_BLOCK_SIZE)` rather than as a number, so
     * it is the value most likely to be got wrong by reading quickly.
     */
    @Test
    fun `the sizes the app enforces match the firmware`() {
        assertEquals(upstream["PUB_KEY_SIZE"], Sizes.PUB_KEY)
        assertEquals(upstream["MAX_PATH_SIZE"], Sizes.MAX_PATH)
        assertEquals(upstream["MAX_TEXT_LEN"], Sizes.MAX_TEXT)
    }

    /**
     * The sentinel that had the people list drawing every new contact as settled.
     *
     * It is 0xFF in `ContactInfo.h`, it is what a contact is created with, and read as a
     * length it is the longest path there could be rather than no path at all.
     */
    @Test
    fun `the no-route sentinel matches the firmware`() {
        assertEquals(upstream["OUT_PATH_UNKNOWN"], OUT_PATH_UNKNOWN)
    }
}
