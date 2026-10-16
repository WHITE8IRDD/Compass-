#!/usr/bin/env bash
#
# Offline build and package step for Compass (unsigned unless external signing is configured).
#
# This is the exact same source tree that the Gradle project builds - it is
# simply compiled with the individual tools (kotlinc, aapt2, d8, apksigner)
# instead of through AGP. It is used when no Android SDK / Gradle / internet
# connection is available.
#
# It needs a tool directory that contains:
#
#   $TOOLCHAIN/jdk/                     a JDK 17 (java, keytool, jar)
#   $TOOLCHAIN/kotlin/                  the Kotlin compiler (kotlinc)
#   $TOOLCHAIN/android/build-tools/aapt2
#   $TOOLCHAIN/android/build-tools/d8.jar
#   $TOOLCHAIN/android/build-tools/apksigner.jar
#   $TOOLCHAIN/android/build-tools/ecj-*.jar     (a Java compiler for R.java)
#   $TOOLCHAIN/android/platforms/android-XX/android.jar
#
# Run it with:
#
#   TOOLCHAIN=/path/to/toolchain ./tools/build-offline.sh
#
# By default this produces an unsigned APK under build/offline/out. To sign,
# provide an owner-controlled keystore and passwords through environment
# variables; no signing material is loaded from or written to this repository:
# COMPASS_RELEASE_KEYSTORE, COMPASS_RELEASE_KEY_ALIAS,
# COMPASS_RELEASE_STORE_PASSWORD, COMPASS_RELEASE_KEY_PASSWORD.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TOOLCHAIN="${TOOLCHAIN:-$HOME/tc}"
BUILD="${BUILD:-$ROOT/build/offline}"
OUT="${OUT:-$BUILD/out}"

JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk}"
KOTLIN_HOME="${KOTLIN_HOME:-$TOOLCHAIN/kotlin}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-$TOOLCHAIN/android/platforms/android-34}"
ANDROID_JAR="$ANDROID_PLATFORM/android.jar"
BUILD_TOOLS="$TOOLCHAIN/android/build-tools"

AAPT2="$BUILD_TOOLS/aapt2"
D8_JAR="$BUILD_TOOLS/d8.jar"
APKSIGNER_JAR="$BUILD_TOOLS/apksigner.jar"
ECJ_JAR="$(ls "$BUILD_TOOLS"/ecj-*.jar | head -1)"
COROUTINES_JAR="$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar"
KOTLIN_STDLIB_JAR="$KOTLIN_HOME/lib/kotlin-stdlib.jar"

# --- optional external signing ----------------------------------------------
SIGNING_KEYSTORE="${COMPASS_RELEASE_KEYSTORE:-}"
SIGNING_KEY_ALIAS="${COMPASS_RELEASE_KEY_ALIAS:-}"
SIGNING_STORE_PASSWORD="${COMPASS_RELEASE_STORE_PASSWORD:-}"
SIGNING_KEY_PASSWORD="${COMPASS_RELEASE_KEY_PASSWORD:-}"
if [[ -n "$SIGNING_KEYSTORE$SIGNING_KEY_ALIAS$SIGNING_STORE_PASSWORD$SIGNING_KEY_PASSWORD" ]]; then
    if [[ -z "$SIGNING_KEYSTORE" || -z "$SIGNING_KEY_ALIAS" || -z "$SIGNING_STORE_PASSWORD" || -z "$SIGNING_KEY_PASSWORD" ]]; then
        echo "External signing is incomplete; set all four COMPASS_RELEASE_* variables or none." >&2
        exit 2
    fi
    if [[ ! -f "$SIGNING_KEYSTORE" ]]; then
        echo "The external signing keystore file does not exist." >&2
        exit 2
    fi
fi

say() { printf '\n\033[1;37m==> %s\033[0m\n' "$1"; }

say "Cleaning"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/res" "$BUILD/gen" "$BUILD/dex" "$OUT"

