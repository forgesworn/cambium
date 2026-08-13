# Changelog

## Unreleased

## 0.4.0 (2026-08-13)

- Websites can now use Cambium through NIP-55 `nostrsigner:` links. Cambium parses the browser
  request parameters, asks for an explicit one-shot approval, and returns a signature, signed
  event, or `Signer1` gzip envelope through a validated HTTPS callback. When there is no callback,
  it copies the result to the clipboard.
- Browser requests never inherit or create a remembered permission for Chrome (or any other
  browser). The approval sheet identifies the callback host and makes the one-shot boundary clear.
  Native Android intent and content-provider permissions are unchanged.
- Minimal NIP-55 web events now sign correctly when they omit `pubkey`, `created_at`, or `tags`,
  as the specification's own example does. Cambium fills the pubkey from the exact paired identity
  proved by the NIP-46 handshake before passing the event to rust-nostr.

## 0.3.6 (2026-08-13)

Pairing fix. Cambium could not pair with a signer that answers `connect` by echoing the bunker
URI's secret, which is what current NIP-46 specifies and what Heartwood's firmware does.

- The rust-nostr dependency moves from 0.44.2 to 0.44.8. The older build accepted only the
  literal `ack` as a `connect` result and refused the secret echo, so pairing ended in
  "Unexpected response" against a spec-following signer. Both forms are accepted now, so
  signers on either convention pair. Verified against live hardware and against `nak bunker`,
  which still answers `ack`.

## 0.3.5 (2026-08-13)

Review hygiene from F-Droid inclusion feedback. No behaviour changes.

- The `ACCESS_NETWORK_STATE` permission is no longer requested. It was scaffold boilerplate:
  nothing in Cambium or its dependencies reads network state, so the app now asks only for
  what it uses.
- The Gradle wrapper now pins the distribution's SHA-256 (`distributionSha256Sum`), so a fresh
  build verifies the Gradle download before running it. Requested in F-Droid review.

## 0.3.4 (2026-08-12)

F-Droid inclusion fix. No behaviour changes.

- Release APKs no longer embed the Play Store dependency metadata block (an encrypted blob only
  Google can read). F-Droid's scanner rejects binaries carrying it, and it served no purpose for
  an app that is not on the Play Store.

## 0.3.3 (2026-08-09)

Security fixes from a full review.

- A `current_user` naming one of your other paired identities no longer forwards silently: an
  approval only ever covers the identity it was granted for, so a cross-identity request now
  asks (with the rebind warning) instead of signing as an identity the approval never covered.
- The activity log screen is now behind the app lock like everything else, and no longer
  appears in recents.

## 0.3.2 (2026-07-10)

Polish on the 0.3.1 fixes.

- The approval sheet now warns live whenever the selected identity differs from the app's
  existing binding, including when the picker is moved by hand, not just on its default.
- Activity log writing is a single process-wide writer fed by a non-blocking queue.
- Queue-shed log lines now name which identity's queue is shedding.
- Faster hex encoding on the request path; a build-time guard against the recurring illegal
  "--" inside XML comments.

## 0.3.1 (2026-07-10)

Fix release from the completed 0.3.0 review.

- A first-time burst of concurrent requests against a newly paired identity could construct
  duplicate session workers and leak the losers; session creation is now atomic.
- The approval sheet's identity picker now defaults to the app's existing binding (after a
  `current_user` match) instead of the first pairing, and says when a different identity is
  selected, so a routine re-approval cannot silently rebind an app.
- Activity log writes are now serialised process-wide and never block the request path; silent
  cache hits are no longer logged (they would crowd out real entries).
- Removing the last pairing from its row now stops the keep-warm service immediately.
- Less repeated work per request on the intent path; shared hex encoding; dead code removed.

## 0.3.0 (2026-07-10)

- Multiple identities: pair each Heartwood identity separately (each bunker URI is one identity).
  Apps bind to an identity at approval; the NIP-55 `current_user` field (npub or hex) selects the
  identity per request. Cambium never substitutes identities silently: a request naming an
  identity it does not hold is refused, and an ambiguous request asks. Each identity gets its own
  isolated connection, request queue and decrypt cache.
- Activity log: an on-phone, metadata-only record of signer activity (app, method, event kind,
  identity, outcome). No event content, plaintext or ciphertext is ever stored. Toggle off or
  clear at any time.
- App lock: an optional biometric or device-credential gate on the management screen and on
  approval decisions. Background signing for already-approved apps is never gated.
- Existing 0.2.x pairings and app approvals migrate automatically.

## 0.2.0 (2026-07-09)

First signed release.

- `decrypt_zap_event`: private zaps sent to your identity now decrypt (DIP-03, recipient path).
  Cambium unpacks the zap request's `anon` tag locally and asks Heartwood for an ordinary
  nip04_decrypt. Viewing your own *sent* private zaps is permanently out of reach over NIP-46
  and fails as a normal decrypt error.
- Keep connection warm: an optional, off-by-default foreground service holds the signer session
  between requests, so silent signing skips the reconnect penalty. Survives reboots when enabled.
- Persistent denial: the approval sheet gains an "always deny this app" action. Denied apps get
  a terminal rejection on every path instead of a repeating approval sheet. A connected-apps list
  on the pairing screen shows every remembered choice with a Forget action.
- The first-approval sheet now shows the permissions a client asked for at login. Display only:
  Heartwood's own policy decides what actually gets signed.
- Decrypt results are cached (successes always, provably-permanent failures too), so re-reads of
  the same messages answer instantly instead of costing a relay round trip each.
- Requests from already-approved apps process genuinely invisibly: no flash, no dimmed overlay.
- Hardening: a malformed zap request can no longer crash the provider; a stray back-press can no
  longer swallow an in-flight request's result.

## 0.1.0 (2026-07-08)

Initial scaffold: pairing (QR scan or paste), NIP-46 client with a shared kept-warm session,
NIP-55 intent handling (`get_public_key`, `sign_event`, `nip04`/`nip44` encrypt/decrypt), silent
content-provider path for approved apps. Unreleased.
