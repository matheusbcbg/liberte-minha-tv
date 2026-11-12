#!/usr/bin/env bash
# Início's data layer on the plain JDK: HomeLayout, TitleMatch and ShelfMerge are pure Java (no android.*, no org.json).
set -euo pipefail
cd "$(dirname "$0")/.."
for f in HomeLayout TitleMatch ShelfMerge; do
  if grep -nE "^import (android|org\.json)\." "app/src/io/freemytv/$f.java"; then
    echo "$f.java must not use android.* or org.json" >&2
    exit 1
  fi
done
OUT="${HOME_TEST_OUT:-out/home-tests}"
mkdir -p "$OUT"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d "$OUT" \
  app/src/io/freemytv/HomeLayout.java app/src/io/freemytv/TitleMatch.java app/src/io/freemytv/ShelfMerge.java \
  tests/HomeLayoutTest.java tests/TitleMatchTest.java tests/ShelfMergeTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" io.freemytv.HomeLayoutTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" io.freemytv.TitleMatchTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" io.freemytv.ShelfMergeTest
