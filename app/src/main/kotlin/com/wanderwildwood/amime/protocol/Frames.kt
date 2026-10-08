package com.wanderwildwood.amime.protocol

/** One frame decoded off the radio's TX characteristic. */
sealed interface Frame {

    /** The radio did what was asked and had nothing to add. */
    data object Ok : Frame

    /** The radio refused. [code] is one of [Err]. */
    data class Failed(val code: Int) : Frame

    /** Command refused because the radio is in a state that disallows it. */
    data object Disabled : Frame

    /**
     * What the radio is. Reply to [Commands.deviceQuery].
     *
     * [maxContacts] is doubled on the way out: the firmware halves it to fit a byte, so the
     * wire carries 50 for a node that holds 100.
     */
    data class DeviceInfo(
        val firmwareVersionCode: Int,
        val maxContacts: Int,
        val maxGroupChannels: Int,
        val blePin: Long,
        val buildDate: String,
        val manufacturer: String,
        val firmwareVersion: String,
        /**
         * Whether this radio has been told to relay other people's packets.
         *
         * `_prefs.isRepeatEn()` in the firmware, and it is about a setting rather than about
         * what the device is: a companion radio with client repeating turned on reports true
         * here and is still a companion radio. Whether some *other* node is a repeater is a
         * different question, answered by its advertised type on its contact row.
         */
        val repeatEnabled: Boolean,
        val pathHashMode: Int,
    ) : Frame

    /**
     * Who this radio is. Reply to [Commands.appStart].
     *
     * [frequencyKhz] and [bandwidthHz] really are in different units — the firmware multiplies
     * a float of MHz by 1000 for one and a float of kHz by 1000 for the other. That is not a
     * transcription slip here; it is what goes over the wire.
     *
     * [latitude] and [longitude] are micro-degrees, and are 0 when the node has no fix or has
     * been told not to share one.
     */
    data class SelfInfo(
        val advertType: Int,
        val txPowerDbm: Int,
        val maxTxPowerDbm: Int,
        val publicKey: ByteArray,
        val latitude: Int,
        val longitude: Int,
        val multiAcks: Int,
        val advertLocationPolicy: Int,
        val telemetryModes: Int,
        val manualAddContacts: Boolean,
        val frequencyKhz: Long,
        val bandwidthHz: Long,
        val spreadingFactor: Int,
        val codingRate: Int,
        val name: String,
    ) : Frame {
        // A ByteArray field means the generated equals/hashCode compare by identity, which
        // would quietly make two decodes of the same frame unequal.
        override fun equals(other: Any?): Boolean =
            this === other || (other is SelfInfo && name == other.name &&
                publicKey.contentEquals(other.publicKey) && advertType == other.advertType &&
                txPowerDbm == other.txPowerDbm && maxTxPowerDbm == other.maxTxPowerDbm &&
                latitude == other.latitude && longitude == other.longitude &&
                multiAcks == other.multiAcks &&
                advertLocationPolicy == other.advertLocationPolicy &&
                telemetryModes == other.telemetryModes &&
                manualAddContacts == other.manualAddContacts &&
                frequencyKhz == other.frequencyKhz && bandwidthHz == other.bandwidthHz &&
                spreadingFactor == other.spreadingFactor && codingRate == other.codingRate)

        override fun hashCode(): Int = 31 * name.hashCode() + publicKey.contentHashCode()
    }

    /**
     * The radio has learnt a way to reach a contact, or a better one.
     *
     * Carries the whole 32-byte key and nothing else — not the path itself. The contact's
     * `lastmod` is bumped before this is sent, so asking for what has changed since the last
     * sync brings back this contact with its new path on it.
     */
    data class PathUpdated(val publicKey: ByteArray) : Frame {
        override fun equals(other: Any?): Boolean =
            this === other || (other is PathUpdated && publicKey.contentEquals(other.publicKey))

        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    /**
     * The radio threw a contact away to make room for a newer one.
     *
     * It keeps a fixed number and overwrites the oldest that is not a favourite, so this is
     * the ordinary consequence of a busy mesh rather than a fault. The app is told which one
     * because a list still showing them is a list offering to write to somebody the radio can
     * no longer address.
     */
    data class ContactDeleted(val publicKey: ByteArray) : Frame {
        override fun equals(other: Any?): Boolean =
            this === other || (other is ContactDeleted && publicKey.contentEquals(other.publicKey))

        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    /**
     * The radio's contact table is full.
     *
     * Carries nothing; it is a statement about the radio, not about a contact. What follows
     * if nothing is done is that new people stop appearing, which from the outside is
     * indistinguishable from an empty mesh.
     */
    data object ContactsFull : Frame

    /** The contact list is about to arrive. [count] contacts will follow. */
    data class ContactsStart(val count: Long) : Frame

