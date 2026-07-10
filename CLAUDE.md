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

`checkXmlCommentHyphens` (in `app/build.gradle.kts`, wired into `preBuild` -- runs on every build,
not a separate step to remember) fails the build if any XML comment under `src/main` contains a
literal `--`, which is illegal per the XML spec and something Android's resource merger/data-binding
parser reject outright rather than warn about. A cheap guard: this exact mistake (an em-dash-style
`--` used as a sentence separator inside a multi-line comment) was hand-introduced and caught only
by the build itself failing several times over the course of review.

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
- `pairing/PairingStore.kt` -- persists every paired Heartwood identity (signer pubkey, relays,
  secret, an optional user label) and the per-app permission state in `EncryptedSharedPreferences`,
  each as a single encrypted JSON blob (`kotlinx.serialization`) rather than one preference key per
  field. Calls into `signer.ClientKeys` for key generation rather than importing rust-nostr itself
  (see below). All pairings currently share **one** Cambium client keypair (`ensureClientKeys`) --
  one NIP-46 client identity presented to every paired bunker, not one per identity -- generated
  once and reused; `Pairing.clientSecretKeyHex`/`clientPublicKeyHex` carry the same value on every
  entry, kept per-`Pairing` so single-`Pairing` call sites don't need a second lookup.

  Per-app state is a tri-state -- `AppPermissionState.APPROVED`/`DENIED`, or `null` meaning "ask"
  -- plus, for an approval, which paired identity it was approved for (`AppPermission.boundIdentityPubkeyHex`;
  denial carries no binding, since it blocks the app outright regardless of identity).
  `approve`/`deny`/`forget` are one-line callers of a private `setPermission(packageName, permission: AppPermission?)`.

  `addPairing(bunkerUri, label)` upserts by signer pubkey (re-pairing refreshes relays/secret in
  place rather than duplicating; omitting `label` on a re-pair keeps whatever label the entry
  already had). `removePairing(signerPubkeyHex)` also forgets any app bound to that identity --
  keeping a remembered approval bound to an identity that no longer exists risks a future silent
  fallback to a *different* identity (see `IdentityRouting`), so it is cleared outright rather than
  left dangling. `clearAll()` is a full reset (every pairing, every permission, the keep-alive
  toggle, and the client keypair itself) -- the same "forget everything" `MainActivity`'s "Unpair
  all" action triggers, matching what unpairing the single 0.2.x pairing used to do.

  `ensureMigrated` transparently upgrades 0.2.x's single-pairing schema (flat `signer_pubkey_hex`/
  `relays`/`secret` keys, `allowed_packages`/`denied_packages` `StringSet`s) to the list-of-pairings
  schema on first read after an upgrade, checked once per `PairingStore` instance and a no-op
  forever after the new JSON key exists. The actual transform is pure and lives in
  `PairingMigration.kt` so it is JVM-testable independent of `EncryptedSharedPreferences`; this
  class's job is just reading the old flat keys, calling it, and writing the result -- the new JSON
  and the removal of every old key happen in one `Editor`/`apply()`, so a process death mid-migration
  cannot leave the store readable under neither schema.
- `pairing/PairingMigration.kt` -- pure Kotlin (no Android): the 0.2.x-to-0.3.0 schema transform
  itself, see `PairingStore.ensureMigrated`. The migrated pairing gets no label (falls back to a
  truncated npub for display). Every previously-approved package migrates bound to the migrated
  pairing's identity, since it was the only identity that existed; denied packages carry no
  binding, matching denial's identity-agnostic meaning everywhere else.
