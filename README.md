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

**Version 0.1.0, and there is nothing to look at yet.** This is the companion protocol and
its tests: 30 of them, green, and no user interface at all. The app installs and does
nothing, because there is nothing yet for it to do.

What works is the part that decides whether anything else can: encoding the commands the
radio understands and decoding the frames it sends back. That is done against
`examples/companion_radio/MyMesh.cpp` in the MeshCore firmware rather than against the
protocol documentation, for a reason given below.

Still to come, roughly in order: the BLE transport and its bonding, a contact list, a
conversation, and only then anything with type on it.

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

## Building

```
./gradlew :app:testDebugUnitTest
```

There is no checked-in signing key and no fallback. Without `signing/signing.keystore` a
release build comes out unsigned, which will not install anywhere.

## Licence

GPL-3.0-only. MeshCore itself is MIT, and is not vendored here — only its protocol is
implemented, from reading its source.
