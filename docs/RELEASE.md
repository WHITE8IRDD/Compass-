# Release 0.1 - notes and checklist

**Tag:** `v0.1` · **Title:** `Compass 0.1` · **File:** `Compass-0.1.apk`

---

## Release notes (copy & paste for the GitHub release)

```markdown
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
```

---

## The tag

```bash
git tag -a v0.1 -m "Compass 0.1 - encrypted vault disguised as a compass"
git push origin v0.1
```

## The asset

```bash
gh release create v0.1 \
  --title "Compass 0.1" \
  --notes-file docs/RELEASE-NOTES.md \
  dist/Compass-0.1.apk
```

---

## Pre-release checklist

Run through this before you tag:

**Build**

- [x] `versionName = "0.1"`, `versionCode = 1` in `app/build.gradle.kts`
- [x] The build is a **release** build (not debug), signed with
      `keystore/compass-release.jks`
- [x] The output is renamed to `Compass-0.1.apk`
- [x] `apksigner verify` passes (v1 + v2 + v3)
- [x] The APK is 4-byte aligned (`resources.arsc` and `classes.dex`)

**Security**

- [x] No `INTERNET` permission in the manifest
- [x] No networking class in the DEX (`HttpURLConnection`, `Socket`,
      `WebView`, OkHttp)
- [x] `FLAG_SECURE` is set on the single window
- [x] `allowBackup="false"` + `dataExtractionRules`
- [x] The crypto self test passes (22 assertions)
- [x] The Keystore key is created with AES-256 + GCM and
      `setRandomizedEncryptionRequired(true)`

**Function**

- [ ] Install and launch on a real device (`adb install -r`)
- [ ] The setup wizard runs to the end
- [ ] The secret bearing unlocks the vault
- [ ] The PIN unlocks the vault, the lock-out kicks in after 5 wrong tries
- [ ] The biometric prompt opens (Android 9+)
- [ ] A photo imports, appears in the list, opens in the viewer
- [ ] A video imports and plays; the temporary file is gone afterwards
- [ ] A screenshot attempt produces a black screen
- [ ] The app locks after the grace period and the vault needs the gesture
      again

(The last block needs a real phone - see
[VERIFICATION.md](VERIFICATION.md) for the full manual test list.)

**The "no network" promise, one more time**

```bash
aapt2 dump permissions dist/Compass-0.1.apk
```

If `INTERNET` ever shows up here, something is wrong with a dependency.
