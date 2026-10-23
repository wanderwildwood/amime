package com.wanderwildwood.amime.meshtastic

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config

/** The link that is also the QR code. The example is the one Meshtastic's own tests use. */
class ChannelUrlTest {
    /** From meshtastic/Meshtastic-Android's ChannelSetTest: the default LongFast channel. */
    private val upstream = "https://meshtastic.org/e/#CgMSAQESBggBQANIAQ"

    @Test
    fun the_upstream_example_reads_as_the_default_channel() {
        val set = ChannelUrl.parse(upstream)!!
        assertEquals(1, set.settings.size)
        assertEquals("", set.settings[0].name)
        assertEquals(ChannelKeys.DEFAULT, set.settings[0].psk)
        assertEquals(3, set.lora_config!!.hop_limit)
        assertTrue(set.lora_config!!.use_preset)
        assertTrue(set.lora_config!!.tx_enabled)
    }

    @Test
    fun the_upstream_example_writes_back_the_same_link() {
        assertEquals(upstream, ChannelUrl.encode(ChannelUrl.parse(upstream)!!))
    }

    @Test
    fun a_set_with_names_and_keys_survives_a_round_trip() {
        val key = ByteArray(32) { it.toByte() }.toByteString()
        val set = ChannelSet(
            settings = listOf(
                ChannelSettings(psk = ChannelKeys.DEFAULT),
                ChannelSettings(name = "ops", psk = key, uplink_enabled = true),
            ),
            lora_config = Config.LoRaConfig(region = Config.LoRaConfig.RegionCode.US, use_preset = true, hop_limit = 4),
        )
        val url = ChannelUrl.encode(set)
        assertFalse(url.contains('='))
        assertFalse(url.substringAfter('#').let { it.contains('+') || it.contains('/') })
        assertEquals(set, ChannelUrl.parse(url))
    }

    @Test
    fun the_scheme_the_case_and_the_www_do_not_matter() {
        val bare = ChannelUrl.parse("meshtastic.org/e/#CgMSAQESBggBQANIAQ")!!
        assertEquals(1, bare.settings.size)
        assertNotNull(ChannelUrl.parse("HTTPS://MESHTASTIC.ORG/E/#CgMSAQESBggBQANIAQ"))
        assertNotNull(ChannelUrl.parse("https://www.meshtastic.org/e/#CgMSAQESBggBQANIAQ"))
        assertNotNull(ChannelUrl.parse("https://meshtastic.org/e#CgMSAQESBggBQANIAQ"))
        assertNotNull(ChannelUrl.parse("  https://meshtastic.org/e/#CgMSAQESBggBQANIAQ\n"))
    }

    @Test
    fun add_true_drops_the_radio_settings_wherever_it_sits() {
        assertNull(ChannelUrl.parse("https://meshtastic.org/e/#CgMSAQESBggBQANIAQ?add=true")!!.lora_config)
        assertNull(ChannelUrl.parse("https://meshtastic.org/e/?add=true#CgMSAQESBggBQANIAQ")!!.lora_config)
        assertNotNull(ChannelUrl.parse("https://meshtastic.org/e/?add=false#CgMSAQESBggBQANIAQ")!!.lora_config)
    }

    @Test
    fun channels_only_writes_add_true_and_no_settings() {
        val set = ChannelUrl.parse(upstream)!!
        val url = ChannelUrl.encode(set, channelsOnly = true)
        assertTrue(url.startsWith("https://meshtastic.org/e/?add=true#"))
        val back = ChannelUrl.parse(url)!!
        assertNull(back.lora_config)
        assertEquals(set.settings, back.settings)
    }

    @Test
    fun things_that_are_not_channel_links_are_refused() {
        assertNull(ChannelUrl.parse(""))
        assertNull(ChannelUrl.parse("hello"))
        assertNull(ChannelUrl.parse("https://example.com/e/#CgMSAQESBggBQANIAQ"))
        assertNull(ChannelUrl.parse("https://meshtastic.org/x/#CgMSAQESBggBQANIAQ"))
        assertNull(ChannelUrl.parse("https://meshtastic.org/e/"))
        assertNull(ChannelUrl.parse("https://meshtastic.org/e/#"))
        assertNull(ChannelUrl.parse("https://meshtastic.org/e/#!!!notbase64!!!"))
        assertNull(ChannelUrl.parse("https://meshtastic.org/e/#CgM")) // cut off
        // a valid, empty set has nothing to add
        assertNull(ChannelUrl.parse(ChannelUrl.encode(ChannelSet())))
        // a contact link is not a channel link
        assertNull(ChannelUrl.parse("https://meshtastic.org/v/#CgMSAQESBggBQANIAQ"))
    }

    @Test
    fun key_kinds_are_told_apart_without_showing_the_key() {
        assertEquals(KeyKind.NONE, KeyKind.of(ByteString.EMPTY))
        assertEquals(KeyKind.NONE, KeyKind.of(ByteString.of(0)))
        assertEquals(KeyKind.DEFAULT, KeyKind.of(ByteString.of(1)))
        assertEquals(KeyKind.DEFAULT_VARIANT, KeyKind.of(ByteString.of(7)))
        assertEquals(KeyKind.OWN_128, KeyKind.of(ByteArray(16).toByteString()))
        assertEquals(KeyKind.OWN_256, KeyKind.of(ByteArray(32).toByteString()))
        assertEquals(KeyKind.ODD, KeyKind.of(ByteArray(5).toByteString()))
        assertTrue(KeyKind.DEFAULT.isPublic)
        assertFalse(KeyKind.OWN_128.isPublic)
    }

    @Test
    fun a_pasted_key_must_be_a_length_the_radio_uses() {
        val k16 = ByteArray(16) { (it + 1).toByte() }.toByteString()
        assertEquals(k16, ChannelKeys.fromText(k16.base64()))
        assertEquals(k16, ChannelKeys.fromText(k16.base64Url().trimEnd('=')))
        assertEquals(k16, ChannelKeys.fromText(" " + k16.base64() + "\n"))
        assertEquals(ChannelKeys.DEFAULT, ChannelKeys.fromText("AQ=="))
        assertEquals(ByteString.EMPTY, ChannelKeys.fromText(""))
        assertNull(ChannelKeys.fromText(ByteArray(5).toByteString().base64()))
        assertNull(ChannelKeys.fromText("not base64 at all!"))
    }

    @Test
    fun a_random_key_is_256_bits_and_comes_from_the_filler() {
        val k = ChannelKeys.random { it.fill(9) }
        assertEquals(32, k.size)
        assertEquals(ByteArray(32) { 9 }.toByteString(), k)
        assertFalse(ChannelKeys.random() == ChannelKeys.random())
        assertEquals(KeyKind.OWN_256, KeyKind.of(k))
    }
}