    /** The contact list is complete, as of [mostRecentLastMod]. */
    data class ContactsEnd(val mostRecentLastMod: Long) : Frame

    /**
     * One contact.
     *
     * Arrives as a [Resp.CONTACT] during a list, or unprompted as [Push.NEW_ADVERT] when a
     * node this one had not met advertises itself. [isNewAdvert] says which, because the two
     * mean quite different things to a UI.
     */
    data class Contact(
        val publicKey: ByteArray,
        val type: Int,
        val flags: Int,
        /**
         * The `out_path_len` byte exactly as it arrived, sentinel and all.
         *
         * Kept raw because the byte carries three different things — whether a route is
         * known at all, how many hops it is, and how wide each hop's hash is — and a reader
         * that wants any one of them wants a different question asked of it.
         */
        val outPathLen: Int,
        val outPath: ByteArray,
        val name: String,
        val lastAdvert: Long,
        val latitude: Int,
        val longitude: Int,
        val lastMod: Long,
        val isNewAdvert: Boolean,
    ) : Frame {
        /** The six bytes this contact is addressed by. */
        val prefix: ByteArray get() = publicKey.copyOf(Sizes.PUB_KEY_PREFIX)

        /**
         * Whether the radio knows a way to reach this node.
         *
         * Not "is the path non-empty": a node one hop away has a path of **no** hops, which
         * is a known route and an empty array, and a node nobody has a route to reports
         * [OUT_PATH_UNKNOWN] — which, read as a length, is a full path rather than no path.
         * Both of the two commonest cases come out backwards that way round.
         */
        val pathKnown: Boolean get() = outPathLen != OUT_PATH_UNKNOWN

        override fun equals(other: Any?): Boolean =
            this === other || (other is Contact && name == other.name &&
                publicKey.contentEquals(other.publicKey) && type == other.type &&
                flags == other.flags && outPathLen == other.outPathLen &&
                outPath.contentEquals(other.outPath) &&
                lastAdvert == other.lastAdvert && latitude == other.latitude &&
                longitude == other.longitude && lastMod == other.lastMod &&
                isNewAdvert == other.isNewAdvert)

        override fun hashCode(): Int = 31 * name.hashCode() + publicKey.contentHashCode()
    }

    /**
     * A message was handed to the radio. Reply to [Commands.sendTextMessage].
     *
     * This says the radio accepted it, not that anyone received it. [expectedAck] is the hash
     * to watch for in a later [Push.SEND_CONFIRMED]; when it is 0 no acknowledgement is coming
     * and there will never be a confirmation, which is not the same as a failure.
     * [estimatedTimeoutMs] is the radio's own guess at how long to wait before believing that.
     */
    data class Sent(
        val byFlood: Boolean,
        val expectedAck: Long,
        val estimatedTimeoutMs: Long,
    ) : Frame

    /**
     * A message arrived.
     *
     * [snr] is the real value: the wire carries it multiplied by four, and it is divided back
     * here. [pathLength] is [PATH_LEN_DIRECT] when the message came direct rather than by
     * flood. [senderPrefix] identifies the contact by the same six bytes used to address one.
     *
     * [signedSenderPrefix] is set only for [TxtType.SIGNED_PLAIN], which carries four extra
     * bytes ahead of the text. Parsing those as text is what happens if the type is ignored.
     */
    data class MessageReceived(
        val snr: Float,
        val senderPrefix: ByteArray,
        val pathLength: Int,
        val txtType: Int,
        val senderTimestamp: Long,
        val text: String,
        val signedSenderPrefix: ByteArray? = null,
    ) : Frame {
        val cameDirect: Boolean get() = pathLength == PATH_LEN_DIRECT

        override fun equals(other: Any?): Boolean =
            this === other || (other is MessageReceived && text == other.text &&
                snr == other.snr && senderPrefix.contentEquals(other.senderPrefix) &&
                pathLength == other.pathLength && txtType == other.txtType &&
                senderTimestamp == other.senderTimestamp &&
                (signedSenderPrefix ?: ByteArray(0))
                    .contentEquals(other.signedSenderPrefix ?: ByteArray(0)))

        override fun hashCode(): Int = 31 * text.hashCode() + senderPrefix.contentHashCode()
    }

    /**
     * What is in one channel slot. Reply to [Commands.getChannel].
     *
     * An unused slot answers like any other, with an empty name and a key of zeros — which is
     * also exactly what leaving a channel writes back — so [isEmpty] is how a free slot is
     * found.
     */
    data class ChannelInfo(val index: Int, val name: String, val secret: ByteArray) : Frame {
        val isEmpty: Boolean get() = name.isEmpty() && secret.all { it == 0.toByte() }

