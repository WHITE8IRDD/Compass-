# Release status

## Compass 2.4 — published (pre-release)

**Release name:** Compass 2.4 · **tag:** `v2.4` · **package:** `com.compass.vault` ·
**versionName:** 2.4 · **versionCode:** 24

- GitHub release: `https://github.com/WHITE8IRDD/Compass-/releases/tag/v2.4`
- **Assets: none.** The build environment can reach `api.github.com` but not
  `uploads.github.com` (asset upload host), so the binary could not be
  attached. The release body links the identical files committed at the tag:
  - `https://github.com/WHITE8IRDD/Compass-/raw/v2.4/dist/Compass-2.4.apk`
  - `https://github.com/WHITE8IRDD/Compass-/raw/v2.4/dist/Compass-2.4.apk.sha256`
  - Owner command to attach them properly:
    `gh release upload v2.4 dist/Compass-2.4.apk dist/Compass-2.4.apk.sha256`
- Repository copy: `dist/Compass-2.4.apk`
- SHA-256: `d7d11f41c69263394690b256721a2c09470912ccf69fb8f633c2765094ce5578`
- Signer: RSA 4096, `CN=Compass, OU=Compass Android releases`,
  certificate SHA-256 `88ed48c454d52f62efa6d0299c3b7a26916c5644f991d7ac14356270a8c507be`,
  APK Signature Scheme v2 + v3 verified
- Marked **pre-release**, so the older **COMPASS 0.2** (`v0.2`) remains the
  GitHub "Latest" release until the manual device checklist has been run.

Why it is a pre-release: the APK was compiled, packaged, aligned and signed
in an environment that has **no device, no emulator and no `adb`**, so it has
never been installed or launched. The automated tests (135 assertions,
including the end-to-end file transaction on the JVM) and the static checks
pass, but MediaStore/SAF runtime behaviour, Android's delete-consent dialogs,
biometric prompts, lifecycle and performance are unverified on real hardware.
See [VERIFICATION.md](VERIFICATION.md) for the full list and for the manual
device test plan. Promoting the release to "Latest" is one click once that
checklist has been executed.

## Signing material

The 0.1/0.2 signing material was tracked in repository history and is
considered exposed. It must not be reused, and it must not be deleted from
history by a rewrite for this release either - the existing `v0.1`/`v0.2`
tags and their APKs stay exactly as they are.

Compass 2.4 is signed with a **new** key generated in the build sandbox:

- Keystore: `compass-signing/compass-release.jks` (PKCS#12, RSA 4096, alias
  `compass-release`, valid until 2056) - outside this repository
- Password: `compass-signing/passwords.env` - same directory, mode 600
- Instructions: `compass-signing/README.txt`

Both files must be stored somewhere safe by the project owner. **Every future
update of `com.compass.vault` must be signed with this key**; if it is lost,
no update can be installed over an existing Compass 2.4 installation.

Rebuild and sign:

```bash
cd /path/to/Compass-
set -a; . /path/to/compass-signing/passwords.env; set +a
TOOLCHAIN=/path/to/toolchain ./tools/build-offline.sh
# -> build/offline/out/Compass-2.4.apk
```

Without the four `COMPASS_RELEASE_*` variables the same script produces an
unsigned `build/offline/out/Compass-2.4-unsigned.apk`, which is **not**
installable and must not be published.

## Installation warning

Because the signer changed, Android refuses this APK as an update of an
installation signed with the 0.1/0.2 key. It installs only as a fresh
install, and uninstalling an app erases its private vault data. Export or
unlock anything important from an older installation first - but note that
the old version had no verified unlock path, so treat such files as copies.

## Existing public release

GitHub lists **COMPASS 0.2** at tag [`v0.2`](https://github.com/WHITE8IRDD/Compass-/releases/tag/v0.2).
It is an older, separate artifact and has not been overwritten by the
Compass 2.4 work.

- Attached APK: [`Compass-0.2.apk`](https://github.com/WHITE8IRDD/Compass-/releases/download/v0.2/Compass-0.2.apk)
- Attached checksum: [`Compass-0.2.apk.sha256`](https://github.com/WHITE8IRDD/Compass-/releases/download/v0.2/Compass-0.2.apk.sha256)
- Repository APK SHA-256: `0d2e11e3674cb5651f692bfc5b96e9a130dff18a06e98c846e2e72e80df30f25`

## Release safeguards

- Preserve the existing `v0.1`/`v0.2` tags and APK bytes.
- Never attach a debug or unsigned APK to a release.
- Never commit signing keys or passwords to the repository or paste them into
  chat/issues.
- Attach a `.sha256` file next to every APK, and record the verification
  results in [VERIFICATION.md](VERIFICATION.md) before changing the release
  status from pre-release to Latest.
