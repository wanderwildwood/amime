# 網目 amime — Mesh

A [MeshCore](https://meshcore.co.nz/) companion client for the
[Mudita Kompakt](https://mudita.com/products/kompakt/) and its E Ink screen. Talks to a LoRa
radio over Bluetooth and sends messages through a mesh that needs no towers, no carrier and
no internet.

*Amime* is 網目 — the eye of a net, the gap the weave makes. Which is the shape of the thing:
not the nodes but the spaces they hold open between them.

Not a fork. Written from scratch in Kotlin, and where it needs a screen it will use Mudita's
own [MMD](https://github.com/mudita/MMD) design system, as the rest of these apps do.

## Where this is up to

**Version 0.1.0, and there is nothing to look at yet.** This is the protocol, the session
that drives it and the Bluetooth transport underneath: 54 tests, green, and no user
interface at all. The app installs and does nothing, because there is nothing yet for it
to do.

What works is the part that decides whether anything else can. The commands and frames are
written against `examples/companion_radio/MyMesh.cpp` in the MeshCore firmware rather than
against the protocol documentation, for a reason given below. The session — handshake order,
draining the message queue, matching an acknowledgement to the send that expected it — has no
Android in it at all, so those rules are tested without a radio or a phone.

**None of it has touched real hardware yet.** The node here is a ThinkNode M5 on
companion-v1.16.0, and the first thing that will be learnt from it is which of these
assumptions is wrong.

Still to come: a contact list, a conversation, and only then anything with type on it.

## The documentation is wrong in at least one place that matters

`docs/companion_protocol.md` presents `0x03` as the opcode for sending a text message. It is
not. In the firmware, 3 is `CMD_SEND_CHANNEL_TXT_MSG` and 2 is `CMD_SEND_TXT_MSG`, so a
client written to the documentation broadcasts every private message to a group channel — and
does it silently, because the radio accepts the frame and answers that it was sent.

Several other things in that document are worth distrusting: it gives the datagram opcode a
number belonging to a different command, and states that no way of listing contacts is
documented, when `CMD_GET_CONTACTS` is opcode 4 and has been all along. Everything in
`Codes.kt` is transcribed from the firmware source instead, and `CommandsTest` has a test
whose only job is to fail if the message opcode is ever "corrected" back.

## Things the firmware does that are worth knowing

- **The radio must be told what the app understands, and it only listens once.**
  `CMD_DEVICE_QUERY` carries a protocol version in its second byte, and that is the sole
  place the firmware reads it. Until it arrives the radio treats the app as version 0 and
  answers with a pre-v3 message layout that has no SNR and different offsets for every field
  after it. Nothing reports an error; the messages simply decode to rubbish.
- **Bonding is mandatory.** The GATT is configured with MITM protection on both the ESP32 and
  nRF52 builds, so the phone has to pair rather than merely connect. The PIN is 123456 unless
  it has been changed, and the device reports its own in the reply to `CMD_DEVICE_QUERY`.
- **A contact is addressed by six bytes, not thirty-two.** Messages carry a six-byte prefix
  of the public key in both directions, so a contact table keyed on the whole key cannot
  answer who sent something.
- **`PUSH_CODE_MSG_WAITING` carries nothing** — no count, no sender. The only correct response
  is to call `CMD_SYNC_NEXT_MESSAGE` until the radio says there are no more.
- **A signed message hides four bytes in front of its text.** `TXT_TYPE_SIGNED_PLAIN` puts a
  four-byte sender prefix between the header and the body; read as text, it is the rubbish on
  the front of the message.
- **The contact count is not how many contacts are coming.** `RESP_CODE_CONTACTS_START` gives
  the node's total, and the `since` filter is applied afterwards, so a sync that asks for
  recent changes gets a count far larger than the number of frames that follow.
- **Frequency and bandwidth arrive in different units.** The firmware multiplies a float of
  MHz by 1000 for one and a float of kHz by 1000 for the other, so `SELF_INFO` carries
  frequency in kHz and bandwidth in Hz.

## What the Bluetooth side has to get right

The radio marks both characteristics MITM-protected, so the phone must be *bonded*, not
merely connected. Android will start pairing by itself when an unbonded app touches such a
characteristic, but the operation that triggered it is often dropped rather than retried —
most visibly the notification-enable, which leaves a connection that looks healthy and never
delivers a message. So `BleTransport` bonds deliberately and up front and touches nothing
until that has completed.

Two more, both silent when wrong:

- **A frame is one characteristic value.** The firmware calls `setValue` then `notify` and
  never chunks, so the negotiated MTU has to hold the largest frame whole. The default ATT
  MTU of 23 leaves 20 bytes of payload and would cut a 148-byte contact frame to a seventh of
  itself. The MTU is requested before service discovery, not after.
- **Android accepts one GATT operation at a time.** A second write issued before the first
  calls back does not queue and does not throw: it returns false and is dropped, and the
  frame with it. Everything goes through `OperationQueue`, which also has to cope with an
  operation the stack refuses outright — no callback is coming for one of those, so a queue
  that waits for one waits for good.

## Building

```
./gradlew :app:testDebugUnitTest
```

There is no checked-in signing key and no fallback. Without `signing/signing.keystore` a
release build comes out unsigned, which will not install anywhere.

## Licence

GPL-3.0-only. MeshCore itself is MIT, and is not vendored here — only its protocol is
implemented, from reading its source.
