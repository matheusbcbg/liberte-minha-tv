#!/usr/bin/env bash
# Explore's pure row and page arithmetic (RowMath), plus BrowsePage backdrop-follow checks.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p out/explore-ui-tests
"$JAVAC" -encoding UTF-8 -d out/explore-ui-tests \
  app/src/io/freemytv/RowMath.java tests/RowMathTest.java
"$JAVA" -cp out/explore-ui-tests io.freemytv.RowMathTest

# BrowsePage.backdropOf (needs out/classes from ./build.sh and android.jar on the classpath).
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
CPSEP=:; case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) CPSEP=";" ;; esac
if [ -d out/classes ] && [ -f "${AJ:-}" ]; then
  "$JAVAC" -encoding UTF-8 -cp "out/classes${CPSEP}${AJ}" -d out/explore-ui-tests tests/BrowseBackdropTest.java
  "$JAVA" -cp "out/explore-ui-tests${CPSEP}out/classes${CPSEP}${AJ}" io.freemytv.BrowseBackdropTest
else
  echo "BrowseBackdropTest skipped: run ./build.sh first." >&2
  exit 1
fi
