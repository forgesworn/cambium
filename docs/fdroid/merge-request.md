# Ready-to-paste text for the fdroiddata merge request

Branch name: `cambium` · Target: `fdroid/fdroiddata:master` · File to add:
`metadata/dev.forgesworn.cambium.yml` (content: `dev.forgesworn.cambium.yml` in this directory).

## MR title

```
New app: Cambium
```

## MR description

```
### New app: Cambium

Android NIP-55 signer proxy that holds no user keys — every signing request is
forwarded to a paired Heartwood hardware signer over NIP-46 (Nostr relays).

- Source: https://github.com/forgesworn/cambium
- Licence: MIT
- App ID: dev.forgesworn.cambium
- Author: I am the upstream author of this app
- Fastlane metadata (summary, description, changelogs, icon) is in the app repo
- Tags follow `vX.Y.Z`; AutoUpdateMode: Version should pick up future releases

Notes for reviewers:
- Depends on org.rust-nostr:nostr-sdk from Maven Central, which ships a prebuilt
  native AAR — same situation as existing signer apps (e.g. Amber,
  com.greenart7c3.nostrsigner, whose recipe this one mirrors).
- Build needs JDK 21 (AGP 8.x, jvmToolchain(21)); happy to adjust the recipe to
  whatever the buildserver expects.
- Releases are also self-published (GitHub/Zapstore) under our own key; we are
  aware the F-Droid build will carry F-Droid's signature. A reproducible-builds
  follow-up (AllowedAPKSigningKeys) may come later.
```

## IzzyOnDroid inclusion issue (https://gitlab.com/IzzyOnDroid/repo/-/issues/new)

Title:

```
[App inclusion] Cambium — dev.forgesworn.cambium
```

Body:

```
Repo: https://github.com/forgesworn/cambium
App ID: dev.forgesworn.cambium
Licence: MIT
Signed release APKs are attached to GitHub releases (currently v0.3.2, ~19.6 MB),
with SHA256SUMS and the signing-certificate digest in the release notes.
Fastlane metadata is in the repo. I am the upstream author.
```
