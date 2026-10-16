# Verification

Two parts: what has been **checked automatically** in the build
environment, and what has to be **checked by hand** on a real phone.

The build environment for this project has no Android device, no emulator
and no `adb`, so the second part is still open. It is not a small detail:
an app that crashes on launch would be worthless, and this document exists
so that you can finish the job in ten minutes.

---

## Part 1 - Automatic checks (already done)

### 1.1 The code compiles

```bash
kotlinc -classpath android.jar:kotlinx-coroutines-core-jvm.jar \
        -jvm-target 17 -d build/classes app/src/main/java/com/compass/vault
```

Result: **100 class files, 0 errors**.

The build is deliberately compiled against the **API 30** `android.jar`, so
no API newer than 30 can be referenced by accident. (`BiometricGate` keeps
the API 28+ prompt in a separate class for the same reason - the class
does not exist on API 26/27.)

### 1.2 The crypto self test

```bash
TOOLCHAIN=/path/to/toolchain ./tools/run-selftest.sh
```

Result: **22 / 22 checks passed.** The test runs the *real* production
classes (`KeystoreCipher`, `PinHasher`, `SecureWipe`) on a desktop JVM with
an ordinary JCE key instead of the Android Keystore.

| # | Check | What it proves |
|---|---|---|
| 1 | ciphertext is larger than the plaintext | the IV and the tag are really there |
| 2 | a 5 MB stream round trips byte for byte | the streaming encrypt/decrypt is lossless |
| 3 | two encryptions of the same data differ | a fresh IV per encryption |
| 4 | a modified ciphertext is rejected | the GCM tag is verified (`AEADBadTagException`) |
| 5 | a truncated ciphertext is rejected | the tag covers the length too |
| 6 | a different key cannot decrypt it | the key really is the key |
| 7 | a byte payload round trips | the small-payload API works |
| 8 | the byte payload is not stored in the clear | no accidental plaintext |
| 9 | the ciphertext on disk does not contain the plaintext | no accidental plaintext on disk |
| 10 | the payload grew by exactly IV + tag | the file format is what the code claims |
| 11-20 | the salt, the hash, the constant-time comparison, the PIN rules | the PIN handling is correct |
| 21-22 | the wiped file / directory is gone | `SecureWipe` really deletes |

### 1.3 The APK itself

```bash
aapt2 dump badging dist/Compass-0.1.apk
```

```
package: name='com.compass.vault' versionCode='1' versionName='0.1'
minSdkVersion:'26'
targetSdkVersion:'35'
launchable-activity: name='com.compass.vault.MainActivity'
```

```bash
aapt2 dump permissions dist/Compass-0.1.apk
```

```
uses-permission: name='android.permission.USE_BIOMETRIC'
uses-permission: name='android.permission.USE_FINGERPRINT'
uses-permission: name='android.permission.VIBRATE'
```

No `INTERNET`. No storage. No camera.

```bash
java -jar apksigner.jar verify --min-sdk-version 23 --verbose dist/Compass-0.1.apk
```

```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate DN: CN=Compass Vault, OU=Personal, O=Compass, L=Casablanca, ST=Souss-Massa, C=MA
Signer #1 key algorithm: RSA / key size: 4096
```

The DEX was also scanned for networking classes - `HttpURLConnection`,
`Socket`, `WebView`, OkHttp: **none present**.

The archive was checked with Python's `zipfile.testzip()` (no corrupt
entry) and every stored entry is 4-byte aligned.

---

## Part 2 - The manual test list (needs a phone)

Do this once, on the phone you intend to use, before you put anything
valuable into the vault.

### 2.1 Install and start

```bash
adb install -r dist/Compass-0.1.apk
adb shell am start -n com.compass.vault/.MainActivity
adb logcat -c && adb shell am start -n com.compass.vault/.MainActivity
adb logcat -d | grep -iE "compass|AndroidRuntime|FATAL"
```

