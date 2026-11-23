# Hardware test checklist

These journeys cannot be proved by the JVM or emulator suites, so they run against a physical
Heartwood. The list was written on 23 November 2026, after 0.9.1. It collects everything
`FEATURE-ACCEPTANCE.md`, the [0.9.0 receipt](acceptance/0.9.0-physical-2026-10-13.json) and PRs
#35 and #36 record as not yet exercised on hardware.

Each section gives the purpose, setup, steps, pass criteria and what to record. Record results in
`acceptance/<version>-physical-<date>.json`, using the shape of the 0.9.0 receipt (`checks`,
`not_exercised`, `privacy`, `cleanup`). Add the narrative to `FEATURE-ACCEPTANCE.md`.

## Before starting

- **Phone.** The Pixel runs the published 0.9.1 from Obtainium or Zapstore. Hash the installed
  `base.apk` against the release (`b9f11de0…3693`, see `DISTRIBUTION.md`) and keep that install
  throughout. Section 1 uses an emulator so that nothing on the Pixel is replaced.
- **Board.** Firmware 0.18.0-beta.19 or later. Rendezvous provisioning needs beta.9; the five-word
  enrolment check needs beta.19. Record the exact version. Phone unlock also needs a board that
  restarts locked, with its keys encrypted at rest.
- **Sapwood.** Needed for a throwaway connection, a second identity and the unlock invite.
- **A temporary caller app.** The 0.9.0 caller was not kept, so it must be rebuilt. It needs to
  send:
  - `get_public_key`
  - `sign_event` for a controlled kind 1
  - `sign_event` for a kind 22242 login challenge carrying a `code` tag
  - one provider query
  - a `sign_event` batch of two or three requests using
    `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP`

  It must never publish anything.
- **Privacy, as in 0.9.0.** Publish no controlled events and read no private keys or pairing
  secrets. Keep no payloads, labels, public keys or relay addresses in the receipt.
- **Order.** Run section 7, which moves the phone's clock, last. Three sections leave something
  behind, and the list under Afterwards says what to keep or remove:
  - Section 1 copies a derived key to the emulator.
  - Section 4 leaves an unlock enrolment.
  - Section 5 leaves a second pairing.

## 1. Rendezvous provisioning through Cambium (PR #35)

**Proves:** KithMoot's Advanced "Heartwood rendezvous key" works through Cambium against a real
board. KithMoot has offered it to Cambium users since v0.6.115, but no released Cambium supports
it. Until now only a synthetic Heartwood has run it (the Vennel lab, 25 October).

**Where:** an arm64 emulator on the Mac, not the Pixel. A debug build has the same package name as
the release but a different signing key. It therefore cannot be installed over the Pixel's Cambium
without uninstalling that, which deletes its pairings, approvals and phone-unlock enrolments. The
emulator reaches the board over relays, exactly as the phone does.

**What it hands out:** the board derives the real rendezvous child key for the paired identity at
the index you enter, then encrypts it to KithMoot's device key on the emulator. Use an index you do
not use for real, so the emulator never holds your actual rendezvous child, and delete the
emulator afterwards. The board itself keeps only a replay receipt per success: at most 32 per
identity, each dropped once its request expires (within ten minutes).

**Setup:**

- Install a Cambium debug APK built from PR #35. On 23 November that means branch
  `fix/rendezvous-provision-review-20261123` at `e700904`: PR #35 (`e82a247`) merged with main at
  `3985a3d`, plus two review fixes that are not on the PR yet.
  - A pairing with one unreachable relay no longer holds the request back for 120 seconds.
  - Cambium names the board's reason when it refuses.

  That build passed with no failures:
  - 341 JVM tests
  - all 41 instrumented tests on an API 35 emulator with animations off, as CI runs them
  - the 16 UI regressions at 320 dp and 150% text

  Its debug APK hash was `fe1fd377…cf15`. A rebuild on another machine gets a different hash,
  because the debug signing key differs. If PR #35 has moved on by then, build its head and record
  that commit instead.