- `pairing/IdentityRouting.kt` -- pure Kotlin (no Android): decides which paired identity a NIP-55
  request routes to, shared by `SignerActivity` and `SignerProvider`. `normaliseCurrentUser`
  accepts NIP-55's `current_user` extra as either an npub or 64-hex pubkey (Amber accepts both),
  decoding npub via `Bech32.decode(_, expectedHrp = "npub")`; garbage input and "a well-formed
  pubkey we don't have" are deliberately indistinguishable to `resolve` -- both refuse rather than
  guess. `resolve`'s precedence: an explicit `current_user` match wins outright; otherwise the
  calling app's bound identity; otherwise the sole pairing, if there is exactly one. A
  `current_user` naming an identity we don't have is `Result.UnknownCurrentUser`, never falling
  through to the binding or the sole pairing -- signing with a different identity than explicitly
  named would be worse than refusing. Two or more pairings with neither a match nor a binding is
  `Result.Ambiguous` for the same reason. Both callers collapse every non-`Resolved` outcome to
  "defer/ask" -- `SignerProvider` has no way to ask which identity was meant from the silent path
  at all, and `SignerActivity` can only show the picker on the *first* intent of an activity
  instance (see `SignerActivity`'s `decideSilent`).
- `signer/HeartwoodClient.kt` -- **the only file that imports `rust.nostr.sdk`**. Wraps
  `NostrConnect` (rust-nostr's NIP-46 client) behind the `HeartwoodClient` interface so the
  implementation can be swapped or faked without touching pairing storage or the NIP-55 surface.
  Also hosts `ClientKeys` (ephemeral keypair generation), `npubDisplay`, and `HeartwoodSession` for
  the same reason: they are rust-nostr operations, so everything else calls into this file instead
  of importing `rust.nostr.sdk` directly. `Pairing.displayLabel()` (`label` if the user set one,
  else `npubDisplay(signerPubkeyHex)`) lives here too, next to `npubDisplay` -- the one place
  display falls back, so `MainActivity`'s pairing list, its connected-apps rows, and the approval
  sheet's identity picker all agree on what a pairing is called.

  `HeartwoodSession` is a registry of per-identity sessions, keyed by signer pubkey, shared by
  `SignerActivity` and `SignerProvider` -- from 0.3.0 on Cambium pairs more than one Heartwood
  identity, and each one's NIP-46 connection, admission control, and decrypt cache must stay fully
  isolated: a burst against identity A must never shed or slow down identity B, and a decrypt
  cached while talking to A must never leak into B's answers. The private `Session` class is
  exactly the single-pairing design this object used to *be* before 0.3.0, now instantiated once
  per signer pubkey (`sessions.computeIfAbsent(pairing.signerPubkeyHex) { Session(it) }`) instead of
  once for the whole app -- every invariant below still holds, just scoped to one identity's worker
  instead of the app's only worker. A live test against a real device showed a fresh session per
  request cost multiple seconds each, hence keeping one warm at all. `computeIfAbsent`
  specifically, not Kotlin's `getOrPut` extension: `getOrPut` is plain get-then-put with no
  atomicity of its own even on a `ConcurrentHashMap`, so two threads racing it for the same
  brand-new identity (a first-time burst of concurrent requests against a freshly paired identity
  is exactly the trigger) could each construct a `Session` (spinning up its own worker thread)
  before either `put()` ran -- the loser's `Session`, and its thread, would be silently overwritten
  in the map and leaked, with nothing left holding a reference to ever shut it down.
  `computeIfAbsent` is atomic per key on a real `ConcurrentHashMap`. `Session` takes the signer
  pubkey as a constructor parameter purely to give itself a short log tag (`recordShed`'s "shed:
  queue full" line otherwise had no way to say *which* identity's queue) -- the registry's map key
  lives in the outer `HeartwoodSession` object, not in `Session` itself.

  `trySilent`/`withClient` return a `HeartwoodOutcome<String>` (`.result` is the same
  `HeartwoodResult<String>` either call always returned pre-0.3.0), not a bare `HeartwoodResult`,
  so that a `Cached` answer is distinguishable from a `Fresh` one -- the activity log
  (`log/ActivityLog.kt`'s `outcomeFor`) needs an accurate `SIGNED`-vs-`ANSWERED_FROM_CACHE`
  outcome rather than a guess, and there was previously no way for a caller to tell the two apart.

  Within a `Session`, every call is handed to exactly one dedicated worker coroutine on its own
  single-thread dispatcher, running in a `CoroutineScope` with no parent, so nothing a caller does
  can ever cancel work already handed to it; a caller gets its result via a `CompletableDeferred`
  and only ever cancels *its own wait* on that, never the underlying job. This exists because of a
  live-test finding: under a burst of ~10 concurrent provider queries (Amethyst querying while the
  user typed a reply), an earlier mutex-guarded design still let a caller's own `withTimeoutOrNull`
  cancel the very coroutine that was mid-FFI-call, since a mutex only serialises *entry*, not the
  calling coroutine's own cancellation reaching through it. Two requests came back
  `Protocol(unauthorised)` (consistent with a half-torn-down call) and the process died outright
  once with no Java exception -- suspected native wedge from a cancelled in-flight rust-nostr call,
  which is not documented as cancellation-safe. `trySilent` (used by `SignerProvider`) additionally
  sheds load *per identity*: if 3 calls are already queued or running against that one identity's
  worker, a new silent-path request against it is refused immediately rather than joining the
  queue -- a burst against one paired signer can never shed a request against another.
  `RustNostrHeartwoodClient` itself also wraps every actual FFI call in
  `withContext(NonCancellable + Dispatchers.IO)` as a second, independent line of defence, since
  rust-nostr's own `NostrConnect` constructor already takes a `Duration` that bounds each relay
  round trip internally -- there was never a need for a JVM-side timeout that could cancel the
  call out from under the native code.

  `MainActivity`'s pairing test deliberately uses its own disposable client instead of
  `HeartwoodSession` (it is testing a URI that has not been saved yet, so there is no persisted
  `Pairing` to key a session on), then calls `HeartwoodSession.shutdown(signerPubkeyHex)` after
  saving so the next real request against that identity reconnects fresh rather than reusing a
  stale client -- relevant on a re-pair; a brand new identity has no session to discard yet. It is
  not routed through any worker queue -- it is a single, foreground, user-initiated action that
  never overlaps with itself -- but it does get the same `NonCancellable` protection as everything
  else. `shutdownAll()` drops every identity's session at once, used by `MainActivity`'s "Unpair
  all" action (`PairingStore.clearAll()`).

  Continued daily use surfaced a third finding: Amethyst re-requests the same nip04/nip44 decrypt
  repeatedly while browsing, including legacy content that will never decrypt ("Could not
  decrypt" items), each retry costing a full round trip. Both `trySilent` and `withClient` now
  consult `DecryptCache` (see `DecryptCache.kt`) *before* the queue at all when the caller passes
  a `CacheableDecrypt` key -- a hit answers instantly, never reaching the worker. Successes are
  always cached; failures are cached only when `isDeterministicDecryptFailure` recognises the
  firmware's "decryption failed" wording -- a timeout, a connect error, or an "unauthorised"
  refusal are all transient/repairable and must never be cached. Signs and encrypts are never
  cacheable (nonce freshness). Each `Session` instantiates its own `DecryptCache` in its
  constructor, which is what makes the cache partitioned per pairing -- there is no shared cache
  instance for two identities' entries to collide in, by construction, not by a key that happens to
  include the signer pubkey. `HeartwoodSession.shutdown(signerPubkeyHex)` clears that one
  identity's cache alongside its session. Admission-control shedding (queue already at
  `MAX_QUEUED`, per identity) logs a rate-limited summary line (about once a minute) so future
  tuning of the queue depth has real data to work from.
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
  remembered choice yet) appearing on every subsequent request otherwise, not just at login.
  `singleTop` with `onNewIntent` handling, since a client can fire a second request before the
  user dismisses the first (e.g. `sign_event` right after `get_public_key`).

  `silent` is decided once in `onCreate`, before any window setup, and is no longer a plain
  function of the caller's remembered choice alone the way it was pre-0.3.0: `decideSilent` also
  has to look ahead at whether the *first* intent's identity routing (see `IdentityRouting`) will
  actually resolve. An approved caller whose `current_user` cannot be resolved must fall back to
  the visible sheet rather than guess an identity -- which is only possible if the invisible theme
  was never applied in the first place, since switching a visible theme for an invisible one (or
  the reverse) after the window already exists is unreliable either way. A later `onNewIntent`-
  delivered request that hits the same routing failure on an already-silent-themed window instead
  rejects outright (`handleIncomingIntent`'s `else` branch) -- there is no reliable way to grow a
  sheet's decorations onto an invisible window after the fact, so it cannot fall back to asking the
  way the first intent could. `decideSilent` returns a `SilentDecision` (its parsed `Nip55Request`
  and `IdentityRouting.Result`, alongside the `silent` verdict itself), which the very first
  `handleIncomingIntent` call reuses instead of re-parsing the same intent and re-running routing
  against it a second time immediately afterwards; `onNewIntent`-delivered requests still compute
  both fresh, since `SilentDecision` only ever describes the *first* intent.

  The approval sheet (shown only for "ask", or for an approved caller whose routing did not
  resolve on the first intent) has an "always deny this app" link below the normal Approve/Decline
  buttons -- a deliberately secondary affordance so a permanent block needs its own action, not an
  accidental extra tap on Decline. It also renders `get_public_key`'s optional `permissions` extra
  (see `nip55/RequestedPermissions.kt`) as a summary line with a note that Heartwood's own
  `ClientPolicy` is the actual authority -- display only, Cambium does not pre-authorise anything
  from this list. An identity picker (`Spinner`, only shown once there is more than one pairing)
  defaults to the request's `current_user` match if it named one we have, else the caller's
  *existing* bound identity, else the first pairing. In practice a `current_user` match can never
  coexist with a *different* existing binding here -- this sheet only ever shows for an
  already-approved caller when their `current_user` named an identity we don't have (see
  `decideSilent` above) -- but the picker does not rely on precedence alone to prevent a silent
  rebind: `identityRebindHint` (a calm one-line warning, "Currently bound to `<label>`") compares
  whatever ends up selected against `AppPermission.boundIdentityPubkeyHex` directly, via the
  picker's own `OnItemSelectedListener`, and shows whenever they differ -- covering a user
  manually moving the picker away from its default, not just the default itself. Approve binds the
  app to whichever identity is selected at tap time (`pairingStore.approve(packageName, chosen.signerPubkeyHex)`).

  `silentBackPressBlock`, a no-op `OnBackPressedCallback`, is enabled only for the two silent
  forwarding windows (`handle`, `handleDecryptZapEvent`, only when `silent`) and reset to disabled
  at the top of every `handleIncomingIntent` call. A stray back-press finishing the activity
  mid-request would not stop the underlying call -- it keeps running on `HeartwoodSession`'s
  worker regardless -- but `setResult`/`finish()` need a live activity to deliver the outcome to,
  so a lost activity means a silently lost result. The visible Approve/Decline sheet's back-press
  behaviour (same as Decline, Android's default) is unchanged, since the callback is never enabled
  on that path.

  The same result-delivery gap exists for a system-driven recreation, not just a stray tap: a
  config change (rotation, dark/light switch, locale, ...) mid-request would otherwise destroy and
  recreate the activity, cancelling `lifecycleScope` along with it. The manifest declares
  `android:configChanges` for this activity covering every change worth handling ourselves, so the
  instance -- and its in-flight coroutine -- survives instead; there is nothing here worth
  recreating for (the invisible path shows no UI, the visible path's progress overlay is not worth
  preserving pixel-perfect layout for).

  `handle` and `handleDecryptZapEvent` both funnel into `submitAndRespond` (progress/back-press
  toggle, `HeartwoodSession.withClient`, `Success`/`Failure` dispatch to `respondSuccess`/
  `showErrorAndReject`) instead of each repeating that tail -- `handleDecryptZapEvent` differs only
  in decoding the `anon` tag first and using `PrivateZap.decryptAndValidate`/`cacheableFor` as its
  operation and cache key.
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
  `withApprovedCaller` centralise this tri-state check so every `query*` method gets it uniformly,
  and now also carry the caller's bound identity (`AppPermission.boundIdentityPubkeyHex`) through
  to `requirePairing`, which resolves it against `projection`'s `current_user` (index 2) via
  `IdentityRouting.resolve` -- same precedence as the intent path. A `current_user` naming an
  identity we don't have, or nothing resolving at all (more than one pairing, no `current_user`, no
  binding -- should not normally happen for an approved caller, since approving always binds),
  both answer `null` here: there is no way to ask which identity was meant from the silent path, so
  it defers to the intent rather than guessing.
- `nip55/RequestedPermissions.kt` -- pure Kotlin parser for `get_public_key`'s optional
  `permissions` extra (a JSON array of `{ "type": ..., "kind": ... }`) into a display summary for
  the first-approval sheet. Cambium does not pre-authorise anything from this list; it is shown so
  the user knows what was asked for, with a note that Heartwood's `ClientPolicy` remains the actual
  authority.
- `nip57/Bech32.kt` -- pure Kotlin BIP-173 bech32 encode/decode, deliberately without the spec's
  90-character length recommendation (which exists for Bitcoin-address readability, not
  cryptographic reasons) -- DIP-03's private-zap `anon` tag payload routinely exceeds it. Tested
  against the real bech32 strings in DIP-03's own worked example, not just round-tripped against
  its own `encode()`. `convertBits` writes into a pre-sized `ByteArray` by index (the output length
  is exactly computable from the input size up front) rather than a boxed `MutableList<Byte>`,
  since it runs on the binder thread inside `decrypt_zap_event` query bursts.
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

  `decryptAndValidate(client, forward)` (the nip04_decrypt call plus the kind-9733 check,
  synthesising a failure with `INVALID_PRIVATE_ZAP_MESSAGE` when it is not one) and
  `cacheableFor(forward)` (the `CacheableDecrypt.Method.ZAP` key, built from the anon tag's own
  value) are the single shared home for that pair of operations -- `SignerActivity.handleDecryptZapEvent`
  and `SignerProvider.queryDecryptZapEvent` both call these two instead of each duplicating the
  block. They reference `HeartwoodClient`/`HeartwoodResult`/`HeartwoodError`/`CacheableDecrypt`
  from `signer`, but those are plain Kotlin types with nothing Android or rust-nostr in their own
  signatures, so this file's JVM-testability is unaffected.

  `decodeAnonTag` locates the `anon` tag entry first, then extracts its second element in its own
  `runCatching` mapped to `MalformedAnon` -- kept separate from the tag *lookup* itself (which
  falls back to `NoAnonTag`, "ordinary public zap") specifically so a found `anon` tag whose value
  isn't a JSON string (adversarial or corrupt input, e.g. a nested array) is reported as malformed
  rather than misclassified as "no anon tag at all".
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
  in our tone) or paste the bunker URI text, with an optional label field, to pair a signer. A
  successful scan pairs immediately (fills the field and calls the same `connectAndSave` path as
  pressing Pair, so it is one tap total). The pairing form stays visible regardless of how many
  signers are already paired -- `connectAndSave` calls `PairingStore.addPairing`, which appends (or
  upserts, on a re-pair of the same identity) rather than overwriting a single slot -- so a second,
  third, ... signer is added the exact same way as the first. `CAMERA` is a runtime permission
  requested only on the Scan QR tap; denial shows a static inline hint that pasting still works and
  is never re-prompted automatically -- the system's own permission dialog already refuses to
  reappear after a user denies twice, so there is no custom "don't ask again" tracking needed on
  top of that. The library's own `CaptureActivity` is pulled in entirely via manifest merger (not
  exported -- it declares no intent filter, so it defaults closed) and is pure Java
  (`com.google.zxing:core`), no Google Play services, matching the GrapheneOS target.

  `renderPairingsList` shows one `item_pairing.xml` row per pairing (label or truncated npub,
  npub, relays), each with its own confirm-gated unpair (`PairingStore.removePairing` +
  `HeartwoodSession.shutdown(signerPubkeyHex)`, and `HeartwoodKeepAliveService.stop` too if that
  was the last remaining pairing -- the service's own ping loop already stops itself once it next
  finds `pairings()` empty, but not stopping it here immediately would leave a "keeping your
  signer warm" notification showing for up to a full ping interval with nothing left to keep
  warm); a separate "Unpair all" action (`PairingStore.clearAll` + `HeartwoodSession.shutdownAll`,
  which already stops the service unconditionally) is a full reset, confirmed with its own dialog,
  for when the user wants to start over rather than remove signers one at a time. The
  paired-signers section, the keep-alive toggle, and the connected-apps list are all hidden
  together on a fresh install (`pairings.isEmpty()`) -- there is nothing to show but the pairing
  form. Also hosts the "Keep connection warm" toggle -- see `service/HeartwoodKeepAliveService.kt`
  -- requesting `POST_NOTIFICATIONS` on API 33+ only when the toggle is switched on, with the same
  permanent-decline-shows-a-static-hint pattern as the camera permission; and a connected-apps list
  (package, state, a "Forget" action that calls `PairingStore.forget` -- back to "ask") built from
  `PairingStore.allPermissions()`, one `item_connected_app.xml` row inflated per entry rather than
  a `RecyclerView`, since the list is realistically tiny (a handful of paired Nostr clients). An
  approved row also shows which paired identity it is bound to
  (`AppPermission.boundIdentityPubkeyHex` resolved back to a `Pairing` and its `displayLabel()`).
  `render()` (called from both `onCreate` and `onResume`) force-hides the stale
  notification-permission hint whenever `hasNotificationPermission()` is now true -- the in-app
  denial path is the only thing that turns the hint on, so nothing previously cleared it again if
  the user instead granted the permission from system Settings and returned via `onResume`.
- `AndroidExtensions.kt` -- `PackageManager.displayNameFor(packageName)`, the app-label lookup
  (falls back to the raw package string on any failure) shared by `SignerActivity`'s approval sheet
  and `MainActivity`'s connected-apps list.
- `HexUtils.kt` -- pure Kotlin (no Android): `ByteArray.toHex()`, lowercase hex encoding, shared by
  `IdentityRouting`'s `current_user` normalisation and `DecryptCache`'s cache key rather than each
  keeping its own copy of the same encoding -- both on hot paths (a burst of silent-path queries;
  every cache lookup), so this writes into a `CharArray` via a lookup table instead of
  `"%02x".format(...)`: `String.format`/`Formatter` parses the format string and boxes every byte
  on each call, real overhead on a path called this often.
- `log/ActivityLog.kt` -- the on-phone activity log's pure-Kotlin core: `ActivityLogEntry`
  (timestamp, calling package, NIP-55 method, event kind -- `sign_event` only, never any other
  method's payload -- identity display label, outcome) and `ActivityLog`, the capped append/rotate
  logic (`MAX_ENTRIES` 500, oldest dropped first) plus `outcomeFor`, which classifies a
  `HeartwoodOutcome` into `SIGNED`/`ANSWERED_FROM_CACHE`/`REJECTED_POLICY`/`FAILED` (`REJECTED_USER`
  is set directly by the two callers below, for a Cambium-level user decision rather than anything
  Heartwood answered). **Metadata only, deliberately**: never a payload, plaintext, ciphertext or
  event body -- the point is reassurance/transparency ("what has Cambium done on my behalf"), not
  an audit trail of what was actually signed, which Cambium cannot see anyway. References
  `HeartwoodOutcome`/`HeartwoodResult`/`isPolicyRefusal` from `signer`, but those have nothing
  Android or rust-nostr in their own signatures (same reasoning as `nip57/PrivateZap.kt`'s
  dependency on `signer`), so this file stays JVM-testable.
- `log/ActivityLogStore.kt` -- persists the log as a small JSON file in `filesDir` (metadata only,
  nothing here needs Keystore encryption) and the enabled toggle in its own tiny plain
  `SharedPreferences`, independent of `PairingStore` -- a diagnostic feature, not pairing state.
  Defaults to **on** (opt-out): the log exists to reassure, so it should work without first being
  found and switched on. `append` no-ops entirely, without touching the writer queue, when disabled.

  A **process-wide singleton** (`ActivityLogStore.getInstance(context)`, private constructor), not
  a plain constructor call: `SignerActivity`, `SignerProvider` and `ActivityLogActivity` each need
  to write to the same underlying file, and an earlier version that let each construct its own
  instance guarded reads/writes with `@Synchronized`, which only serialises calls against *one*
  instance's own monitor, not across several instances racing on the same file. All file access now
  goes through exactly one dedicated writer coroutine, on its own single-thread dispatcher, fed by
  an unbounded `Channel` -- the same single-worker-plus-inbox shape `HeartwoodSession.Session`
  already uses, for the same reason: one consumer, one thread, nothing to race. `append` is
  fire-and-forget: `Channel.trySend` on an unbounded channel never suspends and never fails, so it
  enqueues and returns immediately without blocking the caller's thread on file I/O --
  `SignerProvider`'s binder thread in particular, where blocking would undercut a `DecryptCache`
  hit's whole point of answering without a round trip (see `SignerProvider`'s own class doc for the
  matching cache-hit-skip). `entries`/`clear` still block their caller briefly (a request/response
  round trip through the same channel via `CompletableDeferred`) -- both are rare, user-initiated
  calls from `ActivityLogActivity`, where blocking until the effect is actually visible is the
  wanted behaviour, and routing them through the channel too (rather than reading the file
  directly) keeps them correctly ordered relative to any in-flight appends.
- `log/ActivityLogActivity.kt` -- read-only log screen (newest first, monospace), reached from a
  button in `MainActivity`'s paired section. Off-toggle and a confirm-gated Clear action. One
  `item_activity_log_entry.xml` row inflated per entry rather than a `RecyclerView`, same trade-off
  `MainActivity`'s connected-apps list already makes at a smaller scale -- an occasionally-opened
  diagnostic screen, not a live feed.

  `SignerActivity` and `SignerProvider` both call `ActivityLogStore.append` only at points that
  reached an actual decision about the paired identity or an explicit user choice -- not at every
  possible termination point. Deliberately unlogged: a malformed/unparsable intent, "nothing paired
  at all", `decrypt_zap_event`'s local "not a decryptable private zap" outcomes (an ordinary public
  zap is the routine case there, not an error), `SignerProvider`'s NIP-37 draft decline, and any
  `forward()` outcome that defers to the intent (`null`) rather than answering definitively -- a
  transient provider-path failure is retried via the intent path, which logs its own outcome once
  the request actually concludes, so logging both would double-count what the user experienced as
  one request. `SignerProvider.forward` additionally skips logging a `HeartwoodOutcome.Cached` hit
  specifically (`logActivityUnlessCached`): a cache hit on the silent path can repeat many times a
  second during a burst (Amethyst re-requesting the same decrypt while the user types), which would
  be pure noise against the cap and needless work on the exact hot path the cache exists to keep
  fast. `SignerActivity`'s intent-path logging is unaffected -- it is a one-off, user-visible flow,
  not a background burst, so `SIGNED` vs `ANSWERED_FROM_CACHE` stays meaningful signal there. This
  keeps the capped 500-entry log meaningful signal (what was actually asked, approved, denied or
  failed) rather than routine background noise from Amethyst's constant drafts/pings/re-queries on
  the silent path.