# --- 1. compile the Kotlin sources ------------------------------------------
say "Compiling Kotlin"
"$KOTLIN_HOME/bin/kotlinc" \
    -classpath "$ANDROID_JAR:$COROUTINES_JAR" \
    -jvm-target 17 \
    -nowarn \
    -d "$BUILD/classes" \
    "$ROOT/app/src/main/java/com/compass/vault"

# --- 2. compile the resources ------------------------------------------------
say "Compiling resources (aapt2)"
"$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$BUILD/res"

# --- 3. link resources + manifest -------------------------------------------
say "Linking resources (aapt2 link)"
# AGP injects the package name into the manifest from the Gradle namespace.
# aapt2 does not know about namespaces, so the attribute is added to a
# temporary copy of the manifest here. The manifest in the repository stays
# free of it, which is what AGP 8 expects.
MANIFEST="$BUILD/AndroidManifest.xml"
python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$MANIFEST" <<'PY'
import re, sys
source, target = sys.argv[1], sys.argv[2]
text = open(source, encoding="utf-8").read()
if 'package=' not in text.split('>')[0]:
    text = text.replace(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    package="com.compass.vault"',
        1)
open(target, "w", encoding="utf-8").write(text)
PY

"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    -A "$ROOT/app/src/main/assets" \
    --java "$BUILD/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --version-code 3 \
    --version-name 0.2 \
    --auto-add-overlay \
    -o "$BUILD/base.apk" \
    "$BUILD"/res/*.flat

# --- 4. compile the generated R.java -----------------------------------------
say "Compiling R.java (ecj)"
"$JAVA_HOME/bin/java" -jar "$ECJ_JAR" -nowarn \
    -source 17 -target 17 \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD/classes" \
    "$BUILD/gen/com/compass/vault/R.java"

# --- 5. dex everything -------------------------------------------------------
say "Dexing (d8)"
# d8 takes jars, not directories, so the compiled classes are packed first.
python3 - "$BUILD/classes" "$BUILD/classes.jar" <<'PY'
import os, sys, zipfile
source, target = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
    for root, _dirs, files in os.walk(source):
        for name in files:
            if name.endswith(".class"):
                path = os.path.join(root, name)
                archive.write(path, os.path.relpath(path, source))
PY

"$JAVA_HOME/bin/java" -cp "$D8_JAR" com.android.tools.r8.D8 \
    --release \
    --min-api 26 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" \
    "$BUILD/classes.jar" \
    "$KOTLIN_STDLIB_JAR" \
    "$COROUTINES_JAR"

# --- 6. put the dex into the apk --------------------------------------------
say "Packing classes.dex"
python3 "$ROOT/tools/apk_pack.py" \
    "$BUILD/base.apk" "classes.dex" "$BUILD/dex/classes.dex" "$BUILD/unsigned.apk"

# --- 7. optionally sign ------------------------------------------------------
if [[ -n "$SIGNING_KEYSTORE" ]]; then
    say "Signing with externally supplied owner-controlled credentials"
    "$JAVA_HOME/bin/java" -jar "$APKSIGNER_JAR" sign \
        --ks "$SIGNING_KEYSTORE" \
        --ks-key-alias "$SIGNING_KEY_ALIAS" \
        --ks-pass env:COMPASS_RELEASE_STORE_PASSWORD \
        --key-pass env:COMPASS_RELEASE_KEY_PASSWORD \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --out "$OUT/Compass-Enhanced-0.2.apk" \
        "$BUILD/unsigned.apk"

    say "Verifying signature"
    "$JAVA_HOME/bin/java" -jar "$APKSIGNER_JAR" verify --verbose --print-certs "$OUT/Compass-Enhanced-0.2.apk"
    echo
    echo "Signed APK: $OUT/Compass-Enhanced-0.2.apk"
    ls -la "$OUT/Compass-Enhanced-0.2.apk"
else
    say "Signing skipped; no external signing credentials configured"
    cp "$BUILD/unsigned.apk" "$OUT/Compass-Enhanced-0.2-unsigned.apk"
    echo
    echo "Unsigned APK (not installable as a release): $OUT/Compass-Enhanced-0.2-unsigned.apk"
    ls -la "$OUT/Compass-Enhanced-0.2-unsigned.apk"
fi