- Install KithMoot Android v0.6.115 or later (v0.6.172 is current).
- In Sapwood, create a throwaway connection (for example "Cambium emulator") and pair the
  emulator's Cambium with it.
  - On a strict slot, the slot's allowed methods must include `heartwood_provision_rendezvous`,
    or the board refuses it.
  - An away-approval (escalate) slot refuses it outright, because it needs a press at the board.
- Sign KithMoot in with Cambium. If KithMoot later says the connection has no protected provision
  target, reopen it once.

**Steps:**

1. In KithMoot, open Settings, Advanced, Heartwood rendezvous key. Enter the test index and start.
2. Cambium's sheet shows persona, target device, index, nonce and expiry, with no lifetime or
   remember controls.
3. Approve. The board shows a "PROVISION RENDEZVOUS" card with the device prefix, index and
   expiry. Hold the button within its 30 seconds.
4. Run it again and decline in Cambium.
5. Run it again and approve, but let the board's card time out.

**Pass:**

- Step 3 ends with "Rendezvous child for index N is protected on this phone".
- Steps 4 and 5 leave KithMoot storing nothing, and the board adds no receipt.
- With the review fixes, step 5 ends with "Heartwood refused: the button was not held in time".

**If step 3 is refused:** a build with the review fixes names the reason. Earlier builds show
"Heartwood refused rendezvous provisioning" for all of these.
- "this connection is not allowed rendezvous keys": a strict slot without the method, or an
  identity the slot may not use.
- "turn off Approve from my phone for this connection": an escalate slot. The request needs a
  press at the board, so the board refuses it outright.
- "its firmware is too old for rendezvous keys": firmware before 0.18.0-beta.9.
- "its clock is not set yet": the board has no trusted time yet.
- "its clock does not match this request's expiry": the phone's clock, or the board's, is out.
- "too many recent provisions": 32 receipts for this identity in the last ten minutes.
- "this request was already completed" or "this request's nonce was already used": a replay.

**Record:** KithMoot version, Cambium commit and APK hash, firmware version, and the outcome and
time from Approve to result for each run. Note whether each Cambium message could be read in
full, because Android 12 and later cut a toast to two lines.

**Unreachable relays:** before the review fixes, one unreachable relay in the pairing held the
request back for the whole 120-second budget, so the board never showed its card. The
instrumented test `Nip46TransportTest.provisionGetsPastAnUnreachableRelayInThePairing` failed
after 120.2 seconds without the fix and passes in 10.5 seconds with it. Nothing needs checking on
the board for this.

## 2. A press late in the board's 30-second window (PR #36)

**Proves:** 0.9.1's 45-second wait for a visible request lets a press 20 to 30 seconds in still
reach the app.

**Setup:** a request the board holds for a press. A login challenge (kind 22242 with a `code` tag)
always is, whatever the slot allows. So is a kind outside a non-strict slot's allowed kinds. A
website sign-in through a `nostrsigner:` link, #36's original case, works too.

On the visible path Cambium treats kind 22242 as an ordinary signature. Only the silent provider
path applies its relay-AUTH rules to that kind: an exact-event cache, single admission and a
60-second circuit.

**Steps:**

1. Send the login challenge through the caller's intent. When the board's card appears, wait
   about 25 seconds, then hold the button.
2. Send a fresh challenge (new `created_at`) and do not press. The board answers `timeout` at 30
   seconds.
3. Optional, to document a known gap. Use a kind other than 22242 that is outside a non-strict
   slot's allowed kinds, so the relay-AUTH rules don't apply. Send it through an approved caller's
   silent provider query. Cambium answers "signer unavailable" after 15 seconds. Note whether the
   board still signs if you press after that. Skip this step on a strict slot, which refuses the
   kind instead.

**Pass:**

- Step 1 returns a signature that verifies.
- Step 2 ends with the board's own timeout, not a phone-side cut-off at 20 seconds.

**Record:** how many seconds into the window you pressed, and the total time.

## 3. App lock

**Proves:** the biometric and device-credential gate on real hardware. Not yet accepted
physically.

**Setup:** the phone has a screen lock (a PIN is enough; a biometric is optional). Turn on App
lock in Cambium.

**Steps:**

1. Leave Cambium for more than a minute and come back. The locked screen and prompt appear.
   Cancelling leaves it locked, with an Unlock button. Authenticating opens it.
