# Distribution

Where Cambium is published, what each channel needs, and what remains manual. The target set is
every store Amber ships on (GitHub Releases, Obtainium, Zapstore, F-Droid) plus the stores the
GrapheneOS project recommends (Accrescent; Obtainium again). GrapheneOS's own Apps store carries
first-party GrapheneOS apps only, so it is not a target.

Licence: MIT (`LICENSE`, detected by GitHub). Donations: GitHub Sponsors
(`https://github.com/sponsors/TheCryptoDonkey`), Geyser (`https://geyser.fund/project/forgesworn`)
and Ko-fi (`https://ko-fi.com/brays`), all declared in `.github/FUNDING.yml`; the Sponsors URL is
what store metadata points at (F-Droid's `Donate:` field takes one URL).

| Channel | Status | Signature users get |
|---|---|---|
| GitHub Releases | **Live** (0.9.0 current; v0.2.0 onward) | Ours (the 0.2.0 trust root) |
| Obtainium | **Live** via the 0.9.0 GitHub release | Ours |
| Zapstore | **Live** (0.9.0 current; 0.6.0 and 0.7.0 were skipped; v0.3.2 onward) | Ours |
| F-Droid | **Live** (0.7.1/versionCode 17 verified 2026-10-13; 0.8.0 and 0.9.0 awaiting automatic rebuild and publication) | Ours, when the reproducibility check passes |
| IzzyOnDroid (optional extra) | Eligible; their tracker moved to Codeberg (account needed) | Ours |
| Accrescent | **Blocked externally** — registration is allowlist-only | Ours |

## GitHub Releases (live)

[0.9.0](https://github.com/forgesworn/cambium/releases/tag/v0.9.0), versionCode 19, was built from the clean tagged commit
`40927a04bbb5e0efa9bf846d4d5b7f86ee07c6c4`. The public APK and `SHA256SUMS` were downloaded and
verified on 2026-10-13. Its APK SHA-256 is
`3510781a23ec1e3c626a9c39ee31f968f4d4ca38d85f626b85c0d617c29fc16a` (19,880,268 bytes). It is signed,
non-debuggable, contains arm64-v8a and x86_64, omits VCS metadata, and retains the signing
certificate used by previous releases and the 0.2.0 trust root below.

The release source passed 330 JVM tests and all 40 Android instrumentation tests, followed by
all 16 UI regressions at 320 dp width and 150% text scaling, with no failures, errors or skips.
Both [PR CI](https://github.com/forgesworn/cambium/actions/runs/38385862632) and
[main CI](https://github.com/forgesworn/cambium/actions/runs/38387503564) passed. The PR build needed a retry after dependency
resolution failed before tests began. The signed release build and release lint passed locally.
The actual SDK is tested against a two-relay local WebSocket signer fixture. The published APK
also passed physical Pixel/Heartwood signing, NIP-04/NIP-44 round trips, consent/scope controls,
grant duration editing, a two-request public-key batch, diagnostics review and reconnect checks
on 2026-10-13. Initial signing and silent-path timeouts recovered on retry/reconnect. Physical
biometric and new phone-unlock enrolment/board-unlock journeys remain unexercised; see the
[acceptance scope](FEATURE-ACCEPTANCE.md#physical-acceptance-13-october-2026) and
[physical receipt](acceptance/0.9.0-physical-2026-10-13.json).

The [public delivery receipt](releases/0.9.0.json) records the source, signatures, hashes, test
counts, channel readbacks and physical acceptance limit.

[0.8.0](https://github.com/forgesworn/cambium/releases/tag/v0.8.0), versionCode 18, was built from
the clean tagged commit `92afdb1a90a7c527adf610facd7a00ce130b2148`. The public APK and `SHA256SUMS`
were downloaded and verified on 2026-10-13. Its APK SHA-256 is
`81685c955f4ee164de761a196533307e5bd360190c2d1ad53c12266c237e5a11` (19,790,284 bytes). It is
non-debuggable, contains arm64-v8a and x86_64, omits VCS metadata, and uses the same signing
certificate as the previous public APK and the 0.2.0 trust root below.

The 0.8.0 source passed 271 JVM tests and all 11 Android checks in both
[PR CI](https://github.com/forgesworn/cambium/actions/runs/38318772846) and
[main CI](https://github.com/forgesworn/cambium/actions/runs/38319136838). The UI review also passed
all nine UI flows in dark mode and at 320dp/200% text. An additional local shipping-time repeat
was inconclusive: the disposable emulator had startup ANRs, then timed out in its first UI flow.
Cambium signing, enrolment and phone unlock against a physical Heartwood were not re-tested.

0.7.1 was published and read back on 2026-09-26, built from the tagged commit (`d2fc9c3`) in a
clean tree. Its APK SHA-256 is
`f95512296a1b6421dbcce9fa7346cd82fc9c177132f0ea5a70eea9d035231f1d`, and its signing certificate
matches the 0.2.0 trust root below. The previous release, 0.7.0 (2026-09-26, `6559364`), has SHA-256
`341b1f77ccd288a19606b7a4a8207c83120b8842dc5fda4cf33a325efe46ca7a`.

The existing flow: bump `versionCode`/`versionName`, tag `vX.Y.Z`, build with the release
keystore (`~/keystores/cambium-release.credentials`), upload the APK plus `SHA256SUMS`, and
include the AppVerifier block in the release notes:

```
dev.forgesworn.cambium
9E:A1:88:EF:A9:01:5F:7E:7F:90:E1:88:8F:58:6F:52:7B:2A:0E:8A:6D:CD:B3:99:1E:41:FB:4F:14:EE:EF:C6
```

## Obtainium (live)

Nothing to maintain — Obtainium tracks GitHub Releases directly. One-tap add link:

```
obtainium://add/https://github.com/forgesworn/cambium
```

## Zapstore (live)

Version 0.9.0 was published through the existing publisher Heartwood connection and read back
on 2026-10-13. The admitted client key resumed without replaying a pairing secret. The app,
release and file event hashes and signatures verify under the publisher identity below. Its
file event carries versionCode 19, the 0.9.0 SHA-256, source commit and trust-root certificate;
the CDN APK matches GitHub byte for byte.

- App event: `c19a52b85395f6ac7044be479c12fff1e2a1a61ce1969bead5538be4aed5fc3a`
- Release event: `abc598f2ee8c95fb55245de00e0f5f78e927bf80a1ab67786dacd4fd4a690257`
- File event: `2920b14db864fec4b1b99d8b8e5b0c2bc2944f578d185c159ceaf39032eb9ec5`

Version 0.8.0 was published through the publisher Heartwood bunker connection and read back on
2026-10-13. Its live kind 3063 file event carries versionCode 18, the 0.8.0 SHA-256 and source
commit above, and the trust-root certificate. The app, release and file event hashes and
signatures were verified under the publisher identity below. The CDN APK was downloaded and
matches GitHub byte for byte.

- App event: `ce5045b48ff6c2c4aad4af4dca68903440956aa3994769f6afcf9a47bf4becdb`
- Release event: `07c9216490b23cfcdcf246aac766c22dd6b85c587ec9904ce39a0c6e3e75e247`
- File event: `32285841977d28a069f82ee123a8da9b517b88cb821a027b341a69bd7ba35be7`

Version 0.7.1 was published on 2026-09-26 through the publisher Heartwood bunker connection, with
the owner approving on the board. Its kind 3063 event carries SHA-256
`f95512296a1b6421dbcce9fa7346cd82fc9c177132f0ea5a70eea9d035231f1d`, version code 17 and the
trust-root certificate, and the CDN copy read back byte-identical. 0.6.0 and 0.7.0 were never
published there.

Version 0.5.0 was published on 2026-09-24 through the publisher Heartwood bunker connection (the
first attempt timed out on the Blossom upload-auth signature while no one was at the board; a
retry with the owner approving on the Heartwood went through). The previous release, 0.4.3, went
out on 2026-08-14. The
kind 32267 app, kind 30063 release and kind 3063 file events are live on `wss://relay.zapstore.dev`
under the publisher identity below. The CDN serves the APK byte-identically to GitHub (SHA-256
`857453f021569c9dffc59afc96e295e4b96afe21cd49224b1aecaaa06d65e983`) and the matching icon (0.5.0: SHA-256 `9ccbf226…d182`, read back byte-identical). The
catalog page may take a short while to refresh its release list after relay publication.

**First published 2026-07-14 (v0.3.2), signed via a Heartwood `bunker://` connection** — the
release events (kind 32267 app, 30063 release, 3063 file) are on `wss://relay.zapstore.dev`
under `npub1mgvlrnf…` and the APK on Zapstore's CDN is byte-identical to the GitHub release
(same SHA-256). App page:

https://zapstore.dev/apps/naddr1qqtxgetk9enx7un8v4ehwmmjdchxxctdvf5h2mgprpmhxue69uhhyetvv9uju7npwpehgmmjv5hxgetkqgsd5x03e56tajjyhe6d5jesdkw3mkrtvdpua72vugkyn3h4nqtwt0grqsqqqlstem32ln

`zapstore.yaml` at the repo root is the publishing config. The CLI is `zsp`
(`go install github.com/zapstore/zsp@latest`). To publish each future release, after the GitHub
release is up:

```bash
SIGN_WITH=<nsec1... | bunker://...> zsp publish --quiet --skip-preview zapstore.yaml
```

`SIGN_WITH` also accepts a `bunker://` URI, so the release events can be signed by Heartwood
itself rather than a raw nsec on this machine — fitting, and worth doing. Publishing signs three
Nostr events (kind 32267 app metadata, 30063 release, 3063 file metadata) under that npub and
uploads the APK to Zapstore's Blossom CDN.

Publisher identity: `npub1mgvlrnf5hm9yf0n5mf9nqmvarhvxkc6remu5ec3vf8r0txqkuk7su0e7q2`
(NIP-05 `darren@600.wtf`, verified to resolve to this key). Zaps on Zapstore flow to the
publishing npub's profile Lightning address — before the first publish, check the kind-0
profile carries `lud16: profusemeat89@walletofsatoshi.com` so zaps actually route.

The NIP-C1 key link (`zsp identity --link-key`, tying the APK signing certificate to the npub)
was published 2026-08-13: kind 30509 on the zapstore, damus and primal relays, expiring
2027-08-13 — renew it then. Headless note: `zsp identity` has no quiet mode, so sign with
`--offline | nak event <relays>` and feed `--verify` the npub on stdin; the JKS must be
converted to a temporary PKCS12 for it (`keytool -importkeystore`), deleted after use.

Each future release: run the same command after the GitHub release is up (the config pulls the
latest release), or add it as a release-checklist step.

## F-Droid (live)

The [inclusion merge request](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/42875) was
merged on 2026-09-13. The public package API lists 0.7.1/versionCode 17 and 0.4.3/versionCode 13
(verified 2026-10-13); 0.8.0/versionCode 18 and 0.9.0/versionCode 19 are not listed yet. From here
`AutoUpdateMode: Version` picks up each `vX.Y.Z` tag, rebuilds it, and publishes our signed APK
from `Binaries:` (`releases/download/v%v/cambium-%v.apk`) only if the rebuild matches it: build
the release APK from the exact tagged commit with a clean tree, and name the asset exactly so.
The live [build recipe](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/dev.forgesworn.cambium.yml)
has picked up 0.8.0/versionCode 18, but has no 0.9.0/versionCode 19 entry yet. The latest completed
[build status](https://f-droid.org/repo/status/build.json), checked at 12:28 UTC on 2026-10-13,
ended at 00:25 UTC on 2026-10-12, before these releases. No current build failure is established;
automatic tag discovery, rebuilding and publication remain external gates.
The history below is kept for reference.

0.5.0 to 0.7.0 were never listed. F-Droid's checkupdates bot opened fdroiddata!50093 for 0.5.0,
and its rebuild matched our APK except for `META-INF/version-control-info.textproto`: ours said
`NO_VALID_GIT_FOUND` (built from a git worktree, where the Android Gradle plugin finds no
repository), F-Droid's recorded the commit. From 0.7.1 the release build type sets
`vcsInfo.include = false`, so that file is not written at all and the checkout no longer matters.
0.6.0 and 0.7.0 have the same difference and cannot be fixed after the fact.

### Submission history

Everything F-Droid reads from the app repo is now in place: `fastlane/metadata/android/en-US/`
(title, summary, full description, per-versionCode changelogs, 512×512 icon). Tags are clean
(`vX.Y.Z`), the licence is MIT, and every dependency comes from Maven Central/Google — including
`org.rust-nostr:nostr-sdk`, whose prebuilt native AAR has direct precedent: Amber is in official
F-Droid with comparable prebuilt native dependencies from Maven Central.

The submission is **done** (2026-07-14): the merge request is
[fdroiddata!42875](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/42875), filed from the
fork `TheCryptoDonkey/fdroiddata` (branch `cambium`) under the GitLab account `TheCryptoDonkey`
(created that day via GitHub OAuth). `fdroid lint dev.forgesworn.cambium` passes locally
(fdroidserver 2.4.5); the fork's own pipeline cannot run until the new GitLab account is
verified for shared runners, which reviewers were told on the MR. Review typically takes days
to a few weeks; reviewers may adjust the build recipe (e.g. a JDK 21 install block, as Amber's
entry carries). Future releases need no new MR, but **while the MR is still open the recipe has
to track each new release** — add a `Builds:` entry for the new tag and bump
`CurrentVersion`/`CurrentVersionCode`, in `docs/fdroid/dev.forgesworn.cambium.yml` here and in the
`cambium` branch of the `TheCryptoDonkey/fdroiddata` fork. Only once it is merged does
`AutoUpdateMode: Version` take over and track our tags unattended.

The local draft recipe and the open MR now include 0.4.3/versionCode 13 at source commit
`79b2b36b`; the fork's `cambium` branch was updated to commit `269da314` on 2026-08-14. Its reviewed recipe uses `Binaries:` plus
`AllowedAPKSigningKeys:` and pins each build to an exact source commit. If
F-Droid's rebuild matches the upstream APK, F-Droid publishes our signed binary; if it does not
match, that version is skipped rather than replaced with an F-Droid-signed build.

Two caveats to state in the MR and be aware of:

- **Reproducibility is now the listing gate.** The recipe asks F-Droid to rebuild from source,
  compare the result with our GitHub APK, and publish only the matching upstream-signed binary.
  That keeps GitHub, Zapstore and F-Droid upgrade-compatible, but a non-reproducible version will
  be skipped until its difference is fixed.
- `AutoUpdateMode: Version` means future tagged releases are picked up automatically **once the
  MR is merged**; only the first listing needs a human. Until then, every release needs the recipe
  updating by hand (see above).

## IzzyOnDroid (optional, low effort)

Not an Amber channel, but popular with the GrapheneOS crowd and served through F-Droid clients.
It indexes our own signed release APKs straight from GitHub Releases (no rebuild, no re-signing,
usually indexed within days). The 19.6 MB APK is within their per-app size budget. Their old
GitLab tracker is now read-only — submissions moved to
https://codeberg.org/IzzyOnDroid/repodata (open an issue with the app-inclusion template; a
Codeberg account is needed). Ready-to-paste text is in `docs/fdroid/merge-request.md`. Nothing
in-repo is missing.

## Accrescent (externally blocked, prerequisites met)

Accrescent is the store the GrapheneOS project recommends first, but developer registration is
currently allowlist-only and they are **not accepting new allowlist requests** (their stated
position as of July 2026, while the console matures). There is no submission to make today.
Watch https://accrescent.app and their blog for registration opening.

Everything on our side is already satisfied, so when it opens this is quick:

- `targetSdk` 35 ✓; release-signed, non-debuggable ✓; no cleartext traffic ✓.
- 512×512 PNG icon ✓ (`fastlane/.../images/icon.png`, source `assets/icon.svg`).
- Upload format is an APK set from `bundletool` (≥ 1.11.4), well under the size cap.
- App-ID domain verification: `dev.forgesworn.cambium` requires proving control of
  `forgesworn.dev` — we control it (cambium.forgesworn.dev is ours).
- No sensitive permissions beyond CAMERA (QR pairing, runtime-requested) and
  POST_NOTIFICATIONS/foreground service for the opt-in keep-warm toggle (also started while a
  board is set up for phone unlock) — none of the
  heavily-restricted categories (VPN, accessibility).

## Release checklist (all channels, once live)

1. Bump `versionCode`/`versionName`, update `CHANGELOG.md`, and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Tag `vX.Y.Z`, build, publish the GitHub release (APK + SHA256SUMS + AppVerifier block).
3. `SIGN_WITH=... zsp publish --quiet --skip-preview zapstore.yaml`, with someone at the Heartwood
   to confirm the signing request.
4. Nothing to submit for F-Droid: the merged recipe tracks tags. Check a few days later that the
   new version appears (`https://f-droid.org/api/v1/packages/dev.forgesworn.cambium`). If it does
   not, inspect the update/build status and reproducibility result before calling it a mismatch.
5. Obtainium tracks GitHub directly. IzzyOnDroid can do the same once the optional listing exists.
