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
| GitHub Releases | **Live** (v0.2.0 onward) | Ours (the 0.2.0 trust root) |
| Obtainium | **Live** via GitHub Releases | Ours |
| Zapstore | **Live** (0.3.2, published 2026-07-14) | Ours |
| F-Droid | **MR open: [fdroiddata!42875](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/42875)** — awaiting review | F-Droid's own key |
| IzzyOnDroid (optional extra) | Eligible; their tracker moved to Codeberg (account needed) | Ours |
| Accrescent | **Blocked externally** — registration is allowlist-only | Ours |

## GitHub Releases (live)

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

**First published 2026-07-14 (v0.3.2), signed via a Heartwood `bunker://` connection** — the
release events (kind 32267 app, 30063 release, 3063 file) are on `wss://relay.zapstore.dev`
under `npub1mgvlrnf…` and the APK on Zapstore's CDN is byte-identical to the GitHub release
(same SHA-256). App page:

https://zapstore.dev/apps/naddr1qqtxgetk9enx7un8v4ehwmmjdchxxctdvf5h2mgprpmhxue69uhhyetvv9uju7npwpehgmmjv5hxgetkqgsd5x03e56tajjyhe6d5jesdkw3mkrtvdpua72vugkyn3h4nqtwt0grqsqqqlstem32ln

`zapstore.yaml` at the repo root is the publishing config. The CLI is `zsp`
(`go install github.com/zapstore/zsp@latest`). To publish each future release, after the GitHub
release is up:

```bash
SIGN_WITH=<nsec1... | bunker://...> zsp publish zapstore.yaml
```

`SIGN_WITH` also accepts a `bunker://` URI, so the release events can be signed by Heartwood
itself rather than a raw nsec on this machine — fitting, and worth doing. Publishing signs three
Nostr events (kind 32267 app metadata, 30063 release, 3063 file metadata) under that npub and
uploads the APK to Zapstore's Blossom CDN.

Publisher identity: `npub1mgvlrnf5hm9yf0n5mf9nqmvarhvxkc6remu5ec3vf8r0txqkuk7su0e7q2`
(NIP-05 `darren@600.wtf`, verified to resolve to this key). Zaps on Zapstore flow to the
publishing npub's profile Lightning address — before the first publish, check the kind-0
profile carries `lud16: profusemeat89@walletofsatoshi.com` so zaps actually route.

Afterwards, consider `zsp identity --link-key` (NIP-C1) to link the APK signing certificate to
the npub, which upgrades Cambium's verification status in the Zapstore client.

Each future release: run the same command after the GitHub release is up (the config pulls the
latest release), or add it as a release-checklist step.

## F-Droid (submission ready)

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
entry carries). Future releases need no new MR — `AutoUpdateMode: Version` tracks our tags.

Two caveats to state in the MR and be aware of:

- **F-Droid signs its own builds.** The F-Droid APK will not be upgrade-compatible with our
  GitHub/Zapstore APK (different signer). That is normal (Amber is in the same position). The
  later alternative — reproducible builds with `Binaries:` + `AllowedAPKSigningKeys`, so F-Droid
  ships our signature — is worth pursuing once the build is proven bit-reproducible, but is not
  a prerequisite for listing.
- `AutoUpdateMode: Version` means future tagged releases are picked up automatically; only the
  first listing needs a human.

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
  POST_NOTIFICATIONS/foreground service for the opt-in keep-warm toggle — none of the
  heavily-restricted categories (VPN, accessibility).

## Release checklist (all channels, once live)

1. Bump `versionCode`/`versionName`, update `CHANGELOG.md`, and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Tag `vX.Y.Z`, build, publish the GitHub release (APK + SHA256SUMS + AppVerifier block).
3. `SIGN_WITH=... zsp publish zapstore.yaml`.
4. Obtainium, F-Droid and IzzyOnDroid pick the release up automatically.
