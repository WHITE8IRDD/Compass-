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

### A warning about the keystore

The release keystore is part of the repository (`keystore/`), because this
is a personal app and you should be able to build updates yourself. If you
ever lose it, Android will refuse to install any future update of this
app, and the only way out is to uninstall - which deletes the vault.
Back it up.
