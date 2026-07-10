# Changelog

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