- [ ] The install succeeds.
- [ ] The app starts and shows the compass.
- [ ] `logcat` shows no `FATAL EXCEPTION`.
- [ ] The needle moves when you rotate the phone.
- [ ] The degree readout changes.

### 2.2 The setup wizard

- [ ] The wizard starts (first launch) and shows the uninstall warning.
- [ ] You can create a PIN, confirm it, and set a secret bearing.
- [ ] The last step offers the biometric, and skipping it is possible.
- [ ] After the setup, the compass shows the "hold the needle on your
      mark" hint.

### 2.3 Unlocking

- [ ] Holding the needle on the secret bearing for 1.5 s opens the vault.
- [ ] Leaving the bearing resets the arc.
- [ ] A long press on the dial opens the keypad.
- [ ] The correct PIN opens the vault.
- [ ] Five wrong PINs are accepted, the sixth one starts the lock-out
      (5 s, then 15 s, 45 s ...).
- [ ] After ten wrong PINs the app shows the empty decoy vault.
- [ ] "FORGOT PIN?" brings the keypad back.
- [ ] The fingerprint opens the vault (Android 9+).

### 2.4 Import

- [ ] `IMPORT` opens the system file picker - **and Compass does not ask
      for any storage permission**.
- [ ] Importing a photo shows the progress and puts it in the list.
- [ ] Importing a video works; a long video does not crash the app.
- [ ] On Android 11+ the system asks to delete the original from the
      gallery, and the toast reminds you about the gallery trash.

### 2.5 The vault

- [ ] The list shows the file name, the size and the date.
- [ ] Tapping a photo opens the viewer; the image is visible.
- [ ] Tapping a video opens the player; the video plays.
- [ ] Leaving the viewer stops the playback.
- [ ] The folder chips filter the list, "+ NEW" creates a folder.
- [ ] Long press -> Export opens the warning and then the save dialog.
- [ ] Long press -> Delete removes the file after the confirmation.
- [ ] The storage figure in the settings is plausible.

### 2.6 The screen cannot be captured

- [ ] Pressing the screenshot button produces **no** image (or a black
      one).
- [ ] The screen-recorder / screen-share shows a black window.
- [ ] The recent-apps thumbnail shows a black rectangle (not the vault).

### 2.7 The auto-lock

- [ ] Press the home button: the app switches back to the compass.
- [ ] Come back within 30 s: the vault is still open.
- [ ] Come back after 30 s: the app asks for the gesture/PIN again.
- [ ] While it was locked, the temporary plaintext files are gone:

```bash
adb shell run-as com.compass.vault ls -la cache/decrypted
# -> should be empty
```

### 2.8 The data at rest

With the app locked, pull the encrypted files and look at them:

```bash
adb shell run-as com.compass.vault ls -la files/vault
adb shell run-as com.compass.vault cat files/vault/<id>.meta | xxd | head
```

- [ ] The `.meta` file contains no readable name or size.
- [ ] The `.bin` file starts with random bytes.

### 2.9 Uninstall warning (optional, and destructive!)

Only if you have a throw-away file in the vault:

- [ ] Uninstalling the app removes the files (this is the warning from the
      first launch - the app cannot stop it).

---

## Part 3 - What to do if something fails

| Symptom | Likely cause | Where to look |
|---|---|---|
| The app does not start | a missing resource or a theme problem | `adb logcat`, `MainActivity.onCreate()` |
| The needle does not move | no magnetometer | `CompassSensor.isAvailable` -> the screen shows "NO MAGNETOMETER" |
| The import does nothing | the callback was lost | `MainActivity.onActivityResult()` |
| The biometric prompt does not appear | the device has no sensor, or API < 28 | `BiometricGate.isAvailable()` |
| The vault stays locked | the lock-out is running | the countdown under the dots |
| The app is killed in the background | the OEM killed the process | the vault is still encrypted - that is fine |
