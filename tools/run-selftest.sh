#!/usr/bin/env bash
#
# Runs the crypto self test on a plain JVM.
#
# It compiles the security classes of the app together with
# `tools/selftest/CryptoSelfTest.kt` and executes the test. The Android
# Keystore is replaced by an ordinary JCE key, everything else (the GCM
# framing, the IV handling, the tag verification, the PIN hashing) is the
# same code the app runs on a phone.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TOOLCHAIN="${TOOLCHAIN:-$HOME/tc}"
JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLIN_HOME="${KOTLIN_HOME:-$TOOLCHAIN/kotlin}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLCHAIN/android/platforms/android-34/android.jar}"
OUT="${OUT:-$ROOT/build/selftest}"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "==> Compiling the self test"
"$KOTLIN_HOME/bin/kotlinc" \
    -classpath "$ANDROID_JAR" \
    -jvm-target 17 -nowarn \
    -d "$OUT" \
    "$ROOT/app/src/main/java/com/compass/vault/security/KeystoreCipher.kt" \
    "$ROOT/app/src/main/java/com/compass/vault/security/PinHasher.kt" \
    "$ROOT/app/src/main/java/com/compass/vault/security/SecureWipe.kt" \
    "$ROOT/tools/selftest/CryptoSelfTest.kt" \
    "$ROOT/tools/selftest/StartupSafetyTest.kt"

echo "==> Running the self test"
"$JAVA_HOME/bin/java" -cp "$OUT:$ANDROID_JAR:$KOTLIN_HOME/lib/kotlin-stdlib.jar" com.compass.vault.selftest.CryptoSelfTestKt

echo
echo "==> Running the startup safety test"
"$JAVA_HOME/bin/java" -Dcompass.root="$ROOT" -cp "$OUT:$ANDROID_JAR:$KOTLIN_HOME/lib/kotlin-stdlib.jar" com.compass.vault.selftest.StartupSafetyTestKt