- `applock/AppLock.kt` -- pure Kotlin: whether a fresh authentication is needed right now, given
  when Cambium was last unlocked (`null` means never, this install) and a grace window
  (`GRACE_WINDOW_MILLIS`, ~1 minute). The grace window exists so a rotation or a quick switch to
  another app and back does not re-prompt -- only walking away for longer does.
- `applock/AppLockStore.kt` -- the toggle and the last-authenticated timestamp in their own small
  plain `SharedPreferences` (a boolean and a timestamp, nothing worth Keystore encryption),
  independent of `PairingStore` -- a device-local UI gate, not pairing state. Off by default
  (opt-in, unlike the activity log's opt-out default): this changes what the user has to do to
  open the app at all. One shared timestamp, not one per activity -- `MainActivity` and
  `SignerActivity` each construct their own `AppLockStore`, backed by the same preferences file, so
  authenticating from either covers the other within the same grace window.
- `applock/AppLockPrompt.kt` -- thin wrapper over `androidx.biometric`'s `BiometricPrompt`/
  `BiometricManager`, `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (never a weak/convenience biometric
  alone; never `setNegativeButtonText`, which androidx.biometric forbids combining with
  `DEVICE_CREDENTIAL`). `requiresAuthenticationNow(context, store)` is the one true "should we gate
  right now" check, combining three things: the toggle (`AppLockStore.isEnabled`), `canAuthenticate`
  (whether the device still has anything enrolled), and `AppLock`'s grace window. `canAuthenticate`
  being part of that combination, not just of what enables the toggle, is deliberate and load-bearing:
  if the toggle was left on and the device later loses its screen lock entirely, gating must fail
  open rather than lock the user out of their own app with no way to ever pass a prompt that can no
  longer be shown.

  Gates exactly two human-interactive actions, both behind `MainActivity`/`SignerActivity`'s own
  `requireUnlockedThen`/equivalent: `MainActivity` itself on every `onResume` (a locked-state screen
  replaces its content entirely, auto-triggering the prompt, with a manual "Unlock" retry button
  for when the prompt is dismissed or fails), and `SignerActivity`'s approval sheet's Approve and
  "always deny" actions specifically -- not Decline (refusing needs no proof of presence), and
  never the silent forwarding path in `handle`/`submitAndRespond` or any part of `SignerProvider`
  at all: background signing for an already-approved caller must keep working with the phone
  locked, since that is precisely the point of Cambium existing as a background NIP-46 proxy.
- `service/HeartwoodKeepAliveService.kt` -- optional, off-by-default foreground service that keeps
  the process (and so `HeartwoodSession`'s warm `NostrConnect`) alive between requests, closing the
  previously-tracked "only warm while the process happens to be running" gap. Pings Heartwood every
  8 minutes via `HeartwoodSession.trySilent(pairing) { it.getPublicKey() }` -- a read-only,
  always-safe operation Heartwood answers without a physical button; rust-nostr's client bindings
  have no lower-level "ping" primitive to call instead (checked directly against the AAR with
  `javap`: neither `NostrConnect` nor `NostrConnectInterface` declare one). `trySilent`, not
  `withClient`: the ping must go through the shedding path, since `withClient` always queues and a
  slow/unreachable Heartwood would let a scheduled ping occupy the single worker for up to the
  silent timeout, inflating queue depth against `MAX_QUEUED` and shedding a real Amethyst burst
  into visible popups -- the exact regression `HeartwoodSession`'s admission control exists to
  prevent. A refusal (queue non-empty) is also the right outcome on its own terms: it means the
  session is demonstrably warm already, so the ping was redundant. `targetSdk` 35 requires
  an explicit `foregroundServiceType`; there is no built-in type for "hold a NIP-46 connection
  open", so this follows Amber's own `ConnectivityService` (verified against its actual source,
  `greenart7c3/Amber` `service/ConnectivityService.kt` and manifest): `specialUse`, declared in the
  manifest with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` description and passed explicitly to
  `ServiceCompat.startForeground` on API 34+, where the type argument became mandatory. Low-
  importance, non-badged notification channel; the toggle and its state live in `PairingStore`
  (`isKeepAliveEnabled`/`setKeepAliveEnabled`), not the service, so `MainActivity` and
  `service/BootReceiver.kt` agree on state without talking to each other directly.
- `service/BootReceiver.kt` -- restarts `HeartwoodKeepAliveService` after a reboot, gated on both
  the toggle and still being paired. Uses `goAsync()` and does the `PairingStore` read (a
  synchronous Keystore-backed EncryptedSharedPreferences init) and the service start on
  `Dispatchers.IO`, not the calling thread -- `BOOT_COMPLETED` delivery is the worst possible
  window to block the main thread, with every other receiver and the rest of the boot sequence
  contending for it too.

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
- Timed remember-my-choice (an expiry -- 1 hour / 1 day / 1 week / always -- on a remembered
  approve/deny, Amber's `acceptUntil`/`rejectUntil`) was scoped for 0.3.0 as a stretch goal and
  deliberately not built. The store side is not the blocker: `AppPermission` could gain an
  `expiresAtMillis: Long?`, and `PairingStore.permission` treating an expired entry as absent on
  read (the same "back to ask" outcome `forget` produces, so `SignerProvider` already treats an
  expired denial as unresolved rather than rejected with no further change) is a small, contained
  addition. The approval sheet is: it already carries a conditional identity picker for
  multi-pairing on top of the app/method/kind/permissions rows, and an expiry choice needs to
  attach to *two* different actions (Approve and "always deny") without either silently changing
  what a plain tap does or turning the sheet into something that needs its own layout pass to stay
  legible on a 320dp modal. That interaction design, not the storage, is the real work here.
- `decrypt_zap_event` only implements the recipient path (decoding a private zap sent *to* the
  paired identity). The sender path -- recognising your own anonymous zaps by regenerating the
  ephemeral key -- needs the raw private key hashed directly, which is permanently impossible over
  NIP-46 (see `nip57/PrivateZap.kt`'s class doc), not a gap that will be closed later. The
  decrypted plaintext is checked for `"kind": 9733` but its signature is not verified.
- The decrypt cache (`DecryptCache`) has no eviction on staleness beyond LRU size -- there is no
  defence against a cached plaintext outliving its usefulness if the counterparty rotates keys.
  Partitioning per pairing (each `HeartwoodSession.Session` owns its own `DecryptCache` instance)
  is a structural guarantee verifiable by reading the constructor, not something with its own
  automated test -- `HeartwoodSession` cannot be unit tested at all: any exercise of it eventually
  constructs a real `RustNostrHeartwoodClient`, which needs rust-nostr's per-ABI native library and
  cannot load on a host JVM (see `signer/HeartwoodClient.kt`'s "only file that imports
  `rust.nostr.sdk`" note). This predates 0.3.0 -- the single-session design was never unit tested
  either -- and is unchanged by moving to one session per identity.
- All pairings share one Cambium client keypair (`PairingStore.ensureClientKeys`) rather than one
  per identity -- a deliberate simplification (Cambium presents one NIP-46 client identity to every
  bunker it talks to), not a limitation flagged as a gap to close, but worth knowing if a future
  design wants per-identity client key isolation.
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
