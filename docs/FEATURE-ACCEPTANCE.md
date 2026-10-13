# Connection, consent and diagnostics controls

This records the features requested on 13 October 2026. They are development changes after
0.8.0; the published release and distribution channels have not been changed.

| Requested feature | Implementation | Automated evidence |
| --- | --- | --- |
| Connection health and recovery | Per-identity observed status, safe errors, last actual contact, explicit test/reconnect, successful pairing contact | `ConnectionHealthTest`, `Nip46TransportTest`, main-screen Android regressions |
| Approve once or temporarily | Once/hour/day/always; expiry and clock rollback checks; existing consent preserved | `AppPermissionTest`, `IntentGateTest`, `PermissionStoreRegressionTest`, real once/hour approval controls |
| Permissions by operation | Chosen methods and signing kinds enforced before cache/queue; public identity discovery remains usable | `RequestPermissionScopeTest`, `ProviderGateTest`, `PermissionScopeEditorTest`; malformed restrictions cannot become all-kind approval |
| Understandable request details | Familiar summaries, bounded and sanitised temporary event details, private/encrypted content hidden | `RequestPresentationTest`, native/web approval Android regressions |
| Better diagnostics | Safe categories/duration, app/identity/outcome filters, stable local identity references, reviewed allowlisted report with aliases | `ActivityLogIdentityTest`, `DiagnosticReportTest`, `DiagnosticsUiTest`; duplicate labels and renames stay distinct/correct; Review/Cancel starts no external sharing activity |
| Edit existing connections | Rename without changing credentials; confirmed rebind preserves scope/expiry; atomic stale-edit checks | Real rename/rebind UI test and encrypted-store concurrency/CAS Android regressions |
| Native signing batches | Ordered bounded results and IDs; signatures for signing; per-request rejection; attributable caller checks and explicit one-shot fallback | `NativeBatchTest`, `NativeBatchActivityTest`; original authority cannot be borrowed by an unattributable reused intent |
| Signer-directed relay updates | Signed NIP-44 RPC, exact signer/recipient/request checks, bounded secure endpoints, persistence before transport change | `RelaySwitchTest`, `ConnectionHealthTest`, `Nip46EnvelopeTest`, actual SDK/loopback WebSocket test |

## Verification scope

The JVM suite checks pure decisions and worker/cache lifecycle against controlled clients. The
Android tests use real encrypted Android preferences, real activities and permission controls,
and the actual rust-nostr SDK for envelope cryptography and a local WebSocket signer fixture.
The fixture verifies handshake, fresh ping, malicious response rejection, `switch_relays`, an
unsupported optional method and a subsequent valid event signature. It does not contact a real
Heartwood or public relay.

The local verification uses one isolated Android 15/API 35 arm64 emulator and JDK 21. Builds
reuse the existing Gradle caches and limit workers/heap to reduce resource use:

```sh
./gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

The final local build passed all 330 JVM tests across 40 suites, with zero failures, errors or
skips, and assembled both the debug and instrumentation APKs. A local Android run before the
final consent/storage fixes passed all 36 then-existing instrumented tests without skips.

CI runs the final 40-test Android suite on a fresh API 35 emulator, followed by 16 UI regressions
at 320 dp width and 150% text scaling. The compact run requires all 16 tests to complete without
skips. The draft pull request records the exact committed head, CI run and verified result counts;
its checks are the current acceptance receipt. The native transport regressions include a
two-relay migration and process restart with a signer that refuses consumed admission secrets.
Runner summaries are checked alongside per-test
statuses: assumptions and skipped tests are not counted as proof.

## Behaviour and limits

- Native approvals outside an existing scope default to this request only, preserving the
  earlier grant. Client-requested permissions are displayed but never automatically trusted.
  Denial remains permanent and identity-agnostic. Websites never create remembered browser consent.
- An approval sheet compares the displayed permission and connection atomically; it cannot
  overwrite a newer scope, identity or denial, or grant against changed credentials. A management
  dialog applies the same stale-choice protection.
- Batch reuse on older Android requires visible one-shot consent. On Android 15, an attributed
  sender must match the original result recipient; unavailable identity does not fall back to
  the original permission. A denied result recipient cannot obtain answers through unattributed
  one-shot consent. Back on a waiting batch sheet declines that request; handed-off native
  work retains its result-delivery activity until completion.
- Batches allow at most 32 requests, 128 KiB of incoming request strings, IDs up to 256 characters
  and 384 KiB of results. Result capacity reserves space for correlated rejection rows.
- Last contact is an observation in this process, not a promise of current reachability.
  Cached/local answers do not refresh it. Old workers cannot overwrite a newer observation.
- Relay discovery is optional and limited to once an hour per held session. Unsupported or
  invalid replies preserve the current transport. A durable encrypted-preferences commit must
  succeed before the transport changes; failed or throwing persistence stops that attempt and
  restores the stored relay list. Transport relays are not the user's publishing or read relay list.
  Pairing admission uses the bunker secret; all saved-session connections resume with the same
  authorised client key without replaying that secret, covering signers with single-use admission.
- Reports contain a fixed allowlist. App/identity labels, event data, plaintext/ciphertext,
  pairing links, relay addresses, raw errors, keys and local identity references are excluded.
  Reports are not stored as files. Entries written before local identity references existed remain
  in separately labelled older groups; their identities cannot safely be inferred from labels.

Physical Android/Heartwood acceptance and publication of a release remain separate checks.
No physical device, firmware, existing user emulator or external message recipient was changed
by this work.