2. Leave and come back within a minute. There is no prompt (the grace window is 60 seconds).
3. From an unapproved caller, check that Approve and Always deny need authentication and Decline
   does not.
4. With the phone's own screen locked, an approved caller's silent request still completes.
5. The activity log is behind the lock and does not appear in recents.

**Do not** remove the phone's screen lock to test the fail-open rule on the main phone. That also
destroys every phone-unlock Keystore key.

## 4. Phone unlock: enrolment and a real board unlock

**Proves:** the whole unlock path on hardware. Not yet accepted physically.

**Setup:** a board that restarts locked. Cambium may post notifications, and grant the battery
optimisation exemption when asked.

**Steps:**

1. In Sapwood, open Phones that can unlock and choose Add a phone. In Cambium, choose Set up phone
   unlock on the paired signer, then Scan Sapwood's code.
2. Compare the five words with the board's card before holding its button.
3. Compare the six-character check code with Sapwood. Authenticate to store the secret.
4. Power-cycle the board. The notification names the board, the restart reason (power-on), the
   network and the restart count.
5. Tap it and authenticate. The board unlocks.
6. Power-cycle again and ignore the notification. The board keeps asking every minute, and Cambium
   never unlocks it on its own.
7. Optional: revoke this phone in Sapwood, power-cycle and answer. After about 25 seconds Cambium
   says the board kept asking.

**Pass:** steps 4 to 6 behave as described. If step 7 was run, re-enrol afterwards.

**Record:** the time from power-on to notification, and from tap to unlocked.

**Afterwards:** keep the enrolment, and enrol a second device as the README advises.

## 5. Switching identities

**Proves:** per-identity routing and binding on hardware.

**Setup:** pair a second identity from Sapwood (each bunker URI is one identity) and label both.

**Steps:**

1. Approve the caller for identity A. A request with no `current_user` signs as A.
2. Send a request with `current_user` set to B's npub. Cambium asks, showing "Currently bound to
   A". Approve once: it signs as B, and Connected apps still shows A.
3. Send a request whose `current_user` names an identity Cambium does not hold. The provider
   answers nothing, and the intent shows the sheet instead of signing.
4. Remove B. Any app bound to B is forgotten.

**Pass:** every signature's public key is the identity expected, checked independently.

## 6. Signatures inside a batch

**Proves:** batched `sign_event` results on hardware. In 0.9.0 only `get_public_key` was batched.

**Steps:**

1. Send two or three `sign_event` requests as one batch.
2. Approve each. A caller that does not share its identity with Android gets a one-shot sheet for
   each extra request.
3. Decline one of them.

**Pass:**

- `results` keeps arrival order and IDs.
- Every signed entry carries `result` and `signature`, and each signature verifies against its own
  event.
- The declined entry is a correlated rejection.

## 7. Timed approval expiry and clock rollback (run last)

**Proves:** timed grants expire, and a clock set back fails closed.

**Steps:**

1. Approve the caller for an hour.
2. In Settings, turn off automatic time and move the clock forward 61 minutes. The next request
   asks again, and the provider answers nothing. Decline.
3. Turn automatic time back on, then approve for an hour again.
4. Turn automatic time off and move the clock back to before that approval. The next request asks
   instead of forwarding. Decline.
5. Turn automatic time back on and check the clock.

While the clock is wrong, only whether Cambium asks counts. Relays and the board may refuse events
stamped an hour out, so approve nothing until the clock is right again. The wrong clock also
affects other apps, so keep this short.

## Not testable yet

**Signer-directed relay migration.** Heartwood answers `switch_relays` with `"{}"`
(`heartwood-esp32` `firmware/src/nip46_handler.rs`). That matches the two-character string
result the 0.9.0 probe saw. Cambium correctly refuses it, so this needs a firmware change before it
can be tested.

## Afterwards

- Forget the caller's approval and uninstall it.
- Revoke the emulator's Sapwood connection, then delete the emulator. That also removes the test
  rendezvous child from section 1.
- Keep the phone-unlock enrolment from section 4.
- Decide whether to keep the second identity.
- Check that automatic time is back on.
- Write the receipt and the `FEATURE-ACCEPTANCE.md` entry, and post section 1's result on PR #35.
