# Compass 2.4

**Release name:** Compass 2.4 · **tag:** `v2.4` · **package:** `com.compass.vault` ·
**versionCode:** 24 · **minSdk:** 26 (Android 8.0) · **targetSdk:** 35

Compass 2.4 is an offline file browser with a real vault: it lists the files
Android exposes to the app, and **Lock** moves a file into app-private
AES-256-GCM storage - verifying the encrypted copy first and only then
removing the public original. **Unlock** puts the file back where it came
from. There is no account, no server, no telemetry, and no `INTERNET`
permission.

## What is new in 2.4

**A real file library instead of a hidden list.**
The home screen has sections for **Videos**, **Photos**, **Documents**,
**APK files** and **Other files**, each with previews, type badges, size,
date and lock status. Category chips open the complete, paged list of one
type. Search, sort (newest / name / largest), multi-select and bulk locking
are built in. Thumbnails are cached in RAM and loaded with a bounded number
of parallel decoders, so a big library scrolls instead of freezing.

**Lock really moves the file.**
1. The file is streamed through AES-256-GCM in 64 KB chunks into
   `filesDir/vault` (the key is generated inside the Android Keystore and is
   not exportable).
2. The finished ciphertext is decrypted again and compared with the source
   byte count and SHA-256. Only a verified ciphertext is published.
3. The removal intent is journalled to disk (`SOURCE_REMOVAL_PENDING`).
4. The public original is removed: SAF documents through their provider,
   media through Android's own consent dialog (one dialog for the whole
   batch on Android 11+, item by item on Android 10, the write permission on
   Android 8–9).
5. Each URI is read back. Only a source that is provably gone is recorded as
   removed.

A file that still has a public copy is labelled
**"NOT FULLY HIDDEN · PUBLIC ORIGINAL MAY REMAIN"** with a retry action.
Compass never says "hidden" before Android confirmed it.

**Unlock restores automatically and safely.**
The recorded location decides the path: an approved SAF folder, the
MediaStore folder, or (on Android 8–9) the recorded absolute path. The
written file is verified before the encrypted copy is deleted; if the
original folder is gone, the Android save dialog is offered instead of a
guess; an existing file with the same name is never overwritten - the
restored file becomes `name (1).ext`.

**No more export workflow.**
The old "export a plaintext copy" feature is gone. Lock and unlock are the
only flows, so there is exactly one protected copy and no leftover public
duplicate. Temporary playback files are wiped when the vault locks, and
Settings has an explicit "Clear temporary playback data" action.

**Multi-folder discovery with honest limits.**
Several SAF folders can be approved (Settings → "Where Compass can look"),
each with its own grant, and revoked grants are detected and reported
instead of silently producing an empty list. The app states plainly that
Android does not let an ordinary app crawl shared storage: documents and APK
files are visible inside approved folders (or on Android 10–12 through the
media database), and nothing else is claimed to be protected.

**Persistence and crash recovery.**
The vault index is encrypted and written atomically. Anything that was
interrupted is resolved on the next launch from the real state of the disk
and the provider - never from the index alone - and unresolved operations
are shown as such rather than hidden.

**Authentication (unchanged core, now protecting the vault tab).**
PIN (PBKDF2-HMAC-SHA256, 250 000 iterations, salted; never stored in plain
text, rate-limited with escalating lock-outs and an optional decoy route)
plus Android's system biometric prompt on API 28+ (FingerprintManager on
26–27). Compass never registers fingerprints and never stores templates or
images. The Locked Files tab is not rendered before authentication, the app
auto-locks when it goes to the background after the configured grace period,
and every screen runs under `FLAG_SECURE`.

## Permissions and privacy

Declared: `USE_BIOMETRIC`, `USE_FINGERPRINT`, `VIBRATE`,
`READ_EXTERNAL_STORAGE` (≤ API 32), `WRITE_EXTERNAL_STORAGE` (≤ API 28),
`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`,
`READ_MEDIA_VISUAL_USER_SELECTED`. Media access is optional: without it,
files can still be added with the system file picker and locked.

Not declared: `INTERNET`, `ACCESS_NETWORK_STATE`, all-files access,
`QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`. No network code exists;
every core feature (browsing, thumbnails, lock, unlock, playback, PIN,
fingerprint, search, sort, filter) works in airplane mode.

APK files are treated as files: Compass can lock, restore and delete them
but never installs or executes them.

## Verification

- **135 / 135 automated assertions passed**: AES-GCM round trips and
  tamper/truncation rejection, PIN hashing, the end-to-end file transaction
  (9 MB source: encrypt → re-decrypt → compare digest → publish → verified
  restore → delete source) including the failure cases, the library rules,
  and 51 static checks of the security-critical step order.
- **Offline compile**: 0 errors (Kotlin 2.0.21, API 34 stubs; the Gradle
  project declares compile SDK 35 and D8 prints Kotlin-metadata warnings -
  see [VERIFICATION.md](VERIFICATION.md)).
- **APK**: `Compass-2.4.apk`, 4,007,708 bytes, SHA-256
  `d7d11f41c69263394690b256721a2c09470912ccf69fb8f633c2765094ce5578`,
  ZIP integrity OK, stored entries 4-byte aligned, signature verified
  (APK Signature Scheme v2 + v3, RSA 4096), no `INTERNET` string in
  `classes.dex`.

