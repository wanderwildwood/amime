# Privacy

Mesh cannot reach the internet. Not "does not" — cannot: it holds no internet permission, so
Android refuses it a socket.

That is the short version. The rest of this page is the evidence for it, and the part that is
less obvious: what a message does once it leaves the phone, which is not this app's doing and
is worth understanding before you trust it with anything.

## Permissions

`app/src/main/AndroidManifest.xml` declares two, and both are about talking to a radio over
Bluetooth:

- `BLUETOOTH_CONNECT` — to connect to the radio and exchange frames with it.
- `BLUETOOTH_SCAN`, marked **`neverForLocation`** — to find a radio that this phone has not
  met before. A Bluetooth scan counts as location access unless an app promises not to use it
  that way, and this app makes that promise in the manifest, where the system holds it to it.
  Nothing here derives a position from a scan result.

There is no `android.permission.INTERNET`, and there will not be one. A mesh app that phoned
home would be a contradiction.

A third entry appears if you read the permissions out of the built APK rather than out of the
manifest, and it is worth naming rather than letting you find it:
`com.wanderwildwood.amime.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. It is not an Android
permission and it asks nothing of you. AndroidX defines it inside this app's own package, at
signature level, so that a broadcast receiver the app registers at runtime — the one that
watches for the radio finishing pairing — is not exposed to other apps. Only something signed
with the same key could hold it, which means only this app. It is a lock, not a key.

## What is stored, and where

Your conversations, in the app's own private storage, as a file of plain lines. Nowhere else.

They are kept because the radio does not keep them: a message it has handed over is a message
it no longer holds, so if this app did not write them down they would be gone the next time
Android reclaimed the process.

**Uninstalling the app takes them with it.** There is no copy anywhere else, no export, and
nothing is synchronised. Contacts are not stored here at all — they live on the radio and are
read from it at every connection.

One thing is stored somewhere else, briefly, and it is not this app's doing. A message that
arrives while the app is closed waits **on the radio**, in its memory, already decrypted and
ready to be handed over — that is what makes it possible to collect later. It leaves the radio
when this app takes it, and it is gone anyway if the radio loses power, because that queue is
memory rather than storage. Whoever holds the radio holds those messages until then.

## What leaves the phone

Everything this app sends goes over Bluetooth to the radio beside you, and from there over
LoRa. There is no account, no server and no carrier anywhere in that path.

Three things about the air are the mesh's doing rather than this app's, and all three are
worth knowing:

- **The body of a message is encrypted** to the contact it is for, with a secret derived from
  their key and yours, and carries a MAC so it cannot be altered on the way. A repeater
  carrying it cannot read it.
- **Who it is between is not encrypted.** A short hash of the sender and a short hash of the
  destination ride in front of the ciphertext, because that is how the mesh knows where to
  take it. Anyone in range learns that those two nodes exchanged something, and how long it
  was, even though they cannot read it.
- **An advert is not encrypted at all.** Announcing your radio — the press at the foot of the people list —
  broadcasts your node's name and public key to anyone in range, and through repeaters to
  anyone the mesh reaches. That is what makes you addressable, and it is deliberate, but it
  is a public statement rather than a private one. Nothing else transmits without a press.

The name your radio advertises is the radio's own setting, not this app's. It is whatever you
set on the node.

## What is logged

Nothing, in a released build. Frame-level diagnostics exist in the source for working on the
protocol, and they are compiled out of a release, so the text of a message never reaches the
system log.

## The one address in the app

The llama at the foot of the About opens `square.link/u/AGu8oT10` in whatever browser you
have. That is a hand-off: this app does not fetch the page, and it learns nothing about
whether you went there.
