# Learn the code, file by file

The project is written so that every file only uses things that were
introduced before it. Read it in this order and you will never wonder
"where does that come from?".

The whole app is about **4 980 lines of Kotlin** in 22 files. That is
deliberate: a security app you cannot fully read is a security app you
cannot trust.

Before you start, get the project to build:

```bash
./gradlew assembleDebug        # or: TOOLCHAIN=... ./tools/build-offline.sh
```

---

## Part 0 - The map

```
com.compass.vault
├── CompassApp.kt        Application + dependency graph
├── MainActivity.kt      the one Activity: routing, picker, FLAG_SECURE
├── security/            the trust boundary
│   ├── KeystoreCipher.kt    AES-256-GCM + the Keystore key
│   ├── PinHasher.kt         PBKDF2 (pure, testable)
│   ├── PinStore.kt          PIN state, lock-out, decoy
│   ├── SecureWipe.kt        overwrite before unlink
│   ├── BiometricGate.kt     the platform BiometricPrompt
│   └── LockController.kt    the open/locked state machine
├── data/
│   ├── VaultFile.kt         the model
│   └── VaultRepository.kt   everything that touches the disk
├── util/
│   ├── Dispatchers.kt       coroutine dispatchers
│   └── CompassSensor.kt     magnetometer -> heading
└── ui/
    ├── Design.kt            colours, type, spacing, small view helpers
    ├── Screen.kt            the Screen and Navigator contracts
    ├── widget/              CompassView, PinPadView, PinDotsView
    └── screen/              the six screens
```

---

## Part 1 - The security core

### 1. `security/KeystoreCipher.kt`

The heart of the app. Open it first and read the class comment - it
explains the four rules the whole design is built on.

**What to look at**

* `AndroidKeystoreKeyProvider.getOrCreateKey()` - how the key is created:
  `KeyGenParameterSpec` with `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`,
  `BLOCK_MODE_GCM`, `setKeySize(256)` and
  `setRandomizedEncryptionRequired(true)`.
* `encryptStream()` - the file format:
  `[1 byte IV length][12 bytes IV][ciphertext][16 byte tag]`.
* `decryptStream()` - the tag verification happens in `cipher.doFinal()`.
  If the data was tampered with, this throws `AEADBadTagException`.
* `isHardwareBacked()` - asks the Keystore whether the key lives in a TEE.

**Why the key provider is injected**

`KeystoreCipher` takes a `KeyProvider` in its constructor. The default one
is the Android Keystore, but the self test injects a plain JCE key - which
is how the crypto can be tested on a desktop JVM.

**Try this**

Open `tools/selftest/CryptoSelfTest.kt` and change
`ITERATIONS` to 10 000 in the PIN test. Watch the test still pass (and get
much faster). Then set the GCM tag length to 96 bits and see the self test
fail - the tag is what makes tampering detectable.

### 2. `security/PinHasher.kt`

Pure functions, no Android imports, so they can be unit tested anywhere.

* `derive()` - PBKDF2-HMAC-SHA256, 250 000 rounds, 256-bit output.
* `constantTimeEquals()` - `MessageDigest.isEqual`, which always looks at
  all bytes, so the comparison time does not depend on how many leading
  bytes matched.

**Try this**

Time `derive()` with 250 000 and with 25 000 rounds. On a phone the first
takes about half a second - that is deliberate, it makes an offline
dictionary attack on a stolen hash expensive.

### 3. `security/PinStore.kt`

The PIN state machine.

* `verify()` returns one of four results: `Ok`, `NotConfigured`, `Wrong`,
  `LockedOut`.
* `lockoutDelayFor()` - the escalating delay: 5 s, 15 s, 45 s, 135 s, ...
  capped at 15 minutes.
* `isDecoyActive()` - after 10 failures the app shows the decoy vault.
* `persist()` - the state (salt, hash, counters) is written as **JSON,
  encrypted** into `filesDir/.authstate`. The hash never touches the disk
  in the clear.

**Try this**

Add a fourth `VerifyResult` that reports "you have 3 attempts left" and
show it under the dots in the PIN screen.

### 4. `security/SecureWipe.kt`

`wipe(file)` overwrites the file with random bytes **from the end
backwards** (so a crash half way through still destroys the tail) and then
unlinks it. `wipe(buffer)` zeroes a byte array in memory.

The class comment is honest about the limits: on wear-levelled flash this
is defence in depth, not a guarantee. The real protection is the
encryption.

### 5. `security/BiometricGate.kt`

The platform `BiometricPrompt` (`android.hardware.biometrics`), not the
AndroidX wrapper. Two details worth noticing:

* the prompt is a **system dialog** - it is drawn by the system UI process,
  so a malicious app cannot spoof it with an overlay,
* the code that touches the class lives in a separate `PlatformPrompt`
  class, because the class does not exist before API 28 and the app
  supports API 26.

### 6. `security/LockController.kt`

The state machine of the whole app:

```
locked --unlock()--> open --onEnteredBackground()--> grace period
   ^                     |                                  |
   |                     +----------- lock() <---------------+
```

`lock()` calls the cleanup hook, which wipes the decrypted temporary files
and drops the thumbnail cache. That hook is wired in `CompassApp.kt`.

---

## Part 2 - The data layer

### 7. `data/VaultFile.kt`

A small data class: id, name, mime type, size, date, folder, kind
(`IMAGE` / `VIDEO` / `DOCUMENT`) and duration. It knows how to serialise
itself to JSON and back - the JSON is what gets encrypted into `<id>.meta`.

### 8. `data/VaultRepository.kt`

Everything that touches the disk. The two methods to study are `import()`
and `export()`:

