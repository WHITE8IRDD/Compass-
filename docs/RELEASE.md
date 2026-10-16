# Release status

## Target release: Compass 2.4

**Compass 2.4** is the exact intended release name for this source candidate
(`versionName` `2.4`, `versionCode` `24`). It is **not published** and is not
release-ready from the checks performed here.

- Default offline output: `build/offline/out/Compass-2.4-unsigned.apk`.
- A separate sideload-test APK was generated at the session-local,
  Git-ignored path `downloads/Compass-2.4-test.apk`; it was signed with a fresh
  temporary test key and verified with `apksigner`. It is not committed or
  uploaded to GitHub, and is not an owner-signed release.
- No Compass 2.4 GitHub release was created by this work.
- No owner-controlled release signature, device test, emulator test, or normal
  Gradle release build was produced.
- The offline build had D8 Kotlin-metadata warnings and used Android API 34
  stubs while the Gradle project declares compile SDK 35.

Do not distribute the temporary test-signed artifact as a release or represent
compilation/signature verification as proof that file operations or
authentication work on Android. The one-off test keystore was deleted, so this
APK cannot update a later build; its signature also cannot update the
historical v0.2 install. Before publication, require a compatible official
build, a freshly provisioned owner-controlled signing key, signature and
checksum verification, the manual device tests in [VERIFICATION.md](VERIFICATION.md),
and explicit release approval. Do not reuse historical signing material.

## Existing public release

GitHub currently lists **COMPASS 0.2** at tag [`v0.2`](https://github.com/WHITE8IRDD/Compass-/releases/tag/v0.2) as Latest. It is an older, separate artifact and has not been overwritten by the Compass 2.4 work.

- Attached APK: [`Compass-0.2.apk`](https://github.com/WHITE8IRDD/Compass-/releases/download/v0.2/Compass-0.2.apk)
- Attached checksum: [`Compass-0.2.apk.sha256`](https://github.com/WHITE8IRDD/Compass-/releases/download/v0.2/Compass-0.2.apk.sha256)
- Repository APK size: **4,052,764 bytes**
- Repository APK SHA-256: `0d2e11e3674cb5651f692bfc5b96e9a130dff18a06e98c846e2e72e80df30f25`

The repository copy's ZIP integrity and historical signature schemes were
previously checked. That is not verification of the Compass 2.4 source or
artifact. The old signing identity was exposed in repository history; do not
reuse it. A new signing identity may not update installations signed by the
old key.

## Release safeguards

- Preserve the existing `v0.2` tag/release and APK bytes.
- Do not attach the temporary test-signed Compass 2.4 APK as a public release asset.
- Do not generate or commit signing keys/passwords, and never request them in
  chat.
- Publish only after the verification gates above are completed and the owner
  approves the exact release name **Compass 2.4**.
