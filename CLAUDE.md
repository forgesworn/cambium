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
  Also hosts `ClientKeys` (ephemeral keypair generation) and `npubDisplay` for the same reason:
  they are rust-nostr operations, so everything else calls into this file instead of importing
  `rust.nostr.sdk` directly.
- `nip55/Nip55Request.kt` -- pure Kotlin parser from a plain `RawSignerIntent` data class to a
  sealed `Nip55Request`. JVM-testable; the actual `android.content.Intent` mapping is a single
  private extension function in `SignerActivity.kt`.
- `nip55/SignerActivity.kt` -- exported activity handling `nostrsigner:` intents: approval sheet,
  forwards to `HeartwoodClient`, answers `get_public_key` from the pairing record directly (no
  relay round trip needed once paired).
- `nip55/SignerProvider.kt` -- exported content provider, the NIP-55 "silent" path. Only ever
  answers `get_public_key` (from local state, for already-approved callers); everything else
  returns `null` so the client falls back to the intent. A provider `query()` runs on the
  caller's binder thread and must never block on a relay round trip.
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
- A keep-warm foreground service for the relay connection (each request currently opens its own
  short-lived `NostrConnect` session).
- Richer per-app permissions (kind-level allow/deny); v1 is a single per-package allow set.
- Multi-signer support (v1 pairs exactly one Heartwood).