* `import()` opens the content provider stream and pipes it into
  `cipher.encryptStream()` in 64 KB chunks. The result is written to a
  `.part` file and renamed at the very end, so an interrupted import leaves
  nothing behind.
* `export()` is the mirror image: `cipher.decryptStream()` into the
  output stream of the target URI.

Also worth reading: `thumbnail()` (bitmaps live in RAM only) and
`wipeTempFiles()`.

---

## Part 3 - The plumbing

### 9. `util/Dispatchers.kt`

The app uses `kotlinx-coroutines-core` but **not**
`kotlinx-coroutines-android` (that would be a second dependency). So the
main dispatcher is built by hand: a `Handler` on the main looper.

### 10. `util/CompassSensor.kt`

`TYPE_ROTATION_VECTOR` if the device has it, otherwise the classic
accelerometer + magnetometer pair. The heading is smoothed with a small
low-pass filter and the angle difference is computed so that the filter
also works across the 0°/360° boundary.

### 11. `ui/Design.kt`

The design system in one file: `Palette` (black, white, two greys, one
hairline), `Spacing`, `Type` (the Inter variable font at different
weights), `Radii`, and small helpers (`label()`, `body()`, `hairline()`,
`spacer()`, `outlineButton()`, `settingRow()`).

The font is loaded from the assets with the variable-font API:

```kotlin
Typeface.Builder(context.assets, "fonts/InterVariable.ttf")
    .setFontVariationSettings("wght 500")
    .build()
```

### 12. `ui/widget/CompassView.kt`

The dial, drawn by hand with `Canvas` - circles, lines and a `Path` for
the needle. No image assets, no drawing library, crisp on every density.

The visual model: the **rose** rotates so that the cardinal letter of the
current heading comes to the top, the **needle** stays at the top of the
screen. `unlockProgress` draws the slim arc that fills up during the
secret-bearing unlock.

### 13. `ui/widget/PinPadView.kt` and `PinDotsView.kt`

A custom keypad drawn with `Canvas` too, and a row of dots that fills up
as you type. The bottom-left key is the "confirm / backspace" key.

### 14. `ui/Screen.kt`

Two tiny contracts that keep the navigation honest:

```kotlin
interface Screen {
    val view: View
    fun onShow() {}
    fun onHide() {}
    fun onBack(): Boolean = false
}
```

`Navigator` is what a screen uses to talk to the activity: `showVault()`,
`showPin()`, `pickFiles()`, `toast()` and so on.

---

## Part 4 - The app shell

### 15. `CompassApp.kt`

The `Application` class and the tiny dependency graph (`AppGraph`). No DI
framework - four objects that live as long as the process:

```
KeystoreCipher  ->  PinStore
                 ->  VaultRepository
                 ->  LockController
```

### 16. `MainActivity.kt`

The only Activity. It is responsible for:

* `FLAG_SECURE` (one window, so it covers every screen),
* routing (`renderRoute()` decides between setup / vault / compass),
* the system picker (`ACTION_OPEN_DOCUMENT` for import,
  `ACTION_CREATE_DOCUMENT` for export),
* the MediaStore delete request,
* the auto-lock: `onStop()` switches the visible screen back to the
  compass and arms the timer, `onStart()` decides whether the vault is
  still open.

---

## Part 5 - The screens

### 17. `ui/screen/CompassScreen.kt`

The disguise. The unlock gesture lives in `updateUnlockProgress()`:

```kotlin
val difference = angleDifference(heading, target)
if (abs(difference) <= TOLERANCE_DEGREES) {
    // start the timer, fill the arc, unlock after 1.5 s
} else {
    holdStartedAt = 0L      // let go -> the arc disappears again
}
```

A long press on the dial opens the PIN screen.

### 18. `ui/screen/PinScreen.kt`

Three modes in one screen: `VERIFY` (unlock), `CREATE` and `CONFIRM`
(the setup wizard uses the last two). It shows the lock-out countdown, the
biometric shortcut and - after 10 failures - the decoy vault.

### 19. `ui/screen/SetupScreen.kt`

The first-launch wizard, four steps:

1. the warning: *"Files live inside the app. Uninstalling the app deletes
   it forever."*
2. create a PIN
3. confirm the PIN
4. pick the secret bearing (the live compass dial helps you choose)

### 20. `ui/screen/VaultScreen.kt`

The file list: folder chips, the import button with a progress bar, the
long-press action dialog (open / export / move / delete) and the "new
folder" dialog.

### 21. `ui/screen/ViewerScreen.kt`

Images are decoded into memory; videos are decrypted to a temporary file
and played with `MediaPlayer`. `stopPlayback()` wipes that file.

### 22. `ui/screen/SettingsScreen.kt`

Everything that can be configured: the secret bearing, the biometric
toggle, the decoy toggle, the auto-lock grace period, "change PIN",
storage usage and the about box.

---

## Part 6 - Make it yours

Good first exercises, in increasing difficulty:

1. **Change the accent of the design system** - edit `Palette` and watch
   the whole app follow. (Keep it monochrome, that is the point.)
2. **Change the unlock tolerance** - `TOLERANCE_DEGREES` and
   `HOLD_MILLIS` in `CompassScreen`.
3. **Add a fourth digit to the PIN dots** - `dots.total` in `PinScreen`
   and `MAX` in the `PinPadView`.
4. **Add a "delete all" action** to the settings, using
   `SecureWipe.wipeDirectory()`.
5. **Add a photo to the decoy vault** so it is not empty.
6. **Port one screen to Compose** - see
   [COMPOSE-MIGRATION.md](COMPOSE-MIGRATION.md).
