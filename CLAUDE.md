# Cambium

Android NIP-55 signer proxy. Holds no user keys; forwards every signing request to a paired
Heartwood hardware signer over NIP-46 (Nostr relays). See `README.md` for the security model and
pairing flow.

## Build & test

```bash
./gradlew :app:testDebugUnitTest   # unit tests: JVM only, no emulator needed
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:installDebug        # install on an attached device/emulator
```

`local.properties` must set `sdk.dir` to the local Android SDK. Requires JDK 21.

## Architecture

```
Amethyst / Primal / Voyage ...
        | NIP-55 (intent + content provider)
     Cambium
        | NIP-46 over relays (NIP-44 envelopes)
   Nostr relay(s)  <--  Heartwood (WiFi-standalone)
```

- `pairing/BunkerUri.kt` -- pure Kotlin `bunker://` URI parser. No Android, no rust-nostr:
  rust-nostr ships native code per ABI and cannot load on a host JVM, so anything touching it is
  unusable in plain unit tests. This file stays JVM-testable so the parser can be exercised fast.
- `pairing/PairingStore.kt` -- persists the single pairing (signer pubkey, relays, secret, our
  ephemeral client key) and the per-app approval set in `EncryptedSharedPreferences`. Calls into
  `signer.ClientKeys` for key generation rather than importing rust-nostr itself (see below).
- `signer/HeartwoodClient.kt` -- **the only file that imports `rust.nostr.sdk`**. Wraps
  `NostrConnect` (rust-nostr's NIP-46 client) behind the `HeartwoodClient` interface so the
  implementation can be swapped or faked without touching pairing storage or the NIP-55 surface.
  Also hosts `ClientKeys` (ephemeral keypair generation), `npubDisplay`, and `HeartwoodSession` for
  the same reason: they are rust-nostr operations, so everything else calls into this file instead
  of importing `rust.nostr.sdk` directly. `HeartwoodSession` is the one application-scoped,
  mutex-guarded, kept-warm `NostrConnect` session shared by `SignerActivity` and `SignerProvider`
  -- a live test against a real device showed a fresh session per request cost multiple seconds
  each. `MainActivity`'s pairing test deliberately uses its own disposable client instead (a
  one-off validation before anything is persisted), then calls `HeartwoodSession.shutdown()` after
  saving so the next real request reconnects against the pairing that was just confirmed.
- `nip55/Nip55Request.kt` -- pure Kotlin parser from a plain `RawSignerIntent` data class to a
  sealed `Nip55Request`. JVM-testable; the actual `android.content.Intent` mapping is a single
  private extension function in `SignerActivity.kt`.
- `nip55/SignerActivity.kt` -- exported, translucent/modal activity (see `Theme.Cambium.Dialog`)
  handling `nostrsigner:` intents: approval sheet, forwards to `HeartwoodClient`, answers
  `get_public_key` from the pairing record directly (no relay round trip needed once paired).
  `singleTop` with `onNewIntent` handling, since a client can fire a second request before the
  user dismisses the first (e.g. `sign_event` right after `get_public_key`).
- `nip55/SignerProvider.kt` -- exported content provider, the NIP-55 "silent" path. A live test
  showed Amethyst queries this provider for *every* operation once an app is approved, not just
  get_public_key, so `SIGN_EVENT`, `NIP04_ENCRYPT`/`DECRYPT`, and `NIP44_ENCRYPT`/`DECRYPT` forward
  to the shared `HeartwoodSession` synchronously inside `query()` (via `runBlocking`, capped at
  15s) for already-approved callers -- acceptable because these clients call `query()` from a
  background thread. `get_public_key` is still declared for discovery but always answers `null`:
  both Amber and Primal force login through the visible intent rather than the silent path.
  `DECRYPT_ZAP_EVENT` is declared (its absence spammed provider-discovery errors on every zap in
  Amethyst's feed) but always answers `null` -- Amber's implementation unwraps a zap request event
  embedded in a zap receipt rather than doing a plain nip44_decrypt, and Cambium does not implement
  that (known gap below). `PING` answers directly for an approved, paired caller. Every other case
  returns `null` so the client falls back to the intent, and the caller is always taken from
  `getCallingPackage`, never from query arguments.
- `nip55/EventJson.kt` -- tiny shared helpers (`extractEventKind`, `extractEventSignatureHex`) used
  by both `SignerActivity` (approval sheet, legacy `signature` extra) and `SignerProvider` (the
  `signature` cursor column for forwarded `SIGN_EVENT`).
- `MainActivity.kt` -- pairing status screen: paste a bunker URI, see connection details, unpair.

## Conventions

- British English in all prose, comments, and UI copy. No exclamation marks; calm, factual tone.
- Kotlin only, coroutines for async work. No Google Play services, no Firebase, no analytics.
- ESM/TypeScript conventions from the wider workspace do not apply here (this is a Kotlin/Gradle
  project) -- Gradle version catalogue (`gradle/libs.versions.toml`) is the source of truth for
  dependency versions.
- Git commits: `type: description` format (e.g. `feat:`, `fix:`, `refactor:`, `docs:`). No
  `Co-Authored-By` lines.

## Known gaps (tracked, not yet built)

- QR pairing (paste-only for now).
- A foreground service to keep the process (and so `HeartwoodSession`) alive proactively; today
  the session is kept warm only while the process happens to be running, not deliberately kept
  alive in the background.
- Richer per-app permissions (kind-level allow/deny); v1 is a single per-package allow set. There
  is also no persistent per-app *denial* yet -- declining a request just doesn't approve it, it
  doesn't block future requests from showing the approval sheet again. `SignerProvider` therefore
  cannot yet distinguish "not yet approved" from "permanently rejected"; both are `null`.
- `DECRYPT_ZAP_EVENT` is not implemented (see `SignerProvider`'s class doc) -- Amber's zap-request
  unwrapping is different from a plain nip44_decrypt and hasn't been built.
- Multi-signer support (v1 pairs exactly one Heartwood).
- NIP-55 single-intent batch requests (the `results` JSON-array response) -- currently each
  intent is handled individually, though `SignerActivity` does handle multiple *separate* intents
  arriving in sequence via `onNewIntent`.
