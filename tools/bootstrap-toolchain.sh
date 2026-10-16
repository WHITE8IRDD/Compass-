#!/usr/bin/env bash
#
# Bootstraps the offline Android build toolchain from npm and PyPI.
#
# No Android SDK, no Gradle, no Google Maven - just the three package
# registries that a sandbox can always reach:
#
#   PyPI  jdk4py              -> a full JDK 17 (java, keytool, jar)
#   npm   kotlin-compiler     -> kotlinc 2.0.21 + the Kotlin standard library
#   npm   @drxiaozhi/minapk   -> android.jar (API 30), d8, apksigner, ecj
#   npm   aaptjs3             -> aapt2 (Linux x64)
#
# The result is the layout the build script expects:
#
#   $TOOLCHAIN/kotlin/bin/kotlinc
#   $TOOLCHAIN/kotlin/lib/kotlin-stdlib.jar
#   $TOOLCHAIN/kotlin/lib/kotlinx-coroutines-core-jvm.jar
#   $TOOLCHAIN/android/platforms/android-34/android.jar
#   $TOOLCHAIN/android/build-tools/{aapt2,d8.jar,apksigner.jar,ecj-*.jar}
#   $TOOLCHAIN/jdkpkg/jdk4py/java-runtime/bin/java
#
# Usage:
#   ./tools/bootstrap-toolchain.sh [target-directory]     # default: $HOME/tc
#
set -euo pipefail

TOOLCHAIN="${1:-${TOOLCHAIN:-$HOME/tc}}"
export PATH="$TOOLCHAIN/jdk/bin:$PATH"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

JDK_VERSION="17.0.9.2"
KOTLIN_VERSION="2.0.21"
MINAPK_VERSION="0.4.0"

say() { printf '\n\033[1;37m==> %s\033[0m\n' "$1"; }

mkdir -p "$TOOLCHAIN/android/build-tools" \
         "$TOOLCHAIN/android/platforms/android-34" \
         "$TOOLCHAIN/kotlin/lib" \
         "$TOOLCHAIN/jdkpkg"

# --- 1. the JDK -------------------------------------------------------------
say "Fetching the JDK 17 (PyPI: jdk4py $JDK_VERSION)"
python3 -m pip download "jdk4py==$JDK_VERSION" -d "$WORK/jdk" --no-deps -q
JDK_WHEEL="$(ls "$WORK"/jdk/jdk4py-*.whl | head -1)"
python3 - "$JDK_WHEEL" "$TOOLCHAIN/jdkpkg" <<'PY'
import sys, zipfile
wheel, target = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(wheel) as archive:
    archive.extractall(target)
print("extracted", wheel)
PY
# zipfile does not keep the permission bits, so make the binaries runnable
chmod -R u+rx "$TOOLCHAIN/jdkpkg/jdk4py/java-runtime/bin" 2>/dev/null || true
find "$TOOLCHAIN/jdkpkg/jdk4py/java-runtime" -name "*.so" -exec chmod u+x {} \; 2>/dev/null || true
ln -sfn "$TOOLCHAIN/jdkpkg/jdk4py/java-runtime" "$TOOLCHAIN/jdk"
"$TOOLCHAIN/jdk/bin/java" -version 2>&1 | head -1

# --- 2. the Kotlin compiler -------------------------------------------------
say "Fetching the Kotlin compiler (npm: kotlin-compiler $KOTLIN_VERSION)"
cd "$WORK"
npm pack "kotlin-compiler@$KOTLIN_VERSION" --silent >/dev/null
tar -xzf kotlin-compiler-*.tgz
cp -R package/bin "$TOOLCHAIN/kotlin/"
cp -R package/lib "$TOOLCHAIN/kotlin/"

# --- 3. android.jar, d8, apksigner, ecj ------------------------------------
say "Fetching the Android build tools (npm: @drxiaozhi/minapk $MINAPK_VERSION)"
npm pack "@drxiaozhi/minapk@$MINAPK_VERSION" --silent >/dev/null
tar -xzf drxiaozhi-minapk-*.tgz
cp package/tools/android.jar "$TOOLCHAIN/android/platforms/android-34/android.jar"
cp package/tools/d8.jar "$TOOLCHAIN/android/build-tools/d8.jar"
cp package/tools/apksigner.jar "$TOOLCHAIN/android/build-tools/apksigner.jar"
cp package/tools/ecj*.jar "$TOOLCHAIN/android/build-tools/"

# --- 4. aapt2 --------------------------------------------------------------
say "Fetching aapt2 (npm: aaptjs3)"
npm pack aaptjs3 --silent >/dev/null
tar -xzf aaptjs3-*.tgz
cp package/bin/x64/linux/aapt2 "$TOOLCHAIN/android/build-tools/aapt2"
chmod +x "$TOOLCHAIN/android/build-tools/aapt2"

# --- 5. the coroutines jar the build needs ---------------------------------
if [ ! -f "$TOOLCHAIN/kotlin/lib/kotlinx-coroutines-core-jvm.jar" ]; then
    say "Fetching kotlinx-coroutines-core (npm)"
    npm pack kotlinx-coroutines-core-jvm@1.8.0 --silent >/dev/null || true
    tar -xzf kotlinx-coroutines-core-jvm-*.tgz 2>/dev/null || true
    find package -name "kotlinx-coroutines-core-jvm-*.jar" -exec cp {} \
        "$TOOLCHAIN/kotlin/lib/kotlinx-coroutines-core-jvm.jar" \; 2>/dev/null || true
fi

say "Toolchain ready"
"$TOOLCHAIN/android/build-tools/aapt2" version
"$TOOLCHAIN/kotlin/bin/kotlinc" -version 2>&1 | head -1
echo
echo "TOOLCHAIN=$TOOLCHAIN"
echo "JAVA_HOME=$TOOLCHAIN/jdk"
