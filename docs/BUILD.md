# Build Compass

## Current status

The project targets Android 15 (API 35), uses Gradle/AGP for a normal build,
and retains an offline packaging script for restricted environments. No
release signing material is stored in the working tree. The previous signing
material was exposed in repository history and must not be reused; the
existing **COMPASS 0.2** GitHub release at `v0.2` is preserved.

The offline toolchain currently available in this workspace can compile
against API 34 and package an **unsigned** APK. It is useful for source and
resource validation, but is not a release build and is not installable as a
signed update. See [Verification](VERIFICATION.md) for the exact results and
limitations.

## Gradle build (recommended)

Requirements:

- JDK 17
- Android SDK platform 35 and build-tools 35
- Gradle wrapper (8.11.1); dependencies may need network access

For a local debug build:

```bash
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. The debug variant
uses the `.debug` application ID suffix and is separate from the release app.

For an **unsigned** release-variant build:

```bash
./gradlew assembleRelease
```

Unless external signing is configured, AGP produces an unsigned release APK.
Do not distribute it or treat it as installable.

### External signing

The Gradle build reads signing configuration only from the following
environment variables; it does not read a keystore or password file from the
repository:

- `COMPASS_RELEASE_KEYSTORE` — path to an owner-controlled keystore
- `COMPASS_RELEASE_KEY_ALIAS`
- `COMPASS_RELEASE_STORE_PASSWORD`
- `COMPASS_RELEASE_KEY_PASSWORD`

Set these through a private local environment or a secure CI secret store,
never in source, shell history, chat, or committed Gradle properties. If any
variable is missing, the build stays unsigned. Use only a fresh, safely
managed key after deciding how a new signing identity will affect upgrades;
a different key cannot update an app signed with the previous identity.

The current package version remains `0.2` / version code `3`, and the
published `v0.2` release is already Latest. Do not overwrite or recreate it
as part of a source build. A future public release needs an explicit version
and release/tag decision.

## Offline package validation

`tools/build-offline.sh` compiles Kotlin, links Android resources, generates
`R.java`, dexes classes, and packages the APK. It never reads signing data
from the repository. The default output is unsigned:

```bash
TOOLCHAIN=/path/to/toolchain ./tools/build-offline.sh
```

Default output:

```text
build/offline/out/Compass-Enhanced-0.2-unsigned.apk
```

To produce a signed output, configure all four `COMPASS_RELEASE_*`
environment variables listed above and run the script. It writes
`Compass-Enhanced-0.2.apk` to the output directory and invokes `apksigner
verify`. The script uses `env:` password sources rather than putting password
values in command-line arguments.

Optional build variables:

```text
TOOLCHAIN          root containing JDK, Kotlin, Android tools
JAVA_HOME          JDK 17 directory (defaults to $TOOLCHAIN/jdk)
KOTLIN_HOME        Kotlin compiler directory (defaults to $TOOLCHAIN/kotlin)
ANDROID_PLATFORM   Android platform directory (defaults to android-34)
BUILD              scratch directory (defaults to build/offline)
OUT                output directory (defaults to $BUILD/out)
```

The script is a packaging aid, not a substitute for the official Gradle
release toolchain. In the current sandbox, D8 reports Kotlin-metadata parsing
warnings because its R8/D8 version is older than the bootstrapped Kotlin
compiler. A successful package step does **not** clear those warnings or
prove runtime behavior.

## Self-tests

```bash
TOOLCHAIN=/path/to/toolchain ./tools/run-selftest.sh
```

The script compiles the production `KeystoreCipher`, `PinHasher`, and
`SecureWipe` classes on a desktop JVM using a test-only JCE key. It does not
exercise Android Keystore, MediaStore, SAF, biometric hardware, or device
permissions. `StartupSafetyTest` also performs static source checks for
startup and file-transaction ordering.

## Icon generator

```bash
python3 tools/make_icons.py
```

This dependency-free script regenerates the PNG density fallbacks for the
existing compass launcher icon.
