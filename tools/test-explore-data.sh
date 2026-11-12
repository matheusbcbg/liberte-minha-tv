#!/usr/bin/env bash
# Explore's data layer on the plain JDK: TmdbPaths and ProviderTable are pure Java (no android.*, no org.json).
set -euo pipefail
cd "$(dirname "$0")/.."
for f in TmdbPaths ProviderTable; do
  if grep -nE "^import (android|org\.json)\." "app/src/io/freemytv/$f.java"; then
    echo "$f.java must not use android.* or org.json" >&2
    exit 1
  fi
done
mkdir -p out/explore-data-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/explore-data-tests \
  app/src/io/freemytv/TmdbPaths.java app/src/io/freemytv/ProviderTable.java app/src/io/freemytv/ExploreModel.java \
  tests/TmdbPathsTest.java tests/ProviderTableTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/explore-data-tests io.freemytv.TmdbPathsTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/explore-data-tests io.freemytv.ProviderTableTest
