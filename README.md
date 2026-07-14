# Cambium

[![GitHub Sponsors](https://img.shields.io/github/sponsors/TheCryptoDonkey?logo=githubsponsors&color=ea4aaa&label=Sponsor)](https://github.com/sponsors/TheCryptoDonkey)

An Android [NIP-55](https://github.com/nostr-protocol/nips/blob/master/55.md) signer that holds
no user keys. Every signing request is proxied to your [Heartwood](https://github.com/forgesworn/heartwood-esp32) hardware
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
   "Cambium on my phone") -> it shows a `bunker://` URI as a QR code (and as text).
3. In Cambium: tap Scan QR and point the camera at it, or paste the URI text and press Pair.
   A successful scan pairs immediately, no separate Pair tap needed. Either way, Cambium connects
   to your Heartwood over the relays in the URI, confirms the handshake, and stores the pairing.
   The camera is optional -- pasting works without it, on any device.
4. In any Amber-compatible app: choose "login with external signer" and pick Cambium. Approve the
   request the first time; Cambium remembers that app afterwards. Every signature still comes from
   your hardware signer, gated by its own policy and physical button, not from this phone.

Signing takes one relay round trip (roughly half a second to a couple of seconds) since every
request goes out to Heartwood and back.

## Install

Grab the signed APK from [GitHub Releases](https://github.com/forgesworn/cambium/releases), or
point [Obtainium](https://github.com/ImranR98/Obtainium) at this repository for automatic update
tracking (one-tap add: `obtainium://add/https://github.com/forgesworn/cambium`). Chrome is not
needed; Cambium runs on any Android 8.1+ device, including GrapheneOS (no Google Play services,
no Firebase, no analytics).

Also on [Zapstore](https://zapstore.dev/apps/naddr1qqtxgetk9enx7un8v4ehwmmjdchxxctdvf5h2mgprpmhxue69uhhyetvv9uju7npwpehgmmjv5hxgetkqgsd5x03e56tajjyhe6d5jesdkw3mkrtvdpua72vugkyn3h4nqtwt0grqsqqqlstem32ln),
signed with the same key as the GitHub releases. An F-Droid listing is under review
([fdroiddata!42875](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/42875)); per-store
status and details live in [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md).

Verify the APK signature with [AppVerifier](https://github.com/soupslurpr/AppVerifier) before
installing:

```
dev.forgesworn.cambium
9E:A1:88:EF:A9:01:5F:7E:7F:90:E1:88:8F:58:6F:52:7B:2A:0E:8A:6D:CD:B3:99:1E:41:FB:4F:14:EE:EF:C6
```

Releases before 0.2.0 were never published; the 0.2.0 key is the trust root.

## Build

Requires Android SDK (`sdk.dir` in `local.properties`) and JDK 21.

```bash
./gradlew :app:testDebugUnitTest   # unit tests (pairing parser, QR scan validation, NIP-55 request parsing)
./gradlew :app:assembleDebug       # debug APK -> app/build/outputs/apk/debug/
```

Install directly with an attached device:

```bash
./gradlew :app:installDebug
```

## Status

Pairing (scan or paste) with multiple Heartwood identities, NIP-46 client with a kept-warm
session per identity, NIP-55 intent handling (`get_public_key`, `sign_event`, `nip04`/`nip44`
encrypt/decrypt, `decrypt_zap_event`, `current_user` identity selection), a silent
content-provider path that forwards those methods to Heartwood without a visible popup for
already-approved apps, persistent per-app approval or denial, an optional keep-warm foreground
service, a metadata-only activity log, and an optional biometric app lock. Kind-level permissions
live on the signer itself (Heartwood's policy engine, managed via Sapwood), not on the phone.

### Private zaps

`decrypt_zap_event` decodes DIP-03 "private zaps" (a de facto convention used by Damus, Amethyst
and Amber -- not part of the core NIP-57 spec, which explicitly defers zap privacy to future work)
for the **recipient** of a private zap: Cambium unpacks the zap request's encrypted `anon` tag and
asks Heartwood to decrypt it, the same as any other nip04_decrypt.

*Viewing your own sent private zaps is not supported and never will be through Cambium.* DIP-03's
sender-side path needs an ephemeral key derived as `sha256(your raw private key + note id +
created_at)` -- that requires the raw private key itself, which a NIP-46 remote signer like
Heartwood never exposes over the wire. Attempting it just fails as an ordinary decrypt error.

## Support

If Cambium is useful to you, support development via
[GitHub Sponsors](https://github.com/sponsors/TheCryptoDonkey),
[Geyser](https://geyser.fund/project/forgesworn) or [Ko-fi](https://ko-fi.com/brays),
or zap sats over Lightning: `profusemeat89@walletofsatoshi.com`
(Nostr: `darren@600.wtf`).

## Licence

MIT. See [LICENSE](LICENSE).
