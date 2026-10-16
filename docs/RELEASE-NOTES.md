# Historical release notes — Compass 0.1

> These notes describe only the old `v0.1` artifact. The published Latest release is **COMPASS 0.2** at `v0.2`; the current working tree has not been released. The previous signing material was tracked in repository history and is considered exposed. Do not reuse it. See the current [verification report](VERIFICATION.md) and [release status](RELEASE.md).

## Compass 0.1

A working compass that is also a private, encrypted vault.

Compass looks like an ordinary compass app: a black screen, a needle, a
degree readout. Hidden behind a secret bearing (a number *you* choose -
hold the needle on it for 1.5 seconds) is an AES-256-GCM encrypted vault
for your private photos, videos and documents. A long press on the dial
opens a PIN keypad, and a fingerprint works too.

### What is in this release

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

### Minimum Android version

**Android 8.0 (API 26)** or newer. Biometric unlock needs Android 9 (API
28) or newer; on older versions the PIN and the secret bearing work.

### Installing it

1. Download `Compass-0.1.apk` from this release.
2. Copy it to your phone (or download it straight on the phone).
3. Open the file. Android asks you to allow **"install unknown apps"** for
   the app you are opening it with - that is the file manager or browser,
   not Compass.
4. To enable that setting manually:
   **Settings -> Apps -> Special app access -> Install unknown apps ->
   pick the app you are using (Files / Chrome / ...) -> Allow from this
   source.**
5. Go back and tap `Compass-0.1.apk` again. Done.

The app itself asks for **no permissions** except biometrics and
vibration. It cannot access your files until you pick them with the
system picker.

### First launch

The setup wizard asks for a PIN (4-8 digits) and for your secret bearing.
Then it shows one warning, and it means it:

> **Files live inside the app. Uninstalling the app deletes it forever.**

There is no cloud copy. If you delete the app - or lose the phone - the
vault is gone. Keep your own backups of anything you cannot lose.

### Historical signing note — obsolete

This note belonged to the v0.1 release. The signing material was tracked in
repository history and is treated as exposed; it was removed from the current
working tree and must not be reused. The existing v0.2 release remains
published; no signed build of the current source has been created.

### Where to get the APK

The release asset could not be attached to this page by the machine that
built it (its network blocks GitHub's asset upload host). The signed APK is
committed to the repository and can be downloaded directly:

- **https://github.com/WHITE8IRDD/Compass-/raw/v0.1/dist/Compass-0.1.apk**
- or from the repository itself: `dist/Compass-0.1.apk` on the `v0.1` tag

It is the same file: `Compass-0.1.apk`, signed with the release key
(v1 + v2 + v3, RSA-4096). You can verify it with

```bash
apksigner verify --verbose Compass-0.1.apk
```

SHA-256 of the release build:

```
10d18df237a3307b33c31f585e1007e2d254d074c3237dee4795f3875a35731b
```
