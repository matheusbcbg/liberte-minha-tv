#!/usr/bin/env bash
# Explore's pure row and page arithmetic (RowMath), with the plain JDK.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/explore-ui-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/explore-ui-tests \
  app/src/io/freemytv/RowMath.java tests/RowMathTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/explore-ui-tests io.freemytv.RowMathTest
