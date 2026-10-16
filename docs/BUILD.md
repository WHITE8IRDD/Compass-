# Building Compass

There are two build paths. The **Gradle path** is the normal one and is
what you should use. The **offline path** exists because this project was
built on a machine without access to Google Maven, and it is a nice,
dependency-free way to see how an APK is really put together.

---

## Path A - Gradle (recommended)

### Requirements

| | |
|---|---|
| JDK | 17 (the version Android Studio bundles is fine) |
| Android SDK | platform **35** + build-tools 35 |
| Gradle | downloaded automatically by the wrapper (8.11.1) |
| Network | needed **once**, for the Gradle distribution and the two plugins |

### Build

```bash
./gradlew assembleRelease
```

Output:

```
app/build/outputs/apk/release/app-release.apk
```

Copy it and rename it if you like:

```bash
cp app/build/outputs/apk/release/app-release.apk dist/Compass-0.1.apk
```

### Build the debug variant instead

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses the `applicationIdSuffix = ".debug"`, so it can be
installed next to the release build without touching the release data.

### The signing configuration

`app/build.gradle.kts` reads `keystore/keystore.properties`:

```kotlin
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystoreProps.inputStream().use { load(it) }
}
// ...
signingConfigs {
    create("release") {
        if (keystorePropsFile.exists()) {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
}
```

The file is missing-safe: if it is not there, the release build is simply
not signed (and Android Studio will ask you for a key instead).

### Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `SDK location not found` | no Android SDK | create `local.properties` with `sdk.dir=/path/to/Android/Sdk` |
| `Failed to find Build Tools revision 35` | SDK incomplete | SDK Manager -> Build-Tools 35 |
| `Plugin [id: 'com.android.application'] was not found` | no network / wrong repositories | check `settings.gradle.kts` has `google()` and `mavenCentral()` |
| `Keystore file not found` | the keystore was moved | fix `storeFile` in `keystore/keystore.properties` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | a different key signed the installed app | uninstall the old one (⚠️ **this deletes the vault**) |

---

## Path B - The offline build script

`tools/build-offline.sh` builds the APK **without Gradle, without the
Android SDK and without the network**. It is what produced the APK in
`dist/`.

### What it needs

| Tool | Version used | Where it came from |
|---|---|---|
| JDK (JRE + `keytool`) | 17.0.9 | the `jdk4py` wheel on PyPI |
| `kotlinc` | 2.4.21 | the `kotlin-compiler` package on npm |
| `android.jar` | API 30 | the `@drxiaozhi/minapk` package on npm |
| `aapt2` | 2.20-15009934 | the `aaptjs3` package on npm |
| `d8` | 8.2.2 | `@drxiaozhi/minapk` |
| `apksigner` | 0.9 | `@drxiaozhi/minapk` |
| `ecj` | 3.45 | `@drxiaozhi/minapk` (a Java compiler that works on a JRE without `javac`) |
| Python | 3.9+ | for the packer and the icon generator |

Expect the tool chain to live in `$TOOLCHAIN` with this layout:

```
$TOOLCHAIN/
├── android/
│   ├── platforms/android-34/android.jar
│   └── build-tools/{aapt2,d8.jar,apksigner.jar,ecj-3.45.0.jar}
├── jdk/                     <- a JDK 17
└── kotlin/                  <- kotlinc + lib/kotlinx-coroutines-core-jvm.jar
```

### Run it

```bash
TOOLCHAIN=/path/to/toolchain ./tools/build-offline.sh
```

### What it does, step by step

```
1. kotlinc              app/src/main/java/com/compass/vault/**/*.kt
                        -> build/offline/classes        (100 .class files)

2. aapt2 compile        app/src/main/res/**
                        -> build/offline/res/*.flat     (compiled resources)

3. aapt2 link           manifest + flats + assets
                        -> build/offline/base.apk       (resources + assets)

4. ecj                  generates R.java from the resource symbols
                        -> build/offline/gen/com/compass/vault/R.java

5. javac-equivalent     compiles R.java against android.jar
   (ecj)                -> build/offline/gen-classes

6. jar                  classes + gen-classes -> classes.jar

7. d8                   classes.jar -> classes.dex
                        (also dexes the Kotlin standard library)

8. apk_pack.py          appends classes.dex to base.apk and re-aligns
                        every stored entry (4 bytes, 16 for .so)
                        -> build/offline/unsigned.apk

9. apksigner sign       keystore/compass-release.jks
                        -> dist/Compass-0.1.apk
10. apksigner verify    proves the signature
```

