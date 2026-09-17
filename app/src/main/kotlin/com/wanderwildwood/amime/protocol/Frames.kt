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
        val isRepeater: Boolean,
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

        override fun equals(other: Any?): Boolean =
            this === other || (other is Contact && name == other.name &&
                publicKey.contentEquals(other.publicKey) && type == other.type &&
                flags == other.flags && outPath.contentEquals(other.outPath) &&
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
