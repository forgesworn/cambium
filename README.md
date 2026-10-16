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
  URI) and one persistent NIP-46 client keypair, generated on first pairing and shared by all
  pairings (it is cleared only by "Unpair all"). Compromising
  the phone exposes the ability to *request* signatures -- which Heartwood's policy engine and
  physical confirmation button still gate -- never the identity key itself.
- All of the above is stored in Android Keystore-backed `EncryptedSharedPreferences`.
- Per-calling-app approval is tracked locally, bound to an identity and optionally restricted
  by method, event kind and expiry, *in addition to* whatever Heartwood's own slot policy
  enforces. Cambium's local layer is a convenience filter; Heartwood remains the authority on
  what actually gets signed. Existing approvals retain their previous indefinite, whole-app meaning.
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
2. In [Sapwood](https://github.com/forgesworn/sapwood) (desktop or phone handoff): Apps -> Connect an app -> name it (e.g.
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

## Approval and connection controls

Native requests can be approved once, for an hour, for a day, or until the app is forgotten.
An approval can cover all operations, just the requested method (and its exact event kind for
signing), or a chosen set of operations and signing kinds. An operation outside that remembered
scope asks again; a timed approval stops working when it expires. Approving that extra request
once leaves the earlier grant as it was. Public-key discovery remains available for a remembered
identity even when signing or encryption is restricted. Decline only skips the current request;
Always deny blocks the app across identities.

The sheet explains familiar operations in plain language and offers bounded request details for
inspection. Encrypted message content stays hidden. These details are only displayed on that
screen and are never written to the activity log. Website requests have no lifetime or remembered
operation controls and never grant approval to the whole browser.

Each paired identity shows its last successful contact in this process and safe connection
failure reasons. *Test connection* makes an authenticated NIP-46 ping; *Reconnect* rebuilds the
transport on the identity's existing worker, after any in-flight request completes. A past answer
is evidence of contact at that time, not a guarantee the board is still online. Signers that
support NIP-46 `switch_relays` can supply updated transport relays: Cambium verifies the exact
signer's response and persists validated `wss://` endpoints before changing transport. Older
signers remain usable; refresh their pairing from Sapwood when their relay list changes.
The pairing check uses the bunker admission secret. Saved connections resume with the same
authorised client key, including after restart or a relay change, so single-use secrets are
not replayed.

*Rename identity* changes only its local label. A connected app's *Edit* action changes its
operation scope, approval lifetime or identity binding. Changing identity requires confirmation
and preserves the existing scope and expiry. Stale edits cannot overwrite a newer choice.

The activity log records a safe failure category and elapsed time alongside its existing metadata.
Filter it by app, identity or outcome. Local identity references keep renamed identities together
and separate identities that share a label; older entries retain their recorded-label grouping.
*Review diagnostic report* prepares an allowlisted
text report using aliases for apps and identities; it includes no payloads, raw exception messages,
pairing links or keys. Nothing is shared until the owner taps Share and chooses a destination.

### Native batches

Clients can use NIP-55's `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP` flow to queue requests
and receive a `results` JSON array in arrival order, with each request's `id`, result and rejection.
Signing entries contain the signature in `result` and `signature`. Normal single-request extras
remain compatible with existing Amber clients. Batches are bounded to 32 requests, 128 KiB of
incoming request data and 384 KiB of results, with request IDs up to 256 characters.

Android versions before 15 cannot reliably identify the sender of a reused intent. Android 15
also needs the sender to share its identity for attribution. Additional unattributable requests
therefore require visible one-shot approval and cannot borrow the first app's remembered grant.
An attributable sender must match the original result recipient. A non-batch reused intent
cannot replace an existing request. See [Android caller identity](https://developer.android.com/reference/android/app/ComponentCaller).

On a healthy kept-warm session, a fresh signing request takes one relay round trip and commonly
completes in roughly half a second to a couple of seconds. A cold reconnect, contention, or an
unhealthy relay can take longer. Repeat deterministic decrypts and exact duplicate NIP-42 AUTH
requests can be answered from bounded per-identity caches without another hardware round trip.

## For client developers

Cambium's package is `dev.forgesworn.cambium`. It speaks the Amber-compatible NIP-55 surface, in
three forms. A caller must go through the visible intent once so the owner can approve it; only
then does the content provider answer silently.

### Intent (visible path)

Send `Intent.ACTION_VIEW` with a `nostrsigner:` data URI, `setPackage("dev.forgesworn.cambium")`
and start it for a result. The data URI carries the payload (the event JSON, the text or
ciphertext, or nothing for `get_public_key`).

```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$eventJson")).apply {
    setPackage("dev.forgesworn.cambium")
    putExtra("type", "sign_event")
    putExtra("id", "req-1")
    putExtra("current_user", signerNpubOrHex) // optional
}
```

- `type`: `get_public_key`, `sign_event`, `nip04_encrypt`, `nip04_decrypt`, `nip44_encrypt`,
  `nip44_decrypt` or `decrypt_zap_event` (a kind 9734 zap request; recipient side only).
- `id`: optional, echoed back in the result.
- `current_user`: optional signer identity, an `npub` or 64-character hex pubkey. One that names a
  signer Cambium has not paired is never silently swapped for another identity.
- `pubkey` (`pubKey` is also read): the counterparty, required for the four encrypt and decrypt
  types.
- `permissions`: `get_public_key` only, a JSON array of `{ "type": ..., "kind": ... }`. It is shown
  to the owner and pre-authorises nothing.

A successful result carries `result` and echoes `id`. `sign_event` also returns `event` (the signed
event JSON) and `signature` (the bare hex signature). `get_public_key` returns an `npub` in
`result` plus `package`, which is `dev.forgesworn.cambium`. A refusal returns `rejected` set to
`true` and the `id`. Cambium sets `RESULT_OK` for both a success and a refusal, so check for the
extras rather than the result code.
For batching with `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP`, see Native batches above.

### Content provider (silent path)

Once the owner has approved your app, query the provider by authority. The caller is taken from
`getCallingPackage`, so it must be a real `ContentResolver` query from your own package. Arguments
travel in the `projection` array, not in `selectionArgs`:
`[payload, otherPubkey, currentUser]`, with `currentUser` at index 2.

```kotlin
val cursor = contentResolver.query(
    Uri.parse("content://dev.forgesworn.cambium.NIP44_DECRYPT"),
    arrayOf(ciphertext, counterpartyPubkey, currentUser), // currentUser may be ""
    null, null, null,
)
```

| Authority (`content://dev.forgesworn.cambium.` +) | `projection[0]` | `projection[1]` | Answer columns |
| --- | --- | --- | --- |
| `SIGN_EVENT` | event JSON | empty | `result`, `event`, `signature` |
| `NIP04_ENCRYPT`, `NIP44_ENCRYPT` | plaintext | counterparty pubkey | `result` |
| `NIP04_DECRYPT`, `NIP44_DECRYPT` | ciphertext | counterparty pubkey | `result` |
| `DECRYPT_ZAP_EVENT` | kind 9734 zap request JSON | unused | `result` |
| `PING` | unused | unused | `result` (`pong`) |
| `GET_PUBLIC_KEY` | unused | unused | never answers; use the intent |

Read the cursor, not just its first column:

- A `null` cursor means Cambium cannot answer silently: the app is not approved, the request is
  outside its approved methods or kinds, `current_user` is not the identity it is bound to, or an
  argument is missing. Fall back to the intent.
- A row with a `rejected` column (`"true"`) is a terminal refusal: the app is denied, Heartwood's
  policy refused the request, a decrypt can never succeed, a kind 31234 draft was declined, or a
  zap request is not a private zap. Do not retry or fall back.
- A row with `result` empty and `error` set to `signer unavailable` is terminal too: the signer
  was busy, unreachable or took longer than 15 seconds. Do not launch the same request as an
  intent.

### Website (`nostrsigner:` link)

A web page opens a `nostrsigner:` link whose payload is percent-encoded and whose query string
carries `type`. Every website request shows a one-shot approval and is never remembered.

```
nostrsigner:%7B%22kind%22%3A1%2C%22content%22%3A%22hello%22%7D?type=sign_event&callbackUrl=https%3A%2F%2Fexample.com%2Fsigned%3Fevent%3D&returnType=signature&compressionType=none&appName=Example
```

- `callbackUrl`: optional, `https` only (plain `http` is accepted for `localhost`, `127.0.0.1` and
  `::1`), no user info or fragment, at most 4096 characters. Cambium appends the percent-encoded
  result to it and opens it. Without one, the result is copied to the clipboard.
- `returnType`: `signature` (the default, a bare hex signature) or `event` (the signed event
  JSON). `compressionType`: `none` (the default) or `gzip`, which returns `Signer1` followed by
  the base64 of the gzipped event for `sign_event`.
- `appName` (shown to the owner, trimmed to 80 characters), `id`, `current_user`, `pubkey`
  (`pubKey` also read) and `permissions` behave as for the intent. `get_public_key` returns the
  hex pubkey here rather than an `npub`.
- A refused request returns nothing, since NIP-55 defines no rejection callback for websites.

## Phone unlock (Heartwood 0.18.0-beta.17 or later)

A Heartwood that keeps its keys encrypted comes back locked after a power cut. With phone unlock set
up, it asks this phone instead of waiting for Sapwood: a notification says which board restarted,
why (power-on, brownout, watchdog), on which network and how many times, and one tap plus your
phone's screen lock unlocks it. There is no server, no Google push and no third-party app involved.

**Setting it up.** In Sapwood, open *Phones that can unlock* and choose *Add a phone*: it shows a
one-off invite as a QR code. On the paired signer in Cambium, tap *Set up phone unlock*, then
*Scan Sapwood's code* and point the camera at the invite. Cambium answers over the invite's relays,
shows five words to compare with the board's, and you hold the button on the board only if they
match. The board hands this phone its unlock secret, sealed to a key that exists only on that
screen, and Cambium asks for your screen lock to keep it. If the camera is unavailable or Sapwood
cannot scan, tap *Show a code instead*: Cambium shows its own code, which you add in Sapwood under
*Phones that can unlock* before pressing the button on the board. Set up at least two devices, so a
phone lost abroad does not strand the board.

**What the phone holds, per board.**

- The *unlock secret* S. It opens the board's data key, which is useless without the board's own
  flash. It sits under an Android Keystore key that the phone releases only after you
  authenticate, once per unlock: with the phone's own PIN, pattern or password, or a strong
  (class 3) fingerprint or face if you use one (on Android 10 and older, only the biometric).
  StrongBox where the phone has one; destroyed if a biometric is added or changed, or the screen
  lock removed (set it up again afterwards). If you prefer no biometrics on your phone, because a
  finger or face can be forced, a PIN-only phone works.
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
enrolment code could try to answer it first. Cambium shows a six-character check code, large: check
Sapwood shows the same one. If two different answers arrive, Cambium keeps neither.

**Gone quiet.** When the keep-warm toggle is on, Cambium also says when a paired signer stops
answering its scheduled checks (two in a row, eight minutes apart), so you hear about a power cut
before the board is even back. The same foreground service also runs, whatever the toggle says,
while any board is set up for phone unlock, so that the phone can hear lock messages. With the
toggle off it sends no scheduled checks, so it raises no gone-quiet alert.

**Losing the phone.** Revoke its record on the board from Sapwood or over USB. Nothing else on the
board changes. *Forget* in Cambium removes the phone's side; the screen reminds you to do both.

## Install

Grab the signed APK from [GitHub Releases](https://github.com/forgesworn/cambium/releases), or
point [Obtainium](https://github.com/ImranR98/Obtainium) at this repository for automatic update
tracking (one-tap add: `obtainium://add/https://github.com/forgesworn/cambium`). Chrome is not
needed; Cambium runs on any 64-bit Android 8.1+ device, including GrapheneOS (no Google Play
services, no Firebase, no analytics).

Also on [Zapstore](https://zapstore.dev/apps/naddr1qqtxgetk9enx7un8v4ehwmmjdchxxctdvf5h2mgprpmhxue69uhhyetvv9uju7npwpehgmmjv5hxgetkqgsd5x03e56tajjyhe6d5jesdkw3mkrtvdpua72vugkyn3h4nqtwt0grqsqqqlstem32ln),
and [F-Droid](https://f-droid.org/packages/dev.forgesworn.cambium/). Live per-store versions and
details are kept in [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md).

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
persistent per-app approval or denial, an optional keep-warm foreground service (which also runs
while a board is set up for phone unlock), a metadata-only
activity log, an optional biometric app lock, and phone unlock for a Heartwood that restarted
locked (unlock secret behind the Keystore and the phone's screen lock, unfiltered lock
listener, throwaway-key delivery). Kind-level permissions live on the signer itself
(Heartwood's policy engine, managed via Sapwood); Cambium's optional method/kind filter can only
restrict local approval further. Current implementation evidence is recorded in
[docs/FEATURE-ACCEPTANCE.md](docs/FEATURE-ACCEPTANCE.md).

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