        override fun equals(other: Any?): Boolean =
            this === other || (other is ChannelInfo && index == other.index &&
                name == other.name && secret.contentEquals(other.secret))

        override fun hashCode(): Int = 31 * index + secret.contentHashCode()
    }

    /**
     * Somebody said something on a channel.
     *
     * Unlike a direct message there is no sender key — a channel message is encrypted to
     * everyone holding the channel's key and signed by nobody. Who said it is in the [text]
     * itself, `name: words`, put there by the sender's radio. [channelIndex] is the slot on
     * *this* radio whose key opened it.
     */
    data class ChannelMessageReceived(
        val snr: Float,
        val channelIndex: Int,
        val pathLength: Int,
        val txtType: Int,
        val senderTimestamp: Long,
        val text: String,
    ) : Frame {
        val cameDirect: Boolean get() = pathLength == PATH_LEN_DIRECT
    }

    /**
     * A binary datagram on a channel. Not read here, but it arrives off the same queue as a
     * message, so it has to be recognised for the queue to go on draining past it.
     */
    data class ChannelDataReceived(val bytes: ByteArray) : Frame {
        override fun equals(other: Any?): Boolean =
            this === other || (other is ChannelDataReceived && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** The queue is empty. Reply to [Commands.syncNextMessage]. */
    data object NoMoreMessages : Frame

    /** Battery in millivolts, and storage in kilobytes. */
    data class BattAndStorage(
        val batteryMillivolts: Int,
        val storageUsedKb: Long?,
        val storageTotalKb: Long?,
    ) : Frame

    /**
     * Messages are waiting. Carries nothing else — not a count, not a sender.
     *
     * The only correct response is to call [Commands.syncNextMessage] until
     * [NoMoreMessages] comes back.
     */
    data object MessagesWaiting : Frame

    /** A send was acknowledged. Matches the [Sent.expectedAck] handed out earlier. */
    data class SendConfirmed(val ackHash: Long, val roundTripMs: Long) : Frame

    /**
     * A repeater or room server accepted a login.
     *
     * [isAdmin] is what decides whether CLI commands will be obeyed or refused; a guest login
     * connects and can do almost nothing. [serverTimestamp] and [firmwareLevel] are absent on
     * the legacy eight-byte form that older repeaters send.
     */
    data class LoginSucceeded(
        val senderPrefix: ByteArray,
        val permissions: Int,
        val serverTimestamp: Long? = null,
        val aclPermissions: Int? = null,
        val firmwareLevel: Int? = null,
    ) : Frame {
        val isAdmin: Boolean get() = permissions != 0

        override fun equals(other: Any?): Boolean =
            this === other || (other is LoginSucceeded &&
                senderPrefix.contentEquals(other.senderPrefix) &&
                permissions == other.permissions && serverTimestamp == other.serverTimestamp &&
                aclPermissions == other.aclPermissions && firmwareLevel == other.firmwareLevel)

        override fun hashCode(): Int = 31 * permissions + senderPrefix.contentHashCode()
    }

    /**
     * A login was refused.
     *
     * Carries no reason. A wrong password and a node that has run out of client slots look
     * identical from here.
     */
    data class LoginFailed(val senderPrefix: ByteArray) : Frame {
        override fun equals(other: Any?): Boolean =
            this === other || (other is LoginFailed && senderPrefix.contentEquals(other.senderPrefix))

        override fun hashCode(): Int = senderPrefix.contentHashCode()
    }

    /**
     * A packet the radio heard off the air, with how well it heard it.
     *
     * Pushed for **every** raw packet received, whoever it was from and whoever it was for —
     * the firmware logs it before it even tries to parse it. So this says "something is out
     * there" in cases where nothing else does: a node too far away to decode cleanly, or
     * traffic between two other stations that has nothing to do with us.
     *
     * That makes it the one honest answer to "is there anybody within range", which a contact
     * list cannot give, because a contact only appears once a readable advert arrives.
     *
     * [snr] is real dB, divided back down from the quarter-dB the wire carries. [rssi] is dBm.
     */
    data class PacketHeard(val snr: Float, val rssi: Int, val bytes: ByteArray) : Frame {
        override fun equals(other: Any?): Boolean =
            this === other || (other is PacketHeard && snr == other.snr && rssi == other.rssi &&
                bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * rssi + bytes.contentHashCode()
    }

    /** A frame this app does not decode yet, kept whole rather than dropped. */
    data class Unhandled(val code: Int, val bytes: ByteArray) : Frame {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Unhandled && code == other.code &&
                bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * code + bytes.contentHashCode()
    }

    /** A frame that was too short to be what its own opening byte claims. */
    data class Malformed(val code: Int, val length: Int) : Frame
}
