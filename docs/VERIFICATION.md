# Compass 2.4 verification status

**Status date: 2026-10-16.**

This document records exactly what was checked for Compass 2.4, with the
commands and the raw numbers, and - just as important - what was **not**
checked. Nothing in this file is an estimate.

## Summary

| Area | Result |
|---|---|
| Automated self-tests | **135 / 135 assertions passed** (4 programs, exit code 0) |
| Offline Kotlin compile | **0 errors** (Kotlin 2.0.21, API 34 stubs) |
| Resource link + dex + package | **passed**, D8 emitted Kotlin-metadata warnings (see below) |
| ZIP integrity of the release APK | **passed**, 22 entries |
| Zip alignment of stored entries | **passed**, every stored entry (incl. `resources.arsc` and `classes.dex`) starts on a 4-byte boundary |
| APK signature | **verified**, APK Signature Scheme v2 and v3, 1 signer, RSA 4096 |
| `INTERNET` permission | **not declared** and the string is **not present** in `classes.dex` |
| `MANAGE_EXTERNAL_STORAGE` | **not declared** and **not present** in `classes.dex` |
| Installation on a device or emulator | **NOT performed** - no device, emulator or `adb` is available in this environment |
| Android runtime behaviour (providers, consent dialogs, biometrics, lifecycle, playback, performance) | **NOT performed** |

A build that compiles, links, packages and signs is still not proof that a
file was encrypted, removed or restored on a real phone. Every runtime claim
below is marked accordingly.

## 1. Toolchain

The build sandbox has no Android SDK, no Gradle distribution and no network
access to Google Maven, so the project is compiled with the individual tools
of an offline toolchain (`tools/bootstrap-toolchain.sh`):

- JDK 17.0.9 (`java`, `keytool`)
- Kotlin compiler 2.0.21 + `kotlinx-coroutines-core` 1.8.0
- Android platform stubs: **API 34** (`android.jar`)
- `aapt2`, `d8` (from R8 8.2.2-dev), `apksigner`, `ecj` (for the generated
  `R.java`)

Two consequences have to be stated plainly:

1. The Gradle project declares `compileSdk 35`; the offline build compiles
   against **API 34** stubs. Every API the app calls is available in API 34,
   so this does not change behaviour, but the two builds are not byte
   identical to an AGP build.
2. D8 prints `Kotlin metadata` parse/rewrite warnings for Kotlin 2.0 classes
   (the bundled R8 predates Kotlin 2.0). They concern metadata annotations
   only; the class files were written and the APK is valid. A normal AGP
   release build would not show them.

## 2. Automated self-tests

```bash
TOOLCHAIN=/path/to/toolchain ./tools/run-selftest.sh
```

Exit code **0**, 135 assertions, no failures:

| Program | What it actually executes | Assertions |
|---|---|---|
| `CryptoSelfTest` | real AES-256-GCM stream encryption/decryption with a desktop JCE key: round trips, IV handling, truncation and tamper rejection, PBKDF2 PIN hashing, constant-time compare, wipe helpers | 24 |
| `LibraryLogicTest` | the pure rules layer: file classification (MIME/extension), conflict naming, search matching, size/duration/location formatting, type badges | 34 |
| `VaultTransactionTest` | the real file transaction on the JVM: stream encrypt a 9 MB source into the vault, decrypt the ciphertext again and compare byte count + SHA-256, atomic publish, restore with read-back verification, deletion of the encrypted copy only after verification, plus the failure cases (truncated ciphertext, tampered byte, failed write, cross-directory publish) | 26 |
| `StartupSafetyTest` | static source checks: no View built in an Activity field initializer, no `INTERNET`, no all-files permission, version/package, and the **order** of the security-critical steps (verified import before the removal journal, journal before the delete request, read-back before deleting the encrypted copy, authentication gate on the Locked Files tab, the export workflow is gone) | 51 |

`VaultTransactionTest` is the closest thing to a device test that is possible
here: it runs the shipping cipher and the shipping atomic-write helper, and
it proves the two properties that protect user data:

- **nothing is deleted before a verified copy exists** (a crash between
  "publish" and "remove the source" leaves both copies, which the recovery
  pass resolves without data loss);