**Not verified:** no device or emulator is available in the build
environment, so the APK has never been installed or launched. MediaStore/SAF
runtime behaviour, the delete-consent dialogs, biometric prompts, lifecycle,
playback, accessibility and performance are **untested on real hardware**.
Please treat this release as a candidate and test it with disposable files.
The full list is in [VERIFICATION.md](VERIFICATION.md).

## Installation and the signing key

This is a **new signing identity** (`CN=Compass, OU=Compass Android releases`,
RSA 4096, certificate SHA-256
`88ed48c454d52f62efa6d0299c3b7a26916c5644f991d7ac14356270a8c507be`). The old
0.1/0.2 key was exposed in repository history and must not be reused.
Therefore Android will refuse this APK as an update of an existing 0.1/0.2
installation - it installs only as a fresh install. **Uninstalling the app
deletes its private vault data**, so back up anything you cannot lose first,
or install on a clean device. The keystore and its password belong to the
project owner and are required for every future update of this package.

## Known limitations

- Compass can only browse what Android exposes to it: MediaStore media, the
  non-media rows of the media database on Android 10–12, and folders you
  explicitly approve. Other apps' private directories,
  `Android/data`/`Android/obb` and ungranted folders are invisible and are
  never described as protected.
- The Android 11+ consent dialog is Android's, not Compass's: cancelling it
  leaves the public original in place (the UI says so and offers a retry).
- A public original can also exist in a gallery trash, a cloud backup or
  another copy outside Compass's reach, and physical erasure on flash
  storage cannot be guaranteed.
- The vault key is bound to this installation: uninstalling the app or
  losing the Keystore key makes the encrypted files unrecoverable. Compass is
  not a backup tool.
- The app is framework-based (no AndroidX/Compose) and was compiled with an
  offline toolchain, not with AGP; see
  [COMPOSE-MIGRATION.md](COMPOSE-MIGRATION.md) and
  [BUILD.md](BUILD.md).

Compass can be built from source with the documented offline toolchain; see
[BUILD.md](BUILD.md) and [RELEASE.md](RELEASE.md).

---

# Historical release notes — Compass 0.1

> These notes describe only the old `v0.1` artifact. The previous Latest
> release was **COMPASS 0.2** at `v0.2`. The signing material used for 0.1
> and 0.2 was tracked in repository history and is considered exposed. Do not
> reuse it.

## Compass 0.1

A working compass that is also a private, encrypted vault.

Compass looks like an ordinary compass app: a black screen, a needle, a
degree readout. Hidden behind a secret bearing (a number *you* choose -
hold the needle on it for 1.5 seconds) is an AES-256-GCM encrypted vault
for your private photos, videos and documents. A long press on the dial
opens a PIN keypad, and a fingerprint works too.

### What was in that release

- Real magnetometer compass (rotation vector, with an accelerometer
  fallback), drawn by hand with `Canvas` - no image assets.
- Secret-bearing unlock, PIN unlock with an escalating brute-force
  lock-out and a decoy vault, and biometric unlock.
- Import through the system file picker. The bytes are streamed straight
  into the cipher, so a 1 GB video never lands in memory.
- Every file is encrypted with AES-256-GCM. The key is generated inside
  the Android Keystore and is never exportable.
- After an import the app offers to delete the original from your gallery
  (Android 11+) and reminds you to empty the gallery trash.
- In-app video player and image viewer. Playback decrypts to a temporary
  file that is overwritten and deleted as soon as playback stops.
- Export (with a warning), delete (with a secure overwrite), folders.
- Auto-lock after a 30 second grace period, `FLAG_SECURE` on every
  screen, backups disabled.
- **No `INTERNET` permission. The app is permanently offline.**

### Historical minimum Android version

**Android 8.0 (API 26)** or newer. Biometric unlock needs Android 9 (API
28) or newer; on older versions the PIN and the secret bearing work.

### Historical installation directions

1. Download `Compass-0.1.apk` from the `v0.1` release.
2. Copy it to your phone (or download it straight on the phone).
3. Open the file. Android asks you to allow **"install unknown apps"** for
   the app you are opening it with - that is the file manager or browser,
   not Compass.
4. To enable that setting manually:
   **Settings -> Apps -> Special app access -> Install unknown apps ->
   pick the app you are using (Files / Chrome / ...) -> Allow from this
   source.**
5. Go back and tap `Compass-0.1.apk` again.

The app itself asked for no permissions except biometrics and vibration. It
could not access files until they were selected with the system picker.

### Historical signing note

The v0.1 signing material was tracked in repository history and is treated as
exposed; it must not be reused. These historical release instructions and
commands are preserved for record only and are obsolete for Compass 2.4.

### Historical APK location

- `https://github.com/WHITE8IRDD/Compass-/raw/v0.1/dist/Compass-0.1.apk`
- Repository path: `dist/Compass-0.1.apk` on the `v0.1` tag

Historical SHA-256:

```text
10d18df237a3307b33c31f585e1007e2d254d074c3237dee4795f3875a35731b
```
