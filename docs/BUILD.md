# Build Compass 2.4

## Working-tree status

This branch is the **Compass 2.4** source candidate (`versionName = 2.4`,
`versionCode = 24`), not a published release. The app targets Android 15
(API 35). It has been compiled and packaged using the repository's offline
validation script. A separate one-off test APK is signed for sideload testing,
but has not been installed or tested on a device and is not a release. See
[VERIFICATION.md](VERIFICATION.md).

The existing **COMPASS 0.2** release at `v0.2` remains published and Latest;
it is a separate, unchanged artifact. Signing material from the old release
was exposed in repository history and must never be reused.

## Standard Gradle build

Recommended release toolchain:

- JDK 17
- Android SDK platform 35 and build-tools 35
- Gradle wrapper 8.11.1

Debug build:

```bash
./gradlew assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`; its application
ID has a `.debug` suffix and it is not the release identity.

Unsigned release-variant build:

```bash
./gradlew assembleRelease
```

Without externally configured signing, the result is unsigned. Do not
install or distribute it as a release.

### External signing policy

The Gradle build accepts signing configuration only from these environment
variables; no key or password file is loaded from this repository:

- `COMPASS_RELEASE_KEYSTORE` — owner-controlled keystore path
- `COMPASS_RELEASE_KEY_ALIAS`
- `COMPASS_RELEASE_STORE_PASSWORD`
- `COMPASS_RELEASE_KEY_PASSWORD`

Set them only in a private local environment or secure CI secret store. Never
commit signing material or put passwords in source, shell history, or chat.
Use a newly provisioned, safely backed-up key only after the owner decides
how the new signing identity affects upgrades. The historical key is exposed
and must not be reused; a different key cannot update an APK signed with the
old identity.

## Offline compile/package validation

The offline toolchain can be recreated from the permitted package registries:

```bash
./tools/bootstrap-toolchain.sh /path/to/toolchain
TOOLCHAIN=/path/to/toolchain ./tools/build-offline.sh
```

The bootstrap matches the configured Kotlin compiler version (`2.0.21`) and
includes JDK 17, coroutines 1.8.0, an Android API 34 `android.jar`, and the
packaging tools. The offline package path is a validation aid, not the normal
AGP release build. Its API platform is 34 while the Gradle project declares
compile SDK 35; its D8/R8 build emits Kotlin-metadata parsing warnings.
Review [VERIFICATION.md](VERIFICATION.md) for the exact build result and
caveats.

Default output is an **unsigned** APK:

```text
build/offline/out/Compass-2.4-unsigned.apk
```

For an owner-signed release output, provide all four `COMPASS_RELEASE_*`
variables above. The script writes `Compass-2.4.apk` and calls `apksigner
verify`. For this session only, a separate APK was signed with a freshly
generated temporary **test-only** key, verified, then copied to the ignored,
session-local `downloads/Compass-2.4-test.apk`; this artifact is not committed
or hosted on GitHub. Its keystore was deleted after signing. This
is not an owner/release signature, cannot update the old v0.2 installation,
and cannot update future test builds. Do not publish it or use valuable files
for first-run testing.

Optional variables:

```text
TOOLCHAIN          root containing JDK, Kotlin, Android tools
JAVA_HOME          JDK 17 directory (defaults to $TOOLCHAIN/jdk)
KOTLIN_HOME        Kotlin compiler directory (defaults to $TOOLCHAIN/kotlin)
ANDROID_PLATFORM   Android platform directory (defaults to android-34)
BUILD              scratch directory (defaults to build/offline)
OUT                output directory (defaults to $BUILD/out)
```

## Self-tests

```bash
TOOLCHAIN=/path/to/toolchain ./tools/run-selftest.sh
```

The crypto self-test runs production AES-GCM, PIN-hash, and wipe helpers on a
desktop JVM with a test-only JCE key. The startup/transaction test performs
static source checks. They do **not** exercise Android Keystore, MediaStore,
SAF providers, permissions, biometric hardware, lifecycle timing, or the
end-to-end lock/restore behavior on a device.

## Icon generator

```bash
python3 tools/make_icons.py
```

This dependency-free script regenerates the launcher icon's density-specific
PNG fallbacks.