- **nothing is deleted on a failed verification** (a truncated or modified
  ciphertext is rejected by the GCM tag and the encrypted copy is kept).

What these tests can **not** cover: `ContentResolver`, MediaStore, SAF
providers, Android's delete-consent dialog, the Keystore, biometric prompts
and app lifecycle. Those need a device.

## 3. Build, package and sign

```bash
set -a; . /path/to/compass-signing/passwords.env; set +a
TOOLCHAIN=/path/to/toolchain ./tools/build-offline.sh
```

Exit code **0**. Result: `build/offline/out/Compass-2.4.apk` (the same bytes
are committed as `dist/Compass-2.4.apk`).

- **Size:** 4,007,708 bytes
- **SHA-256:** `d7d11f41c69263394690b256721a2c09470912ccf69fb8f633c2765094ce5578`
- `classes.dex`: 3,515,600 bytes, stored uncompressed and 4-byte aligned
- `aapt2 dump badging`: package `com.compass.vault`, versionName `2.4`,
  versionCode `24`, minSdkVersion `26`, targetSdkVersion `35`
- Permissions declared: `USE_BIOMETRIC`, `USE_FINGERPRINT`, `VIBRATE`,
  `READ_EXTERNAL_STORAGE` (maxSdk 32), `WRITE_EXTERNAL_STORAGE` (maxSdk 28),
  `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`,
  `READ_MEDIA_VISUAL_USER_SELECTED`
- Permissions **not** declared: `INTERNET`, `ACCESS_NETWORK_STATE`,
  `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`
- Signature (`apksigner verify --print-certs`): **verifies**; APK Signature
  Scheme v2 and v3 true; 1 signer; RSA 4096;
  certificate SHA-256 `88ed48c454d52f62efa6d0299c3b7a26916c5644f991d7ac14356270a8c507be`
- The APK was signed **after** packing/alignment, so the alignment is intact.

### Signing identity

The historical 0.1/0.2 signing material is exposed in repository history and
was **not** reused. Compass 2.4 is signed with a newly generated key
(`CN=Compass, OU=Compass Android releases`, RSA 4096, valid until 2056) that
lives **outside** this repository, together with its password, in the
workspace directory `compass-signing/` (`compass-release.jks`,
`passwords.env`) and in the release notes. Consequence, stated as a warning
rather than hidden in a footnote: **because the signer changed, this APK
installs only as a fresh install; Android rejects it as an update of an
existing 0.1/0.2 installation.** Uninstalling an app deletes its private
vault data.

## 4. Not performed (and therefore not claimed)

- Normal Gradle/AGP debug or release build (`./gradlew assembleRelease`).
- Installing the APK: no device, emulator or `adb` in this environment. The
  APK has **never been installed or launched**.
- MediaStore queries, SAF folder grants, `DocumentsContract.deleteDocument`,
  `MediaStore.createDeleteRequest` consent, the Android 10
  `RecoverableSecurityException` queue, permission revocation.
- Runtime permission matrix on API 26–35 (including Android 14 selected-media
  access).
- PIN entry, lock-out timing, decoy route, biometric prompt, auto-lock and
  grace-period behaviour, `FLAG_SECURE` screenshot blocking on real OEMs.
- In-app playback/preview codecs, thumbnails on a large library,
  accessibility (TalkBack), touch-target audit, thousands-of-files
  responsiveness, memory and battery.
- Crash/force-stop injection during encryption, source removal or restore
  (the *rules* are tested on the JVM, the *process death* is not).

Any statement elsewhere in the repository that sounds like a runtime result
should be read against this list.

## 5. Manual device test plan (not yet executed)

Use disposable files, keep independent copies, and test on a clean device or
emulator. Suggested matrix: API 26–28, API 29, API 30–32, API 33, API 34–35.

### Discovery

- [ ] First launch: library opens with sections Videos / Photos / Documents /
      APK files / Other files; no crash with zero files.
- [ ] Grant media access: photos, videos and audio appear with thumbnails;
      deny it: sections explain what is missing instead of showing empty lies.
- [ ] Android 14: "selected photos" access shows the partial-access message.
- [ ] Add a folder (Documents, and a subfolder of Download): its files appear;
      remove the folder in Settings and confirm the list updates.
