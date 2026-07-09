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
  ephemeral client key) and the per-app permission state in `EncryptedSharedPreferences`. Calls
  into `signer.ClientKeys` for key generation rather than importing rust-nostr itself (see below).

  Per-app state is a tri-state -- `AppPermissionState.APPROVED`/`DENIED`, or `null` meaning "ask"
  -- backed by two independent `StringSet`s (`approve`/`deny` are mutually exclusive; `forget`
  clears both, back to "ask"). The pre-existing approved set (`allowed_packages`) already meant
  exactly "approved" before this model existed, so it needed no migration step -- only the new
  `denied_packages` set is new. `allPermissionStates()` backs `MainActivity`'s connected-apps list.
- `signer/HeartwoodClient.kt` -- **the only file that imports `rust.nostr.sdk`**. Wraps
  `NostrConnect` (rust-nostr's NIP-46 client) behind the `HeartwoodClient` interface so the
  implementation can be swapped or faked without touching pairing storage or the NIP-55 surface.
  Also hosts `ClientKeys` (ephemeral keypair generation), `npubDisplay`, and `HeartwoodSession` for
  the same reason: they are rust-nostr operations, so everything else calls into this file instead
  of importing `rust.nostr.sdk` directly.

  `HeartwoodSession` is the one application-scoped, kept-warm `NostrConnect` session shared by
  `SignerActivity` and `SignerProvider` -- a live test against a real device showed a fresh
  session per request cost multiple seconds each. Every call is handed to exactly one dedicated
  worker coroutine on its own single-thread dispatcher, running in a `CoroutineScope` with no
  parent, so nothing a caller does can ever cancel work already handed to it; a caller gets its
  result via a `CompletableDeferred` and only ever cancels *its own wait* on that, never the
  underlying job. This exists because of a second live-test finding: under a burst of ~10
  concurrent provider queries (Amethyst querying while the user typed a reply), the earlier
  mutex-guarded design still let a caller's own `withTimeoutOrNull` cancel the very coroutine that
  was mid-FFI-call, since a mutex only serialises *entry*, not the calling coroutine's own
  cancellation reaching through it. Two requests came back `Protocol(unauthorised)` (consistent
  with a half-torn-down call) and the process died outright once with no Java exception --
  suspected native wedge from a cancelled in-flight rust-nostr call, which is not documented as
  cancellation-safe. `trySilent` (used by `SignerProvider`) additionally sheds load: if 3 calls are
  already queued or running, a new silent-path request is refused immediately rather than joining
  the queue. `RustNostrHeartwoodClient` itself also wraps every actual FFI call in
  `withContext(NonCancellable + Dispatchers.IO)` as a second, independent line of defence, since
  rust-nostr's own `NostrConnect` constructor already takes a `Duration` that bounds each relay
  round trip internally -- there was never a need for a JVM-side timeout that could cancel the
  call out from under the native code.

  `MainActivity`'s pairing test deliberately uses its own disposable client instead of
  `HeartwoodSession` (it is testing a URI that has not been saved yet, so there is no `Pairing` to
  key a shared session on), then calls `HeartwoodSession.shutdown()` after saving so the next real
  request reconnects against the pairing that was just confirmed. It is not routed through the
  worker queue -- it is a single, foreground, user-initiated action that never overlaps with
  itself -- but it does get the same `NonCancellable` protection as everything else.

  Continued daily use surfaced a third finding: Amethyst re-requests the same nip04/nip44 decrypt
  repeatedly while browsing, including legacy content that will never decrypt ("Could not
  decrypt" items), each retry costing a full round trip. Both `trySilent` and `withClient` now
  consult `DecryptCache` (see `DecryptCache.kt`) *before* the queue at all when the caller passes
  a `CacheableDecrypt` key -- a hit answers instantly, never reaching the worker. Successes are
  always cached; failures are cached only when `isDeterministicDecryptFailure` recognises the
  firmware's "decryption failed" wording -- a timeout, a connect error, or an "unauthorised"
  refusal are all transient/repairable and must never be cached. Signs and encrypts are never
  cacheable (nonce freshness). `HeartwoodSession.shutdown()` clears the cache alongside the
  session. Admission-control shedding (queue already at `MAX_QUEUED`) logs a rate-limited summary
  line (about once a minute) so future tuning of the queue depth has real data to work from.
- `signer/DecryptCache.kt` -- pure Kotlin (no Android), JVM-testable: a small in-memory LRU
  (~512 entries, keyed on method + counterparty pubkey + a sha-256 of the payload so the key size
  is bounded regardless of payload length) plus `isDeterministicDecryptFailure`, the classifier
  that decides whether a failure is safe to cache.
- `nip55/Nip55Request.kt` -- pure Kotlin parser from a plain `RawSignerIntent` data class to a
  sealed `Nip55Request`. JVM-testable; the actual `android.content.Intent` mapping is a single
  private extension function in `SignerActivity.kt`.
- `nip55/SignerActivity.kt` -- exported activity handling `nostrsigner:` intents. Genuinely
  invisible for a caller with *any* remembered choice, approved or denied: `Theme.Cambium.Invisible`
  is swapped in via `setTheme` before any window setup, no content view is ever set. An approved
  caller's request runs on `HeartwoodSession`'s worker with a `setResult`/`finish()` at the end; a
  denied one is rejected immediately, no Heartwood call at all -- daily use showed the
  translucent/dimmed `Theme.Cambium.Dialog` overlay (still used only for a caller with *no*
  remembered choice yet) appearing on every subsequent request otherwise, not just at login. This
  decision (`silent`/`permissionState`) is made once in `onCreate` and reused for the instance's
  lifetime, not re-evaluated in `onNewIntent`, since swapping a visible theme for an invisible one
  after the window already exists is unreliable. `singleTop` with `onNewIntent` handling, since a
  client can fire a second request before the user dismisses the first (e.g. `sign_event` right
  after `get_public_key`).

  The approval sheet (shown only for "ask") has an "always deny this app" link below the normal
  Approve/Decline buttons -- a deliberately secondary affordance so a permanent block needs its own
  action, not an accidental extra tap on Decline. It also renders `get_public_key`'s optional
  `permissions` extra (see `nip55/RequestedPermissions.kt`) as a summary line with a note that
  Heartwood's own `ClientPolicy` is the actual authority -- display only, Cambium does not
  pre-authorise anything from this list.

  `silentBackPressBlock`, a no-op `OnBackPressedCallback`, is enabled only for the two silent
  forwarding windows (`handle`, `handleDecryptZapEvent`, only when `silent`) and reset to disabled
  at the top of every `handleIncomingIntent` call. A stray back-press finishing the activity
  mid-request would not stop the underlying call -- it keeps running on `HeartwoodSession`'s
  worker regardless -- but `setResult`/`finish()` need a live activity to deliver the outcome to,
  so a lost activity means a silently lost result. The visible Approve/Decline sheet's back-press
  behaviour (same as Decline, Android's default) is unchanged, since the callback is never enabled
  on that path.
- `nip55/SignerProvider.kt` -- exported content provider, the NIP-55 "silent" path. A live test
  showed Amethyst queries this provider for *every* operation once an app is approved, not just
  get_public_key, and can burst around ten concurrent queries while the user is typing (drafts
  plus decrypts). `SIGN_EVENT`, `NIP04_ENCRYPT`/`DECRYPT`, and `NIP44_ENCRYPT`/`DECRYPT` forward to
  `HeartwoodSession.trySilent` (admission-controlled, capped at 15s -- see `HeartwoodSession`'s
  class doc) for already-approved callers -- acceptable because these clients call `query()` from
  a background thread. `get_public_key` is still declared for discovery but answers `null` for anyone
  the user has not denied (a denied caller gets the terminal `rejected` even here): both Amber
  and Primal force login through the visible intent rather than the silent path.
  `DECRYPT_ZAP_EVENT` decodes the DIP-03 "private zap" `anon` tag locally (see `nip57/PrivateZap.kt`
  below) and forwards the result as an ordinary nip04_decrypt; a public zap (no `anon` tag) or a
  malformed one answers `rejected` immediately, no relay round trip. `PING` answers directly for an
  approved, paired caller.

  `SIGN_EVENT` declines NIP-37 draft events (kind 31234) immediately, without forwarding or
  joining the queue: Amethyst auto-saves a draft roughly every 2s while typing, which floods a
  1-2s hardware round trip and buries real requests behind it. An explicit policy refusal from
  Heartwood (error text containing "unauthorised"/"unauthorized"/"not allowed"/"refused"/"denied")
  or a deterministic decrypt failure (see `DecryptCache.kt`'s `isDeterministicDecryptFailure`, also
  used for an invalid decrypted zap -- see below) answers a `rejected` cursor rather than `null`,
  so a client stops re-escalating a blocked or unrecoverable request to the visible flow every
  couple of seconds. All three are answered with a distinct `rejected` cursor column that clients
  should treat as terminal (no intent fallback). `NIP04_DECRYPT`/`NIP44_DECRYPT` results, and
  `DECRYPT_ZAP_EVENT`'s under its own `CacheableDecrypt.Method.ZAP` namespace, are cached by
  `HeartwoodSession` before admission control -- see its class doc. Everything else that cannot be
  answered here -- an unapproved/unpaired caller, a missing argument, the worker queue being full,
  a timeout, or any other failure -- returns `null` so the client falls back to the intent. The
  caller is always taken from `getCallingPackage`, never from query arguments. Diagnostic logging
  (tag `CambiumProvider`) covers every refusal path and timing for each forward, added during
  live-device debugging and kept deliberately.

  A caller with a *remembered denial* gets `rejected` immediately, for every authority, without
  ever resolving a pairing or touching the queue -- distinct from a caller with no remembered
  choice yet (`null`, "try the intent", where they see the approval sheet). `resolveCaller`/
  `withApprovedCaller` centralise this tri-state check so every `query*` method gets it uniformly.
- `nip55/RequestedPermissions.kt` -- pure Kotlin parser for `get_public_key`'s optional
  `permissions` extra (a JSON array of `{ "type": ..., "kind": ... }`) into a display summary for
  the first-approval sheet. Cambium does not pre-authorise anything from this list; it is shown so
  the user knows what was asked for, with a note that Heartwood's `ClientPolicy` remains the actual
  authority.
- `nip57/Bech32.kt` -- pure Kotlin BIP-173 bech32 encode/decode, deliberately without the spec's
  90-character length recommendation (which exists for Bitcoin-address readability, not
  cryptographic reasons) -- DIP-03's private-zap `anon` tag payload routinely exceeds it. Tested
  against the real bech32 strings in DIP-03's own worked example, not just round-tripped against
  its own `encode()`.
- `nip57/PrivateZap.kt` -- decodes DIP-03's "private zap" `anon` tag (a de facto convention, not in
  the core NIP-57 spec, which explicitly defers zap privacy to "future work") into a plain
  nip04_decrypt call, and validates the decrypted plaintext is a kind 9733 event. Pure Kotlin (uses
  `kotlinx.serialization.json`, already a dependency, rather than `org.json`, to stay consistent
  with the rest of the JVM-testable modules and avoid depending on AGP's unit-test Android stubs
  behaving like the real implementation). **Sender**-side private-zap decoding (recognising your
  own anonymous zaps by regenerating the ephemeral key via `sha256(privkey + noteId + createdAt)`)
  is a known, permanent limitation, not a gap to close later: it needs the raw private key fed
  directly into a hash function, which NIP-46 remote signing has no operation for and Heartwood
  will never expose. Only the recipient path is implemented.
- `nip55/EventJson.kt` -- tiny shared helpers (`extractEventKind`, `extractEventSignatureHex`) used
  by both `SignerActivity` (approval sheet, legacy `signature` extra) and `SignerProvider` (the
  `signature` cursor column for forwarded `SIGN_EVENT`).
- `pairing/QrPairingScan.kt` -- pure Kotlin (no Android, no zxing), same JVM-testable pattern as
  `BunkerUri.kt`: turns a raw scan result string into `Accepted`/`Rejected`/`Cancelled`. `null`
  (zxing's cancelled-scan result) maps to `Cancelled` (no error shown); a `nostrconnect://` link
  gets a distinct rejection message, since that is the client-initiated direction Sapwood's
  bunker-URI QR never produces and Cambium never consumes.
- `MainActivity.kt` -- pairing status screen: scan a QR (via `zxing-android-embedded`'s
  `ScanContract`/`ScanOptions` ActivityResult API -- QR-only, no beep, orientation locked, prompt
  in our tone) or paste the bunker URI text, see connection details, unpair. A successful scan
  pairs immediately (fills the field and calls the same `connectAndSave` path as pressing Pair,
  so it is one tap total). `CAMERA` is a runtime permission requested only on the Scan QR tap;
  denial shows a static inline hint that pasting still works and is never re-prompted
  automatically -- the system's own permission dialog already refuses to reappear after a user
  denies twice, so there is no custom "don't ask again" tracking needed on top of that. The
  library's own `CaptureActivity` is pulled in entirely via manifest merger (not exported --
  it declares no intent filter, so it defaults closed) and is pure Java (`com.google.zxing:core`),
  no Google Play services, matching the GrapheneOS target. Also hosts the "Keep connection warm"
  toggle -- see `service/HeartwoodKeepAliveService.kt` -- requesting `POST_NOTIFICATIONS` on API 33+
  only when the toggle is switched on, with the same permanent-decline-shows-a-static-hint pattern
  as the camera permission; and a connected-apps list (package, state, a "Forget" action that calls
  `PairingStore.forget` -- back to "ask") built from `PairingStore.allPermissionStates()`, one
  `item_connected_app.xml` row inflated per entry rather than a `RecyclerView`, since the list is
  realistically tiny (a handful of paired Nostr clients).
- `AndroidExtensions.kt` -- `PackageManager.displayNameFor(packageName)`, the app-label lookup
  (falls back to the raw package string on any failure) shared by `SignerActivity`'s approval sheet
  and `MainActivity`'s connected-apps list.
- `service/HeartwoodKeepAliveService.kt` -- optional, off-by-default foreground service that keeps
  the process (and so `HeartwoodSession`'s warm `NostrConnect`) alive between requests, closing the
  previously-tracked "only warm while the process happens to be running" gap. Pings Heartwood every
  4 minutes via `HeartwoodSession.withClient(pairing) { it.getPublicKey() }` -- a read-only,
  always-safe operation Heartwood answers without a physical button; rust-nostr's client bindings
  have no lower-level "ping" primitive to call instead (checked directly against the AAR with
  `javap`: neither `NostrConnect` nor `NostrConnectInterface` declare one). `targetSdk` 35 requires
  an explicit `foregroundServiceType`; there is no built-in type for "hold a NIP-46 connection
  open", so this follows Amber's own `ConnectivityService` (verified against its actual source,
  `greenart7c3/Amber` `service/ConnectivityService.kt` and manifest): `specialUse`, declared in the
  manifest with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` description and passed explicitly to
  `ServiceCompat.startForeground` on API 34+, where the type argument became mandatory. Low-
  importance, non-badged notification channel; the toggle and its state live in `PairingStore`
  (`isKeepAliveEnabled`/`setKeepAliveEnabled`), not the service, so `MainActivity` and
  `service/BootReceiver.kt` (restarts the service after a reboot, gated on both the toggle and
  still being paired) agree on state without talking to each other directly.

## Conventions

- British English in all prose, comments, and UI copy. No exclamation marks; calm, factual tone.
- Kotlin only, coroutines for async work. No Google Play services, no Firebase, no analytics.
- ESM/TypeScript conventions from the wider workspace do not apply here (this is a Kotlin/Gradle
  project) -- Gradle version catalogue (`gradle/libs.versions.toml`) is the source of truth for
  dependency versions.
- Git commits: `type: description` format (e.g. `feat:`, `fix:`, `refactor:`, `docs:`). No
  `Co-Authored-By` lines.

## Known gaps (tracked, not yet built)

- Richer per-app permissions are still kind-agnostic (a whole app is approved or denied, not
  individual `kind`s or methods); v1's tri-state (`AppPermissionState`) covers the whole-app case.
  The one-time "Decline" button on the approval sheet still does not remember anything -- only the
  separate "always deny" link does; a plain Decline shows the sheet again next time.
- `decrypt_zap_event` only implements the recipient path (decoding a private zap sent *to* the
  paired identity). The sender path -- recognising your own anonymous zaps by regenerating the
  ephemeral key -- needs the raw private key hashed directly, which is permanently impossible over
  NIP-46 (see `nip57/PrivateZap.kt`'s class doc), not a gap that will be closed later. The
  decrypted plaintext is checked for `"kind": 9733` but its signature is not verified.
- The decrypt cache (`DecryptCache`) has no eviction on staleness beyond LRU size, and no
  per-pairing partitioning -- unpairing clears it entirely via `HeartwoodSession.shutdown()`, but
  re-pairing with a *different* signer inside the same process would need the same clear (already
  covered, since `save()` in `MainActivity` also calls `shutdown()`), and there is no defence
  against a cached plaintext outliving its usefulness if the counterparty rotates keys.
- Multi-signer support (v1 pairs exactly one Heartwood).
- NIP-55 single-intent batch requests (the `results` JSON-array response) -- currently each
  intent is handled individually, though `SignerActivity` does handle multiple *separate* intents
  arriving in sequence via `onNewIntent`.
- The NIP-37 draft-decline (kind 31234) is a hardcoded constant in `SignerProvider`, not a user
  setting; `HeartwoodSession`'s queue depth (3), timeouts (15s silent, 20s intent), and
  `DecryptCache`'s size (512 entries) are likewise hardcoded rather than configurable. The queue
  depth in particular has not been revisited since the cache landed -- the shed-count log line
  (tag `HeartwoodSession`, about once a minute when shedding) exists specifically to gather real
  data on whether 3 is still the right number now that repeat decrypts rarely reach the queue.
- The suspected root cause of the one observed process death (concurrent + cancelled rust-nostr
  calls corrupting native state) is a diagnosis from live-test symptoms, not a confirmed repro in
  a controlled setting -- the single-worker gate and `NonCancellable` wrapping address every
  mechanism that could produce it, but there is no automated test that reproduces the crash to
  verify the fix against.
