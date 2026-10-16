#!/bin/sh
#
# Gradle start up script for POSIX (Linux / macOS).
#
# It is a deliberately simple, well commented version of the script that
# Gradle itself generates. It does exactly three things:
#
#   1. find a Java runtime (JAVA_HOME, or "java" from the PATH),
#   2. locate the gradle-wrapper.jar that sits next to this file,
#   3. run org.gradle.wrapper.GradleWrapperMain with your arguments.
#
# The wrapper then downloads the Gradle version that is named in
# gradle/wrapper/gradle-wrapper.properties (the first run needs internet
# access) and runs it.
#
# Usage:
#   ./gradlew assembleRelease      -> the signed release APK
#   ./gradlew installDebug         -> install the debug build on a phone
#   ./gradlew tasks                -> list all available tasks
#

set -e

# --- 1. locate the application directory ----------------------------------
APP_HOME=$(cd -P -- "$(dirname -- "$0")" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "ERROR: $WRAPPER_JAR is missing." >&2
    echo "Download it once with 'gradle wrapper' or copy it from any Gradle project." >&2
    exit 1
fi

# --- 2. locate java --------------------------------------------------------
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVACMD=java
else
    echo "ERROR: no Java runtime found." >&2
    echo "Install JDK 17 and point JAVA_HOME at it, for example:" >&2
    echo "  export JAVA_HOME=/path/to/jdk-17" >&2
    exit 1
fi

# --- 3. run the wrapper ----------------------------------------------------
exec "$JAVACMD" \
    -Dorg.gradle.appname=gradlew \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