- [ ] Search, sort by name/size/date, refresh, category chips, VIEW ALL.
- [ ] Lock a file with the phone in airplane mode with Wi-Fi and mobile data
      off - everything above must still work.

### Lock

- [ ] Lock a photo/video/audio item: encrypt first, Android asks to delete,
      approve it, the row disappears from the library **and** from the system
      gallery app after a refresh.
- [ ] Lock a file inside an approved folder: it disappears from that folder in
      a file manager, without a consent dialog.
- [ ] Cancel the consent dialog: the file stays in the vault, the row shows
      "NOT FULLY HIDDEN · PUBLIC ORIGINAL MAY REMAIN" and "Retry removing the
      public original" succeeds on the next attempt.
- [ ] Deny the media permission entirely: files can still be added with the
      file picker and locked (delete consent still applies).
- [ ] Multi-select and lock several files at once; verify one consent covers
      the whole batch on Android 11+ and one dialog per item on Android 10.
- [ ] Force-stop the app right after the encryption phase: on restart both
      copies exist and no file is lost or duplicated.
- [ ] Try a file larger than the free space: the operation fails with a clear
      message and nothing is deleted.

### Unlock / restore

- [ ] Unlock a MediaStore item: the file returns to its folder and appears in
      the gallery again, the encrypted copy is gone.
- [ ] Unlock a SAF item: it returns to its original folder (or, if that folder
      is gone, the save dialog appears instead of a guess).
- [ ] Create a file with the same name at the original location, then unlock:
      the restored file is named `name (1).ext`, nothing is overwritten.
- [ ] Delete a locked item permanently: only the encrypted copy is removed.
- [ ] Reboot the phone between lock and unlock: the vault survives and the
      approved folder grants still work.
- [ ] Put the vault through a restart while a restore is interrupted
      (force-stop) and confirm the next launch reports the pending operation
      instead of hiding it.

### Authentication

- [ ] First-run wizard: protection explanation → PIN → confirm → optional
      fingerprint → app; skipping the fingerprint must not block setup.
- [ ] Press "Locked Files" without unlocking: the PIN screen appears.
- [ ] Cancel/fail the fingerprint prompt repeatedly: the vault stays locked.
- [ ] Wrong PIN five times: delays appear and the decoy route is offered.
- [ ] Background the app and return after the grace period: the protected
      screen is replaced by the public library and the vault locks.
- [ ] Check the recent-apps preview and screenshot behaviour on the device.

## 6. Known limitations

These are properties of the design and of Android, not bugs to be fixed:

- Android does not let an ordinary app crawl shared storage. Compass shows
  what MediaStore exposes (media, and non-media rows on Android 10–12) plus
  what the owner explicitly approves through SAF. Files in other apps'
  private directories, `Android/data`/`Android/obb`, and folders without a
  grant are invisible - and are **not** claimed to be protected.
- Removing the public original is performed by Android (or the document
  provider); Compass reports exactly what it could verify afterwards. Files
  may also exist in a gallery trash, a cloud copy or a backup outside
  Compass's reach.
- The vault key is bound to the Android Keystore and the installation:
  uninstalling the app or losing the key makes the encrypted files
  unrecoverable. Compass is not a backup tool.
- Physical secure erasure cannot be guaranteed on flash storage; the wipe
  helper is best-effort overwriting before unlinking.
- The biometric prompt gates the app-level UI. It does not re-wrap the
  Keystore key, and no fingerprint data is ever read, stored or transmitted
  by Compass.

## 7. Release

The verified artifact above is published as the GitHub release
**Compass 2.4** (tag `v2.4`) on `WHITE8IRDD/Compass-`, marked pre-release
because of the missing device verification. The APK and its SHA-256 file are
committed at that tag (`dist/Compass-2.4.apk`,
`dist/Compass-2.4.apk.sha256`) and linked from the release notes.

**They could not be attached as release assets:** this build environment can
reach `api.github.com` but not `uploads.github.com`, the host GitHub uses for
asset uploads (the attempt failed with a connection reset). The release body
states this and gives the owner the one-line command to attach the identical
files. See [RELEASE.md](RELEASE.md) and [RELEASE-NOTES.md](RELEASE-NOTES.md).
The older **COMPASS 0.2** release and its APK are unchanged.
