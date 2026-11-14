#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
D8="$ANDROID_HOME/build-tools/35.0.0/d8"

rm -rf build
mkdir -p build/classes build/dex dist

javac --release 11 -Xlint:-options -cp "$ANDROID_JAR" -d build/classes \
  $(find src -name '*.java')
"$D8" --min-api 30 --lib "$ANDROID_JAR" --output build/dex \
  $(find build/classes -name '*.class')

rm -f dist/hotspot.jar
(cd build/dex && zip -q ../../dist/hotspot.jar classes.dex)
ls -l dist/hotspot.jar
