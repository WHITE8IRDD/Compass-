# Verification status

**Status date: 2026-10-16.** Source, static checks, and an offline package
were validated in the Arena sandbox. No Android device, emulator, or `adb`
is available here, so runtime storage, UI, biometric, playback, and lifecycle
behavior remain unverified.

## Checks run

### Kotlin source compilation

All Kotlin sources compiled against the bootstrapped Android API 34
`android.jar` and bundled coroutines library with JDK 17:

```bash
kotlinc -classpath android.jar:kotlinx-coroutines-core-jvm.jar \
  -jvm-target 17 -d /tmp/compass-classes \
  app/src/main/java/com/compass/vault
```

**Result:** 0 errors. The compiler reported existing deprecation/redundancy
warnings (framework back handling, legacy fingerprint callbacks, window
insets, and one numeric conversion). This validates Kotlin types and syntax,
not Android runtime behavior or API 35 compatibility.

### Security and transaction self-tests

```bash
TOOLCHAIN=/path/to/toolchain ./tools/run-selftest.sh
```

The production AES-GCM, PIN hashing, and wipe helpers run on a desktop JVM
with a test-only JCE key. Static startup/file-flow checks scan source code.

- **23 / 23 crypto assertions passed.**
- **27 / 27 startup and transaction-order assertions passed.**

These tests do not exercise Android Keystore, `ContentResolver`, MediaStore,
SAF providers, runtime permissions, or device biometric APIs.

### Offline resource/package validation

`tools/build-offline.sh` completed without signing. The generated artifact
was kept outside the Git repository at
`/home/user/.cache/compass-tc/offline-apk/Compass-Enhanced-0.2-unsigned.apk`.

- Size: **4,173,451 bytes**
- SHA-256: `b174630f3ee3b6f49a87ee4a8a28af49ec22fc46a80fe624c5afdea2514a1426`
- Python `zipfile.testzip()`: **no corrupt entry**
- Contains `classes.dex`: **yes** (3,697,788 bytes)
- `aapt2 dump badging`: package `com.compass.vault`, version `0.2`, code `3`, min SDK `26`, target SDK `35`
- Manifest permissions include the expected biometric, haptic, legacy read/write-limited, modern media read, and Android 14 selected-visual-media declarations.
- Manifest/DEX scan: **no `INTERNET` permission/string found** in the checked DEX.
- Signature verification: **not applicable; this APK is unsigned** and is not a distributable release.

The sandbox's bootstrapped Kotlin compiler is newer than the project Gradle
plugin, while its D8/R8 is older. D8 emitted Kotlin-metadata parsing warnings
but completed packaging. The available Android platform is API 34 although
the Gradle project configures compile SDK 35. A normal Gradle build with the
project's supported toolchain is still required before any release decision.

## Release state and signing

The existing GitHub release **COMPASS 0.2**, tag `v0.2`, remains Latest.
The tag and APK bytes are unchanged; the release notes were updated with a
prominent download link and a replacement signing warning. The release page
still has no attached binary assets: `gh release upload` failed because the
build environment's connection to `uploads.github.com` returned EOF. The
release notes link directly to the APK stored in the tag.

The checked-in `dist/Compass-0.2.apk` is the existing published artifact,
not this source build. Its size is 4,052,764 bytes, its SHA-256 is
`0d2e11e3674cb5651f692bfc5b96e9a130dff18a06e98c846e2e72e80df30f25`, its
ZIP integrity passed, and `apksigner verify --verbose --min-sdk-version 23`
verified v1, v2, and v3 signatures. The signing material was tracked in
repository history and is treated as exposed; do not reuse that identity.
No signed APK of the current source or new tag/release was produced.

## Not yet tested on a device

Before trusting this build with valuable files, test the following on Android
versions spanning API 26–28, 29–32, 33, and 34–35 as available. Use throwaway
files and retain an independent backup.

### Permissions and media library

- [ ] Deny all shared-media permissions; verify individual SAF file selection still works.
- [ ] Grant only images, only video, only audio, and Android 14 selected-visual-media access; confirm Compass shows the accessible subset and labels partial access accurately.
- [ ] Change/revoke access in Android Settings while Compass is running and after a restart; verify absence is never treated as proof of deletion.
- [ ] Exercise All Media paging, search, all sort orders, thumbnails, folders, and provider/query failures.

### Import, removal, and restore

- [ ] Import image, video, audio, large file, zero-byte file, SAF document, and multiple selected files.
- [ ] Simulate a provider read error and app cancellation; verify the public source is never removed and failed temporary data is cleaned up.
- [ ] Confirm Android's system delete-consent cancellation leaves the item marked public; approve deletion and verify that Compass only reports confirmed removal after the source is unreadable/absent.
- [ ] Retry removal for a previously public source.
- [ ] Restore to the recorded MediaStore folder and to a SAF destination; compare the restored bytes with the original.
- [ ] Force destination write/read-back failures; verify the encrypted copy remains and partial public output is reported/cleaned where the provider permits.
- [ ] Test same-name conflicts, an existing unchanged source, a changed source at the original URI, removed SAF grants, and revoked media permissions.

### Authentication, playback, and lifecycle

- [ ] Test PIN, biometric success/cancel/error/no-enrollment, and fallback on supported Android versions/devices.
- [ ] Test protected image/video/audio view, public image/video/audio preview, seek/pause/completion, and unsupported codecs.
- [ ] Navigate away, background the app, trigger auto-lock, and force-stop during playback/import/restore; verify the UI is gated and cache cleanup runs.
- [ ] Verify `FLAG_SECURE` behavior on the target device (OS/OEM screen-capture behavior can differ).
- [ ] Test corrupted ciphertext/metadata and Keystore key invalidation; verify Compass surfaces an error and does not present a damaged vault as empty.

## Verification commands

With a compatible Android SDK/toolchain, inspect a built APK using:

```bash
aapt2 dump badging path/to/app.apk
aapt2 dump permissions path/to/app.apk
apksigner verify --verbose path/to/app.apk
sha256sum path/to/app.apk
```

Only claim a signed release after the official build, signer identity,
`apksigner` result, APK checksum, device testing, and GitHub release state
have all been separately confirmed.
