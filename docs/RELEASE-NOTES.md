# Compass 2.4 — draft release notes (not published)

**Release name:** Compass 2.4

**Status:** Draft source candidate. A one-off test-signed APK exists only for sideload testing; no owner-signed release or GitHub release exists.

Compass 2.4 is intended to turn the existing Compass app into an offline
local-file browser with an encrypted vault. The current source candidate
includes:

- A browsable public library for accessible photos, videos, and audio, plus a
  user-selected SAF folder for documents, APKs, and other files.
- Category filters, name search, sorting, refresh, type badges, thumbnails,
  and file details.
- Streamed AES-GCM import into app-private storage with digest verification
  before any source-removal request.
- Durable source-removal and restore transaction states, conservative
  reporting when Android cannot confirm deletion, and recovery-before-listing.
- Restore/unlock, export, folder organization, and protected image/video/audio
  viewing, subject to provider permissions and Android support.
- Optional Android biometric authentication with PIN fallback; no network
  permission or remote service is required.

## Important limits

This draft has passed repository self-tests and offline compile/package
validation. A separate test APK was signed with a freshly generated temporary
key so it can be sideloaded; it is not an owner-signed release. Android
MediaStore/SAF behavior, delete consent, restore recovery, biometric prompts,
UI/accessibility, playback, and lifecycle have not been tested on a device or
emulator. The offline D8 step emitted Kotlin-metadata warnings and used API 34
stubs while the project declares compile SDK 35. The temporary test keystore
was deleted; do not distribute the test APK as a release or treat the build as
an installable production update.

Compass can browse only items Android exposes through MediaStore or an
explicit user-selected SAF grant. It cannot inspect arbitrary storage, empty
gallery trash, remove cloud copies, or guarantee physical erasure on flash
storage. A public original is not considered protected unless Android/provider
removal is confirmed. Keep independent backups; do not first test with valuable
files.

Release/signing requirements and exact validation results are recorded in
[RELEASE.md](RELEASE.md) and [VERIFICATION.md](VERIFICATION.md). The existing
COMPASS 0.2 release remains unchanged. Historical signing material is exposed
and must not be reused.

---

# Historical release notes — Compass 0.1

> These notes describe only the old `v0.1` artifact. The published Latest
> release is **COMPASS 0.2** at `v0.2`; these instructions do not apply to
> Compass 2.4. The previous signing material was tracked in repository history
> and is considered exposed. Do not reuse it.

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
