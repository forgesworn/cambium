# Cambium

[![GitHub Sponsors](https://img.shields.io/github/sponsors/TheCryptoDonkey?logo=githubsponsors&color=ea4aaa&label=Sponsor)](https://github.com/sponsors/TheCryptoDonkey)

An Android [NIP-55](https://github.com/nostr-protocol/nips/blob/master/55.md) signer that holds
no user keys. Cryptographic results come from your [Heartwood](https://github.com/forgesworn/heartwood-esp32) hardware
signer over [NIP-46](https://github.com/nostr-protocol/nips/blob/master/46.md) (Nostr relays), with
only safe, exact repeats answered from Cambium's bounded caches.
The name follows the tree: cambium is the living layer between bark and the wood.

## Why

Amethyst, Primal and most other Amber-compatible Android Nostr clients cannot log in to a remote
NIP-46 bunker directly, but they all support signing in via any installed NIP-55 external signer.
Cambium fills that gap: it registers as a signer, but it is not one. It is a thin proxy that turns
uncached NIP-55 intents into NIP-46 requests against your Heartwood, and hands the response straight
back.

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
- **Phone unlock is the one exception, and it is opt-in.** A board set up to be unlocked from this
  phone gives it an *unlock secret* for that board (see below). It is not an identity key and signs
  nothing.

```
Android apps              Websites
        | NIP-55 native      | nostrsigner: callback / clipboard
        +--------------------+
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

Websites that support NIP-55 can open Cambium with a `nostrsigner:` link. Website requests always
show a one-shot approval and are never remembered for the whole browser. Cambium returns the result
to a validated HTTPS callback (or localhost during development), or copies it to the clipboard when
the website did not provide a callback.

On a healthy kept-warm session, a fresh signing request takes one relay round trip and commonly
completes in roughly half a second to a couple of seconds. A cold reconnect, contention, or an
unhealthy relay can take longer. Repeat deterministic decrypts and exact duplicate NIP-42 AUTH
requests can be answered from bounded per-identity caches without another hardware round trip.

## Phone unlock (Heartwood 0.18.0-beta.17 or later)

A Heartwood that keeps its keys encrypted comes back locked after a power cut. With phone unlock set
up, it asks this phone instead of waiting for Sapwood: a notification says which board restarted,
why (power-on, brownout, watchdog), on which network and how many times, and one tap plus a
fingerprint unlocks it. There is no server, no Google push and no third-party app involved.

**Setting it up.** On a paired signer, tap *Set up phone unlock*. Cambium shows a code. In
Sapwood, add the phone under *Phones that can unlock* by scanning it, then press the button on the
board. (Until Sapwood's panel ships, heartwood-esp32's `scripts/phone-unlock.mjs enrol-for --code
'<code>'` does the same over USB.) The board hands this phone its unlock secret, sealed to a key that exists only on that
screen, and Cambium asks for a fingerprint to keep it. Set up at least two devices, so a phone lost
abroad does not strand the board.

**What the phone holds, per board.**

- The *unlock secret* S. It opens the board's data key, which is useless without the board's own
  flash. It sits under an Android Keystore key that only a **strong biometric** releases, once per
  unlock: no PIN fallback, StrongBox where the phone has one, and destroyed if the enrolled
  fingerprints or faces change (set it up again afterwards).
- A *phone key* K derived from S, in encrypted storage without a biometric, so Cambium can
  recognise and read the board's lock messages in the background. K cannot unlock the board, but
  whoever has it can write a convincing fake lock message for this phone; tapping that would send
  S to them. The warning below applies to that as much as to a stolen board.

**Always a tap.** The board has no flash encryption or secure boot, so whoever holds it can make it
ask to be unlocked, on any network, with any story. The notification says so every time. Only
unlock when you expect it. Cambium never unlocks on its own, and there is no setting to make it.

**No stable phone identifier on the wire.** A locked board's message carries no tag naming this
phone, only a hint that changes with every restart. Cambium reads *every* lock message on its
relays and matches locally, so a subscription says nothing about which board it waits for. Each
unlock is sent from a fresh throwaway key, to that board's own relays only. Cambium never contacts
the board because a lock message arrived. What a relay can still see: this phone's IP address; that
the connection which just sent an unlock answered one particular lock message, so the relay can
tie that IP to that board's restart; and, if Cambium's ordinary NIP-46 pairing uses the same relay,
the same IP talking to the signer. Use relays you run, or a VPN or Tor on the phone, if that
matters to you.

The enrolment hand-off is not signed by anything the phone already trusts, so anyone who sees the
enrolment code could try to answer it first. Cambium shows the board record number it received:
check Sapwood shows the same one. If two different answers arrive, Cambium keeps neither.

**Gone quiet.** When the keep-warm service is on, Cambium also says when a paired signer stops
answering its scheduled checks (two in a row, eight minutes apart), so you hear about a power cut
before the board is even back.

**Losing the phone.** Revoke its record on the board from Sapwood or over USB. Nothing else on the
board changes. *Forget* in Cambium removes the phone's side; the screen reminds you to do both.

## Install

Grab the signed APK from [GitHub Releases](https://github.com/forgesworn/cambium/releases), or
point [Obtainium](https://github.com/ImranR98/Obtainium) at this repository for automatic update
tracking (one-tap add: `obtainium://add/https://github.com/forgesworn/cambium`). Chrome is not
needed; Cambium runs on any 64-bit Android 8.1+ device, including GrapheneOS (no Google Play
services, no Firebase, no analytics).

Also on [Zapstore](https://zapstore.dev/apps/naddr1qqtxgetk9enx7un8v4ehwmmjdchxxctdvf5h2mgprpmhxue69uhhyetvv9uju7npwpehgmmjv5hxgetkqgsd5x03e56tajjyhe6d5jesdkw3mkrtvdpua72vugkyn3h4nqtwt0grqsqqqlstem32ln),
with v0.4.3 published from the same signing key as the GitHub release. An F-Droid listing is under
review ([fdroiddata!42875](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/42875)); live
per-store status and details are kept in [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md).

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
session per identity, NIP-55 native and web-intent handling (`get_public_key`, `sign_event`,
`nip04`/`nip44` encrypt/decrypt, `decrypt_zap_event`, `current_user` identity selection), HTTPS
callback and clipboard delivery for websites, a silent
content-provider path that forwards those methods to Heartwood without a visible popup for
already-approved apps, and terminal unavailable responses that prevent a technical failure from
amplifying into foreground popups. Per-identity bounded priority queues reserve capacity for user
work. Exact NIP-42 AUTH duplicates coalesce and cache briefly; at most one distinct AUTH challenge
is admitted per identity, it is never retried internally, and a transport failure opens a 60-second
AUTH circuit while ordinary signing, reactions, and encryption remain available. Cambium also has
persistent per-app approval or denial, an optional keep-warm foreground service, a metadata-only
activity log, an optional biometric app lock, and phone unlock for a Heartwood that restarted
locked (biometric-bound unlock secret, unfiltered lock listener, throwaway-key delivery). Kind-level permissions live on the signer itself
(Heartwood's policy engine, managed via Sapwood), not on the phone.

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
(Nostr: `npub1mgvlrnf5hm9yf0n5mf9nqmvarhvxkc6remu5ec3vf8r0txqkuk7su0e7q2`).

## Licence

MIT. See [LICENSE](LICENSE).