### Why not just use `zip`?

Because `zip` rewrites the archive and loses the 4-byte alignment of
`resources.arsc`, which the platform likes (it can then memory-map the
resource table instead of copying it). `tools/apk_pack.py` does the job
properly: it copies the compressed bytes verbatim and pads the local file
headers so that every stored entry starts aligned. It also rebuilds the
central directory and the end-of-central-directory record, and finally
re-opens the result with Python's `zipfile` to prove that nothing is
corrupt.

---

## Regenerating the app icon

```bash
python3 tools/make_icons.py
```

`tools/make_icons.py` is a dependency-free PNG writer (it builds the PNG
chunks itself - no Pillow) that draws the compass ring and needle at five
densities:

```
app/src/main/res/mipmap-mdpi/ic_launcher.png            48x48
app/src/main/res/mipmap-mdpi/ic_launcher_round.png      48x48
app/src/main/res/mipmap-hdpi/ic_launcher.png            72x72
app/src/main/res/mipmap-hdpi/ic_launcher_round.png      72x72
app/src/main/res/mipmap-xhdpi/ic_launcher.png           96x96
app/src/main/res/mipmap-xhdpi/ic_launcher_round.png     96x96
app/src/main/res/mipmap-xxhdpi/ic_launcher.png         144x144
app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png   144x144
app/src/main/res/mipmap-xxxhdpi/ic_launcher.png        192x192
app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png  192x192
```

The adaptive icon (Android 8+) is a vector and needs no generation:

```
app/src/main/res/drawable/ic_launcher_foreground.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
```

`ic_launcher.xml` wires three layers: a solid background colour, the
foreground vector and a **monochrome** layer (Android 13+ themed icons).

---

## The self test

```bash
TOOLCHAIN=/path/to/toolchain ./tools/run-selftest.sh
```

It compiles the real security classes together with
`tools/selftest/CryptoSelfTest.kt` and runs 22 assertions on a plain JVM:
the GCM framing, the IV uniqueness, the tag verification (tampered,
truncated and wrong-key data must all be rejected), the PBKDF2 hashing, the
constant-time comparison and the secure wipe.

The Android Keystore is replaced by an ordinary JCE key - everything else
is the code that runs on the phone.

---

## Building the release by hand (no script)

If you want to see every step:

```bash
# 1. compile the Kotlin sources
kotlinc -classpath $ANDROID_JAR:$COROUTINES_JAR -jvm-target 17 \
        -d build/classes app/src/main/java/com/compass/vault

# 2. compile and link the resources
aapt2 compile --dir app/src/main/res -o build/res.zip
aapt2 link -o build/base.apk -I $ANDROID_JAR --manifest app/src/main/AndroidManifest.xml \
        --java build/gen --min-sdk-version 26 --target-sdk-version 35 \
        --version-code 1 --version-name 0.1 build/res.zip

# 3. compile the generated R.java
ecj -source 17 -target 17 -classpath $ANDROID_JAR -d build/gen-classes build/gen/.../R.java

# 4. dex everything
jar cf build/classes.jar -C build/classes . -C build/gen-classes .
java -jar d8.jar --min-api 26 --output build/dex build/classes.jar

# 5. pack, sign, verify
python3 tools/apk_pack.py build/base.apk classes.dex build/unsigned.apk
java -jar apksigner.jar sign --ks keystore/compass-release.jks \
     --ks-pass pass:compass-vault-2024 --key-pass pass:compass-vault-2024 \
     --out dist/Compass-0.1.apk build/unsigned.apk
java -jar apksigner.jar verify --verbose dist/Compass-0.1.apk
```

(The script also injects `package="com.compass.vault"` into a temporary
copy of the manifest - `aapt2 link` needs it, and AGP does that for you.)
