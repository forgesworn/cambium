# Cambium

An Android [NIP-55](https://github.com/nostr-protocol/nips/blob/master/55.md) signer that holds
no user keys. Every signing request is proxied to your [Heartwood](../heartwood-esp32) hardware
signer over [NIP-46](https://github.com/nostr-protocol/nips/blob/master/46.md) (Nostr relays).
The name follows the tree: cambium is the living layer between bark and the wood.

## Why

Amethyst, Primal and most other Amber-compatible Android Nostr clients cannot log in to a remote
NIP-46 bunker directly, but they all support signing in via any installed NIP-55 external signer.
Cambium fills that gap: it registers as a signer, but it is not one. It is a thin proxy that turns
NIP-55 intents into NIP-46 requests against your Heartwood, and hands the response straight back.

## Security model

- **No user secrets ever touch the phone.** Cambium stores only the bunker pairing (the paired
  Heartwood's public key, its relay list, and the per-pairing connection secret from the bunker
  URI) and Cambium's own ephemeral NIP-46 client keypair, generated on first pairing. Compromising
  the phone exposes the ability to *request* signatures -- which Heartwood's policy engine and
  physical confirmation button still gate -- never the identity key itself.
- All of the above is stored in Android Keystore-backed `EncryptedSharedPreferences`.
- Per-calling-app approval is tracked locally (a simple allow-set, mirroring Amber's UX) *in
  addition to* whatever Heartwood's own slot policy enforces. Cambium's local layer is a
  convenience filter; Heartwood remains the authority on what actually gets signed.
- All NIP-46 payloads are NIP-44-encrypted, matching Heartwood's firmware.

```
Amethyst / Primal / Voyage ...
        | NIP-55 (intent + content provider)
     Cambium
        | NIP-46 over relays (NIP-44 envelopes)
   Nostr relay(s)  <--  Heartwood (WiFi-standalone)
```

## Pairing from Sapwood

1. Install Cambium.
2. In [Sapwood](../sapwood) (desktop or phone handoff): Apps -> Connect an app -> name it (e.g.
   "Cambium on my phone") -> it shows a `bunker://` URI.
3. In Cambium: paste that URI and press Pair. Cambium connects to your Heartwood over the relays
   in the URI, confirms the handshake, and stores the pairing.
4. In any Amber-compatible app: choose "login with external signer" and pick Cambium. Approve the
   request the first time; Cambium remembers that app afterwards. Every signature still comes from
   your hardware signer, gated by its own policy and physical button, not from this phone.

Signing takes one relay round trip (roughly half a second to a couple of seconds) since every
request goes out to Heartwood and back.

## Build

Requires Android SDK (`sdk.dir` in `local.properties`) and JDK 21.

```bash
./gradlew :app:testDebugUnitTest   # unit tests (pairing parser, NIP-55 request parsing)
./gradlew :app:assembleDebug       # debug APK -> app/build/outputs/apk/debug/
```

Install directly with an attached device:

```bash
./gradlew :app:installDebug
```

## Status

Foundation scaffold: pairing, NIP-46 client, NIP-55 intent handling (`get_public_key`,
`sign_event`, `nip04`/`nip44` encrypt/decrypt) and the silent content-provider path for
`get_public_key`. QR pairing, a keep-warm relay session, and richer per-app permissions are
later milestones.

## Licence

MIT. See [LICENSE](LICENSE).
